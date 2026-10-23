package de.perigon.companion.sensors.service

import android.Manifest
import android.app.Notification
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
import de.perigon.companion.sensors.SensorsChannels
import de.perigon.companion.sensors.data.GeoidGrid
import de.perigon.companion.sensors.data.RecordingMode
import de.perigon.companion.sensors.data.TrackConfigEntity
import de.perigon.companion.sensors.data.TrackConfigPrefs
import de.perigon.companion.sensors.data.TrackPointBuffer
import de.perigon.companion.sensors.data.TrackPointEntity
import de.perigon.companion.sensors.data.TrackRepository
import de.perigon.companion.sensors.data.toKalmanTuning
import de.perigon.companion.sensors.domain.AltitudeKalmanFilter
import de.perigon.companion.sensors.domain.FixEffect
import de.perigon.companion.sensors.domain.FixEvent
import de.perigon.companion.sensors.domain.FixState
import de.perigon.companion.sensors.domain.FixStrategy
import de.perigon.companion.sensors.domain.Background
import de.perigon.companion.sensors.domain.BackgroundConfig
import de.perigon.companion.sensors.domain.KalmanMode
import de.perigon.companion.sensors.domain.KalmanModel
import de.perigon.companion.sensors.domain.KalmanTuning
import de.perigon.companion.sensors.domain.fixTransition
import de.perigon.companion.sensors.domain.formatIntervalLabel
import de.perigon.companion.sensors.domain.formatModeLabel
import de.perigon.companion.sensors.data.TrackPrefs
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
    // Live pressure reading. Feeds QFE and the KF's baro measurement.
    val currentPressureHpa:    Float?            = null,
    // KF-fused MSL altitude.
    val kalmanAltitudeM:       Float?            = null,
    // KF vertical speed (m/s, positive = up). In the 3-state model this is
    // the v_z state; in the 2-state model it is derived from the cascade
    // position-difference velocity filter inside the KF. Non-null from the
    // first measurement pair after seed in either model.
    val verticalSpeedMps:      Float?            = null,
    // slope = 100 * v_z / v_h. Null when v_h is below the minimum slope
    // speed (0.5 m/s), or before v_z is available.
    val slopePercent:          Float?            = null,
    // QNH (barometric sea-level pressure). Null before the KF has seeded
    // from a 3D GPS fix. Not cleared on model swap or airplane-mode entry —
    // the last-known-good outside QNH remains useful, and it will be
    // overwritten by the next post-reseed baro tick.
    val currentQnhHpa:         Float?            = null,
    // The KF's estimate of the barometric bias b. Positive means rawBaro
    // reads higher than true altitude (weather low-pressure system, or
    // aircon on in a car). This is the calibration "offset" — not a stored
    // config value, just a live state estimate.
    val kalmanBiasM:           Float?            = null,
    // KF covariance diagonals as standard deviations, exposed for the KF
    // debug tab. σ(h) is the altitude uncertainty, σ(b) the bias uncertainty.
    val kalmanAltitudeSigmaM:  Float?            = null,
    val kalmanBiasSigmaM:      Float?            = null,
    // Last innovation (measurement minus prediction) per source, in metres.
    // Positive = measurement above prediction. Diagnostics only.
    val lastGpsInnovationM:    Float?            = null,
    val lastBaroInnovationM:   Float?            = null,
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
        private const val CHANNEL_ID           = SensorsChannels.RECORDER
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
    @Inject lateinit var geoidGrid: GeoidGrid
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    private lateinit var fixScheduler: FixScheduler
    private lateinit var locationManager: LocationManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var sensorManager: SensorManager

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
        kalmanModel            = KalmanModel.THREE_STATE,
        kalmanMode             = KalmanMode.GAUSSIAN,
        iskfInverseHessian     = false,
        kalmanTuning           = KalmanTuning(),
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

    /** Raw ISA altitude — exactly what SensorManager.getAltitude returns. */
    private fun rawBaroAltitude(pressureHpa: Float): Float =
        SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, pressureHpa)

    /**
     * Barometric measurement sigma, honouring the user's tuning override.
     * Read at each updateBaro call rather than cached, so a change in the
     * KF or Track tab's Advanced expander takes effect on the next
     * pressure tick without needing a filter rebuild.
     */
    private fun baroSigma(): Float =
        config.kalmanTuning.baroSigmaM ?: KalmanTuning.DEFAULT_BARO_SIGMA_M

    /**
     * GPS vertical sigma floor, honouring the user's tuning override. Same
     * live-read discipline as [baroSigma]. 0 disables the floor entirely;
     * the default (10 m) is calibrated for Pixels whose HAL smooths the
     * altitude solution during plateaus. The recorded `accuracyM` field
     * stores the raw reported accuracy, not this floored value — the floor
     * is a modelling choice for the filter, not a claim about the raw GPS.
     */
    private fun gpsVerticalFloor(): Float =
        config.kalmanTuning.gpsVerticalMinM ?: KalmanTuning.DEFAULT_GPS_VERTICAL_MIN_M

    private fun convert(location: Location): TrackPointEntity {
        val undulation = geoidGrid.undulation(location.latitude, location.longitude)
        val storeBaro = config.recordBaroAltitude && !config.airplaneMode
        return TrackPointEntity(
            segmentId  = 0L,
            lat        = location.latitude,
            lon        = location.longitude,
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
                altitudeFilter.updateBaro(z, baroSigma())
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
                        currentPressureHpa   = p,
                        kalmanAltitudeM      = kfAlt,
                        verticalSpeedMps     = altitudeFilter.verticalSpeed(),
                        // QNH not cleared in airplane mode — the last pre-
                        // airplane outside reading is the last-known-good.
                        currentQnhHpa        = qnh ?: it.currentQnhHpa,
                        kalmanBiasM          = altitudeFilter.bias(),
                        kalmanAltitudeSigmaM = altitudeFilter.altitudeSigma(),
                        kalmanBiasSigmaM     = altitudeFilter.biasSigma(),
                        lastGpsInnovationM   = altitudeFilter.lastGpsInnovationM(),
                        lastBaroInnovationM  = altitudeFilter.lastBaroInnovationM(),
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
            // Apply the tuning-driven floor. See gpsVerticalFloor().
            val floor = gpsVerticalFloor()
            val vAcc = when {
                location.hasVerticalAccuracy() ->
                    maxOf(location.verticalAccuracyMeters, floor)
                point.accuracyM != null ->
                    maxOf(point.accuracyM * GPS_VERTICAL_FALLBACK_FACTOR, floor)
                else -> GPS_VERTICAL_DEFAULT_M
            }
            altitudeFilter.updateGps(gpsAlt, vAcc)
            _status.update {
                it.copy(
                    kalmanAltitudeM      = altitudeFilter.altitude(),
                    verticalSpeedMps     = altitudeFilter.verticalSpeed(),
                    kalmanBiasM          = altitudeFilter.bias(),
                    kalmanAltitudeSigmaM = altitudeFilter.altitudeSigma(),
                    kalmanBiasSigmaM     = altitudeFilter.biasSigma(),
                    lastGpsInnovationM   = altitudeFilter.lastGpsInnovationM(),
                    lastBaroInnovationM  = altitudeFilter.lastBaroInnovationM(),
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
     * was already running, velocity and its covariance are preserved in the
     * 3-state model; the 2-state model's cascade velocity estimator is
     * reseeded, since the snap is a discontinuity.
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
                currentPressureHpa   = pressure,
                kalmanAltitudeM      = refAltM,
                verticalSpeedMps     = altitudeFilter.verticalSpeed(),
                currentQnhHpa        = qnh ?: it.currentQnhHpa,
                kalmanBiasM          = altitudeFilter.bias(),
                kalmanAltitudeSigmaM = altitudeFilter.altitudeSigma(),
                kalmanBiasSigmaM     = altitudeFilter.biasSigma(),
                lastGpsInnovationM   = altitudeFilter.lastGpsInnovationM(),
                lastBaroInnovationM  = altitudeFilter.lastBaroInnovationM(),
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

    /**
     * slope = 100 · v_z / v_h. v_z is model-agnostic — the filter returns
     * the 3-state velocity state when it exists, or the cascade
     * position-difference filter's output in 2-state — so this function
     * does not need to know which model is active. Null when v_z is not
     * available yet, or when v_h is too small for a meaningful slope.
     */
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

    private fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r    = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val h    = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                   Math.cos(Math.toRadians(lat1)) *
                   Math.cos(Math.toRadians(lat2)) *
                   Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return 2 * r * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h))
    }

    private fun accumulateLiveDistance(point: TrackPointEntity) {
        val last = lastDistanceFix
        if (last != null) {
            val d = haversineM(last.lat, last.lon, point.lat, point.lon)
            _status.update { it.copy(liveDistanceM = (it.liveDistanceM ?: 0f) + d.toFloat()) }
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
            altitudeFilter = AltitudeKalmanFilter(
                initialModel = config.kalmanModel,
                initialMode = config.kalmanMode,
                initialIskfInverseHessian = config.iskfInverseHessian,
                initialTuning = config.kalmanTuning,
            )

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
                currentPressureHpa   = null,
                kalmanAltitudeM      = null,
                currentQnhHpa        = null,
                verticalSpeedMps     = null,
                slopePercent         = null,
                kalmanBiasM          = null,
                kalmanAltitudeSigmaM = null,
                kalmanBiasSigmaM     = null,
                lastGpsInnovationM   = null,
                lastBaroInnovationM  = null,
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
                altitudeFilter = AltitudeKalmanFilter(
                    initialModel = config.kalmanModel,
                    initialMode = config.kalmanMode,
                    initialIskfInverseHessian = config.iskfInverseHessian,
                    initialTuning = config.kalmanTuning,
                )
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
            _status.update { it.copy(pendingPoints = 0) }
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
            _status.update { it.copy(pendingPoints = 0) }
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

    /**
     * Applies a runtime config change.
     *
     * Method/mode changes are applied in place — h, v, b, and P all survive.
     * Recreating the filter there would blank altitude/sigma for several
     * seconds after every settings toggle, which read as "GPS reset" in the
     * field.
     *
     * Model changes now also carry state across (h, b, v_z and their
     * covariance block, see AltitudeKalmanFilter.transitionModel), so a 3↔2
     * swap does not blank the filter for several seconds while it re-seeds.
     * The RTS smoother's step-record buffer is the KF tab's concern, not
     * the recorder's; the recorder never owned one, so nothing to clear
     * here. Tuning changes that affect process noise re-instantiate the
     * model object and leave x/P/initialized untouched — the accumulated b
     * survives a q_b slider change, which is the whole point.
     *
     * The isInitialized() guard below now fires only when the filter
     * genuinely was not seeded yet (before the first GPS fix); the KF-
     * derived status fields are cleared then to keep the UI honest about
     * "no state yet". A baroSigmaM- or gpsVerticalMinM-only change touches
     * neither state nor model — both live at the caller.
     *
     * QNH is not cleared in either branch: the last-known-good outside
     * reading remains useful and is overwritten by the next post-reseed
     * baro tick.
     */
    private suspend fun applyConfigChange(newConfig: BackgroundConfig) {
        buffer?.flush()

        altitudeFilter.setModelAndTuning(newConfig.kalmanModel, newConfig.kalmanTuning)
        if (!altitudeFilter.isInitialized()) {
            _status.update {
                it.copy(
                    kalmanAltitudeM      = null,
                    verticalSpeedMps     = null,
                    slopePercent         = null,
                    kalmanBiasM          = null,
                    kalmanAltitudeSigmaM = null,
                    kalmanBiasSigmaM     = null,
                    lastGpsInnovationM   = null,
                    lastBaroInnovationM  = null,
                )
            }
        }
        if (config.kalmanMode != newConfig.kalmanMode) {
            altitudeFilter.setMode(newConfig.kalmanMode)
        }
        if (config.iskfInverseHessian != newConfig.iskfInverseHessian) {
            altitudeFilter.setIskfInverseHessian(newConfig.iskfInverseHessian)
        }

        if (config.airplaneMode != newConfig.airplaneMode) {
            altitudeFilter.reset()
            resetDistanceBaseline()
            _status.update {
                it.copy(
                    kalmanAltitudeM      = null,
                    verticalSpeedMps     = null,
                    slopePercent         = null,
                    // currentQnhHpa intentionally preserved — last-known-good
                    // outside pressure stays visible in airplane mode.
                    kalmanBiasM          = null,
                    kalmanAltitudeSigmaM = null,
                    kalmanBiasSigmaM     = null,
                    lastGpsInnovationM   = null,
                    lastBaroInnovationM  = null,
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
        _status.update {
            it.copy(
                currentBaroAltitudeM = null,
                currentPressureHpa   = null,
                currentQnhHpa        = null,
                kalmanBiasM          = null,
                kalmanBiasSigmaM     = null,
                lastBaroInnovationM  = null,
            )
        }
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
    kalmanModel            = kalmanModel,
    kalmanMode             = kalmanMode,
    iskfInverseHessian     = iskfInverseHessian,
    kalmanTuning           = toKalmanTuning(),
)
