package de.perigon.companion.sensors.domain

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign
import kotlin.math.sqrt

enum class KalmanMode { GAUSSIAN, HUBER, VBAKF, ISKF }
enum class UpdateSource { GPS, BARO }

/**
 * Altitude Kalman filter. Three orthogonal axes:
 *
 *   [KalmanModel] — state dimension and dynamics (2-, 3-, 4-, GPS 2-state).
 *   [KalmanMode]  — update rule (GAUSSIAN / HUBER / VBAKF / ISKF).
 *   KalmanTuning  — process/measurement noise overrides.
 *
 * [setMode], [setIskfInverseHessian] and [setAirplaneMode] mutate the filter
 * in place; state and covariance survive. For [KalmanModel.GPS_TWO_STATE],
 * toggling airplane mode reinstantiates the model object (the q_v variant
 * changes) but preserves x, P, initialized, lastMs, and the cascade state.
 * For the fusion models, toggling airplane mode resets the filter — see
 * [setAirplaneMode].
 *
 * [setModel] and [setTuning] PRESERVE state:
 *   - Tuning-only change: model object reinstantiated under new process
 *     noise; x, P, initialized, lastMs, cascades, diagnostics survive.
 *   - Model change: the filter assembles a [CompatibleState] snapshot
 *     from x/P and the cascades, then asks the new model's
 *     [AltitudeStateModel.seedFrom] to build x/P from it. The destination
 *     model ignores fields it doesn't use; the filter does not carry any
 *     per-transition code. Diagnostics are cleared (per-update).
 *   - A change to [KalmanTuning.baroSigmaM] or
 *     [KalmanTuning.gpsVerticalMinM] alone touches neither model nor
 *     state — both live at the caller.
 * [reset] remains the explicit cold start; its callers are the gap timeout
 * in [predict], the airplane-mode flip in [BackgroundService], and the KF
 * tab's "Reset filter".
 *
 * Velocity and bias rate:
 *
 *   `verticalSpeed()` returns the v_z state (3/4/GPS-2-state) or cascade
 *   output (2-state). `biasRate()` returns the v_b state (4-state) or
 *   cascade output (2/3-state). Both cascades are leaves — they never feed
 *   back.
 *
 *   The v_z cascade (2-state) and v_b cascade (2/3-state) are discrete
 *   position-difference estimators, the Kalman analogue of Martin's
 *   observer-for-derivatives: measurement z = Δx/Δt, noise derived from
 *   the main filter's covariance, gain minimises the derivative error.
 *   They are noisier than state-bearing velocities because differentiation
 *   amplifies noise; the KF tab charts both so the difference is visible.
 *
 * Aircon detector (CUSUM):
 *
 *   The principled detector is CUSUM on the *normalized baro innovation*,
 *   extracted into [BaroInnovationCusum] so the detector is orthogonal to
 *   the state model. It tests "are the filter's predictions consistent
 *   with the data?". Under H0 (any slowly varying v_b), z_k = ν_k/√S_k is
 *   N(0,1) and CUSUM stays below threshold. Under H1 (a step in v_b), z_k
 *   has a sustained bias and CUSUM fires.
 *
 *   Crucially, z_k is N(0,1) under H0 for ANY well-specified model, so
 *   the CUSUM statistic survives model swaps. It is only reset on cold
 *   start (`reset`), recalibration (`resetWithAltitude`), and fresh seed
 *   (`seed`) — NOT on `transitionModel`. The pre-swap and post-swap
 *   innovations are both about the same physical baro signal; the
 *   accumulated evidence remains valid.
 *
 *   Observability limit: b is observable only via baro − GPS divergence,
 *   and GPS vertical σ (~10 m) dominates. Detection latency for a 1e-2
 *   m/s aircon step is ~1–2 minutes regardless of implementation. The
 *   4-state's tight q_vb prior is what makes the ratio of innovation to
 *   √S rise cleanly; a different filter would show the same latency with
 *   a noisier threshold.
 *
 * Diagnostics ([lastGpsInnovationM], [lastGpsInnovationS], [lastGpsNis],
 * and the baro equivalents) are exposed for the KF debug tab. NIS is the
 * normalized innovation squared: ν²/S, where S is the *effective*
 * innovation covariance the update actually used. Under a well-specified
 * model, NIS samples are χ²(1) distributed with mean 1.0 and 95% below
 * 3.84.
 *
 * [captureStepRecord] and [smoothBackward] implement a Rauch-Tung-Striebel
 * smoother for the debug tab.
 *
 * Gaps > 60 s reset the filter: either the caller paused or the phone was
 * off long enough that carrying stale state is worse than restarting. The
 * §3 fix — re-check `initialized` after `predict()` — makes the post-gap
 * measurement either re-seed (GPS) or drop (baro), never apply to a reset
 * state. The 60 s threshold is deliberately longer than typical FGS-
 * tolerated screen-off gaps (a couple of seconds), so those do *not*
 * reset; the filter predicts through them with real `dt` and produces
 * honest (larger) P afterward.
 *
 * All internal math is Double; public surface is Float to match the rest
 * of the track module.
 */
class AltitudeKalmanFilter(
    initialModel: KalmanModel = KalmanModel.THREE_STATE,
    initialMode: KalmanMode = KalmanMode.GAUSSIAN,
    initialIskfInverseHessian: Boolean = false,
    initialTuning: KalmanTuning = KalmanTuning(),
    initialAirplaneMode: Boolean = false,
    private val huberDelta: Double = 1.5,
    private val iskfLambdaX: Double = 1.0,
    private val iskfLambdaY: Double = 1.5,
    private val iskfIterations: Int = 2,
    private val vbakfIterations: Int = 3,
    private val vbakfPriorDof: Double = 4.0,
) {
    private var modelEnum: KalmanModel = initialModel
    private var tuning: KalmanTuning = initialTuning
    private var airplaneMode: Boolean = initialAirplaneMode
    private var model: AltitudeStateModel = initialModel.create(initialTuning, initialAirplaneMode)
    private var mode: KalmanMode = initialMode
    private var iskfInverseHessian: Boolean = initialIskfInverseHessian

    /**
     * Cascade velocity random-walk process noise q_v, m²/s³. Used only when
     * the model has no velocity state (2-state). Overridden from
     * [KalmanTuning.derivedVelNoise]. Kept as a plain field, independent of
     * the model object: a `derivedVelNoise`-only change updates it without
     * reinstantiating the model, because neither ThreeStateModel nor
     * FourStateModel depends on it.
     */
    private var derivedVelNoise: Double =
        initialTuning.derivedVelNoise ?: KalmanTuning.DEFAULT_DERIVED_VEL_NOISE

    /**
     * Cascade bias-rate random-walk process noise q_vb, m²/s³. Used only
     * when the model has no bias-rate state (2/3-state). Same
     * independence-from-model argument as [derivedVelNoise].
     */
    private var derivedBiasRateNoise: Double =
        initialTuning.derivedBiasRateNoise ?: KalmanTuning.DEFAULT_DERIVED_BIAS_RATE_NOISE

    private var x: DoubleArray = DoubleArray(model.n)
    private var P: DoubleArray = DoubleArray(model.n * model.n)

    private var initialized = false
    private var lastMs = 0L
    private var lastGpsInnovation: Double? = null
    private var lastBaroInnovation: Double? = null
    private var lastGpsInnovationS: Double? = null
    private var lastBaroInnovationS: Double? = null

    // ---- Cascade velocity estimator (2-state only) ----
    private var derivedVz: Double? = null
    private var derivedVzCov: Double = 1.0
    private var lastH: Double? = null
    private var lastHTime: Long = 0L
    private var lastP00: Double = 0.0

    // ---- Cascade bias-rate estimator (2/3-state only) ----
    private var derivedVb: Double? = null
    private var derivedVbCov: Double = 1.0
    private var lastB: Double? = null
    private var lastBTime: Long = 0L
    private var lastPbb: Double = 0.0

    // ---- Aircon detector (CUSUM on normalized baro innovation) ----
    // Deliberately external: the detector has no knowledge of AltitudeStateModel.
    // It survives model swaps — see transitionModel's doc.
    private val cusum = BaroInnovationCusum()

    // ---- Forward-pass snapshot for the RTS smoother ----
    private var lastPriorX: DoubleArray = DoubleArray(model.n)
    private var lastPriorP: DoubleArray = DoubleArray(model.n * model.n)

    // ---- Diagnostics (KF debug tab) ----

    private var lastSource: UpdateSource? = null
    private var lastHuberWeight: Double? = null
    private var lastVbEstimatedR: Double? = null
    private var lastIskfProcessOutlierNorm: Double? = null
    private var lastIskfMeasOutlier: Double? = null

    fun currentModel(): KalmanModel = modelEnum
    fun currentMode(): KalmanMode = mode
    fun currentTuning(): KalmanTuning = tuning
    fun currentAirplaneMode(): Boolean = airplaneMode

    /**
     * Features of the current model, for callers that need to branch on what
     * the model *is* (has a bias state? fuses baro?) rather than on which
     * enum entry happens to be selected. Reads from the model instance, not
     * from the config — during a config-change transition the config may be
     * ahead of the model, and the model is always internally consistent.
     */
    val hasBias: Boolean            get() = model.hasBias
    val hasBaroMeasurement: Boolean get() = model.hasBaroMeasurement

    /** Update rule only. State, covariance, and model survive. */
    fun setMode(newMode: KalmanMode) { mode = newMode }

    fun setIskfInverseHessian(enabled: Boolean) { iskfInverseHessian = enabled }

    /**
     * Toggle airplane mode. The semantics are model-dependent, and live here:
     *
     *   - Fusion models (`hasBaroMeasurement == true`): cabin pressure
     *     becomes invalid, which taints the bias state that was fused with
     *     it. Resets the filter. Returns true so the caller can do its own
     *     reset-side bookkeeping (distance baseline, status clear).
     *
     *   - No-baro models: baro was never in the measurement map, so nothing
     *     is invalidated. The flag only selects the q_v variant.
     *     Reinstantiates the model, keeps x/P/initialized. Returns false.
     *
     * Callers should not branch on the model's identity here; the return
     * value says whether a reset happened, which is the only fact they need.
     */
    fun setAirplaneMode(enabled: Boolean): Boolean {
        if (airplaneMode == enabled) return false
        airplaneMode = enabled
        return if (model.hasBaroMeasurement) {
            reset()
            true
        } else {
            reinstantiateModel()
            false
        }
    }

    /**
     * Swap model. Carries compatible state across the dimension change
     * via a [CompatibleState] snapshot consumed by the destination model's
     * [AltitudeStateModel.seedFrom]. Diagnostics are cleared (per-update,
     * tied to the old H/dims). The CUSUM is NOT reset — see class doc.
     */
    fun setModel(newModel: KalmanModel) {
        if (modelEnum == newModel) return
        transitionModel(newModel, tuning)
    }

    /**
     * Update tuning overrides.
     *
     * `derivedVelNoise` and `derivedBiasRateNoise` are plain fields of this
     * filter, not constructor arguments of any model, so they are updated
     * unconditionally here. The model object is reinstantiated only when a
     * constructor argument changed — `accelNoiseQ`/`biasNoiseQ` for
     * 3-state, `qH`/`biasNoiseQ` for 2-state, `accelNoiseQ`/`biasRateQ`
     * for 4-state, `gpsOnlyAccelQ`/`gpsOnlyAccelQAirplane` for GPS 2-state
     * — and when it does, x, P, initialized, lastMs, both cascades and
     * diagnostics all survive.
     *
     * A [KalmanTuning.baroSigmaM]- or [KalmanTuning.gpsVerticalMinM]-only
     * change touches neither model nor state: both live at the caller and
     * take effect on the next measurement.
     */
    fun setTuning(newTuning: KalmanTuning) {
        if (tuning == newTuning) return
        val relevant = relevantTuningChange(tuning, newTuning)
        tuning = newTuning
        derivedVelNoise = newTuning.derivedVelNoise ?: KalmanTuning.DEFAULT_DERIVED_VEL_NOISE
        derivedBiasRateNoise = newTuning.derivedBiasRateNoise ?: KalmanTuning.DEFAULT_DERIVED_BIAS_RATE_NOISE
        if (relevant) reinstantiateModel()
    }

    /**
     * Swap model AND tuning in one call. When the model is unchanged,
     * this routes through the tuning-only path — same cost as [setTuning],
     * same state preservation, no state transplant.
     */
    fun setModelAndTuning(newModel: KalmanModel, newTuning: KalmanTuning) {
        if (modelEnum == newModel && tuning == newTuning) return
        if (modelEnum == newModel) {
            val relevant = relevantTuningChange(tuning, newTuning)
            tuning = newTuning
            derivedVelNoise = newTuning.derivedVelNoise ?: KalmanTuning.DEFAULT_DERIVED_VEL_NOISE
            derivedBiasRateNoise = newTuning.derivedBiasRateNoise ?: KalmanTuning.DEFAULT_DERIVED_BIAS_RATE_NOISE
            if (relevant) reinstantiateModel()
        } else {
            transitionModel(newModel, newTuning)
        }
    }

    fun isInitialized(): Boolean = initialized

    fun altitude(): Float? = if (initialized) x[0].toFloat() else null

    /**
     * Best vertical-speed estimate available:
     *
     *  - 3/4/GPS-2-state: the KF's v_z state (proper uncertainty via
     *    [velocitySigma]).
     *  - 2-state: the cascade scalar KF described in the class doc.
     *
     * Null before initialization, or in 2-state for the interval between
     * seed and the first real update.
     */
    fun verticalSpeed(): Float? {
        if (!initialized) return null
        return if (model.hasVelocity) x[1].toFloat() else derivedVz?.toFloat()
    }

    fun bias(): Float? =
        if (initialized && model.hasBias) x[model.biasIndex].toFloat() else null

    /**
     * Bias rate (db/dt, m/s). 4-state: the v_b state. 2/3-state: the
     * cascade output — same semantic, lower quality (differentiation
     * amplifies noise). GPS 2-state: null (no bias in the model).
     */
    fun biasRate(): Float? {
        if (!initialized) return null
        return if (model.hasBiasRate) x[model.n - 1].toFloat()
               else if (model.hasBias) derivedVb?.toFloat()
               else null
    }

    fun altitudeSigma(): Float? =
        if (initialized) sqrt(max(P[0], 0.0)).toFloat() else null

    /**
     * σ(v) from the 3/4/GPS-2-state covariance when v_z is a state, or
     * from the cascade filter's scalar covariance when it is not. Null
     * when no velocity estimate exists yet.
     */
    fun velocitySigma(): Float? {
        if (!initialized) return null
        return if (model.hasVelocity) {
            val i = 1
            sqrt(max(P[i * model.n + i], 0.0)).toFloat()
        } else {
            derivedVz?.let { sqrt(max(derivedVzCov, 0.0)).toFloat() }
        }
    }

    fun biasSigma(): Float? {
        if (!initialized || !model.hasBias) return null
        val i = model.biasIndex
        return sqrt(max(P[i * model.n + i], 0.0)).toFloat()
    }

    fun biasRateSigma(): Float? {
        if (!initialized) return null
        return if (model.hasBiasRate) {
            val i = model.n - 1
            sqrt(max(P[i * model.n + i], 0.0)).toFloat()
        } else if (model.hasBias) {
            derivedVb?.let { sqrt(max(derivedVbCov, 0.0)).toFloat() }
        } else null
    }

    /**
     * CUSUM statistics (g⁺, g⁻) on the normalized baro innovation. Either
     * crossing the CUSUM threshold is the detector firing.
     */
    fun airconCusum(): Pair<Float, Float> = cusum.stats()

    /** True when either CUSUM accumulator has crossed the threshold. */
    fun airconDetected(): Boolean = cusum.detected()

    fun lastGpsInnovationM(): Float? = lastGpsInnovation?.toFloat()
    fun lastBaroInnovationM(): Float? =
        if (model.hasBaroMeasurement) lastBaroInnovation?.toFloat() else null
    fun lastGpsInnovationS(): Float? = lastGpsInnovationS?.toFloat()
    fun lastBaroInnovationS(): Float? =
        if (model.hasBaroMeasurement) lastBaroInnovationS?.toFloat() else null

    fun lastGpsNis(): Float? {
        val v = lastGpsInnovation ?: return null
        val s = lastGpsInnovationS ?: return null
        if (s < 1e-12) return null
        return ((v * v) / s).toFloat()
    }

    fun lastBaroNis(): Float? {
        if (!model.hasBaroMeasurement) return null
        val v = lastBaroInnovation ?: return null
        val s = lastBaroInnovationS ?: return null
        if (s < 1e-12) return null
        return ((v * v) / s).toFloat()
    }

    fun lastUpdateSource(): UpdateSource? = lastSource
    fun lastHuberWeightValue(): Float? = lastHuberWeight?.toFloat()
    fun lastVbEstimatedRValue(): Float? = lastVbEstimatedR?.toFloat()
    fun lastIskfProcessOutlierNormValue(): Float? = lastIskfProcessOutlierNorm?.toFloat()
    fun lastIskfMeasOutlierValue(): Float? = lastIskfMeasOutlier?.toFloat()

    /**
     * Explicit cold start. Readers all gate on [initialized], and [seed]
     * reassigns x, P, lastPriorX, lastPriorP before anything observes them.
     * CUSUM is reset here — cold start is one of its two legitimate reset
     * points (the other being [resetWithAltitude]).
     */
    fun reset() {
        initialized = false
        lastMs = 0L
        clearDiagnostics()
        clearDerivedVelocity()
        clearDerivedBiasRate()
        cusum.reset()
    }

    /**
     * Seed / re-seed with a known altitude, typically from the one-shot
     * calibration dialog.
     *
     * Already initialized: h snaps to [altM] and b to (baroZ − altM). What
     * else survives is the model's decision — the 3/4/GPS-2-state keep P11
     * so a mid-recording recalibrate does not blank out the velocity
     * estimate; the 2-state's cascade velocity estimator is reset (the snap
     * is a discontinuity, and pre-snap Δh would corrupt the first velocity
     * sample). The v_b cascade is reset for the same reason. CUSUM is
     * reset — pre-calibration data was collected with a wrong b.
     *
     * Not initialized: the model's seedState / seedCovariance set up all
     * states from scratch.
     *
     * This is the public seed path; the user-facing entry points are the
     * live card's "recalibrate" link on the Track tab and the Seed button
     * in the KF sandbox. Both go through [BaroCalibrationDialog] with
     * GPS-height or manual entry.
     */
    fun resetWithAltitude(altM: Float, baroZ: Float?) {
        val newX = if (initialized) x.copyOf() else model.seedState(altM.toDouble())
        newX[0] = altM.toDouble()
        if (model.hasBias && baroZ != null) newX[model.biasIndex] = (baroZ - altM).toDouble()
        x = newX
        P = model.calibrateCovariance(if (initialized) P else null)
        clearDiagnostics()
        clearDerivedVelocity()
        clearDerivedBiasRate()
        cusum.reset()
        initialized = true
        lastMs = System.currentTimeMillis()
        capturePrior()
        recordDerivedVelocity()
        recordDerivedBiasRate()
    }

    /** GNSS altitude measurement. Seeds the filter on first call. */
    fun updateGps(altM: Float, sigmaM: Float) {
        if (!initialized) { seed(altM.toDouble()); lastSource = UpdateSource.GPS; return }
        predict()
        // §3 fix: predict() may just have reset (gap > 60 s). Re-seed and
        // return — do NOT apply the measurement to zeroed/stale state.
        if (!initialized) { seed(altM.toDouble()); lastSource = UpdateSource.GPS; return }
        applyMeasurement(model.gpsH(), altM.toDouble(), sigmaM.toDouble().let { it * it }, isBaro = false)
        recordDerivedVelocity()
        // The v_b cascade is deliberately NOT updated here: in 2/3-state,
        // b is not observable from GPS (H=[1,0,0] or [1,0]), so a GPS-only
        // measurement leaves b unchanged and z_vb = 0. Feeding that into
        // the cascade pulls v_b toward 0 and corrupts the diagnostic.
        // Cascade updates happen only on baro updates, where b actually
        // moves.
    }

    /** Baro altitude measurement. No-op until GNSS has seeded the filter. */
    fun updateBaro(altM: Float, sigmaM: Float) {
        if (!initialized) return
        if (!model.hasBaroMeasurement) return
        predict()
        // §3 fix: gap-reset during predict() has de-initialized us; drop the
        // baro sample rather than apply it to zeros. b is unobservable from
        // baro alone, so there is nothing valid to do here either.
        if (!initialized) return
        applyMeasurement(model.baroH(), altM.toDouble(), sigmaM.toDouble().let { it * it }, isBaro = true)
        recordDerivedVelocity()
        recordDerivedBiasRate()
        feedCusum()
    }

    /**
     * QNH from a raw pressure reading. QNH = P / (1 − L·h/T₀)^n. This
     * depends only on P and h, not on the calibration offset: with or
     * without offset, the recovered QNH is the same once the filter has
     * converged. Callers should still gate on "not airplane mode" — in a
     * pressurized cabin, h_KF is real but P is cabin pressure, so the
     * formula yields nonsense. Returns null for models with no bias state
     * (GPS 2-state), since the whole concept requires a baro reference.
     */
    fun qnhHpa(pressureHpa: Float): Float? {
        if (!initialized || !model.hasBias) return null
        val L = 0.0065
        val T0 = 288.15
        val n = 5.25588
        val base = 1.0 - L * x[0] / T0
        if (base < 0.001) return null
        return (pressureHpa / Math.pow(base, n)).toFloat()
    }

    /**
     * Snapshot the current forward pass step for the RTS smoother. Call
     * immediately after a successful [updateGps] or [updateBaro]; returns
     * null if the filter is not initialized.
     */
    fun captureStepRecord(timeMs: Long): KfStepRecord? {
        if (!initialized) return null
        return KfStepRecord(
            timeMs = timeMs,
            xPrior = lastPriorX.copyOf(),
            PPrior = lastPriorP.copyOf(),
            xPost = x.copyOf(),
            PPost = P.copyOf(),
        )
    }

    /**
     * RTS smoother over [records]. Runs the backward pass and returns the
     * smoothed h, b, and their standard deviations for each measurement.
     *
     * Gaps > 60 s in the record list are treated as chain breaks: no
     * smoothing across a reset. Each contiguous run is smoothed independently.
     *
     * The smoothed h will differ from the filtered h most at the *oldest*
     * samples in the window — a RTS smoother improves past estimates as new
     * measurements arrive. At the leading edge (most recent sample) the
     * smoothed and filtered estimates are close by construction.
     */
    fun smoothBackward(records: List<KfStepRecord>): List<SmoothedSample> {
        if (records.isEmpty()) return emptyList()
        val n = records.size
        val nS = model.n
        val smoothedX = Array(n) { records[it].xPost.copyOf() }
        val smoothedP = Array(n) { records[it].PPost.copyOf() }

        var k = n - 2
        while (k >= 0) {
            val cur = records[k]
            val next = records[k + 1]
            val dtSec = (next.timeMs - cur.timeMs) / 1000.0
            if (dtSec <= 0.0 || dtSec > 60.0) {
                k -= 1
                continue
            }
            val F = model.transitionMatrix(dtSec)
            val invPrior = invertSpd(next.PPrior)
            val C = matMul(matMul(cur.PPost, transpose(F)), invPrior)
            val Ct = transpose(C)

            val xdiff = DoubleArray(nS) { smoothedX[k + 1][it] - next.xPrior[it] }
            val xcorr = matVec(C, xdiff)
            for (i in 0 until nS) smoothedX[k][i] = cur.xPost[i] + xcorr[i]

            val pdiff = DoubleArray(nS * nS) { smoothedP[k + 1][it] - next.PPrior[it] }
            val pcorr = matMul(matMul(C, pdiff), Ct)
            for (i in 0 until nS * nS) smoothedP[k][i] = cur.PPost[i] + pcorr[i]

            k -= 1
        }

        val biasIdx = if (model.hasBias) model.biasIndex else -1
        return records.mapIndexed { i, r ->
            SmoothedSample(
                timeMs = r.timeMs,
                h = smoothedX[i][0].toFloat(),
                sigmaH = sqrt(max(smoothedP[i][0], 0.0)).toFloat(),
                b = if (biasIdx >= 0) smoothedX[i][biasIdx].toFloat() else 0f,
                sigmaB = if (biasIdx >= 0)
                             sqrt(max(smoothedP[i][biasIdx * nS + biasIdx], 0.0)).toFloat()
                         else Float.NaN,
            )
        }
    }

    // ---- internals ----

    private fun capturePrior() {
        lastPriorX = x.copyOf()
        lastPriorP = P.copyOf()
    }

    private fun clearDiagnostics() {
        lastGpsInnovation = null
        lastBaroInnovation = null
        lastGpsInnovationS = null
        lastBaroInnovationS = null
        lastSource = null
        lastHuberWeight = null
        lastVbEstimatedR = null
        lastIskfProcessOutlierNorm = null
        lastIskfMeasOutlier = null
    }

    private fun clearDerivedVelocity() {
        derivedVz = null
        derivedVzCov = 1.0
        lastH = null
        lastHTime = 0L
        lastP00 = 0.0
    }

    private fun clearDerivedBiasRate() {
        derivedVb = null
        derivedVbCov = 1.0
        lastB = null
        lastBTime = 0L
        lastPbb = 0.0
    }

    /**
     * Rebuild the model OBJECT under the current tuning, leaving state
     * untouched. Every model captures q in its constructor, so a
     * process-noise change requires a fresh instance — but x, P, initialized,
     * lastMs, both cascades and diagnostics are independent of the model
     * object and survive. `derivedVelNoise` / `derivedBiasRateNoise` are
     * filter fields, not model constructor arguments, and are updated by
     * [setTuning] / [setModelAndTuning] directly.
     */
    private fun reinstantiateModel() {
        model = modelEnum.create(tuning, airplaneMode)
    }

    /**
     * Move to [newModel] / [newTuning], carrying compatible state.
     *
     * Mechanics:
     *
     *   1. Snapshot [CompatibleState] from x/P and the cascades. Every
     *      component that MIGHT be transferable is captured here, whether
     *      it lives in a state vector or in a cascade. Nulls mean "source
     *      doesn't have it" — the destination model interprets null as
     *      "use my seed default".
     *   2. Swap the model object.
     *   3. Ask the new model's [AltitudeStateModel.seedFrom] to build
     *      x/P. The destination model uses only the fields it knows how
     *      to receive, ignoring the rest. There is no per-transition
     *      code in the filter.
     *   4. Filter-level cascade housekeeping: a cascade is dormant when
     *      the new model has the corresponding state (its estimate lives
     *      in x now), and active otherwise. When it becomes active
     *      (state → cascade hand-off), the CompatibleState seeds it from
     *      the old state. When it stays dormant, it's cleared so a future
     *      swap back starts from the state, not a stale cascade sample.
     *
     * Diagnostics are cleared (per-measurement, tied to the old H/dims).
     * CUSUM is NOT cleared — see class doc for the invariance argument.
     *
     * Called only from setModel and setModelAndTuning, both of which guard
     * on `modelEnum != newModel`; the 2→2, 3→3, 4→4 and GPS2→GPS2 cases
     * go through reinstantiateModel instead.
     *
     * When the source model has no bias state (GPS 2-state), the b/hVar
     * fields of [CompatibleState] are populated with the source's cold-
     * start placeholder values (b = 0, bVar = 10000.0, hbCov = 0.0).
     * The destination model interprets bVar as "this is a cold-start
     * uncertainty, not a preserved estimate" and treats the branch the
     * same as if the source simply had no bias — [AltitudeStateModel.seedFrom]
     * does not distinguish "null" from "cold-start default" here because
     * the destination's own seed default would be identical.
     */
    private fun transitionModel(newModel: KalmanModel, newTuning: KalmanTuning) {
        val oldModel = model
        val oldN = oldModel.n
        val wasInit = initialized
        val oldLastMs = lastMs
        val oldX = x
        val oldP = P

        val compatible: CompatibleState? = if (wasInit) CompatibleState(
            h = oldX[0],
            hVar = oldP[0 * oldN + 0],
            b = if (oldModel.hasBias) oldX[oldModel.biasIndex] else 0.0,
            // 10000.0 matches seedCovariance()'s bias variance — the model's
            // own cold-start uncertainty. A wider placeholder (e.g. 1e6) is
            // not neutral: it lets the first post-swap baro measurement
            // dominate b even when that measurement is a transient outlier.
            // The transition means "state preserved, dimensions changed";
            // for the source's missing component the correct prior is the
            // one a fresh filter would have.
            bVar = if (oldModel.hasBias) oldP[oldModel.biasIndex * oldN + oldModel.biasIndex] else 10000.0,
            hbCov = if (oldModel.hasBias) oldP[0 * oldN + oldModel.biasIndex] else 0.0,
            vZ = if (oldModel.hasVelocity) oldX[1] else derivedVz,
            vZVar = if (oldModel.hasVelocity) oldP[1 * oldN + 1]
                    else derivedVzCov.takeIf { derivedVz != null },
            vB = if (oldModel.hasBiasRate) oldX[oldN - 1] else derivedVb,
            vBVar = if (oldModel.hasBiasRate) oldP[(oldN - 1) * oldN + (oldN - 1)]
                    else derivedVbCov.takeIf { derivedVb != null },
        ) else null

        modelEnum = newModel
        tuning = newTuning
        model = newModel.create(newTuning, airplaneMode)
        derivedVelNoise = newTuning.derivedVelNoise ?: KalmanTuning.DEFAULT_DERIVED_VEL_NOISE
        derivedBiasRateNoise = newTuning.derivedBiasRateNoise ?: KalmanTuning.DEFAULT_DERIVED_BIAS_RATE_NOISE

        if (compatible == null) {
            // Unseeded: nothing to preserve. Resize arrays and clear
            // diagnostics.
            x = DoubleArray(model.n)
            P = DoubleArray(model.n * model.n)
            lastPriorX = DoubleArray(model.n)
            lastPriorP = DoubleArray(model.n * model.n)
            initialized = false
            lastMs = 0L
            clearDiagnostics()
            clearDerivedVelocity()
            clearDerivedBiasRate()
            return
        }

        // Hand the compatible state to the new model; it decides what to use.
        val (newX, newP) = model.seedFrom(compatible)
        x = newX
        P = newP
        lastPriorX = newX.copyOf()
        lastPriorP = newP.copyOf()
        initialized = true
        lastMs = oldLastMs
        clearDiagnostics()

        // Cascade housekeeping. Velocity:
        if (model.hasVelocity) {
            // 2→3/4/GPS2: v_z now lives in the state vector; the cascade is
            // dormant. Clear its stale values so a future 3/4/GPS2→2 swap
            // starts from the state's v_z, not a leftover cascade sample.
            clearDerivedVelocity()
        } else {
            // 3/4/GPS2→2: hand the old state's v_z to the cascade, with its
            // P11 as the cascade's initial covariance. Re-anchor the h-
            // difference baseline so the first post-swap sample computes
            // v_z from the transplanted height, not a stale pre-swap one.
            //
            // The takeIf guard mirrors seedFrom: a non-positive or
            // non-finite variance is treated as "no estimate" and falls
            // back to the cascade's default. The invariant holds today
            // (Joseph-form updates keep P[v_z,v_z] strictly positive),
            // but the two directions of a transition should defend
            // identically so a future invariant break fails safe.
            if (oldModel.hasVelocity) {
                derivedVz = compatible.vZ
                derivedVzCov = compatible.vZVar?.takeIf { it > 0.0 && it.isFinite() } ?: 1.0
            }
            lastH = newX[0]
            lastHTime = oldLastMs
            lastP00 = newP[0 * model.n + 0]
        }

        // Bias rate:
        if (model.hasBiasRate) {
            // 2/3/GPS2→4: v_b now lives in the state vector; the cascade is
            // dormant. Clear its stale values.
            clearDerivedBiasRate()
        } else if (model.hasBias) {
            // 4→2/3: hand the old state's v_b to the cascade. Same
            // takeIf defence as the velocity hand-off above.
            if (oldModel.hasBiasRate) {
                derivedVb = compatible.vB
                derivedVbCov = compatible.vBVar?.takeIf { it > 0.0 && it.isFinite() } ?: 1.0
            }
            lastB = newX[model.biasIndex]
            lastBTime = oldLastMs
            lastPbb = newP[model.biasIndex * model.n + model.biasIndex]
        } else {
            // GPS 2-state has no bias state and no cascade — clear.
            clearDerivedBiasRate()
        }
    }

    /**
     * Whether the tuning change requires reinstantiating the model object.
     * This depends only on the model's constructor arguments — the process
     * noise that gets baked into the model. It is deliberately independent
     * of `derivedVelNoise` / `derivedBiasRateNoise`, which live on the
     * filter, not on any model.
     */
    private fun relevantTuningChange(old: KalmanTuning, new: KalmanTuning): Boolean =
        when (modelEnum) {
            KalmanModel.THREE_STATE ->
                old.accelNoiseQ != new.accelNoiseQ ||
                old.biasNoiseQ != new.biasNoiseQ
            KalmanModel.TWO_STATE ->
                old.qH != new.qH ||
                old.biasNoiseQ != new.biasNoiseQ
            KalmanModel.FOUR_STATE ->
                old.accelNoiseQ != new.accelNoiseQ ||
                old.biasRateQ != new.biasRateQ
            KalmanModel.GPS_TWO_STATE ->
                old.gpsOnlyAccelQ != new.gpsOnlyAccelQ ||
                old.gpsOnlyAccelQAirplane != new.gpsOnlyAccelQAirplane
        }

    private fun seed(altM: Double) {
        x = model.seedState(altM)
        P = model.seedCovariance()
        clearDiagnostics()
        clearDerivedVelocity()
        clearDerivedBiasRate()
        cusum.reset()
        initialized = true
        lastMs = System.currentTimeMillis()
        capturePrior()
        recordDerivedVelocity()
        recordDerivedBiasRate()
    }

    private fun predict() {
        val now = System.currentTimeMillis()
        val dt = (now - lastMs) / 1000.0
        if (dt <= 0.0) return
        // Gap too long — stale state is worse than a fresh start.
        if (dt > 60.0) { reset(); lastMs = now; return }
        model.predict(x, P, dt)
        lastMs = now
    }

    /**
     * Update the cascade scalar velocity filter after a main-filter update.
     *
     * No-op for models that own a velocity state. The measurement is the
     * filtered altitude difference over the elapsed time:
     *
     *   z_v = (h_k − h_{k−1}) / Δt
     *   R_v = (P_hh(k) + P_hh(k−1)) / Δt²
     *
     * The velocity state evolves as a random walk with spectral density
     * [derivedVelNoise]. This is the discrete position-difference velocity
     * estimator: the gain minimises velocity error covariance (the
     * discrete analogue of Martin's Kalman observer for derivatives). It
     * is a leaf — v_z never feeds back into h or b.
     */
    private fun recordDerivedVelocity() {
        if (model.hasVelocity) return
        val h = x[0]
        val p00 = P[0]
        val now = lastMs

        if (lastH == null) {
            lastH = h
            lastHTime = now
            lastP00 = p00
            return
        }

        val dt = (now - lastHTime) / 1000.0
        if (dt <= 1e-6) return

        val zv = (h - lastH!!) / dt
        val rv = (p00 + lastP00) / (dt * dt)

        val vPrior = derivedVz ?: 0.0
        val covPrior = derivedVzCov + derivedVelNoise * dt
        val k = covPrior / (covPrior + rv)
        derivedVz = vPrior + k * (zv - vPrior)
        derivedVzCov = (1.0 - k) * covPrior

        lastH = h
        lastHTime = now
        lastP00 = p00
    }

    /**
     * Cascade v_b update (2/3-state only). Measurement z_vb = Δb/Δt with
     * R_vb = (P_bb(k) + P_bb(k−1)) / Δt². Leaf — v_b never feeds back.
     * Noisier than the 4-state's v_b state (differentiation amplifies
     * noise), but model-robust and useful for visual comparison on the KF
     * tab. Only called from [updateBaro] — GPS-only updates leave b
     * unchanged, so z_vb would be spuriously zero. Returns immediately for
     * models with no bias state (GPS 2-state).
     */
    private fun recordDerivedBiasRate() {
        if (model.hasBiasRate) return
        if (!model.hasBias) return
        val bIdx = model.biasIndex
        val b = x[bIdx]
        val pbb = P[bIdx * model.n + bIdx]
        val now = lastMs

        if (lastB == null) {
            lastB = b
            lastBTime = now
            lastPbb = pbb
            return
        }

        val dt = (now - lastBTime) / 1000.0
        if (dt <= 1e-6) return

        val zvb = (b - lastB!!) / dt
        val rvb = (pbb + lastPbb) / (dt * dt)

        val vPrior = derivedVb ?: 0.0
        val covPrior = derivedVbCov + derivedBiasRateNoise * dt
        val k = covPrior / (covPrior + rvb)
        derivedVb = vPrior + k * (zvb - vPrior)
        derivedVbCov = (1.0 - k) * covPrior

        lastB = b
        lastBTime = now
        lastPbb = pbb
    }

    /**
     * Feed CUSUM with the normalized baro innovation just observed.
     * Extraction of z (and its clamp) is inside [BaroInnovationCusum]; the
     * filter just hands it the normalized value.
     */
    private fun feedCusum() {
        val nu = lastBaroInnovation ?: return
        val s = lastBaroInnovationS ?: return
        if (s < 1e-12) return
        cusum.feed(nu / sqrt(s))
    }

    private fun applyMeasurement(c: DoubleArray, y: Double, r: Double, isBaro: Boolean) {
        capturePrior()
        lastSource = if (isBaro) UpdateSource.BARO else UpdateSource.GPS
        when (mode) {
            KalmanMode.GAUSSIAN -> gaussian(c, y, r, isBaro)
            KalmanMode.HUBER -> huber(c, y, r, isBaro)
            KalmanMode.VBAKF -> vbakf(c, y, r, isBaro)
            KalmanMode.ISKF -> iskf(c, y, r, isBaro)
        }
    }

    private fun gaussian(c: DoubleArray, y: Double, r: Double, isBaro: Boolean) {
        lastHuberWeight = null
        lastVbEstimatedR = null
        lastIskfProcessOutlierNorm = null
        lastIskfMeasOutlier = null

        val n = model.n
        val innovation = y - dot(c, x)
        val s = quadForm(c, P) + r
        if (s < 1e-12) return
        val k = matVec(P, c)
        for (i in 0 until n) k[i] /= s
        for (i in 0 until n) x[i] += k[i] * innovation
        P = joseph(P, k, c, r)
        if (isBaro) { lastBaroInnovation = innovation; lastBaroInnovationS = s }
        else        { lastGpsInnovation = innovation;  lastGpsInnovationS = s }
    }

    private fun huber(c: DoubleArray, y: Double, r: Double, isBaro: Boolean) {
        lastVbEstimatedR = null
        lastIskfProcessOutlierNorm = null
        lastIskfMeasOutlier = null

        val n = model.n
        val innovation = y - dot(c, x)
        val s0 = quadForm(c, P) + r
        if (s0 < 1e-12) return
        val std = innovation / sqrt(s0)
        val w = if (abs(std) <= huberDelta) 1.0 else huberDelta / abs(std)
        lastHuberWeight = w

        val rEff = r / w
        val s = quadForm(c, P) + rEff
        if (s < 1e-12) return
        val k = matVec(P, c)
        for (i in 0 until n) k[i] /= s
        for (i in 0 until n) x[i] += k[i] * innovation
        P = joseph(P, k, c, rEff)
        if (isBaro) { lastBaroInnovation = innovation; lastBaroInnovationS = s }
        else        { lastGpsInnovation = innovation;  lastGpsInnovationS = s }
    }

    private fun vbakf(c: DoubleArray, y: Double, r: Double, isBaro: Boolean) {
        lastHuberWeight = null
        lastIskfProcessOutlierNorm = null
        lastIskfMeasOutlier = null

        val n = model.n
        val innovation = y - dot(c, x)
        val a0 = vbakfPriorDof / 2.0
        val b0 = a0 * r

        var rVb = r
        var k = matVec(P, c)
        var s = quadForm(c, P) + rVb
        if (s < 1e-12) return
        for (i in 0 until n) k[i] /= s

        repeat(vbakfIterations) {
            val pPost = joseph(P, k, c, rVb)
            val cpc = quadForm(c, pPost)
            val bNew = b0 + 0.5 * (innovation * innovation + cpc)
            val aNew = a0 + 0.5
            rVb = bNew / aNew
            s = quadForm(c, P) + rVb
            k = matVec(P, c)
            for (i in 0 until n) k[i] /= s
        }
        lastVbEstimatedR = rVb

        for (i in 0 until n) x[i] += k[i] * innovation
        P = joseph(P, k, c, rVb)
        if (isBaro) { lastBaroInnovation = innovation; lastBaroInnovationS = s }
        else        { lastGpsInnovation = innovation;  lastGpsInnovationS = s }
    }

    private fun iskf(c: DoubleArray, y: Double, r: Double, isBaro: Boolean) {
        lastHuberWeight = null
        lastVbEstimatedR = null

        val n = model.n
        val x0 = x.copyOf()
        val l = cholesky(P)
        val s = quadForm(c, P) + r
        if (s < 1e-12) return
        val k = matVec(P, c)
        for (i in 0 until n) k[i] /= s

        val ikc = DoubleArray(n * n)
        for (i in 0 until n) for (j in 0 until n) {
            ikc[i * n + j] = (if (i == j) 1.0 else 0.0) - k[i] * c[j]
        }

        val satY = iskfLambdaY * sqrt(r)
        var xk = x.copyOf()
        var lastSigmaVal = 0.0
        var lastRhoNorm = 0.0
        repeat(iskfIterations) {
            val innov = y - dot(c, xk)
            val sigmaVal = saturate(innov, satY)
            lastSigmaVal = sigmaVal

            val d = DoubleArray(n) { x0[it] - xk[it] }
            val w = solveLower(l, d)
            val nw = norm(w)
            lastRhoNorm = nw
            val rho = if (nw <= iskfLambdaX || nw < 1e-12) d else {
                val scale = iskfLambdaX / nw
                DoubleArray(n) { d[it] * scale }
            }

            val ikcRho = matVec(ikc, rho)
            for (i in 0 until n) xk[i] += k[i] * sigmaVal + ikcRho[i]
        }
        for (i in 0 until n) x[i] = xk[i]

        lastIskfProcessOutlierNorm = lastRhoNorm
        lastIskfMeasOutlier = lastSigmaVal

        P = if (iskfInverseHessian) {
            inverseHessian(x0, x, c, y, r, l)
        } else {
            joseph(P, k, c, r)
        }

        val priorInnovation = y - dot(c, x0)
        if (isBaro) { lastBaroInnovation = priorInnovation; lastBaroInnovationS = s }
        else        { lastGpsInnovation = priorInnovation;  lastGpsInnovationS = s }
    }

    private fun inverseHessian(
        x0: DoubleArray,
        xStar: DoubleArray,
        c: DoubleArray,
        y: Double,
        r: Double,
        l: DoubleArray,
    ): DoubleArray {
        val n = model.n
        val sigmaInv = invertSpd(P)
        val d = DoubleArray(n) { xStar[it] - x0[it] }
        val w = solveLower(l, d)
        val nw = norm(w)

        val h0: DoubleArray
        if (nw <= iskfLambdaX || nw < 1e-12) {
            h0 = sigmaInv
        } else {
            val alpha = iskfLambdaX / nw
            val wHat = DoubleArray(n) { w[it] / nw }
            val u = solveUpper(l, wHat)
            h0 = DoubleArray(n * n) { sigmaInv[it] * alpha }
            for (i in 0 until n) for (j in 0 until n) h0[i * n + j] -= alpha * u[i] * u[j]
        }

        val h1 = DoubleArray(n * n)
        val innovStar = y - dot(c, xStar)
        if (abs(innovStar) <= iskfLambdaY * sqrt(r)) {
            for (i in 0 until n) for (j in 0 until n) h1[i * n + j] = c[i] * c[j] / r
        }

        val eps = 1e-4
        val h = DoubleArray(n * n) {
            h0[it] + h1[it] + if (it % (n + 1) == 0) eps else 0.0
        }
        return invertSpd(h)
    }

    private fun saturate(z: Double, threshold: Double): Double =
        if (abs(z) <= threshold) z else sign(z) * threshold

    private fun dot(a: DoubleArray, b: DoubleArray): Double {
        var s = 0.0
        for (i in 0 until model.n) s += a[i] * b[i]
        return s
    }

    private fun quadForm(c: DoubleArray, p: DoubleArray): Double {
        val n = model.n
        var s = 0.0
        for (i in 0 until n) for (j in 0 until n) s += c[i] * p[i * n + j] * c[j]
        return s
    }

    private fun matVec(p: DoubleArray, v: DoubleArray): DoubleArray {
        val n = model.n
        val r = DoubleArray(n)
        for (i in 0 until n) {
            var s = 0.0
            for (j in 0 until n) s += p[i * n + j] * v[j]
            r[i] = s
        }
        return r
    }

    private fun matMul(a: DoubleArray, b: DoubleArray): DoubleArray {
        val n = model.n
        val r = DoubleArray(n * n)
        for (i in 0 until n) for (j in 0 until n) {
            var s = 0.0
            for (k in 0 until n) s += a[i * n + k] * b[k * n + j]
            r[i * n + j] = s
        }
        return r
    }

    private fun transpose(a: DoubleArray): DoubleArray {
        val n = model.n
        val r = DoubleArray(n * n)
        for (i in 0 until n) for (j in 0 until n) r[i * n + j] = a[j * n + i]
        return r
    }

    private fun norm(v: DoubleArray): Double {
        var s = 0.0
        for (i in 0 until model.n) s += v[i] * v[i]
        return sqrt(s)
    }

    private fun cholesky(a: DoubleArray): DoubleArray {
        val n = model.n
        val l = DoubleArray(n * n)
        for (i in 0 until n) {
            for (j in 0..i) {
                var s = a[i * n + j]
                for (k in 0 until j) s -= l[i * n + k] * l[j * n + k]
                if (i == j) l[i * n + j] = sqrt(max(s, 1e-12))
                else l[i * n + j] = s / l[j * n + j]
            }
        }
        return l
    }

    private fun solveLower(l: DoubleArray, b: DoubleArray): DoubleArray {
        val n = model.n
        val y = DoubleArray(n)
        for (i in 0 until n) {
            var s = b[i]
            for (j in 0 until i) s -= l[i * n + j] * y[j]
            y[i] = s / l[i * n + i]
        }
        return y
    }

    private fun solveUpper(l: DoubleArray, b: DoubleArray): DoubleArray {
        val n = model.n
        val y = DoubleArray(n)
        for (i in n - 1 downTo 0) {
            var s = b[i]
            for (j in i + 1 until n) s -= l[j * n + i] * y[j]
            y[i] = s / l[i * n + i]
        }
        return y
    }

    private fun invertSpd(a: DoubleArray): DoubleArray {
        val n = model.n
        val l = cholesky(a)
        val inv = DoubleArray(n * n)
        for (col in 0 until n) {
            val e = DoubleArray(n).also { it[col] = 1.0 }
            val y = solveLower(l, e)
            val v = solveUpper(l, y)
            for (row in 0 until n) inv[row * n + col] = v[row]
        }
        return inv
    }

    private fun joseph(p: DoubleArray, k: DoubleArray, c: DoubleArray, r: Double): DoubleArray {
        val n = model.n
        val a = DoubleArray(n * n)
        for (i in 0 until n) for (j in 0 until n) {
            a[i * n + j] = (if (i == j) 1.0 else 0.0) - k[i] * c[j]
        }
        val ap = matMul(a, p)
        val apat = matMul(ap, transpose(a))
        val krk = DoubleArray(n * n) { k[it / n] * k[it % n] * r }
        return DoubleArray(n * n) { apat[it] + krk[it] }
    }
}
