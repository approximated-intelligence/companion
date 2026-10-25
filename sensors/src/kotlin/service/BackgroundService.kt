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
import de.perigon.companion.core.geo.geoid.GeoidGrid
import de.perigon.companion.core.ui.CoreIcons
import de.perigon.companion.core.ui.navigatePendingIntent
import de.perigon.companion.sensors.SensorsChannels
import de.perigon.companion.sensors.data.RecordingMode
import de.perigon.companion.sensors.data.TrackConfigEntity
import de.perigon.companion.sensors.data.TrackConfigPrefs
import de.perigon.companion.sensors.data.TrackPointBuffer
import de.perigon.companion.sensors.data.TrackPointEntity
import de.perigon.companion.sensors.data.TrackPrefs
import de.perigon.companion.sensors.data.TrackRepository
import de.perigon.companion.sensors.data.toKalmanTuning
import de.perigon.companion.sensors.domain.AltitudeKalmanFilter
import de.perigon.companion.sensors.domain.Background
import de.perigon.companion.sensors.domain.BackgroundConfig
import de.perigon.companion.sensors.domain.FixEffect
import de.perigon.companion.sensors.domain.FixEvent
import de.perigon.companion.sensors.domain.FixState
import de.perigon.companion.sensors.domain.FixStrategy
import de.perigon.companion.sensors.domain.KalmanMode
import de.perigon.companion.sensors.domain.KalmanModel
import de.perigon.companion.sensors.domain.KalmanTuning
import de.perigon.companion.sensors.domain.effectiveHardwareIntervalMs
import de.perigon.companion.sensors.domain.fixTransition
import de.perigon.companion.sensors.domain.formatIntervalLabel
import de.perigon.companion.sensors.domain.formatModeLabel
import de.perigon.companion.sensors.gnss.Position
import de.perigon.companion.sensors.gnss.SatelliteObservation
import de.perigon.companion.sensors.gnss.UTCDateTime
import de.perigon.companion.sensors.middleware.Dop
import de.perigon.companion.sensors.middleware.GnssController
import de.perigon.companion.sensors.middleware.GnssFix
import de.perigon.companion.sensors.middleware.GnssSession
import de.perigon.companion.sensors.middleware.GnssSourceType
import de.perigon.companion.sensors.middleware.Sigma
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

/**
 * Recorder status. All values are populated through the middleware's
 * [GnssFix] stream in CONTINUOUS mode, through single-fix requests in
 * SINGLE_FIX / ALARM modes, and through a legacy passive listener in
 * PASSIVE mode (the middleware has no passive source).
 *
 * Barometric altitude and the Kalman filter operate independently — the
 * sensor listener is not GNSS, and the KF's `updateGps` is fed from the
 * fix that arrived through whichever path is active.
 */
data class RecordingStatus(
    val isRecording: Boolean = false,
    val previewActive: Boolean = false,
    val lastProvidedFix: TrackPointEntity? = null,
    val lastAcceptedFix: TrackPointEntity? = null,
    val lastEnqueuedFix: TrackPointEntity? = null,
    val liveDistanceM: Float? = null,
    // Hardware fact, set once at service start. False on phones without a
    // pressure sensor, and briefly false before onCreate has run.
    val hasBarometer: Boolean = false,
    // Raw ISA barometric altitude (no offset applied). This is exactly what
    // the sensor produces via SensorManager.getAltitude(1013.25, P).
    val currentBaroAltitudeM: Float? = null,
    // Live pressure reading. Feeds QFE and the KF's baro measurement.
    val currentPressureHpa: Float? = null,
    // KF-fused MSL altitude.
    val kalmanAltitudeM: Float? = null,
    // KF vertical speed (m/s, positive = up). In the 3/4/GPS-2-state model
    // this is the v_z state; in the 2-state model it is derived from the
    // cascade position-difference velocity filter inside the KF. Non-null
    // from the first measurement pair after seed in either model.
    val verticalSpeedMps: Float? = null,
    // slope = 100 * v_z / v_h. Null when v_h is below the minimum slope
    // speed (0.5 m/s), or before v_z is available.
    val slopePercent: Float? = null,
    // QNH (barometric sea-level pressure). Null before the KF has seeded
    // from a 3D GPS fix. Not cleared on model swap or airplane-mode entry —
    // the last-known-good outside QNH remains useful, and it will be
    // overwritten by the next post-reseed baro tick. Null for the GPS
    // 2-state model, which has no bias state.
    val currentQnhHpa: Float? = null,
    // The KF's estimate of the barometric bias b. Positive means rawBaro
    // reads higher than true altitude (weather low-pressure system, or
    // aircon on in a car). This is the calibration "offset" — not a stored
    // config value, just a live state estimate. Null for the GPS 2-state
    // model, which has no bias state.
    val kalmanBiasM: Float? = null,
    // Bias rate v_b (m/s). State in 4-state, cascade in 2/3-state. Positive
    // means the raw baro reading is rising relative to true altitude
    // (aircon heating up, or a high-pressure system pushing pressure up).
    val kalmanBiasRateMps: Float? = null,
    // KF covariance diagonals as standard deviations, exposed for the KF
    // debug tab. σ(h) is the altitude uncertainty, σ(b) the bias uncertainty.
    val kalmanAltitudeSigmaM: Float? = null,
    val kalmanBiasSigmaM: Float? = null,
    // Last innovation (measurement minus prediction) per source, in metres.
    // Positive = measurement above prediction. Diagnostics only.
    val lastGpsInnovationM: Float? = null,
    val lastBaroInnovationM: Float? = null,
    // CUSUM accumulators on the normalized baro innovation. Either crossing
    // its threshold (~8) is the aircon detector firing.
    val airconCusumPos: Float? = null,
    val airconCusumNeg: Float? = null,
    // True when either CUSUM accumulator crosses its threshold. Aircon
    // shows up as a step in v_b (cabin HVAC), which the CUSUM on baro
    // innovations detects as a sustained bias — a violation of the model's
    // constant-v_b prediction.
    val airconDetected: Boolean = false,
    val pendingPoints: Int = 0,
    val gnssInfo: GnssInfo = GnssInfo.EMPTY,
    // Last non-null bearing from the chipset, unfiltered. Null-bearing
    // fixes keep the previous value so the cell doesn't flap to "--" when
    // the chipset momentarily drops the course. Reset on preview stop and
    // on service teardown; survives pause→resume and stop→preview, because
    // in both cases the display is still showing a live session.
    val lastHeadingDegrees: Float? = null,
)

@AndroidEntryPoint
class BackgroundService : Service() {

    companion object {
        const val ACTION_START = "de.perigon.companion.track.START"
        const val ACTION_STOP = "de.perigon.companion.track.STOP"
        const val ACTION_PAUSE = "de.perigon.companion.track.PAUSE"
        const val ACTION_NEW_SEGMENT = "de.perigon.companion.track.NEW_SEGMENT"
        const val ACTION_FIX_DUE = "de.perigon.companion.track.FIX_DUE"
        const val ACTION_FLUSH = "de.perigon.companion.track.FLUSH"
        const val ACTION_PREVIEW_START = "de.perigon.companion.track.PREVIEW_START"
        const val ACTION_PREVIEW_STOP = "de.perigon.companion.track.PREVIEW_STOP"
        const val ACTION_CALIBRATE = "de.perigon.companion.track.CALIBRATE"
        const val EXTRA_CALIB_REF_ALT = "calib_ref_alt"
        const val EXTRA_NAVIGATE_TO = "navigate_to"
        const val NAV_RECORDING = "BackgroundService"

        private const val TAG = "BackgroundService"
        private const val CHANNEL_ID = SensorsChannels.RECORDER
        private const val NOTIFICATION_ID = NotificationIds.TRACK
        private const val STOP_REQUEST_CODE = RequestCodes.TRACK
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

        val isRecording get() = _status.map { it.isRecording }
        val previewActive get() = _status.map { it.previewActive }
        val lastProvidedFix get() = _status.map { it.lastProvidedFix }
        val lastAcceptedFix get() = _status.map { it.lastAcceptedFix }
        val lastEnqueuedFix get() = _status.map { it.lastEnqueuedFix }
        val liveDistanceM get() = _status.map { it.liveDistanceM }
        val hasBarometer get() = _status.map { it.hasBarometer }
        val currentBaroAltitudeM get() = _status.map { it.currentBaroAltitudeM }
        val currentQnhHpa get() = _status.map { it.currentQnhHpa }
        val verticalSpeedMps get() = _status.map { it.verticalSpeedMps }
        val slopePercent get() = _status.map { it.slopePercent }
        val kalmanBiasM get() = _status.map { it.kalmanBiasM }
        val kalmanBiasRateMps get() = _status.map { it.kalmanBiasRateMps }
        val airconDetected get() = _status.map { it.airconDetected }
        val pendingPoints get() = _status.map { it.pendingPoints }
        val gnssInfo get() = _status.map { it.gnssInfo }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Inject lateinit var appPrefs: TrackPrefs
    @Inject lateinit var repository: TrackRepository
    @Inject lateinit var configPrefs: TrackConfigPrefs
    @Inject lateinit var geoidGrid: GeoidGrid
    @Inject lateinit var gnssController: GnssController
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
    private var config = BackgroundConfig(
        mode = RecordingMode.CONTINUOUS,
        intervalMs = 10_000L,
        hardwareIntervalMs = 1_000L,
        fixTimeoutMs = 30_000L,
        maxInaccuracyM = 50f,
        keepEphemerisWarm = true,
        holdWakeLock = true,
        recordBaroAltitude = false,
        kalmanEnabled = false,
        airplaneMode = false,
        autoSplitOnDayRollover = true,
        autoSegmentGapMs = TrackConfigEntity.DEFAULT_AUTO_SEGMENT_GAP_MS,
        kalmanModel = KalmanModel.THREE_STATE,
        kalmanMode = KalmanMode.GAUSSIAN,
        iskfInverseHessian = false,
        kalmanTuning = KalmanTuning(),
    )
    private var strategy: FixStrategy = FixStrategy.Continuous(10_000L)
    private var trackId = 0L
    private var segmentId = 0L
    private var currentDate = ""
    private var recording = false
    private var previewing = false
    private var configJob: Job? = null
    private var ephemerisWarmupActive = false
    private var sessionWakeLockHeld = false
    private var sessionWakeLockJob: Job? = null
    private var fixTimeoutJob: Job? = null
    private var fixState: FixState = FixState.Idle
    private var recordingStartElapsedNanos: Long = 0L

    // ---- Middleware ----
    private var gnssSession: GnssSession? = null
    private var gnssFixJob: Job? = null

    // ---- Legacy paths (PASSIVE mode + ephemeris warmup) ----
    // The middleware has no passive source; when mode == PASSIVE, the
    // recorder owns a listener directly and builds fixes via
    // [buildPassiveFix]. Warm-up (keepEphemerisWarm) is a separate concern
    // — it registers a passive listener to keep the chip fresh between
    // cycles in SINGLE_FIX / ALARM modes.
    private var passiveListener: LocationListener? = null
    private var passiveActive = false
    private var ephemerisListener: LocationListener? = null

    private var lastFixRealtimeNanos: Long = -1L

    private val isSingleFixMode: Boolean
        get() = config.mode == RecordingMode.SINGLE_FIX || config.mode == RecordingMode.ALARM

    /** True while the Kalman filter is constructed and being fed. */
    private val kfActive: Boolean
        get() = config.kalmanEnabled

    /**
     * True when the active model fuses baro (the filter is on, the model
     * has a baro measurement, and airplane mode hasn't excluded it). Reads
     * the filter's model via a feature property rather than testing the
     * enum name — the feature property is the model class's own declaration,
     * so it stays correct when a new no-baro model is added without touching
     * this site.
     */
    private val baroFused: Boolean
        get() = kfActive &&
            altitudeFilter.hasBaroMeasurement &&
            !config.airplaneMode

    /** Raw ISA altitude — exactly what SensorManager.getAltitude returns. */
    private fun rawBaroAltitude(pressureHpa: Float): Float =
        SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, pressureHpa)

    /**
     * Build the filter appropriate for [cfg]. When Kalman filtering is off,
     * returns a fresh, un-fed filter (all readers stay null). Airplane mode
     * is passed at construction so [KalmanModel.GPS_TWO_STATE] picks its
     * q_v variant immediately.
     *
     * Keeping an instance (rather than a nullable field) means teardown /
     * config-change / baro-on-off paths never need an `if (kfActive)`
     * branch — they always have a filter to reset. The filter is simply
     * never updated when disabled, so all its readers stay null.
     */
    private fun buildFilter(cfg: BackgroundConfig): AltitudeKalmanFilter =
        if (cfg.kalmanEnabled) {
            AltitudeKalmanFilter(
                initialModel = cfg.kalmanModel,
                initialMode = cfg.kalmanMode,
                initialIskfInverseHessian = cfg.iskfInverseHessian,
                initialTuning = cfg.kalmanTuning,
                initialAirplaneMode = cfg.airplaneMode,
            )
        } else {
            AltitudeKalmanFilter()
        }

    /**
     * Measurement σ for the GPS altitude measurement.
     *
     * For no-baro models (feature, not name): uses [KalmanTuning.gpsOnlySigmaM]
     * directly — those models' R is the *reported series'* per-sample noise
     * (quantization-limited), not the chip's reported vertical accuracy.
     * The 10 m fusion floor is the wrong value for them.
     *
     * For the fusion models: NMEA mode uses GST verbatim (design §4: the
     * floor's reason disappears when GST is present); LOCATION mode uses
     * floored verticalAccuracyMeters (or accuracy × 3 as fallback), because
     * Pixel HALs underreport vertical accuracy during plateaus.
     */
    private fun gpsMeasurementSigma(fix: GnssFix): Float {
        if (kfActive && !altitudeFilter.hasBaroMeasurement) {
            return config.kalmanTuning.gpsOnlySigmaM
                ?: KalmanTuning.DEFAULT_GPS_ONLY_SIGMA_M
        }
        return when (gnssController.sourceType.value) {
            GnssSourceType.NMEA ->
                // GST residual σ is the solution's own noise estimate; the
                // design §4 says the floor's reason disappears when GST is
                // present. Use the value verbatim; fall back to the
                // conservative default only when the chipset emits no GST.
                fix.sigma.verticalM?.toFloat() ?: GPS_VERTICAL_DEFAULT_M
            GnssSourceType.LOCATION -> {
                // Location σ is a HAL-model value, known optimistic during
                // stationary plateaus on Pixel HALs. Apply the user floor.
                val floor = config.kalmanTuning.gpsVerticalMinM
                    ?: KalmanTuning.DEFAULT_GPS_VERTICAL_MIN_M
                when {
                    fix.sigma.verticalM != null ->
                        maxOf(fix.sigma.verticalM.toFloat(), floor)
                    fix.sigma.horizontalM != null ->
                        maxOf(fix.sigma.horizontalM.toFloat() * GPS_VERTICAL_FALLBACK_FACTOR, floor)
                    else -> GPS_VERTICAL_DEFAULT_M
                }
            }
        }
    }

    /**
     * Clear the KF-derived fields of [RecordingStatus] when the filter
     * resets or swaps. QNH is *not* cleared here — it is a weather reading,
     * not filter state, and survives every model/tuning/airplane transition.
     * The only place it is cleared is when the filter turns off entirely
     * (see applyConfigChange) and when the baro sensor stops (stopBarometric).
     */
    private fun clearKfStatus() {
        _status.update {
            it.copy(
                kalmanAltitudeM = null,
                verticalSpeedMps = null,
                slopePercent = null,
                kalmanBiasM = null,
                kalmanBiasRateMps = null,
                kalmanAltitudeSigmaM = null,
                kalmanBiasSigmaM = null,
                lastGpsInnovationM = null,
                lastBaroInnovationM = null,
                airconCusumPos = null,
                airconCusumNeg = null,
                airconDetected = false,
            )
        }
    }

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
     *
     * NOT USED — orphaned. The NMEA-vs-LOCATION σ policy lives in
     * [gpsMeasurementSigma], where the floor expression is inlined because
     * only the LOCATION branch applies it (NMEA trusts GST verbatim, per
     * design §4). Kept for this KDoc: the floor's meaning and its default
     * are still the canonical explanation. No caller today; delete if a
     * future reader might assume it is invoked.
     */
    private fun gpsVerticalFloor(): Float =
        config.kalmanTuning.gpsVerticalMinM ?: KalmanTuning.DEFAULT_GPS_VERTICAL_MIN_M

    /**
     * Convert a unified fix into the point stored in the DB. Fields absent
     * from the fix (because the active source does not provide them) are
     * stored as null — no cross-source fabrication.
     */
    private fun convertFix(fix: GnssFix): TrackPointEntity {
        val storeBaro = config.recordBaroAltitude && !config.airplaneMode
        return TrackPointEntity(
            segmentId = 0L,
            lat = fix.position.lat,
            lon = fix.position.lon,
            ele = fix.altitudeMslM?.toFloat(),
            undulation = if (fix.altitudeWgs84M != null && fix.altitudeMslM != null)
                (fix.altitudeWgs84M - fix.altitudeMslM).toFloat()
            else null,
            baroAlt = if (storeBaro) lastBaroPressureHpa?.let(::rawBaroAltitude) else null,
            accuracyM = fix.sigma.horizontalM?.toFloat(),
            speedMs = fix.speedMps?.toFloat(),
            bearing = fix.courseDeg?.toFloat(),
            time = fix.timestamp.toInstant().toEpochMilli(),
            provider = fix.source.name.lowercase(),
        )
    }

    // ===== Barometric sensor (unchanged in structure — pure sensor) =====

    private val pressureListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_PRESSURE) return
            val p = event.values[0]
            lastBaroPressureHpa = p

            if (baroFused) {
                val z = rawBaroAltitude(p)
                altitudeFilter.updateBaro(z, baroSigma())
            }

            val now = SystemClock.elapsedRealtime()
            if (now - lastBaroStatusMs >= BARO_STATUS_INTERVAL_MS) {
                lastBaroStatusMs = now
                val baroRaw = rawBaroAltitude(p)
                val kfAlt = altitudeFilter.altitude()
                val qnh = if (baroFused) altitudeFilter.qnhHpa(p) else null
                // The CUSUM is only meaningful when baro innovations are
                // being fed. For a no-baro model the accumulators are never
                // updated, so reading them yields 0f — not the same thing as
                // "not applicable". Null preserves the invariant that
                // GPS-only models report null for every baro-derived field.
                val cusum = if (baroFused) altitudeFilter.airconCusum() else null
                _status.update {
                    it.copy(
                        currentBaroAltitudeM = baroRaw,
                        currentPressureHpa = p,
                        kalmanAltitudeM = kfAlt,
                        verticalSpeedMps = altitudeFilter.verticalSpeed(),
                        // QNH not cleared in airplane mode — the last pre-
                        // airplane outside reading is the last-known-good.
                        currentQnhHpa = qnh ?: it.currentQnhHpa,
                        kalmanBiasM = altitudeFilter.bias(),
                        kalmanBiasRateMps = altitudeFilter.biasRate(),
                        kalmanAltitudeSigmaM = altitudeFilter.altitudeSigma(),
                        kalmanBiasSigmaM = altitudeFilter.biasSigma(),
                        lastGpsInnovationM = altitudeFilter.lastGpsInnovationM(),
                        lastBaroInnovationM = altitudeFilter.lastBaroInnovationM(),
                        airconCusumPos = cusum?.first,
                        airconCusumNeg = cusum?.second,
                        airconDetected = baroFused && altitudeFilter.airconDetected(),
                    )
                }
                updateSlope(_status.value.lastProvidedFix?.speedMs)
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    // ===== Passive-mode LocationListener (recording fallback) =====

    private val passiveLocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            emitFix(buildPassiveFix(location))
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    // ===== Ephemeris warm-up LocationListener =====

    /**
     * The warm-up listener only exists to keep the GNSS engine warm
     * between single-fix cycles in SINGLE_FIX / ALARM modes. It does not
     * produce points — its Location payload is discarded. In CONTINUOUS
     * and PASSIVE modes it is not registered.
     */
    private val ephemerisLocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) { /* discarded */ }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LocationManager::class.java)
        notificationManager = getSystemService(NotificationManager::class.java)
        sensorManager = getSystemService(SensorManager::class.java)
        pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
        _status.update { it.copy(hasBarometer = pressureSensor != null) }
        fixScheduler = FixScheduler(this) { dispatchFixEvent(FixEvent.StartCycle) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP -> stopRecording()
            ACTION_PAUSE -> pauseRecording()
            ACTION_NEW_SEGMENT -> newSegment()
            ACTION_FLUSH -> flushBuffer()
            ACTION_FIX_DUE -> onFixDue()
            ACTION_PREVIEW_START -> startPreview()
            ACTION_PREVIEW_STOP -> stopPreview()
            ACTION_CALIBRATE -> {
                val ref = intent.getFloatExtra(EXTRA_CALIB_REF_ALT, Float.NaN)
                if (!ref.isNaN()) calibrateBaro(ref)
            }
        }
        return START_STICKY
    }

    // ===== Calibration (unchanged) =====

    /**
     * One-shot KF seeding. Does NOT store a persistent offset anywhere —
     * the KF's `b` state absorbs any remaining weather bias. If the filter
     * was already running, velocity and bias-rate covariances are preserved
     * in the 3/4/GPS-2-state models; the 2/3-state cascades are reseeded
     * (the snap is a discontinuity). CUSUM is reset by the filter itself.
     *
     * The user-facing entry point is the live card's "recalibrate" link on
     * the Track tab (BaroCalibrationDialog); the KF tab's Seed button uses
     * the same dialog and the same filter path.
     *
     * No-op when Kalman filtering is disabled — the dialog hides the link,
     * but guard here too so a stale intent from before a config flip cannot
     * seed an un-fed filter.
     */
    private fun calibrateBaro(refAltM: Float) {
        if (!kfActive) { Log.w(TAG, "Calibration ignored: Kalman filter disabled"); return }
        if (config.airplaneMode) { Log.w(TAG, "Calibration disabled in airplane mode"); return }
        val pressure = lastBaroPressureHpa
        if (pressure == null) { Log.w(TAG, "Cannot calibrate: no baro reading yet"); return }
        val rawBaro = rawBaroAltitude(pressure)
        altitudeFilter.resetWithAltitude(refAltM, rawBaro)

        val qnh = altitudeFilter.qnhHpa(pressure)
        val cusum = altitudeFilter.airconCusum()
        _status.update {
            it.copy(
                currentBaroAltitudeM = rawBaro,
                currentPressureHpa = pressure,
                kalmanAltitudeM = refAltM,
                verticalSpeedMps = altitudeFilter.verticalSpeed(),
                currentQnhHpa = qnh ?: it.currentQnhHpa,
                kalmanBiasM = altitudeFilter.bias(),
                kalmanBiasRateMps = altitudeFilter.biasRate(),
                kalmanAltitudeSigmaM = altitudeFilter.altitudeSigma(),
                kalmanBiasSigmaM = altitudeFilter.biasSigma(),
                lastGpsInnovationM = altitudeFilter.lastGpsInnovationM(),
                lastBaroInnovationM = altitudeFilter.lastBaroInnovationM(),
                airconCusumPos = cusum.first,
                airconCusumNeg = cusum.second,
                airconDetected = altitudeFilter.airconDetected(),
            )
        }
        Log.i(TAG, "Calibrated KF: ref=%.2fm rawBaro=%.2fm bias=%.2fm"
            .format(refAltM, rawBaro, altitudeFilter.bias() ?: 0f))
    }

    // ===== Core state machine =====

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
            state = fixState,
            event = event,
            maxInaccuracyM = config.maxInaccuracyM,
            isSingleFixMode = isSingleFixMode,
        )
        fixState = transition.state
        executeEffects(transition.effects)
    }

    private fun executeEffects(effects: List<FixEffect>) {
        for (effect in effects) {
            when (effect) {
                is FixEffect.ProvidedFix -> _status.update { it.copy(lastProvidedFix = effect.point) }
                is FixEffect.AcceptFix -> {
                    // lastAcceptedFix is updated inside bufferPoint(), only
                    // when the fix actually enters the buffer. The former
                    // update-here-then-try-buffer pattern updated it even for
                    // rate-rejected fixes, so the live card's "accepted" line
                    // could show a fix that was never stored. "Accepted" now
                    // implies "enqueued"; the two fields always advance
                    // together — the honest reading of the provided →
                    // accepted → enqueued chain.
                    val previousAccepted = _status.value.lastAcceptedFix
                    if (recording) {
                        bufferPoint(effect.point, previousAccepted)
                    } else {
                        // Preview-only: no buffer, so accept is terminal.
                        _status.update { it.copy(lastAcceptedFix = effect.point) }
                    }
                }
                is FixEffect.RequestGpsFix -> requestSingleGpsFix()
                is FixEffect.ScheduleNext -> fixScheduler.schedule(strategy)
                is FixEffect.AcquireFixWakeLock -> acquireFixWakeLock()
                is FixEffect.ReleaseFixWakeLock -> releaseFixWakeLock()
                is FixEffect.SkipCycle -> Log.d(TAG, "Skipping fix cycle: ${effect.reason}")
            }
        }
    }

    /**
     * slope = 100 · v_z / v_h. v_z is model-agnostic — the filter returns
     * the 3/4/GPS-2-state velocity state when it exists, or the cascade
     * position-difference filter's output in 2-state — so this function
     * does not need to know which model is active. Null when v_z is not
     * available yet, when v_h is too small for a meaningful slope, or when
     * the Kalman filter is disabled.
     */
    private fun updateSlope(groundSpeedMps: Float?) {
        val vz = _status.value.verticalSpeedMps
        if (vz == null || groundSpeedMps == null || groundSpeedMps < MIN_SPEED_FOR_SLOPE_MPS) {
            _status.update { it.copy(slopePercent = null) }
            return
        }
        _status.update { it.copy(slopePercent = 100f * vz / groundSpeedMps) }
    }

    // ===== Live distance =====

    private fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val h = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
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

    private fun resetDistanceBaseline() { lastDistanceFix = null }

    // ===== Preview =====

    private fun startPreview() {
        if (recording || previewing) return
        if (!appPrefs.isBackgroundGpsEnabled()) { Log.w(TAG, "Preview disabled"); stopSelf(); return }
        if (!hasLocationPermission()) { Log.w(TAG, "No location permission for preview"); stopSelf(); return }

        previewing = true
        fixState = FixState.Idle
        recordingStartElapsedNanos =
            SystemClock.elapsedRealtimeNanos() - config.fixTimeoutMs * 1_000_000L
        _status.update { it.copy(previewActive = true) }

        scope.launch {
            val cfg = configPrefs.get()
            config = cfg.toRecorderConfig()
            strategy = Background.strategyFor(config.mode, config.intervalMs)
            altitudeFilter = buildFilter(config)

            startForeground(NOTIFICATION_ID, buildPreviewNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)

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
                previewActive = false,
                lastProvidedFix = null,
                lastAcceptedFix = null,
                lastEnqueuedFix = null,
                lastHeadingDegrees = null,
                liveDistanceM = null,
                currentBaroAltitudeM = null,
                currentPressureHpa = null,
                kalmanAltitudeM = null,
                currentQnhHpa = null,
                verticalSpeedMps = null,
                slopePercent = null,
                kalmanBiasM = null,
                kalmanBiasRateMps = null,
                kalmanAltitudeSigmaM = null,
                kalmanBiasSigmaM = null,
                lastGpsInnovationM = null,
                lastBaroInnovationM = null,
                airconCusumPos = null,
                airconCusumNeg = null,
                airconDetected = false,
            )
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ===== Recording lifecycle =====

    private fun startRecording() {
        if (recording) return
        if (!appPrefs.isBackgroundGpsEnabled()) { Log.w(TAG, "Recording disabled"); stopSelf(); return }
        if (!hasLocationPermission()) { Log.w(TAG, "No location permission"); stopSelf(); return }

        val wasPreviewing = previewing
        recording = true
        previewing = false
        fixState = FixState.Idle
        recordingStartElapsedNanos =
            SystemClock.elapsedRealtimeNanos() - config.fixTimeoutMs * 1_000_000L
        _status.update { it.copy(isRecording = true, previewActive = false) }

        scope.launch {
            if (!wasPreviewing) {
                val cfg = configPrefs.get()
                config = cfg.toRecorderConfig()
                strategy = Background.strategyFor(config.mode, config.intervalMs)
                altitudeFilter = buildFilter(config)
                startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                applyBarometric()
                applyWakeLock()
                applyEphemeris()
                beginFixCycle()
                observeConfigChanges()
            }
            val (tId, sId) = repository.startRecording(config.autoSplitOnDayRollover)
            trackId = tId
            segmentId = sId
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
        recording = false
        previewing = true
        val capturedBuffer = buffer
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
        recording = false
        previewing = true
        val capturedBuffer = buffer
        val capturedTrackId = trackId
        buffer = null
        trackId = 0L
        segmentId = 0L
        currentDate = ""
        resetDistanceBaseline()
        _status.update {
            it.copy(
                isRecording = false,
                previewActive = true,
                lastEnqueuedFix = null,
                liveDistanceM = null,
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

    /**
     * Full teardown of GNSS resources. Closes the middleware session
     * (continuous), stops the passive listener (passive), and cancels the
     * ephemeris warm-up listener.
     */
    private fun teardownGps() {
        dispatchFixEvent(FixEvent.Cancel)
        fixTimeoutJob?.cancel(); fixTimeoutJob = null
        configJob?.cancel(); configJob = null

        gnssFixJob?.cancel(); gnssFixJob = null
        gnssSession?.close(); gnssSession = null

        passiveListener?.let { locationManager.removeUpdates(it) }
        passiveListener = null
        passiveActive = false

        stopEphemerisWarmup()
        stopBarometric()

        // gnssInfo is NOT cleared here — it represents "satellite state at
        // last observation" and is useful to keep visible through brief
        // stops (preview stop on screen-off, tab switch). It IS cleared by
        // onDestroy (full status reset).

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

    // ===== Config observation =====

    private fun observeConfigChanges() {
        configJob?.cancel()
        configJob = scope.launch {
            configPrefs.observe()
                .map { it.toRecorderConfig() }
                .distinctUntilChanged()
                .collect { newConfig -> if (newConfig != config) applyConfigChange(newConfig) }
        }
    }

    /**
     * Applies a runtime config change.
     *
     * Method/mode changes are applied in place — h, v, b, v_b, and P all
     * survive. Recreating the filter there would blank altitude/sigma for
     * several seconds after every settings toggle, which read as "GPS
     * reset" in the field.
     *
     * Model changes now also carry state across (h, b, v_z, v_b and their
     * covariance block, see AltitudeKalmanFilter.transitionModel), so a
     * 3↔4 or 2↔4 swap does not blank the filter for several seconds while
     * it re-seeds. The RTS smoother's step-record buffer is the KF tab's
     * concern, not the recorder's; the recorder never owned one, so
     * nothing to clear here. Tuning changes that affect process noise
     * re-instantiate the model object and leave x/P/initialized untouched
     * — the accumulated b survives a q_b or q_vb slider change, which is
     * the whole point.
     *
     * The isInitialized() guard below now fires only when the filter
     * genuinely was not seeded yet (before the first GPS fix); the KF-
     * derived status fields are cleared then to keep the UI honest about
     * "no state yet". A baroSigmaM-, gpsVerticalMinM- or
     * derivedBiasRateNoise-only change touches neither state nor model —
     * both live at the caller.
     *
     * QNH is not cleared in either branch: the last-known-good outside
     * reading remains useful and is overwritten by the next post-reseed
     * baro tick. CUSUM is reset by the filter on any state-affecting
     * change (transitionModel, reset, resetWithAltitude, seed).
     *
     * Enabling / disabling the Kalman filter is a distinct branch: the
     * filter is rebuilt from scratch (there is nothing meaningful to carry
     * across a switch that turns the state estimator on or off), and the
     * KF-derived status fields are cleared so the live card drops from
     * numbers to "--" immediately instead of showing a stale value from
     * before the flip.
     *
     * Airplane mode is handled differently per model, and that
     * model-dependence lives inside [AltitudeKalmanFilter.setAirplaneMode]:
     *   - Fusion models: cabin pressure invalidates the bias state, so the
     *     filter resets. The method returns true so this caller can do its
     *     own reset-side bookkeeping (distance baseline, status clear).
     *   - No-baro models: nothing is invalidated; only the q_v variant
     *     changes. Returns false, no caller bookkeeping needed.
     * The call is made first, before any model swap, so the flag is
     * current when a subsequent `setModelAndTuning` reinstantiates the
     * model under the new q_v.
     */
    private suspend fun applyConfigChange(newConfig: BackgroundConfig) {
        buffer?.flush()

        if (config.kalmanEnabled != newConfig.kalmanEnabled) {
            altitudeFilter = buildFilter(newConfig)
            clearKfStatus()
            // QNH is a filter-derived value — with no filter running there
            // is no altitude to derive it from, so the cached reading would
            // be stale. Cleared here, not in clearKfStatus(), because every
            // other transition (model/tuning/airplane) preserves it.
            _status.update { it.copy(currentQnhHpa = null) }
        } else if (newConfig.kalmanEnabled) {
            // Sync airplane mode first, regardless of whether the model is
            // also changing, so any subsequent reinstantiation observes the
            // correct value. The return says whether the state was
            // invalidated (fusion model) or preserved (no-baro model).
            if (config.airplaneMode != newConfig.airplaneMode) {
                val wasReset = altitudeFilter.setAirplaneMode(newConfig.airplaneMode)
                if (wasReset) {
                    resetDistanceBaseline()
                    clearKfStatus()
                }
            }
            altitudeFilter.setModelAndTuning(newConfig.kalmanModel, newConfig.kalmanTuning)
            if (!altitudeFilter.isInitialized()) clearKfStatus()
            if (config.kalmanMode != newConfig.kalmanMode) {
                altitudeFilter.setMode(newConfig.kalmanMode)
            }
            if (config.iskfInverseHessian != newConfig.iskfInverseHessian) {
                altitudeFilter.setIskfInverseHessian(newConfig.iskfInverseHessian)
            }
        }

        dispatchFixEvent(FixEvent.Cancel)
        fixTimeoutJob?.cancel(); fixTimeoutJob = null
        config = newConfig
        strategy = Background.strategyFor(config.mode, config.intervalMs)
        buffer?.updateConfig(config.mode, config.intervalMs)

        // Tear down the current GNSS path before restarting with the new
        // one. Each path is stopped by its own teardown function.
        gnssFixJob?.cancel(); gnssFixJob = null
        gnssSession?.close(); gnssSession = null
        passiveListener?.let { locationManager.removeUpdates(it) }
        passiveListener = null
        passiveActive = false
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

    // ===== Fix cycle =====

    private fun beginFixCycle() {
        checkDayRollover()
        when (strategy) {
            is FixStrategy.Continuous -> startContinuousUpdates()
            is FixStrategy.DelayedSingle -> dispatchFixEvent(FixEvent.StartCycle)
            is FixStrategy.AlarmSingle -> dispatchFixEvent(FixEvent.StartCycle)
            is FixStrategy.Passive -> startPassiveUpdates()
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
            trackId = tId
            segmentId = sId
            buffer?.setSegmentId(segmentId)
            resetDistanceBaseline()
            _status.update { it.copy(liveDistanceM = 0f) }
        }
    }

    /**
     * CONTINUOUS: acquire a middleware session at the configured hardware
     * interval. Every fix from the active source flows through
     * [emitFix]. The middleware throttles delivery at the minimum interval
     * across all sessions — if another consumer (GNSS tab) holds a faster
     * session, we get their faster cadence and rely on TrackPointBuffer's
     * own `intervalMs` rate filter for storage spacing.
     */
    private fun startContinuousUpdates() {
        if (!hasLocationPermission()) return
        if (gnssSession != null) return

        gnssSession = gnssController.acquire(config.effectiveHardwareIntervalMs)
        gnssFixJob = scope.launch {
            gnssController.fixes.collect { fix -> emitFix(fix) }
        }
    }

    /**
     * PASSIVE: middleware has no passive source. Register a listener
     * directly — the same shape the recorder has always used in this mode.
     * Fixes go through [emitFix] via [buildPassiveFix], so the rest of
     * the pipeline is uniform.
     */
    private fun startPassiveUpdates() {
        if (!hasLocationPermission()) return
        if (passiveActive) return
        try {
            locationManager.requestLocationUpdates(
                LocationManager.PASSIVE_PROVIDER, 0L, 0f, passiveLocationListener)
            passiveListener = passiveLocationListener
            passiveActive = true
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission lost", e)
            stopRecording()
        }
    }

    // ===== Single GPS fix (SINGLE_FIX / ALARM modes) =====

    /**
     * One-shot fix via the middleware. The controller delegates to the
     * active source's `requestSingleFix`, which warms the engine and
     * waits for one complete fix — or times out.
     */
    private fun requestSingleGpsFix() {
        if (!hasLocationPermission()) { dispatchFixEvent(FixEvent.Timeout); return }
        fixTimeoutJob?.cancel()
        fixTimeoutJob = scope.launch {
            val fix = withTimeoutOrNull(config.fixTimeoutMs) {
                gnssController.requestSingleFix(config.fixTimeoutMs)
            }
            fixTimeoutJob = null
            if (fix != null) {
                emitFix(fix)
            } else {
                dispatchFixEvent(FixEvent.Timeout)
            }
        }
    }

    // ===== Fix emission (unified across modes) =====

    /**
     * Route a fix into the state machine, buffer, and filter.
     *
     * Runs for every fix from every path — continuous via middleware,
     * single-fix via middleware, passive via legacy listener. Everything
     * downstream (FixStateMachine, TrackPointBuffer, KF, distance
     * accumulation) sees only [TrackPointEntity], so source identity does
     * not leak past this point.
     */
    private fun emitFix(fix: GnssFix) {
        val ts = fix.monotonicNanos
        if (ts == lastFixRealtimeNanos) return
        if (ts < recordingStartElapsedNanos) return
        lastFixRealtimeNanos = ts

        val point = convertFix(fix)

        // Heading: raw from the fix, no filtering. Non-null bearing updates
        // the display; null bearing leaves the previous value in place so
        // the cell doesn't flap to "--" when the chipset momentarily drops
        // the course. No LPF, no threshold, no state — what the GNSS engine
        // says is what shows.
        if (point.bearing != null) {
            _status.update { it.copy(lastHeadingDegrees = point.bearing) }
        }

        // Satellites: unified from fix. In LOCATION mode, from
        // GnssStatus.Callback; in NMEA mode, from GSV + GSA.
        val gnssInfo = gnssInfoFrom(fix)
        if (gnssInfo.constellations.isNotEmpty()) {
            _status.update { it.copy(gnssInfo = gnssInfo) }
        }

        // Kalman filter GPS update.
        val gpsAlt = point.ele
        if (gpsAlt != null && kfActive) {
            val vAcc = gpsMeasurementSigma(fix)
            altitudeFilter.updateGps(gpsAlt, vAcc)
            _status.update {
                it.copy(
                    kalmanAltitudeM = altitudeFilter.altitude(),
                    verticalSpeedMps = altitudeFilter.verticalSpeed(),
                    kalmanBiasM = altitudeFilter.bias(),
                    kalmanBiasRateMps = altitudeFilter.biasRate(),
                    kalmanAltitudeSigmaM = altitudeFilter.altitudeSigma(),
                    kalmanBiasSigmaM = altitudeFilter.biasSigma(),
                    lastGpsInnovationM = altitudeFilter.lastGpsInnovationM(),
                    lastBaroInnovationM = altitudeFilter.lastBaroInnovationM(),
                )
            }
        }

        dispatchFixEvent(FixEvent.FixProvided(point))
        updateSlope(point.speedMs)
    }

    private fun gnssInfoFrom(fix: GnssFix): GnssInfo {
        if (fix.satellites.isEmpty()) return GnssInfo.EMPTY
        val grouped = fix.satellites.groupBy { it.constellation }
        return GnssInfo(
            constellations = grouped.mapValues { (_, sats) ->
                ConstellationCount(
                    used = sats.count { it.usedInFix },
                    visible = sats.size,
                )
            }
        )
    }

    /**
     * Build a GnssFix from a Location for the PASSIVE path. Not used in
     * CONTINUOUS or SINGLE_FIX modes — those get full fidelity fixes
     * from the middleware. Passive has no satellite enrichment (that is
     * the nature of the passive provider: another app owns the engine).
     */
    private fun buildPassiveFix(location: Location): GnssFix {
        val undulation = geoidGrid.undulation(location.latitude, location.longitude)
        val altWgs84 = if (location.hasAltitude()) location.altitude else null
        return GnssFix(
            source = GnssSourceType.LOCATION,
            timestamp = UTCDateTime.fromEpochMillis(location.time),
            monotonicNanos = location.elapsedRealtimeNanos,
            position = Position(location.latitude, location.longitude),
            altitudeMslM = altWgs84?.minus(undulation),
            altitudeWgs84M = altWgs84,
            speedMps = if (location.hasSpeed()) location.speed.toDouble() else null,
            courseDeg = if (location.hasBearing()) location.bearing.toDouble() else null,
            magVarDeg = null,
            fixQuality = null,
            fixDimension = null,
            satellitesUsed = null,
            satellites = emptyList(),
            dop = Dop.NONE,
            sigma = Sigma(
                horizontalM = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
                verticalM = if (location.hasVerticalAccuracy())
                    location.verticalAccuracyMeters.toDouble() else null,
            ),
            sbasActive = false,
            sourceLocation = location,
        )
    }

    // ===== Point buffering (unchanged) =====

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
                if (enqueued) {
                    accumulateLiveDistance(point)
                    _status.update {
                        it.copy(
                            pendingPoints = buf.pendingCount(),
                            lastAcceptedFix = point,
                            lastEnqueuedFix = point,
                        )
                    }
                } else {
                    _status.update { it.copy(pendingPoints = buf.pendingCount()) }
                }
            }
            return
        }

        val enqueued = buf.add(point)
        if (enqueued) {
            accumulateLiveDistance(point)
            _status.update {
                it.copy(
                    pendingPoints = buf.pendingCount(),
                    lastAcceptedFix = point,
                    lastEnqueuedFix = point,
                )
            }
        } else {
            _status.update { it.copy(pendingPoints = buf.pendingCount()) }
        }
        if (buf.shouldFlush()) {
            scope.launch {
                buf.flush()
                _status.update { it.copy(pendingPoints = buf.pendingCount()) }
            }
        }
    }

    // ===== Barometric (unchanged in structure) =====

    private fun applyBarometric() {
        // Sensor is needed when the user stores baro per point OR when the
        // active KF model fuses baro. recordBaroAltitude is about storage;
        // the model's hasBaroMeasurement declares whether baro is in its
        // measurement map. Previously only recordBaroAltitude was checked,
        // so KF-on + storage-off left the model waiting for a baro update
        // that never came — silent misconfiguration, b never converges.
        val needed = (config.recordBaroAltitude ||
            (config.kalmanEnabled && config.kalmanModel.hasBaroMeasurement)) &&
            pressureSensor != null
        if (needed) {
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
                currentPressureHpa = null,
                currentQnhHpa = null,
                kalmanBiasM = null,
                kalmanBiasRateMps = null,
                kalmanBiasSigmaM = null,
                lastBaroInnovationM = null,
                airconCusumPos = null,
                airconCusumNeg = null,
                airconDetected = false,
            )
        }
    }

    // ===== Wake lock (unchanged) =====

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

    // ===== Ephemeris warm-up (unchanged) =====

    private fun applyEphemeris() {
        stopEphemerisWarmup()
        if (config.mode == RecordingMode.PASSIVE) return
        if (!config.keepEphemerisWarm) return
        if (!hasLocationPermission()) return
        try {
            locationManager.requestLocationUpdates(
                LocationManager.PASSIVE_PROVIDER, 0L, 0f, ephemerisLocationListener)
            ephemerisListener = ephemerisLocationListener
            ephemerisWarmupActive = true
        } catch (e: SecurityException) {
            Log.e(TAG, "Ephemeris warmup failed", e)
        }
    }

    private fun stopEphemerisWarmup() {
        if (!ephemerisWarmupActive) return
        ephemerisListener?.let { locationManager.removeUpdates(it) }
        ephemerisListener = null
        ephemerisWarmupActive = false
    }

    // ===== Notifications (unchanged) =====

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

    // ===== Lifecycle =====

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!recording) {
            stopPreview()
        } else {
            appScope.launch { buffer?.flushRemaining() }
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        recording = false
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
    mode = mode,
    intervalMs = intervalMs,
    hardwareIntervalMs = hardwareIntervalMs,
    fixTimeoutMs = fixTimeoutMs,
    maxInaccuracyM = maxInaccuracyM,
    keepEphemerisWarm = keepEphemerisWarm,
    holdWakeLock = holdWakeLock,
    recordBaroAltitude = recordBaroAltitude,
    kalmanEnabled = kalmanEnabled,
    airplaneMode = airplaneMode,
    autoSplitOnDayRollover = autoSplitOnDayRollover,
    autoSegmentGapMs = autoSegmentGapMs,
    kalmanModel = kalmanModel,
    kalmanMode = kalmanMode,
    iskfInverseHessian = iskfInverseHessian,
    kalmanTuning = toKalmanTuning(),
)
