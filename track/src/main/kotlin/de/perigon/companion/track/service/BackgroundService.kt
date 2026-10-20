package de.perigon.companion.track.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import de.perigon.companion.core.di.ApplicationScope
import de.perigon.companion.core.feature.NotificationIds
import de.perigon.companion.core.feature.RequestCodes
import de.perigon.companion.core.ui.CoreIcons
import de.perigon.companion.core.ui.navigatePendingIntent
import de.perigon.companion.track.TrackChannels
import de.perigon.companion.track.data.RecordingMode
import de.perigon.companion.track.data.TrackConfigEntity
import de.perigon.companion.track.data.TrackConfigPrefs
import de.perigon.companion.track.data.TrackPointBuffer
import de.perigon.companion.track.data.TrackPointEntity
import de.perigon.companion.track.data.TrackRepository
import de.perigon.companion.track.domain.AltitudeKalmanFilter
import de.perigon.companion.track.domain.FixEffect
import de.perigon.companion.track.domain.FixEvent
import de.perigon.companion.track.domain.FixState
import de.perigon.companion.track.domain.FixStrategy
import de.perigon.companion.track.domain.Background
import de.perigon.companion.track.domain.BackgroundConfig
import de.perigon.companion.track.domain.fixTransition
import de.perigon.companion.track.domain.formatIntervalLabel
import de.perigon.companion.track.domain.formatModeLabel
import de.perigon.companion.track.data.TrackPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.ShortBuffer
// import java.nio.FloatBuffer
import java.time.LocalDate
import javax.inject.Inject

data class RecordingStatus(
    val isRecording:           Boolean           = false,
    val previewActive:         Boolean           = false,
    val lastProvidedFix:       TrackPointEntity? = null,
    val lastAcceptedFix:       TrackPointEntity? = null,
    val lastEnqueuedFix:       TrackPointEntity? = null,
    val liveDistanceM:         Float?            = null,
    // Hardware fact, set once at service start. False on phones without a
    // pressure sensor, and briefly false before onCreate has run.
    val hasBarometer:          Boolean           = false,
    // Raw ISA barometric altitude (no offset applied). This is exactly what
    // the sensor produces via SensorManager.getAltitude(1013.25, P).
    val currentBaroAltitudeM:  Float?            = null,
    // KF-fused MSL altitude.
    val kalmanAltitudeM:       Float?            = null,
    // KF vertical speed (m/s, positive = up).
    val verticalSpeedMps:      Float?            = null,
    // slope = 100 * v_z / v_h. Label switches to "gradient" in airplane mode.
    val slopePercent:          Float?            = null,
    // QNH (barometric sea-level pressure). Null in airplane mode, and null
    // until the KF has seeded from a 3D GPS fix.
    val currentQnhHpa:         Float?            = null,
    // The KF's estimate of the barometric bias b. Positive means rawBaro
    // reads higher than true altitude (weather low-pressure system, or
    // aircon on in a car). This is the calibration "offset" — not a stored
    // config value, just a live state estimate.
    val kalmanBiasM:           Float?            = null,
    val pendingPoints:         Int               = 0,
    val gnssInfo:              GnssInfo          = GnssInfo.EMPTY,
)

@AndroidEntryPoint
class BackgroundService : Service() {

    companion object {
        const val ACTION_START         = "de.perigon.companion.track.START"
        const val ACTION_STOP          = "de.perigon.companion.track.STOP"
        const val ACTION_PAUSE         = "de.perigon.companion.track.PAUSE"
        const val ACTION_NEW_SEGMENT   = "de.perigon.companion.track.NEW_SEGMENT"
        const val ACTION_FIX_DUE       = "de.perigon.companion.track.FIX_DUE"
        const val ACTION_FLUSH         = "de.perigon.companion.track.FLUSH"
        const val ACTION_PREVIEW_START = "de.perigon.companion.track.PREVIEW_START"
        const val ACTION_PREVIEW_STOP  = "de.perigon.companion.track.PREVIEW_STOP"
        const val ACTION_CALIBRATE     = "de.perigon.companion.track.CALIBRATE"
        const val EXTRA_CALIB_REF_ALT  = "calib_ref_alt"
        const val EXTRA_NAVIGATE_TO    = "navigate_to"
        const val NAV_RECORDING        = "BackgroundService"

        private const val TAG                  = "BackgroundService"
        private const val CHANNEL_ID           = TrackChannels.RECORDER
        private const val NOTIFICATION_ID      = NotificationIds.TRACK
        private const val STOP_REQUEST_CODE    = RequestCodes.TRACK
        private const val CONTENT_REQUEST_CODE = RequestCodes.TRACK + 1
        private const val PREVIEW_STOP_REQUEST_CODE = RequestCodes.TRACK + 2

        // Session wakelock is a renewed LEASE, not one long hold. While the
        // session runs, a job on `scope` re-acquires every RENEW interval, so
        // the lock never lapses mid-session (the previous single 12h timeout
        // silently dropped it on long recordings — and blocked the per-fix
        // lock, since sessionWakeLockHeld stayed true). If teardown is ever
        // missed, the renew job dies with `scope` and the lock self-expires
        // within one LEASE. The lock is non-reference-counted, so re-acquire
        // extends rather than stacks.
        private const val SESSION_WAKELOCK_LEASE_MS = 60 * 60 * 1000L
        private const val SESSION_WAKELOCK_RENEW_MS = SESSION_WAKELOCK_LEASE_MS / 2

        private const val BARO_STATUS_INTERVAL_MS = 1_000L

        private const val BARO_SIGMA_M = 1.0f
        private const val GPS_VERTICAL_FALLBACK_FACTOR = 3f
        private const val GPS_VERTICAL_DEFAULT_M = 15f

        private const val MIN_SPEED_FOR_SLOPE_MPS = 0.5f

        private val _status = MutableStateFlow(RecordingStatus())
        val status: StateFlow<RecordingStatus> = _status.asStateFlow()

        val isRecording          get() = _status.map { it.isRecording }
        val previewActive        get() = _status.map { it.previewActive }
        val lastProvidedFix      get() = _status.map { it.lastProvidedFix }
        val lastAcceptedFix      get() = _status.map { it.lastAcceptedFix }
        val lastEnqueuedFix      get() = _status.map { it.lastEnqueuedFix }
        val liveDistanceM        get() = _status.map { it.liveDistanceM }
        val hasBarometer         get() = _status.map { it.hasBarometer }
        val currentBaroAltitudeM get() = _status.map { it.currentBaroAltitudeM }
        val kalmanAltitudeM      get() = _status.map { it.kalmanAltitudeM }
        val currentQnhHpa        get() = _status.map { it.currentQnhHpa }
        val verticalSpeedMps     get() = _status.map { it.verticalSpeedMps }
        val slopePercent         get() = _status.map { it.slopePercent }
        val kalmanBiasM          get() = _status.map { it.kalmanBiasM }
        val pendingPoints        get() = _status.map { it.pendingPoints }
        val gnssInfo             get() = _status.map { it.gnssInfo }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Inject lateinit var appPrefs: TrackPrefs
    @Inject lateinit var repository: TrackRepository
    @Inject lateinit var configPrefs: TrackConfigPrefs
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    private lateinit var fixScheduler: FixScheduler
    private lateinit var locationManager: LocationManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var sensorManager: SensorManager
    private lateinit var geoid: ShortBuffer
    // private lateinit var geoid: FloatBuffer

    private var pressureSensor: Sensor? = null
    private var lastBaroPressureHpa: Float? = null
    private var lastBaroStatusMs: Long = 0L

    private var altitudeFilter: AltitudeKalmanFilter = AltitudeKalmanFilter()

    private var lastDistanceFix: TrackPointEntity? = null

    private var buffer: TrackPointBuffer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var config   = BackgroundConfig(
        mode                   = RecordingMode.CONTINUOUS,
        intervalMs             = 10_000L,
        hardwareIntervalMs     = 1_000L,
        fixTimeoutMs           = 30_000L,
        maxInaccuracyM         = 50f,
        keepEphemerisWarm      = true,
        holdWakeLock           = true,
        recordBaroAltitude     = false,
        airplaneMode           = false,
        autoSplitOnDayRollover = true,
        autoSegmentGapMs       = TrackConfigEntity.DEFAULT_AUTO_SEGMENT_GAP_MS,
    )
    private var strategy: FixStrategy = FixStrategy.Continuous(10_000L)
    private var trackId     = 0L
    private var segmentId   = 0L
    private var currentDate = ""
    private var recording   = false
    private var previewing  = false
    private var configJob:  Job? = null
    private var ephemerisWarmupActive  = false
    private var gnssCallbackRegistered = false
    private var sessionWakeLockHeld    = false
    private var sessionWakeLockJob: Job? = null
    private var fixTimeoutJob: Job? = null
    private var fixState: FixState = FixState.Idle
    private var recordingStartElapsedNanos: Long = 0L

    private val isSingleFixMode: Boolean
        get() = config.mode == RecordingMode.SINGLE_FIX || config.mode == RecordingMode.ALARM

    // ---- Geoid ----

    private fun linearInterpolate(a: Float, b: Float, t: Float) = a + t * (b - a)

    private fun undulation(lat: Double, lon: Double): Float {
        val cols = 1441
        val step = 0.25
        val normLon = when {
            lon < -180.0 -> lon + 360.0
            lon > 180.0  -> lon - 360.0
            else         -> lon
        }
        val x  = (normLon + 180.0) / step
        val y  = (lat + 90.0) / step
        val x0 = x.toInt().coerceIn(0, cols - 2)
        val y0 = y.toInt().coerceIn(0, 719)
        val x1 = x0 + 1
        val y1 = y0 + 1
        val tx = (x - x0).toFloat()
        val ty = (y - y0).toFloat()

        val raw = linearInterpolate(
            linearInterpolate(geoid.get(y0 * cols + x0).toFloat(), geoid.get(y0 * cols + x1).toFloat(), tx),
            linearInterpolate(geoid.get(y1 * cols + x0).toFloat(), geoid.get(y1 * cols + x1).toFloat(), tx),
            ty,
        )
        return raw / 10f   // for dm level precision in the geoid_v2, would be / 100f for cm
    }

    // Kept for reference: the float-based geoid.bin loader this replaces.
    // geoid_v2 is int16 (dm precision), half the size, and is copied into
    // cacheDir once at startup rather than read from the asset on every
    // cold start. Documented here because the data format swap (float → int16,
    // divide by 10) is easy to get wrong when reading the ShortBuffer code
    // above and wondering why the divisor isn't 100.
    //
    // private fun undulation(lat: Double, lon: Double): Float {
    //     val cols = 1441
    //     val step = 0.25
    //     val normLon = when {
    //         lon < -180.0 -> lon + 360.0
    //         lon > 180.0  -> lon - 360.0
    //         else         -> lon
    //     }
    //     val x  = (normLon + 180.0) / step
    //     val y  = (lat + 90.0) / step
    //     val x0 = x.toInt().coerceIn(0, cols - 2)
    //     val y0 = y.toInt().coerceIn(0, 719)
    //     val x1 = x0 + 1
    //     val y1 = y0 + 1
    //     val tx = (x - x0).toFloat()
    //     val ty = (y - y0).toFloat()
    //     return linearInterpolate(
    //         linearInterpolate(geoid.get(y0 * cols + x0), geoid.get(y0 * cols + x1), tx),
    //         linearInterpolate(geoid.get(y1 * cols + x0), geoid.get(y1 * cols + x1), tx),
    //         ty,
    //     )
    // }

    /** Raw ISA altitude — exactly what SensorManager.getAltitude returns. */
    private fun rawBaroAltitude(pressureHpa: Float): Float =
        SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, pressureHpa)

    private fun convert(location: Location): TrackPointEntity {
        val undulation = undulation(location.latitude, location.longitude)
        val storeBaro = config.recordBaroAltitude && !config.airplaneMode
        return TrackPointEntity(
            segmentId  = 0L,
            lat        = location.latitude.toFloat(),
            lon        = location.longitude.toFloat(),
            ele        = if (location.hasAltitude()) (location.altitude - undulation).toFloat() else null,
            undulation = undulation,
            baroAlt    = if (storeBaro) lastBaroPressureHpa?.let(::rawBaroAltitude) else null,
            accuracyM  = if (location.hasAccuracy()) location.accuracy else null,
            speedMs    = if (location.hasSpeed()) location.speed else null,
            bearing    = if (location.hasBearing()) location.bearing else null,
            time       = location.time,
            provider   = location.provider ?: "gps",
        )
    }

    // ---- GNSS callbacks ----

    private val gnssStatusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            val counts = mutableMapOf<Int, MutableList<Boolean>>()
            for (i in 0 until status.satelliteCount) {
                val type = status.getConstellationType(i)
                counts.getOrPut(type) { mutableListOf() }.add(status.usedInFix(i))
            }
            _status.update {
                it.copy(gnssInfo = GnssInfo(constellations = counts.mapValues { (_, sats) ->
                    ConstellationCount(used = sats.count { it }, visible = sats.size)
                }))
            }
        }
    }

    private val pressureListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_PRESSURE) return
            val p = event.values[0]
            lastBaroPressureHpa = p

            if (!config.airplaneMode) {
                val z = rawBaroAltitude(p)
                altitudeFilter.updateBaro(z, BARO_SIGMA_M)
            }

            val now = SystemClock.elapsedRealtime()
            if (now - lastBaroStatusMs >= BARO_STATUS_INTERVAL_MS) {
                lastBaroStatusMs = now
                val baroRaw = rawBaroAltitude(p)
                val kfAlt = altitudeFilter.altitude()
                val qnh = if (!config.airplaneMode) altitudeFilter.qnhHpa(p) else null
                _status.update {
                    it.copy(
                        currentBaroAltitudeM = baroRaw,
                        kalmanAltitudeM      = kfAlt,
                        verticalSpeedMps     = altitudeFilter.verticalSpeed(),
                        currentQnhHpa        = qnh,
                        kalmanBiasM          = altitudeFilter.bias(),
                    )
                }
                updateSlope(_status.value.lastProvidedFix?.speedMs)
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private var lastFixRealtimeNanos: Long = -1L

    private fun emitLocation(location: Location) {
        val ts = location.elapsedRealtimeNanos
        if (ts == lastFixRealtimeNanos) return
        if (ts < recordingStartElapsedNanos) return
        lastFixRealtimeNanos = ts

        val point = convert(location)

        val gpsAlt = point.ele
        if (gpsAlt != null) {
            val vAcc = when {
                location.hasVerticalAccuracy() -> location.verticalAccuracyMeters
                point.accuracyM != null        -> point.accuracyM * GPS_VERTICAL_FALLBACK_FACTOR
                else                           -> GPS_VERTICAL_DEFAULT_M
            }
            altitudeFilter.updateGps(gpsAlt, vAcc)
            _status.update {
                it.copy(
                    kalmanAltitudeM  = altitudeFilter.altitude(),
                    verticalSpeedMps = altitudeFilter.verticalSpeed(),
                    kalmanBiasM      = altitudeFilter.bias(),
                )
            }
        }

        dispatchFixEvent(FixEvent.FixProvided(point))
        updateSlope(point.speedMs)
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) { emitLocation(location) }
        override fun onProviderDisabled(provider: String) {}
        override fun onProviderEnabled(provider: String) {}
    }

    private val ephemerisListener = object : LocationListener {
        override fun onLocationChanged(location: Location) { emitLocation(location) }
        override fun onProviderDisabled(provider: String) {}
        override fun onProviderEnabled(provider: String) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        locationManager     = getSystemService(LocationManager::class.java)
        notificationManager = getSystemService(NotificationManager::class.java)
        sensorManager       = getSystemService(SensorManager::class.java)
        pressureSensor      = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
        // Hardware fact, set once here and carried through status. False on
        // phones with no pressure sensor — the UI uses this to hide the
        // calibration row and to say "no sensor" rather than "--".
        _status.update { it.copy(hasBarometer = pressureSensor != null) }
        fixScheduler        = FixScheduler(this) { dispatchFixEvent(FixEvent.StartCycle) }

        // Copy the geoid grid to cache directory (int16 LE, dm scale, 1441 x 721).
        val geoidFile = File(cacheDir, "geoid_v2.bin")
        val expectedBytes = 1441L * 721L * 2L   // 2_078_082 for Short
        // val geoidFile = File(cacheDir, "geoid.bin")
        // val expectedBytes = 1441L * 721L * 4L   // 4_194_304 for Float

        // // Copy the geoid_v2.bin to cache directory, checking for truncation
        if (!geoidFile.exists() || geoidFile.length() != expectedBytes) {
            val tmp = File(cacheDir, "geoid_v2.bin.tmp")
            assets.open("gnss/geoid_v2.bin").use { inputStream ->
                FileOutputStream(tmp).use { inputStream.copyTo(it) }
            }
            if (!tmp.renameTo(geoidFile)) {
                tmp.delete()
                error("Failed to install geoid grid")
            }
        }

        // Kept for reference: loading the float-based geoid.bin from assets on
        // every cold start. Replaced by the one-time copy above (cacheDir
        // survives across launches and avoids the asset I/O on each start).
        //
        // if (!geoidFile.exists()) {
        //     assets.open("gnss/geoid.bin").use { inputStream ->
        //         FileOutputStream(geoidFile).use { outputStream ->
        //             inputStream.copyTo(outputStream)
        //         }
        //     }
        // }

        // Map the file from the cache directory, as ShortBuffer()
        geoid = FileInputStream(geoidFile).channel
            .map(FileChannel.MapMode.READ_ONLY, 0, geoidFile.length())
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()

        // Kept for reference: two alternative loaders for the float-based
        // grid — a FileChannel map from cacheDir, and an asset FileDescriptor
        // map. Both work; the ShortBuffer path above replaced them because
        // geoid_v2 is int16 and half the bytes to map.
        //
        // geoid = FileInputStream(geoidFile).channel
        //     .map(FileChannel.MapMode.READ_ONLY, 0, geoidFile.length())
        //     .order(ByteOrder.nativeOrder())
        //     .asFloatBuffer()
        //
        // geoid = assets.openFd("gnss/geoid.bin").use { afd ->
        //    FileInputStream(afd.fileDescriptor).channel
        //        .map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.length)
        //        .order(ByteOrder.nativeOrder())
        //        .asFloatBuffer()
        // }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START         -> startRecording()
            ACTION_STOP          -> stopRecording()
            ACTION_PAUSE         -> pauseRecording()
            ACTION_NEW_SEGMENT   -> newSegment()
            ACTION_FLUSH         -> flushBuffer()
            ACTION_FIX_DUE       -> onFixDue()
            ACTION_PREVIEW_START -> startPreview()
            ACTION_PREVIEW_STOP  -> stopPreview()
            ACTION_CALIBRATE     -> {
                val ref = intent.getFloatExtra(EXTRA_CALIB_REF_ALT, Float.NaN)
                if (!ref.isNaN()) calibrateBaro(ref)
            }
        }
        return START_STICKY
    }

    // ---- Calibration ----

    /**
     * One-shot KF seeding. Does NOT store a persistent offset anywhere —
     * the KF's `b` state absorbs any remaining weather bias. If the filter
     * was already running, velocity and its covariance are preserved.
     */
    private fun calibrateBaro(refAltM: Float) {
        if (config.airplaneMode) {
            Log.w(TAG, "Calibration disabled in airplane mode")
            return
        }
        val pressure = lastBaroPressureHpa
        if (pressure == null) {
            Log.w(TAG, "Cannot calibrate: no baro reading yet")
            return
        }
        val rawBaro = rawBaroAltitude(pressure)
        altitudeFilter.resetWithAltitude(refAltM, rawBaro)

        val qnh = altitudeFilter.qnhHpa(pressure)
        _status.update {
            it.copy(
                currentBaroAltitudeM = rawBaro,
                kalmanAltitudeM      = refAltM,
                verticalSpeedMps     = altitudeFilter.verticalSpeed(),
                currentQnhHpa        = qnh,
                kalmanBiasM          = altitudeFilter.bias(),
            )
        }
        Log.i(TAG, "Calibrated KF: ref=%.2fm rawBaro=%.2fm bias=%.2fm"
            .format(refAltM, rawBaro, altitudeFilter.bias() ?: 0f))
    }

    // ---- Core state machine ----

    /**
     * FIX_DUE arrives from [FixAlarmReceiver] via startForegroundService.
     * If nothing is recording (process was recycled and a stale alarm slipped
     * past both the receiver guard and the cancel), we still MUST honor the
     * startForeground contract before stopping — silently returning here left
     * the obligation unmet and the system raised
     * ForegroundServiceDidNotStartInTimeException. Recovery of an interrupted
     * recording is owned by CompanionApp.resumeBackground(), not this path.
     */
    private fun onFixDue() {
        if (recording || previewing) {
            dispatchFixEvent(FixEvent.StartCycle)
            return
        }
        try {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (e: Exception) {
            Log.w(TAG, "FIX_DUE foreground handshake failed while idle", e)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun dispatchFixEvent(event: FixEvent) {
        if (!recording && !previewing && event !is FixEvent.Cancel) return
        val transition = fixTransition(
            state           = fixState,
            event           = event,
            maxInaccuracyM  = config.maxInaccuracyM,
            isSingleFixMode = isSingleFixMode,
        )
        fixState = transition.state
        executeEffects(transition.effects)
    }

    private fun executeEffects(effects: List<FixEffect>) {
        for (effect in effects) {
            when (effect) {
                is FixEffect.ProvidedFix -> _status.update { it.copy(lastProvidedFix = effect.point) }
                is FixEffect.AcceptFix   -> {
                    val previousAccepted = _status.value.lastAcceptedFix
                    _status.update { it.copy(lastAcceptedFix = effect.point) }
                    if (recording) bufferPoint(effect.point, previousAccepted)
                }
                is FixEffect.RequestGpsFix      -> requestSingleGpsFix()
                is FixEffect.ScheduleNext       -> fixScheduler.schedule(strategy)
                is FixEffect.AcquireFixWakeLock -> acquireFixWakeLock()
                is FixEffect.ReleaseFixWakeLock -> releaseFixWakeLock()
                is FixEffect.SkipCycle          -> Log.d(TAG, "Skipping fix cycle: ${effect.reason}")
            }
        }
    }

    private fun updateSlope(groundSpeedMps: Float?) {
        val vz = _status.value.verticalSpeedMps
        if (vz == null || groundSpeedMps == null || groundSpeedMps < MIN_SPEED_FOR_SLOPE_MPS) {
            _status.update { it.copy(slopePercent = null) }
            return
        }
        val slope = 100f * vz / groundSpeedMps
        _status.update { it.copy(slopePercent = slope) }
    }

    // ---- Live distance accumulation ----

    private fun haversineM(lat1: Float, lon1: Float, lat2: Float, lon2: Float): Float {
        val r    = 6_371_000.0
        val dLat = Math.toRadians((lat2 - lat1).toDouble())
        val dLon = Math.toRadians((lon2 - lon1).toDouble())
        val h    = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                   Math.cos(Math.toRadians(lat1.toDouble())) *
                   Math.cos(Math.toRadians(lat2.toDouble())) *
                   Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return (2 * r * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h))).toFloat()
    }

    private fun accumulateLiveDistance(point: TrackPointEntity) {
        val last = lastDistanceFix
        if (last != null) {
            val d = haversineM(last.lat, last.lon, point.lat, point.lon)
            _status.update { it.copy(liveDistanceM = (it.liveDistanceM ?: 0f) + d) }
        }
        lastDistanceFix = point
    }

    private fun resetDistanceBaseline() {
        lastDistanceFix = null
    }

    // ---- Preview ----

    private fun startPreview() {
        if (recording || previewing) return
        if (!appPrefs.isBackgroundGpsEnabled()) { Log.w(TAG, "Preview disabled"); stopSelf(); return }
        if (!hasLocationPermission()) { Log.w(TAG, "No location permission for preview"); stopSelf(); return }

        previewing = true
        fixState  = FixState.Idle
        recordingStartElapsedNanos =
            SystemClock.elapsedRealtimeNanos() - config.fixTimeoutMs * 1_000_000L
        _status.update { it.copy(previewActive = true) }

        scope.launch {
            val cfg  = configPrefs.get()
            config   = cfg.toRecorderConfig()
            strategy = Background.strategyFor(config.mode, config.intervalMs)

            startForeground(NOTIFICATION_ID, buildPreviewNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)

            registerGnssCallback()
            applyBarometric()
            applyWakeLock()
            applyEphemeris()
            beginFixCycle()
            observeConfigChanges()
        }
    }

    private fun stopPreview() {
        if (recording) return
        if (!previewing) return
        previewing = false
        teardownGps()
        altitudeFilter.reset()
        resetDistanceBaseline()
        _status.update {
            it.copy(
                previewActive        = false,
                lastProvidedFix      = null,
                lastAcceptedFix      = null,
                lastEnqueuedFix      = null,
                liveDistanceM        = null,
                currentBaroAltitudeM = null,
                kalmanAltitudeM      = null,
                currentQnhHpa        = null,
                verticalSpeedMps     = null,
                slopePercent         = null,
                kalmanBiasM          = null,
            )
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---- Recording lifecycle ----

    private fun startRecording() {
        if (recording) return
        if (!appPrefs.isBackgroundGpsEnabled()) { Log.w(TAG, "Recording disabled"); stopSelf(); return }
        if (!hasLocationPermission()) { Log.w(TAG, "No location permission"); stopSelf(); return }

        val wasPreviewing = previewing
        recording  = true
        previewing = false
        fixState   = FixState.Idle
        recordingStartElapsedNanos =
            SystemClock.elapsedRealtimeNanos() - config.fixTimeoutMs * 1_000_000L
        _status.update { it.copy(isRecording = true, previewActive = false) }

        scope.launch {
            if (!wasPreviewing) {
                val cfg  = configPrefs.get()
                config   = cfg.toRecorderConfig()
                strategy = Background.strategyFor(config.mode, config.intervalMs)
                startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                registerGnssCallback()
                applyBarometric()
                applyWakeLock()
                applyEphemeris()
                beginFixCycle()
                observeConfigChanges()
            }
            val (tId, sId) = repository.startRecording(config.autoSplitOnDayRollover)
            trackId     = tId
            segmentId   = sId
            currentDate = LocalDate.now().toString()
            buffer = TrackPointBuffer(repository, config.intervalMs, config.mode).also {
                it.setSegmentId(segmentId)
            }
            val seedDistance = repository.getStats(tId)?.distanceM ?: 0f
            _status.update { it.copy(liveDistanceM = seedDistance) }
            resetDistanceBaseline()
            notificationManager.notify(NOTIFICATION_ID, buildNotification())
        }
    }

    /**
     * Captures trackId before the background coroutine: the pause itself sets
     * recording = false synchronously, so a fast Resume could overwrite the
     * field before the flush + repository.pauseRecording runs, and the stats
     * recompute would target the wrong (or reset) trackId.
     */
    private fun pauseRecording() {
        if (!recording) return
        recording  = false
        previewing = true
        val capturedBuffer  = buffer
        val capturedTrackId = trackId
        buffer = null
        resetDistanceBaseline()
        _status.update { it.copy(isRecording = false, previewActive = true) }
        appScope.launch {
            capturedBuffer?.flushRemaining()
            repository.pauseRecording(capturedTrackId)
            notificationManager.notify(NOTIFICATION_ID, buildPreviewNotification())
        }
    }

    private fun stopRecording() {
        if (!recording) return
        recording  = false
        previewing = true
        val capturedBuffer  = buffer
        val capturedTrackId = trackId
        buffer    = null
        trackId   = 0L
        segmentId = 0L
        currentDate = ""
        resetDistanceBaseline()
        _status.update {
            it.copy(
                isRecording     = false,
                previewActive   = true,
                lastEnqueuedFix = null,
                liveDistanceM   = null,
            )
        }
        appScope.launch {
            capturedBuffer?.flushRemaining()
            repository.stopRecording(capturedTrackId)
            notificationManager.notify(NOTIFICATION_ID, buildPreviewNotification())
        }
    }

    private fun newSegment() {
        if (trackId == 0L) return
        scope.launch {
            buffer?.flush()
            val newSegId = repository.startNewSegment(trackId)
            segmentId = newSegId
            buffer?.setSegmentId(newSegId)
            resetDistanceBaseline()
            _status.update { it.copy(pendingPoints = buffer?.pendingCount() ?: 0) }
        }
    }

    private fun teardownGps() {
        dispatchFixEvent(FixEvent.Cancel)
        fixTimeoutJob?.cancel(); fixTimeoutJob = null
        configJob?.cancel(); configJob = null
        stopPrimaryListener()
        stopEphemerisWarmup()
        stopBarometric()
        unregisterGnssCallback()
        fixScheduler.cancel()
        releaseSessionWakeLock()
        releaseFixWakeLock()
    }

    private fun flushBuffer() {
        scope.launch {
            buffer?.flushRemaining()
            _status.update { it.copy(pendingPoints = buffer?.pendingCount() ?: 0) }
        }
    }

    // ---- Config observation ----

    private fun observeConfigChanges() {
        configJob?.cancel()
        configJob = scope.launch {
            configPrefs.observe()
                .map { it.toRecorderConfig() }
                .distinctUntilChanged()
                .collect { newConfig ->
                    if (newConfig != config) applyConfigChange(newConfig)
                }
        }
    }

    private fun applyConfigChange(newConfig: BackgroundConfig) {
        if (config.airplaneMode != newConfig.airplaneMode) {
            altitudeFilter.reset()
            resetDistanceBaseline()
            _status.update {
                it.copy(
                    kalmanAltitudeM  = null,
                    verticalSpeedMps = null,
                    slopePercent     = null,
                    currentQnhHpa    = null,
                    kalmanBiasM      = null,
                )
            }
        }

        dispatchFixEvent(FixEvent.Cancel)
        fixTimeoutJob?.cancel(); fixTimeoutJob = null
        config   = newConfig
        strategy = Background.strategyFor(config.mode, config.intervalMs)
        buffer?.updateConfig(config.mode, config.intervalMs)
        stopPrimaryListener()
        fixScheduler.cancel()
        stopEphemerisWarmup()
        applyBarometric()
        applyWakeLock()
        applyEphemeris()
        beginFixCycle()
        notificationManager.notify(
            NOTIFICATION_ID,
            if (recording) buildNotification() else buildPreviewNotification(),
        )
    }

    // ---- Fix cycle ----

    private fun beginFixCycle() {
        checkDayRollover()
        when (strategy) {
            is FixStrategy.Continuous    -> startContinuousUpdates()
            is FixStrategy.DelayedSingle -> dispatchFixEvent(FixEvent.StartCycle)
            is FixStrategy.AlarmSingle   -> dispatchFixEvent(FixEvent.StartCycle)
            is FixStrategy.Passive       -> startPassiveUpdates()
        }
    }

    private fun checkDayRollover() {
        if (!recording) return
        val today = LocalDate.now().toString()
        if (today == currentDate) return
        currentDate = today
        scope.launch {
            buffer?.flushRemaining()
            _status.update { it.copy(pendingPoints = 0) }
            val (tId, sId) = repository.handleDayRollover(config.autoSplitOnDayRollover)
            trackId   = tId
            segmentId = sId
            buffer?.setSegmentId(segmentId)
            resetDistanceBaseline()
            _status.update { it.copy(liveDistanceM = 0f) }
        }
    }

    private fun startContinuousUpdates() {
        if (!hasLocationPermission()) return
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                config.hardwareIntervalMs,
                0f,
                locationListener,
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission lost", e)
            stopRecording()
        }
    }

    private fun startPassiveUpdates() {
        if (!hasLocationPermission()) return
        try {
            locationManager.requestLocationUpdates(
                LocationManager.PASSIVE_PROVIDER, 0L, 0f, locationListener)
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission lost", e)
            stopRecording()
        }
    }

    // ---- Single GPS fix ----

    private fun requestSingleGpsFix() {
        if (!hasLocationPermission()) { dispatchFixEvent(FixEvent.Timeout); return }
        fixTimeoutJob?.cancel()
        fixTimeoutJob = scope.launch {
            delay(config.fixTimeoutMs)
            dispatchFixEvent(FixEvent.Timeout)
        }
        try {
            locationManager.getCurrentLocation(LocationManager.GPS_PROVIDER, null, mainExecutor) { location ->
                fixTimeoutJob?.cancel()
                if (location != null) emitLocation(location) else dispatchFixEvent(FixEvent.Timeout)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission lost", e)
            fixTimeoutJob?.cancel()
            releaseFixWakeLock()
        }
    }

    private fun stopPrimaryListener() {
        locationManager.removeUpdates(locationListener)
    }

    // ---- Point buffering ----

    private fun bufferPoint(point: TrackPointEntity, previousAccepted: TrackPointEntity?) {
        val buf = buffer ?: return

        if (previousAccepted != null && config.autoSegmentGapMs > 0 &&
            point.time - previousAccepted.time > config.autoSegmentGapMs) {
            scope.launch {
                buf.flush()
                val newSegId = repository.startNewSegment(trackId)
                segmentId = newSegId
                buf.setSegmentId(newSegId)
                resetDistanceBaseline()
                val enqueued = buf.add(point)
                if (enqueued) accumulateLiveDistance(point)
                _status.update {
                    it.copy(
                        pendingPoints   = buf.pendingCount(),
                        lastEnqueuedFix = if (enqueued) point else it.lastEnqueuedFix,
                    )
                }
            }
            return
        }

        val enqueued = buf.add(point)
        if (enqueued) accumulateLiveDistance(point)
        _status.update {
            it.copy(
                pendingPoints   = buf.pendingCount(),
                lastEnqueuedFix = if (enqueued) point else it.lastEnqueuedFix,
            )
        }
        if (buf.shouldFlush()) {
            scope.launch {
                buf.flush()
                _status.update { it.copy(pendingPoints = buf.pendingCount()) }
            }
        }
    }

    // ---- GNSS status ----

    private fun registerGnssCallback() {
        if (gnssCallbackRegistered) return
        if (!hasLocationPermission()) return
        try {
            locationManager.registerGnssStatusCallback(mainExecutor, gnssStatusCallback)
            gnssCallbackRegistered = true
        } catch (e: SecurityException) {
            Log.e(TAG, "Cannot register GNSS callback", e)
        }
    }

    private fun unregisterGnssCallback() {
        if (!gnssCallbackRegistered) return
        locationManager.unregisterGnssStatusCallback(gnssStatusCallback)
        gnssCallbackRegistered = false
        _status.update { it.copy(gnssInfo = GnssInfo.EMPTY) }
    }

    // ---- Barometric altitude ----

    private fun applyBarometric() {
        if (config.recordBaroAltitude && pressureSensor != null) {
            sensorManager.registerListener(pressureListener, pressureSensor, SensorManager.SENSOR_DELAY_NORMAL)
        } else {
            stopBarometric()
        }
    }

    private fun stopBarometric() {
        sensorManager.unregisterListener(pressureListener)
        lastBaroPressureHpa = null
        lastBaroStatusMs = 0L
        _status.update { it.copy(currentBaroAltitudeM = null, currentQnhHpa = null, kalmanBiasM = null) }
    }

    // ---- Wake lock ----

    private fun ensureWakeLock(): PowerManager.WakeLock {
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PerigonCompanion:TrackFix")
                // Non-counted: session renewals and per-fix acquires extend one
                // hold instead of stacking counts that each need a release.
                .apply { setReferenceCounted(false) }
        }
        return wakeLock!!
    }

    private fun applyWakeLock() {
        if (config.holdWakeLock) acquireSessionWakeLock() else releaseSessionWakeLock()
    }

    private fun acquireSessionWakeLock() {
        if (sessionWakeLockHeld) return
        sessionWakeLockHeld = true
        val lock = ensureWakeLock()
        sessionWakeLockJob = scope.launch {
            while (isActive) {
                lock.acquire(SESSION_WAKELOCK_LEASE_MS)
                delay(SESSION_WAKELOCK_RENEW_MS)
            }
        }
    }

    private fun releaseSessionWakeLock() {
        if (!sessionWakeLockHeld) return
        sessionWakeLockJob?.cancel()
        sessionWakeLockJob = null
        wakeLock?.let { if (it.isHeld) it.release() }
        sessionWakeLockHeld = false
    }

    private fun acquireFixWakeLock() {
        if (sessionWakeLockHeld) return
        ensureWakeLock().acquire(config.fixTimeoutMs * 3)
    }

    private fun releaseFixWakeLock() {
        if (sessionWakeLockHeld) return
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    // ---- Ephemeris ----

    private fun applyEphemeris() {
        stopEphemerisWarmup()
        if (config.mode == RecordingMode.PASSIVE) return
        if (!config.keepEphemerisWarm) return
        if (!hasLocationPermission()) return
        try {
            locationManager.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 0L, 0f, ephemerisListener)
            ephemerisWarmupActive = true
        } catch (e: SecurityException) {
            Log.e(TAG, "Ephemeris warmup failed", e)
        }
    }

    private fun stopEphemerisWarmup() {
        if (!ephemerisWarmupActive) return
        locationManager.removeUpdates(ephemerisListener)
        ephemerisWarmupActive = false
    }

    // ---- Notification ----

    private fun buildNotification(): Notification {
        val modeLabel = formatModeLabel(config.mode)
        val text = if (config.mode == RecordingMode.PASSIVE) modeLabel
                   else "${formatIntervalLabel(config.intervalMs)} · $modeLabel"
        val contentPi = navigatePendingIntent(this, NAV_RECORDING, CONTENT_REQUEST_CODE)
        val stopPi = PendingIntent.getService(this, STOP_REQUEST_CODE,
            Intent(this, BackgroundService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(CoreIcons.status)
            .setContentTitle("Run")
            .setContentText(text)
            .setContentIntent(contentPi)
            .setOngoing(true)
            .addAction(0, "Stop", stopPi)
            .build()
    }

    private fun buildPreviewNotification(): Notification {
        val contentPi = navigatePendingIntent(this, NAV_RECORDING, CONTENT_REQUEST_CODE)
        val stopPi = PendingIntent.getService(this, PREVIEW_STOP_REQUEST_CODE,
            Intent(this, BackgroundService::class.java).apply { action = ACTION_PREVIEW_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(CoreIcons.status)
            .setContentTitle("Locating…")
            .setContentText("Acquiring position")
            .setContentIntent(contentPi)
            .setOngoing(true)
            .addAction(0, "Stop", stopPi)
            .build()
    }

    // ---- Lifecycle ----

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!recording) {
            stopPreview()
        } else {
            appScope.launch { buffer?.flushRemaining() }
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        recording  = false
        previewing = false
        teardownGps()
        val capturedBuffer = buffer
        buffer = null
        appScope.launch { withTimeoutOrNull(2000) { capturedBuffer?.flushRemaining() } }
        scope.launch { delay(2100); scope.cancel() }
        _status.value = RecordingStatus()
        super.onDestroy()
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
}

private fun TrackConfigEntity.toRecorderConfig() = BackgroundConfig(
    mode                   = mode,
    intervalMs             = intervalMs,
    hardwareIntervalMs     = hardwareIntervalMs,
    fixTimeoutMs           = fixTimeoutMs,
    maxInaccuracyM         = maxInaccuracyM,
    keepEphemerisWarm      = keepEphemerisWarm,
    holdWakeLock           = holdWakeLock,
    recordBaroAltitude     = recordBaroAltitude,
    airplaneMode           = airplaneMode,
    autoSplitOnDayRollover = autoSplitOnDayRollover,
    autoSegmentGapMs       = autoSegmentGapMs,
)
