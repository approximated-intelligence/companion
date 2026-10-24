package de.perigon.companion.sensors.ui.dashboard

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import de.perigon.companion.core.geo.geoid.GeoidGrid
import de.perigon.companion.sensors.data.TrackConfigPrefs
import de.perigon.companion.sensors.data.TrackPrefs
import de.perigon.companion.sensors.domain.AltitudeKalmanFilter
import de.perigon.companion.sensors.domain.KalmanMode
import de.perigon.companion.sensors.domain.KalmanModel
import de.perigon.companion.sensors.domain.KalmanTuning
import de.perigon.companion.sensors.domain.KfStepRecord
import de.perigon.companion.sensors.domain.UpdateSource
import de.perigon.companion.sensors.service.KfSandboxService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class KfSample(
    // Monotonic elapsedRealtime timestamp of this chart sample. Matched
    // against KfStepRecord.timeMs (also elapsedRealtime) so the RTS
    // smoother's per-step output can be folded into the chart history by
    // nearest-timestamp.
    val timeMs: Long = 0L,
    val h: Float?,
    val v: Float?,
    val b: Float?,
    val vB: Float?,
    val sigmaH: Float?,
    val sigmaV: Float?,
    val sigmaB: Float?,
    val sigmaVB: Float?,
    val deltaGps: Float?,
    val deltaBaro: Float?,
    val baroInput: Float?,
    val gpsInput: Float?,
    // Diagnostics (see class doc on KfUiState).
    val nisGps: Float? = null,
    val nisBaro: Float? = null,
    val cusumPos: Float? = null,
    val cusumNeg: Float? = null,
    // RTS-smoothed estimates of h and b for the same timestamp. Null until
    // the smoother's buffer covers this sample's time.
    val hSmoothed: Float? = null,
    val sigmaHSmoothed: Float? = null,
    val bSmoothed: Float? = null,
)

data class KfUiState(
    val model: KalmanModel = KalmanModel.THREE_STATE,
    val mode: KalmanMode = KalmanMode.GAUSSIAN,
    val tuning: KalmanTuning = KalmanTuning(),
    val gnssEnabled: Boolean = true,
    // Tab-local: whether the baro measurement is fed to the filter. Defaults
    // ON — fusing GPS + baro is the filter's intended use, and the tab is a
    // sandbox with none of the recorder's reasons for shipping the feature
    // off (battery, per-point DB writes, an extra GPX column). The listener
    // stays registered regardless so the raw reading is always available;
    // this toggle only gates filter.updateBaro(). Ignored by GPS_TWO_STATE,
    // which has no baro measurement in its map.
    val baroEnabled: Boolean = true,
    // Tab-local. Only applied in ISKF mode; the toggle is only shown there.
    val iskfInverseHessian: Boolean = false,
    // Tab-local: subtract geoid undulation from GPS altitude before feeding.
    // On by default because the recorder always applies it.
    val geoidEnabled: Boolean = true,
    // PERSISTED across tab changes and app restarts via TrackPrefs, unlike
    // the other toggles. Read from prefs at KfViewModel construction; written
    // on every toggle. Rationale: the whole point of the wake-lock is to let
    // the user leave the device unattended for hours; re-enabling it every
    // time they enter the tab would defeat the purpose. The *active* lock is
    // still session-scoped (started when the FGS starts, released when it
    // stops); only the *preference* persists.
    val wakeLockEnabled: Boolean = false,
    // Airplane mode for the GPS-only model's q_v selection. Ignored by the
    // fusion models in the sandbox — their baro exclusion is handled by the
    // baroEnabled toggle above. Only shown when model.hasBias is false.
    val airplaneMode: Boolean = false,
    val isInitialized: Boolean = false,
    val hadGpsSeed: Boolean = false,
    val h: Float? = null,
    val v: Float? = null,
    val b: Float? = null,
    val vB: Float? = null,
    val sigmaH: Float? = null,
    val sigmaV: Float? = null,
    val sigmaB: Float? = null,
    val sigmaVB: Float? = null,
    val deltaGps: Float? = null,
    val deltaBaro: Float? = null,
    val baroInput: Float? = null,
    val gpsInput: Float? = null,
    val baroPressureHpa: Float? = null,
    val geoidUndulationM: Float? = null,
    val gpsSigmaM: Float? = null,
    val hasBarometer: Boolean = false,
    val huberWeight: Float? = null,
    val vbEstimatedR: Float? = null,
    val iskfProcessOutlierNorm: Float? = null,
    val iskfMeasOutlier: Float? = null,
    val lastUpdateSource: UpdateSource? = null,
    // Running means of NIS² over a bounded window. Well-calibrated model:
    // ≈ 1.0. >> 1: overconfident (R_gps underreported, or P too small).
    // << 1: underconfident, OR (under robust modes) many rejected samples
    // with inflated effective S. The chart separates the two cases by
    // showing the per-sample distribution.
    val nisGpsMean: Float? = null,
    val nisBaroMean: Float? = null,
    val cusumPos: Float? = null,
    val cusumNeg: Float? = null,
    val airconDetected: Boolean = false,
    val history: List<KfSample> = emptyList(),
)

/**
 * Runs a standalone AltitudeKalmanFilter for the KF debug tab.
 *
 * TRUE SANDBOX, with one deliberate exception: a foreground service
 * ([KfSandboxService]) keeps the process in FOREGROUND_SERVICE_LOCATION
 * state while the tab is composed, so the filter keeps running (with the
 * screen off) long enough to converge. The tab never reads or writes
 * TrackConfigPrefs during normal operation; the only write path is
 * [applyToTrackSettings], an explicit promotion. Changing a chip in this
 * tab changes only this filter's configuration (state model or update
 * rule); the recorder's filter is untouched until the same setting is
 * changed on the Track tab. The point of the tab is to answer "what would
 * the filter do with these inputs?" without altering — or being altered
 * by — the session you are trying to inspect.
 *
 * Defaults are the filter's INTENDED configuration (3-state, Gaussian, GPS
 * + baro fused, geoid applied), which is what the tab exists to show. They
 * deliberately do not mirror the recorder's shipped defaults: the recorder
 * ships baro off because enabling it has downstream costs a user should
 * opt into (battery, per-point DB writes, GPX size), none of which apply
 * to the sandbox. Tuning starts from [KalmanTuning] defaults (all null).
 *
 * The raw baro reading is shown whether or not it is fused: the question
 * "how far is the sensor from the filter's altitude?" is exactly what the
 * tab exists to answer, and it is independent of the fusion toggle.
 *
 * Toggling geoid mid-session is not reset by design: watching the filter
 * absorb the step between ellipsoidal and orthometric altitude is more
 * informative than a blank chart.
 *
 * Tuning field naming: process-noise fields use the filter's internal
 * representation directly — q_b in m²/s, q_v in m²/s³, q_vb in m²/s³. Not
 * σ, not squared σ. See [KalmanTuning] for the convention.
 *
 * RTS smoother buffer: bounded by TIME (BUFFER_WINDOW_MS), not by sample
 * count. A sample-count cap is coupled to sensor rate; a time window
 * guarantees the smoother always covers the chart's history.
 *
 * State preservation across model/tuning changes is a first-class
 * property here: the whole point of the sandbox is to compare 2-state vs
 * 3-state vs 4-state vs GPS 2-state under the *same* trajectory. `setModel`
 * carries h, b, v_z, v_b across the dimension swap (see
 * [de.perigon.companion.sensors.domain.AltitudeKalmanFilter.transitionModel]),
 * and `setTuning` reinstantiates the model object without touching state.
 * The chart's filtered trace is continuous across both. Diagnostics that
 * cannot span the swap are cleared: the RTS step-record buffer (the
 * backward chain cannot cross a dimension change) so `b*`/`h*` show a
 * brief gap until records accumulate, and the NIS running windows (the
 * mean is a χ²(1) statistic, meaningful only under a single well-
 * specified model).
 *
 * Seeding: [calibrateBaro] mirrors the Track tab's calibration dialog —
 * user-supplied reference altitude snaps h and b via
 * AltitudeKalmanFilter.resetWithAltitude. Same dialog reused via
 * [de.perigon.companion.sensors.ui.BaroCalibrationDialog]. The bias-rate
 * cascade/state and the CUSUM are reset by the filter (pre-calibration
 * data carries no information about aircon).
 *
 * [applyToTrackSettings] is the only path by which this tab writes to
 * TrackConfigPrefs. Explicit and one-way (the tab does not read config):
 * after promoting, the tab's own tuning copy is untouched, so further
 * edits are not auto-synced back to Track.
 */
@HiltViewModel
class KfViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val geoidGrid: GeoidGrid,
    private val configPrefs: TrackConfigPrefs,
    private val prefs: TrackPrefs,
) : ViewModel() {

    private val filter = AltitudeKalmanFilter(
        initialModel = KalmanModel.THREE_STATE,
        initialMode = KalmanMode.GAUSSIAN,
        initialIskfInverseHessian = false,
        initialTuning = KalmanTuning(),
        initialAirplaneMode = false,
    )

    // Wake-lock preference is persisted; read synchronously from the warmed
    // snapshot at construction, same as any other prefs read in the sensors
    // module.
    private val _state = MutableStateFlow(KfUiState(
        wakeLockEnabled = prefs.isKfWakeLockEnabled(),
    ))
    val state: StateFlow<KfUiState> = _state.asStateFlow()

    private var locationListener: LocationListener? = null
    private var pressureListener: SensorEventListener? = null
    private var sensorManager: SensorManager? = null
    private var gnssActive = false
    private var baroActive = false

    // FGS lifecycle tracking. Set true after a successful startForegroundService
    // intent; cleared when we tell the service to stop. Used to skip action
    // intents (wake-lock toggle) that would otherwise try to start the
    // service as non-foreground while it is not running.
    private var serviceStarted = false

    private var lastHistoryMs = 0L
    private val history = ArrayDeque<KfSample>(HISTORY_SIZE + 1)

    // RTS smoother buffer. Time-bounded (see BUFFER_WINDOW_MS), with a
    // hard sample cap as a backstop against a pathological sensor rate.
    // Cleared on model swap (dimension change) and on gap detection (a
    // filter reset means the forward pass has a discontinuity the smoother
    // should not bridge). NOT cleared on a tuning change — the filter's
    // state survives, so the forward pass is continuous and the buffer
    // remains valid.
    private val stepRecords = ArrayDeque<KfStepRecord>()

    // NIS running windows. Sampled per measurement, not per chart tick, so
    // the mean reflects the actual measurement distribution rather than a
    // downsampled one. Cleared on model swap (along with stepRecords):
    // the mean is a χ²(1) statistic only under a single well-specified
    // model, so mixing pre- and post-swap samples makes it meaningless.
    //
    // Also cleared on airplane-mode toggle for GPS-only models: the q_v
    // variant is a different specification, and its innovations belong to
    // a different χ²(1) population. Fusion-model airplane toggles reset
    // the filter (see AltitudeKalmanFilter.setAirplaneMode) and are
    // handled by the standard reset path — no separate clear needed here.
    private val nisGpsWindow = ArrayDeque<Float>(NIS_WINDOW)
    private val nisBaroWindow = ArrayDeque<Float>(NIS_WINDOW)

    companion object {
        const val HISTORY_SIZE = 180
        const val HISTORY_INTERVAL_MS = 500L
        private const val GPS_VERTICAL_DEFAULT_M = 15f
        private const val GPS_VERTICAL_FALLBACK_FACTOR = 3f
        // Time window for the RTS smoother buffer. Rate-independent (unlike
        // a sample count) so slow GPS-only configurations get the same
        // coverage as fast baro configurations. 180s covers the 90s history
        // window with 2× margin.
        private const val BUFFER_WINDOW_MS = 180_000L
        // Absolute sample cap to protect against a pathological sensor rate
        // (some devices fire TYPE_PRESSURE at 100+ Hz). At 20 Hz (high but
        // plausible for baro), 4000 samples = 200s of data, comfortably
        // above the 180s window.
        private const val BUFFER_HARD_MAX = 4000
        private const val NIS_WINDOW = 100
    }

    fun start() {
        // Hardware detection, independent of the fusion toggle: the switch
        // must be disabled on phones without a pressure sensor.
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        _state.update { it.copy(hasBarometer = sm.getDefaultSensor(Sensor.TYPE_PRESSURE) != null) }

        // Always start both listeners. The baro listener is what feeds the
        // raw pressure readout; whether its measurement is FUSED is the
        // separate baroEnabled toggle. Starting/stopping the listener on a
        // toggle would give the tab two different "baro is off" states that
        // look the same in the UI but read differently in diagnostics.
        startBaro()
        if (_state.value.gnssEnabled) startGnss()

        // FGS: keeps the process high-priority so screen-off does not throttle
        // location/sensor delivery. Permission-gated because Android 14+
        // requires fine location to start a location-type FGS; if it isn't
        // granted yet (RequireAccess gate is showing), startForegroundService
        // would throw. The RequireAccess content block calls start() again
        // after permission, which will succeed.
        startSandboxService()
    }

    fun stop() {
        stopGnss()
        stopBaro()
        stopSandboxService()
    }

    // ---- Toggles (all tab-local, none writes to config) ----

    /**
     * Model changes transplant compatible state across the dimension swap
     * (h, b, v_z, v_b and their covariance block; see
     * AltitudeKalmanFilter.transitionModel). History survives — the chart
     * continues across the swap, which is what makes 2↔3↔4↔GPS2 comparison
     * meaningful. The RTS step-record buffer and the NIS running windows
     * are cleared: the backward chain cannot span a dimension change, and
     * a χ²(1) mean mixes two models' calibration. So `b*`/`h*` show a gap
     * until records accumulate, and NIS restarts from empty; the filtered
     * trace itself is continuous.
     *
     * If the filter was not yet seeded (before the first GPS fix), there
     * is nothing to preserve; clearFilterState() handles the honest reset.
     */
    fun setModel(m: KalmanModel) {
        if (_state.value.model == m) return
        val wasInit = filter.isInitialized()
        filter.setModel(m)
        // The smoother's backward chain cannot bridge a dimension change;
        // drop the buffer. History survives so the swap is visible on the
        // chart.
        stepRecords.clear()
        // NIS is χ²(1) only under a single well-specified model; a mixture
        // of two models' means is neither model's mean. Clear it — the
        // running mean repopulates from the first post-swap measurement.
        nisGpsWindow.clear()
        nisBaroWindow.clear()
        _state.update { it.copy(model = m) }
        if (wasInit) {
            // Publish refreshes h/v/b/v_b/σ from the post-transition state;
            // appends at most one new history sample if the interval has
            // elapsed, so the swap is visible as a continuous trace.
            publish()
        } else {
            clearFilterState(model = m)
        }
    }

    fun setMode(mode: KalmanMode) {
        if (_state.value.mode == mode) return
        filter.setMode(mode)
        _state.update { it.copy(mode = mode) }
    }

    fun setIskfInverseHessian(enabled: Boolean) {
        if (_state.value.iskfInverseHessian == enabled) return
        filter.setIskfInverseHessian(enabled)
        _state.update { it.copy(iskfInverseHessian = enabled) }
    }

    /**
     * Update the tab's tuning overrides. A change affecting process noise
     * for the current model re-instantiates the model object; x, P,
     * initialized, cascade state, history, NIS windows and the step-record
     * buffer all survive. The accumulated b (and v_b) estimates in
     * particular survive a q_b/q_vb change — the whole point of moving from
     * rebuild() to reinstantiateModel() in the filter.
     *
     * A `baroSigmaM`-, `gpsVerticalMinM`-, `gpsOnlySigmaM`-,
     * `derivedVelNoise`- or `derivedBiasRateNoise`-only change updates the
     * record without reinstantiating the model or touching state (the first
     * three live at the caller and take effect on the next measurement; the
     * last two are filter fields, updated unconditionally by the filter's
     * setTuning).
     */
    fun setTuning(newTuning: KalmanTuning) {
        if (_state.value.tuning == newTuning) return
        filter.setTuning(newTuning)
        _state.update { it.copy(tuning = newTuning) }
        // No clearFilterState: the filter guarantees non-de-initialization
        // on tuning-only change. If that ever changes, a post-condition
        // check would belong here rather than at every call site.
    }

    /**
     * Toggle the sandbox FGS's wake-lock. PERSISTED via TrackPrefs — unlike
     * the other tab toggles — because the wake-lock's purpose is overnight
     * convergence, and re-enabling it each session would defeat the point.
     * The *active* lock is still session-scoped: released when the tab is
     * left, re-acquired when the tab is re-entered.
     */
    fun setWakeLockEnabled(enabled: Boolean) {
        if (_state.value.wakeLockEnabled == enabled) return
        _state.update { it.copy(wakeLockEnabled = enabled) }
        viewModelScope.launch { prefs.setKfWakeLockEnabled(enabled) }
        if (serviceStarted) {
            context.startService(Intent(context, KfSandboxService::class.java).apply {
                action = KfSandboxService.ACTION_SET_WAKELOCK
                putExtra(KfSandboxService.EXTRA_WAKELOCK, enabled)
            })
        }
    }

    /**
     * Toggle airplane mode for the sandbox. Only meaningful for
     * [KalmanModel.GPS_TWO_STATE] (`model.hasBias == false`), whose q_v
     * variant switches when airplane mode flips. Ignored by the fusion
     * models: their baro exclusion is handled by [setBaroEnabled] instead.
     *
     * The NIS GPS window is cleared on toggle: the q_v variant is a
     * different specification, so pre- and post-toggle innovations belong
     * to different χ²(1) populations and their mean is not a meaningful
     * statistic. `stepRecords` is left alone — the smoother uses
     * [AltitudeStateModel.transitionMatrix], which is identical between
     * the ground and airplane q_v variants (only the Q differs), so the
     * backward chain remains valid.
     */
    fun setAirplaneMode(enabled: Boolean) {
        if (_state.value.airplaneMode == enabled) return
        filter.setAirplaneMode(enabled)
        nisGpsWindow.clear()
        _state.update { it.copy(airplaneMode = enabled) }
    }

    /**
     * Explicitly copy the tab's current tuning overrides to TrackConfigPrefs.
     * The tab keeps its own copy; subsequent edits are not mirrored
     * automatically. `null` fields are written as "clear the override", i.e.
     * "use the built-in default in the recorder too" — matching what the tab
     * is currently showing.
     *
     * Process-noise fields are copied as q (variance rate) directly, same
     * convention as the panel sliders and the config entity fields.
     */
    fun applyToTrackSettings() {
        val t = _state.value.tuning
        viewModelScope.launch {
            configPrefs.update { cfg ->
                cfg.copy(
                    kalmanAccelNoiseQ     = t.accelNoiseQ,
                    kalmanBiasNoiseQ      = t.biasNoiseQ,
                    kalmanQH              = t.qH,
                    kalmanDerivedVelNoise = t.derivedVelNoise,
                    kalmanBiasRateQ       = t.biasRateQ,
                    kalmanDerivedBiasRateNoise = t.derivedBiasRateNoise,
                    baroSigmaM            = t.baroSigmaM,
                    kalmanGpsVerticalMinM = t.gpsVerticalMinM,
                    gpsOnlyAccelQ         = t.gpsOnlyAccelQ,
                    gpsOnlyAccelQAirplane = t.gpsOnlyAccelQAirplane,
                    gpsOnlySigmaM         = t.gpsOnlySigmaM,
                )
            }
        }
    }

    fun setBaroEnabled(enabled: Boolean) {
        if (_state.value.baroEnabled == enabled) return
        _state.update { it.copy(baroEnabled = enabled) }
    }

    fun setGeoidEnabled(enabled: Boolean) {
        if (_state.value.geoidEnabled == enabled) return
        _state.update { it.copy(geoidEnabled = enabled) }
    }

    fun setGnssEnabled(enabled: Boolean) {
        if (_state.value.gnssEnabled == enabled) return
        _state.update { it.copy(gnssEnabled = enabled) }
        if (enabled) startGnss() else stopGnss()
    }

    /**
     * One-shot filter seed, identical to Track tab's calibration dialog.
     *
     * [refAltM] is the reference orthometric altitude (GPS height from the
     * last fix, or a manual value). [baroInput] is the current raw ISA
     * barometric altitude; the filter snaps b to baro − refAlt so any
     * remaining weather bias is absorbed immediately. If no baro reading
     * is available, only h is snapped (b is left where it was, or 0 if
     * not previously seeded) — same behavior as resetWithAltitude(null).
     *
     * Clears the CUSUM and the derived bias-rate baseline: pre-calibration
     * data was collected with a wrong b, so its rate and innovation bias
     * carry no information about aircon. The 3/4/GPS-2-state's v_z/v_b
     * states survive via calibrateCovariance.
     */
    fun calibrateBaro(refAltM: Float) {
        val baroZ = _state.value.baroInput
        filter.resetWithAltitude(refAltM, baroZ)
        publish()
    }

    fun resetFilter() {
        filter.reset()
        history.clear()
        lastHistoryMs = 0L
        clearBuffers()
        _state.update {
            it.copy(
                isInitialized = false,
                hadGpsSeed = false,
                h = null, v = null, b = null, vB = null,
                sigmaH = null, sigmaV = null, sigmaB = null, sigmaVB = null,
                deltaGps = null, deltaBaro = null,
                baroInput = null, gpsInput = null,
                baroPressureHpa = null,
                geoidUndulationM = null,
                gpsSigmaM = null,
                huberWeight = null,
                vbEstimatedR = null,
                iskfProcessOutlierNorm = null,
                iskfMeasOutlier = null,
                lastUpdateSource = null,
                nisGpsMean = null,
                nisBaroMean = null,
                cusumPos = null, cusumNeg = null,
                airconDetected = false,
                history = emptyList(),
            )
        }
    }

    private fun clearBuffers() {
        stepRecords.clear()
        nisGpsWindow.clear()
        nisBaroWindow.clear()
    }

    /**
     * Genuine cold-start display reset. Called only when there was no
     * filter state to preserve (setModel on an unseeded filter) and from
     * resetFilter(). Not called on tuning changes, where the filter's
     * state survives — see setTuning(). Not called on a model swap with a
     * seeded filter — see setModel(), which preserves history and
     * republishes instead.
     */
    private fun clearFilterState(model: KalmanModel) {
        history.clear()
        lastHistoryMs = 0L
        clearBuffers()
        _state.update {
            it.copy(
                model = model,
                isInitialized = false,
                hadGpsSeed = false,
                h = null, v = null, b = null, vB = null,
                sigmaH = null, sigmaV = null, sigmaB = null, sigmaVB = null,
                deltaGps = null, deltaBaro = null,
                baroInput = null, gpsInput = null,
                baroPressureHpa = null,
                geoidUndulationM = null,
                gpsSigmaM = null,
                huberWeight = null,
                vbEstimatedR = null,
                iskfProcessOutlierNorm = null,
                iskfMeasOutlier = null,
                lastUpdateSource = null,
                nisGpsMean = null,
                nisBaroMean = null,
                cusumPos = null, cusumNeg = null,
                airconDetected = false,
                history = emptyList(),
            )
        }
    }

    // ---- FGS lifecycle ----

    private fun startSandboxService() {
        if (serviceStarted) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return
        try {
            context.startForegroundService(Intent(context, KfSandboxService::class.java).apply {
                action = KfSandboxService.ACTION_START
            })
            serviceStarted = true
            // Re-acquire the persisted wake-lock if it was enabled in a
            // previous session. The active lock is session-scoped (released
            // by ACTION_STOP) but the preference persists.
            if (_state.value.wakeLockEnabled) {
                context.startService(Intent(context, KfSandboxService::class.java).apply {
                    action = KfSandboxService.ACTION_SET_WAKELOCK
                    putExtra(KfSandboxService.EXTRA_WAKELOCK, true)
                })
            }
        } catch (_: Exception) {
            // FGS contract refused (permission revoked between check and call,
            // or restrictive OEM policy). The sandbox still works with the
            // screen on; without the FGS it will throttle when backgrounded.
            serviceStarted = false
        }
    }

    private fun stopSandboxService() {
        if (!serviceStarted) return
        try {
            context.startService(Intent(context, KfSandboxService::class.java).apply {
                action = KfSandboxService.ACTION_STOP
            })
        } catch (_: Exception) {
            // Best effort; the service's own lifecycle will end when the
            // process is next reclaimed.
        }
        serviceStarted = false
    }

    // ---- Baro ----

    private fun startBaro() {
        if (baroActive) return
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val pressure = sm.getDefaultSensor(Sensor.TYPE_PRESSURE) ?: return

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type != Sensor.TYPE_PRESSURE) return
                val p = event.values[0]
                val z = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, p)
                // Fusion is the toggle; the reading is always published so
                // the numeric and chart output show "what baro says" even
                // while it is not being fed to the filter. That is the
                // question the tab exists to answer. The filter itself
                // no-ops updateBaro for GPS_TWO_STATE — no bias state to
                // absorb it against.
                if (_state.value.baroEnabled) {
                    val sigma = _state.value.tuning.baroSigmaM
                        ?: KalmanTuning.DEFAULT_BARO_SIGMA_M
                    filter.updateBaro(z, sigma)
                    afterMeasurement()
                }
                publish(
                    baroPressureHpa = p,
                    baroInput = z,
                )
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sm.registerListener(listener, pressure, SensorManager.SENSOR_DELAY_NORMAL)
        sensorManager = sm
        pressureListener = listener
        baroActive = true
    }

    private fun stopBaro() {
        val listener = pressureListener ?: return
        sensorManager?.unregisterListener(listener)
        pressureListener = null
        sensorManager = null
        baroActive = false
    }

    // ---- GNSS ----

    private fun startGnss() {
        if (gnssActive) return
        if (ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (!location.hasAltitude()) return
                // Measurement sigma depends on the active model. No-baro
                // models (feature read from the filter, not from the enum)
                // use gpsOnlySigmaM — the per-sample noise of the reported
                // series (quantization-limited), not verticalAccuracyMeters.
                // Fusion models use the floored verticalAccuracyMeters: the
                // Pixel HAL's reported verticalAccuracyMeters is optimistic
                // during stationary plateaus, where the solution is
                // smoothed; flooring prevents the filter from following
                // false steps and — the key reason — from attributing
                // baro/alt divergence to bias when the barometer moved
                // correctly and GPS did not. Read from the live tuning each
                // time, like baroSigmaM, so a slider change takes effect on
                // the next sample without a rebuild.
                val vAcc = if (!filter.hasBaroMeasurement) {
                    _state.value.tuning.gpsOnlySigmaM
                        ?: KalmanTuning.DEFAULT_GPS_ONLY_SIGMA_M
                } else {
                    val floor = _state.value.tuning.gpsVerticalMinM
                        ?: KalmanTuning.DEFAULT_GPS_VERTICAL_MIN_M
                    when {
                        location.hasVerticalAccuracy() ->
                            maxOf(location.verticalAccuracyMeters, floor)
                        location.hasAccuracy() ->
                            maxOf(location.accuracy * GPS_VERTICAL_FALLBACK_FACTOR, floor)
                        else -> GPS_VERTICAL_DEFAULT_M
                    }
                }
                // Geoid is read unconditionally so the numeric grid can show
                // the undulation even when the toggle is off.
                val geoidM = geoidGrid.undulation(location.latitude, location.longitude)
                val fedAlt = if (_state.value.geoidEnabled)
                    (location.altitude - geoidM).toFloat()
                else
                    location.altitude.toFloat()
                filter.updateGps(fedAlt, vAcc)
                afterMeasurement()
                publish(
                    gpsInput = fedAlt,
                    gpsSigmaM = vAcc,
                    geoidUndulationM = geoidM,
                )
            }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener)
            locationListener = listener
            gnssActive = true
        } catch (e: SecurityException) {
            // permission revoked between check and here
        }
    }

    private fun stopGnss() {
        val listener = locationListener ?: return
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            lm.removeUpdates(listener)
        } catch (e: SecurityException) {
            // ignore
        }
        locationListener = null
        gnssActive = false
    }

    // ---- Diagnostics buffering ----

    /**
     * Called immediately after each filter measurement. Appends the forward-
     * pass step record for the RTS smoother, and updates the running NIS
     * windows.
     *
     * Buffer bounding is TIME-based (BUFFER_WINDOW_MS = 180s), not sample-
     * based: a sample-count cap couples the buffer's coverage to sensor
     * rate, so a GPS-only configuration would hold hours of data while a
     * baro-heavy one would hold seconds. The time window guarantees the
     * smoother always covers at least the chart's history window with
     * margin, regardless of how fast measurements arrive. A hard sample
     * cap backstops against pathological sensor rates.
     *
     * Gap handling: if the new step is more than 60 s after the previous
     * one, the filter reset in between (predict detects dt > 60 s and re-
     * seeds). Clear the step buffer so the smoother does not bridge the
     * reset.
     *
     * Uses elapsedRealtime, same clock as publish() and KfStepRecord.timeMs,
     * so nearest-timestamp matching between history and smoothed output
     * works without offset adjustment.
     */
    private fun afterMeasurement() {
        val now = SystemClock.elapsedRealtime()
        val prev = stepRecords.lastOrNull()
        if (prev != null && (now - prev.timeMs) > 60_000L) {
            stepRecords.clear()
        }
        filter.captureStepRecord(now)?.let {
            stepRecords.addLast(it)
            val cutoff = now - BUFFER_WINDOW_MS
            while (stepRecords.isNotEmpty() && stepRecords.first().timeMs < cutoff) {
                stepRecords.removeFirst()
            }
            while (stepRecords.size > BUFFER_HARD_MAX) {
                stepRecords.removeFirst()
            }
        }
        filter.lastGpsNis()?.let {
            if (it.isFinite() && it >= 0f && it < 1e4f) {
                nisGpsWindow.addLast(it)
                while (nisGpsWindow.size > NIS_WINDOW) nisGpsWindow.removeFirst()
            }
        }
        filter.lastBaroNis()?.let {
            if (it.isFinite() && it >= 0f && it < 1e4f) {
                nisBaroWindow.addLast(it)
                while (nisBaroWindow.size > NIS_WINDOW) nisBaroWindow.removeFirst()
            }
        }
    }

    /**
     * Re-run the RTS smoother over the current step-record buffer and fold
     * the results into the chart history by nearest-timestamp match. Called
     * only on publish ticks (2 Hz), not per measurement, so the O(N·n³)
     * cost of the backward pass stays off the per-measurement path.
     *
     * Smoothed and filtered timestamps do not align — the smoother runs per
     * measurement (6+ Hz) while the chart samples at 2 Hz. Nearest-match is
     * adequate: within ±250 ms the smoothed trajectory's h and b are flat to
     * well below the 0.1 m visual resolution of the plot.
     *
     * Truncation: samples older than the smoothed buffer's range get null
     * smoothed values — rendered as a gap. With the time-based buffer (180s
     * vs. 90s history), this only happens (a) during warmup before the
     * buffer has accumulated, and (b) after a model swap, when stepRecords
     * is cleared by setModel() but history survives. Both are transient;
     * both are honest about what the smoother actually knows.
     */
    private fun smoothHistory(): List<KfSample> {
        if (stepRecords.isEmpty()) return history.toList()
        val smoothed = filter.smoothBackward(stepRecords.toList())
        if (smoothed.isEmpty()) return history.toList()

        val hasBias = filter.hasBias

        var si = 0
        return history.map { sample ->
            while (si < smoothed.size - 1 && smoothed[si + 1].timeMs <= sample.timeMs) si++
            val s = smoothed[si]
            if (s.timeMs > sample.timeMs) {
                // Sample predates the smoothed window; no match. This is
                // the post-swap case: pre-swap samples keep whatever
                // smoothed values they were given under the old model,
                // post-swap samples get filled in as the buffer refills.
                sample
            } else {
                sample.copy(
                    hSmoothed = s.h,
                    sigmaHSmoothed = s.sigmaH,
                    // Smoothing b is meaningless for no-bias models — the
                    // filter's smoother returns NaN/0 placeholders for it,
                    // and there is no b to overlay against the filtered b.
                    bSmoothed = if (hasBias) s.b else null,
                )
            }
        }
    }

    // ---- Publish ----

    private fun publish(
        baroPressureHpa: Float? = _state.value.baroPressureHpa,
        baroInput: Float? = _state.value.baroInput,
        gpsInput: Float? = _state.value.gpsInput,
        gpsSigmaM: Float? = _state.value.gpsSigmaM,
        geoidUndulationM: Float? = _state.value.geoidUndulationM,
    ) {
        val h = filter.altitude()
        val v = filter.verticalSpeed()
        val b = filter.bias()
        val vb = filter.biasRate()
        val sh = filter.altitudeSigma()
        val sv = filter.velocitySigma()
        val sb = filter.biasSigma()
        val svb = filter.biasRateSigma()
        val dg = filter.lastGpsInnovationM()
        val db = filter.lastBaroInnovationM()
        val initialized = filter.isInitialized()
        val cusum = filter.airconCusum()
        val aircon = filter.airconDetected()
        val hasBias = filter.hasBias

        val now = SystemClock.elapsedRealtime()
        val push = initialized && (now - lastHistoryMs) >= HISTORY_INTERVAL_MS

        var newHistory = _state.value.history

        if (push) {
            lastHistoryMs = now
            if (history.size >= HISTORY_SIZE) history.removeFirst()
            history.addLast(
                KfSample(
                    timeMs = now,
                    h = h, v = v,
                    b = if (hasBias) b else null,
                    vB = if (hasBias) vb else null,
                    sigmaH = sh, sigmaV = sv,
                    sigmaB = if (hasBias) sb else null,
                    sigmaVB = if (hasBias) svb else null,
                    deltaGps = dg,
                    deltaBaro = if (hasBias) db else null,
                    baroInput = baroInput, gpsInput = gpsInput,
                    nisGps = filter.lastGpsNis(),
                    nisBaro = if (hasBias) filter.lastBaroNis() else null,
                    cusumPos = if (hasBias) cusum.first else null,
                    cusumNeg = if (hasBias) cusum.second else null,
                )
            )
            newHistory = smoothHistory()
        }

        _state.update {
            it.copy(
                isInitialized = initialized,
                hadGpsSeed = it.hadGpsSeed || (dg != null || (initialized && filter.lastUpdateSource() == UpdateSource.GPS)),
                h = h, v = v, b = b, vB = vb,
                sigmaH = sh, sigmaV = sv, sigmaB = sb, sigmaVB = svb,
                deltaGps = dg, deltaBaro = db,
                baroInput = baroInput,
                gpsInput = gpsInput,
                baroPressureHpa = baroPressureHpa,
                geoidUndulationM = geoidUndulationM,
                gpsSigmaM = gpsSigmaM,
                huberWeight = filter.lastHuberWeightValue(),
                vbEstimatedR = filter.lastVbEstimatedRValue(),
                iskfProcessOutlierNorm = filter.lastIskfProcessOutlierNormValue(),
                iskfMeasOutlier = filter.lastIskfMeasOutlierValue(),
                lastUpdateSource = filter.lastUpdateSource(),
                nisGpsMean = if (nisGpsWindow.isEmpty()) null else nisGpsWindow.average().toFloat(),
                nisBaroMean = if (nisBaroWindow.isEmpty()) null else nisBaroWindow.average().toFloat(),
                cusumPos = cusum.first,
                cusumNeg = cusum.second,
                airconDetected = aircon,
                history = newHistory,
            )
        }
    }

    override fun onCleared() {
        stop()
    }
}
