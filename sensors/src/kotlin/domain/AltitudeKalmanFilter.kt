package de.perigon.companion.sensors.domain

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign
import kotlin.math.sqrt

enum class KalmanMode { GAUSSIAN, HUBER, VBAKF, ISKF }
enum class UpdateSource { GPS, BARO }

/**
 * Altitude Kalman filter. Two orthogonal axes:
 *
 *   [KalmanModel] — state dimension and dynamics (3-state CV / 2-state RW).
 *                   See [ThreeStateModel] and [TwoStateModel] for the
 *                   state / process / measurement definitions.
 *   [KalmanMode]  — update rule. The prediction step is identical for all
 *                   modes; only the measurement update differs:
 *                    - GAUSSIAN: standard Riccati.
 *                    - HUBER: Huber M-estimation with effective covariance
 *                      and Joseph form.
 *                    - VBAKF: variational Bayes estimation of the scalar
 *                      measurement noise.
 *                    - ISKF: Iteratively Saturated KF (Yang & Boyd 2025),
 *                      convex robust MAP with separate process and
 *                      measurement outlier terms.
 *
 * [setMode] and [setIskfInverseHessian] mutate the filter in place; state
 * and covariance survive. This is important when the user switches update
 * rules mid-recording — the state representation is the same for all modes,
 * only the update rule changes, so there is no reason to reset.
 *
 * [setModel] and [setTuning] PRESERVE state:
 *   - Tuning change: the model object is reinstantiated under the new
 *     process noise; x, P, initialized, lastMs and cascade state all
 *     survive. The accumulated b estimate — the parameter most directly
 *     about the q_b slider being tuned — is retained.
 *   - Model change: the compatible state is transplanted across the
 *     dimension swap — h (index 0), b (index n-1), and their covariance
 *     block are copied through; v_z is handed across (state ↔ cascade).
 *     Per-update diagnostics are cleared (they belong to the old model's H
 *     and dims) and repopulate on the next measurement. The RTS smoother's
 *     step-record buffer is owned by the caller ([KfViewModel]) and is
 *     cleared there; the filter does not own it.
 *   - A change to [KalmanTuning.baroSigmaM] or [KalmanTuning.gpsVerticalMinM]
 *     alone touches neither model nor state — both live at the caller
 *     (sigma arguments to [updateBaro] and [updateGps]).
 * [reset] remains the explicit cold start; its callers are the gap timeout
 * in [predict], the airplane-mode flip in [BackgroundService], and the KF
 * tab's "Reset filter".
 *
 * Velocity:
 *
 *   When the model has a velocity state (3-state), [verticalSpeed] returns
 *   that state directly, and [velocitySigma] returns σ(v_z) from the main
 *   covariance.
 *
 *   When it does not (2-state), [verticalSpeed] returns the output of a
 *   scalar cascade Kalman filter that treats (Δh / Δt) between successive
 *   main-filter updates as a noisy measurement of v_z. The measurement
 *   noise covariance is derived from the main filter's own altitude
 *   covariance: R_v = (P_hh(k) + P_hh(k−1)) / Δt². This is the discrete
 *   position-difference velocity estimator used in GPS/INS integration and
 *   is the discrete analogue of the Kalman observer for derivatives
 *   (Martin 1970): the gain minimises velocity error covariance rather
 *   than state error covariance. It is a leaf — v_z never feeds back into
 *   h or b, and the cascade runs only when the model has no velocity
 *   state. Across a model swap the cascade and the 3-state v_z hand off
 *   to each other, so [verticalSpeed] is continuous through the swap.
 *
 * Diagnostics ([lastGpsInnovationM], [lastGpsInnovationS], [lastGpsNis],
 * and the baro equivalents) are exposed for the KF debug tab. NIS is the
 * normalized innovation squared: ν²/S, where S is the *effective* innovation
 * covariance the update actually used (after Huber/VBAKF inflation for
 * outlier rejection). Under a well-specified model, NIS samples are χ²(1)
 * distributed with mean 1.0 and 95% below 3.84; robust methods will show
 * NIS < 1 on rejected samples (inflated S). This is the calibration check:
 * if the running mean drifts above 1.0, the filter is overconfident
 * (typically R_gps underreported, or P too small); below 1.0, underconfident.
 *
 * [captureStepRecord] and [smoothBackward] implement a Rauch-Tung-Striebel
 * smoother for the debug tab. Capture a snapshot after each measurement;
 * pass the buffered list to [smoothBackward] to produce an h trajectory
 * conditioned on all measurements in the window. Divergence between
 * filtered h and smoothed h beyond sqrt(P_filtered + P_smoothed) is a
 * second calibration signal (the filter's posterior differs from what a
 * hindsight-optimal estimator would produce).
 *
 * Gaps > 60 s reset the filter: either the caller paused or the phone was
 * off long enough that carrying stale state is worse than restarting. The
 * §3 fix — re-check `initialized` after `predict()` — makes the post-gap
 * measurement either re-seed (GPS) or drop (baro), never apply to a reset
 * state. The 60 s threshold is deliberately longer than typical FGS-
 * tolerated screen-off gaps (a couple of seconds), so those do *not*
 * reset; the filter predicts through them with real `dt` and produces
 * honest (larger) P afterward. A genuine reset only happens after a
 * minute of silence, where the state really is worthless.
 *
 * All internal math is Double; public surface is Float to match the rest
 * of the track module.
 */
class AltitudeKalmanFilter(
    initialModel: KalmanModel = KalmanModel.THREE_STATE,
    initialMode: KalmanMode = KalmanMode.GAUSSIAN,
    initialIskfInverseHessian: Boolean = false,
    initialTuning: KalmanTuning = KalmanTuning(),
    private val huberDelta: Double = 1.5,
    private val iskfLambdaX: Double = 1.0,
    private val iskfLambdaY: Double = 1.5,
    private val iskfIterations: Int = 2,
    private val vbakfIterations: Int = 3,
    private val vbakfPriorDof: Double = 4.0,
) {
    private var modelEnum: KalmanModel = initialModel
    private var tuning: KalmanTuning = initialTuning
    private var model: AltitudeStateModel = initialModel.create(initialTuning)
    private var mode: KalmanMode = initialMode
    private var iskfInverseHessian: Boolean = initialIskfInverseHessian

    /**
     * Cascade velocity random-walk process noise q_v, m²/s³. Used only when
     * the model has no velocity state (2-state). Overridden from
     * [KalmanTuning.derivedVelNoise]. Kept as a plain field, independent of
     * the model object: a `derivedVelNoise`-only change updates it without
     * reinstantiating the model, because neither ThreeStateModel nor
     * TwoStateModel depends on it.
     */
    private var derivedVelNoise: Double =
        initialTuning.derivedVelNoise ?: KalmanTuning.DEFAULT_DERIVED_VEL_NOISE

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

    /** Update rule only. State, covariance, and model survive. */
    fun setMode(newMode: KalmanMode) { mode = newMode }

    fun setIskfInverseHessian(enabled: Boolean) { iskfInverseHessian = enabled }

    /**
     * Swap model. Carries compatible state across the dimension change:
     * h, b, and their covariance block are transplanted; v_z is handed
     * between the 3-state's state vector and the 2-state's cascade.
     * Diagnostics are cleared (per-update, tied to the old H/dims);
     * history, NIS windows and the step-record buffer are the caller's
     * to manage.
     */
    fun setModel(newModel: KalmanModel) {
        if (modelEnum == newModel) return
        transitionModel(newModel, tuning)
    }

    /**
     * Update tuning overrides.
     *
     * The [KalmanTuning.derivedVelNoise] field is a plain field of this
     * filter, not a constructor argument of either model, so it is updated
     * unconditionally here. The model object is reinstantiated only when a
     * constructor argument changed — `accelNoiseQ`/`biasNoiseQ` for 3-state,
     * `qH`/`biasNoiseQ` for 2-state — and when it does, x, P, initialized,
     * lastMs, cascade state and diagnostics all survive.
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
        if (relevant) reinstantiateModel()
    }

    /**
     * Swap model AND tuning in one call. Used by callers that detect both
     * changed at once (the config observer). When the model is unchanged,
     * this routes through the tuning-only path — same cost as [setTuning],
     * same state preservation, no state transplant.
     */
    fun setModelAndTuning(newModel: KalmanModel, newTuning: KalmanTuning) {
        if (modelEnum == newModel && tuning == newTuning) return
        if (modelEnum == newModel) {
            val relevant = relevantTuningChange(tuning, newTuning)
            tuning = newTuning
            derivedVelNoise = newTuning.derivedVelNoise ?: KalmanTuning.DEFAULT_DERIVED_VEL_NOISE
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
     *  - 3-state: the KF's v_z state (proper uncertainty via [velocitySigma]).
     *  - 2-state: the cascade scalar KF described in the class doc. Non-null
     *    once two altitude samples have been differenced.
     *
     * Null before initialization, or in 2-state for the interval between
     * seed and the first real update.
     */
    fun verticalSpeed(): Float? {
        if (!initialized) return null
        return if (model.hasVelocity) x[1].toFloat() else derivedVz?.toFloat()
    }

    fun bias(): Float? =
        if (initialized) x[model.n - 1].toFloat() else null

    fun altitudeSigma(): Float? =
        if (initialized) sqrt(max(P[0], 0.0)).toFloat() else null

    /**
     * σ(v) from the 3-state covariance when v_z is a state, or from the
     * cascade filter's scalar covariance when it is not. Null when no
     * velocity estimate exists yet.
     */
    fun velocitySigma(): Float? {
        if (!initialized) return null
        return if (model.hasVelocity) sqrt(max(P[4], 0.0)).toFloat()
        else derivedVz?.let { sqrt(max(derivedVzCov, 0.0)).toFloat() }
    }

    fun biasSigma(): Float? {
        if (!initialized) return null
        val i = model.n - 1
        return sqrt(max(P[i * model.n + i], 0.0)).toFloat()
    }

    fun lastGpsInnovationM(): Float? = lastGpsInnovation?.toFloat()
    fun lastBaroInnovationM(): Float? = lastBaroInnovation?.toFloat()
    fun lastGpsInnovationS(): Float? = lastGpsInnovationS?.toFloat()
    fun lastBaroInnovationS(): Float? = lastBaroInnovationS?.toFloat()

    fun lastGpsNis(): Float? {
        val v = lastGpsInnovation ?: return null
        val s = lastGpsInnovationS ?: return null
        if (s < 1e-12) return null
        return ((v * v) / s).toFloat()
    }

    fun lastBaroNis(): Float? {
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
     * reassigns x, P, lastPriorX, lastPriorP before anything observes them,
     * so there is no need to zero the raw buffers here — a reset is a
     * logical reset, not a memory wipe.
     */
    fun reset() {
        initialized = false
        lastMs = 0L
        clearDiagnostics()
        clearDerivedVelocity()
    }

    /**
     * Seed / re-seed with a known altitude, typically from the one-shot
     * calibration dialog.
     *
     * Already initialized: h snaps to [altM] and b to (baroZ − altM). What
     * else survives is the model's decision — the 3-state keeps P11 so a
     * mid-recording recalibrate does not blank out the velocity estimate;
     * the 2-state's cascade velocity estimator is reset (the snap is a
     * discontinuity, and pre-snap Δh would corrupt the first velocity
     * sample). See [AltitudeStateModel.calibrateCovariance].
     *
     * Not initialized: the model's seedState / seedCovariance set up all
     * states from scratch.
     */
    fun resetWithAltitude(altM: Float, baroZ: Float?) {
        val newX = if (initialized) x.copyOf() else model.seedState(altM.toDouble())
        newX[0] = altM.toDouble()
        if (baroZ != null) newX[model.n - 1] = (baroZ - altM).toDouble()
        x = newX
        P = model.calibrateCovariance(if (initialized) P else null)
        clearDiagnostics()
        clearDerivedVelocity()
        initialized = true
        lastMs = System.currentTimeMillis()
        capturePrior()
        recordDerivedVelocity()
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
    }

    /** Baro altitude measurement. No-op until GNSS has seeded the filter. */
    fun updateBaro(altM: Float, sigmaM: Float) {
        if (!initialized) return
        predict()
        // §3 fix: gap-reset during predict() has de-initialized us; drop the
        // baro sample rather than apply it to zeros. b is unobservable from
        // baro alone, so there is nothing valid to do here either.
        if (!initialized) return
        applyMeasurement(model.baroH(), altM.toDouble(), sigmaM.toDouble().let { it * it }, isBaro = true)
        recordDerivedVelocity()
    }

    /**
     * QNH from a raw pressure reading. QNH = P / (1 − L·h/T₀)^n. This
     * depends only on P and h, not on the calibration offset: with or
     * without offset, the recovered QNH is the same once the filter has
     * converged. Callers should still gate on "not airplane mode" — in a
     * pressurized cabin, h_KF is real but P is cabin pressure, so the
     * formula yields nonsense.
     */
    fun qnhHpa(pressureHpa: Float): Float? {
        if (!initialized) return null
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

        val biasIdx = nS - 1
        return records.mapIndexed { i, r ->
            SmoothedSample(
                timeMs = r.timeMs,
                h = smoothedX[i][0].toFloat(),
                sigmaH = sqrt(max(smoothedP[i][0], 0.0)).toFloat(),
                b = smoothedX[i][biasIdx].toFloat(),
                sigmaB = sqrt(max(smoothedP[i][biasIdx * nS + biasIdx], 0.0)).toFloat(),
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

    /**
     * Rebuild the model OBJECT under the current tuning, leaving state
     * untouched. This is the lossless half of what used to be rebuild():
     * ThreeStateModel/TwoStateModel capture q in their constructors, so a
     * process-noise change requires a fresh instance — but x, P, initialized,
     * lastMs, cascade state and diagnostics are independent of the model
     * object and survive. derivedVelNoise is a filter field, not a model
     * constructor argument, and is updated by [setTuning] / [setModelAndTuning]
     * directly.
     */
    private fun reinstantiateModel() {
        model = modelEnum.create(tuning)
    }

    /**
     * Move to [newModel] / [newTuning], carrying every state component that
     * is compatible across the dimension change:
     *
     *   h   — index 0 in both models, copied directly.
     *   b   — index n-1 in both models, copied directly.
     *   v_z — 3-state keeps its own; 3→2 hands the state's v_z to the
     *         cascade; 2→3 hands the cascade's v_z to the state (or the
     *         seed covariance if the cascade never ran).
     *
     * P is transplanted for the {h, b} submatrix; cross-terms with v_z are
     * preserved on 3→3 (impossible via setModel's guard) and zeroed on 2→3
     * (no a priori knowledge of the correlation across the swap). 3→2 keeps
     * the h-h, h-b, b-h, b-b submatrix and discards the v_z row/col, which
     * the cascade then re-initialises from the discarded P11.
     *
     * Diagnostics (per-measurement) are cleared — they describe the last
     * measurement under the old model and would be misleading. They
     * repopulate on the next update. Cascade state, NIS running windows
     * and the RTS step-record buffer are the caller's to manage (see
     * KfViewModel.setModel / BackgroundService.applyConfigChange).
     *
     * Called only from setModel and setModelAndTuning, both of which guard
     * on `modelEnum != newModel`; the 3→3 and 2→2 cases never reach here.
     */
    private fun transitionModel(newModel: KalmanModel, newTuning: KalmanTuning) {
        val oldModel     = model
        val oldN         = oldModel.n
        val oldX         = x
        val oldP         = P
        val wasInit      = initialized
        val oldLastMs    = lastMs
        val cascadeVz    = derivedVz
        val cascadeVzCov = derivedVzCov

        modelEnum       = newModel
        tuning          = newTuning
        model           = newModel.create(newTuning)
        derivedVelNoise = newTuning.derivedVelNoise ?: KalmanTuning.DEFAULT_DERIVED_VEL_NOISE

        if (!wasInit) {
            // Unseeded: nothing to preserve. Arrays may also be the wrong
            // size for the new model; resize them to the new dimension so
            // subsequent seed()/predict() operate on the right shape.
            x = DoubleArray(model.n)
            P = DoubleArray(model.n * model.n)
            lastPriorX = DoubleArray(model.n)
            lastPriorP = DoubleArray(model.n * model.n)
            initialized = false
            lastMs = 0L
            clearDiagnostics()
            clearDerivedVelocity()
            return
        }

        val bNew = model.n - 1
        val bOld = oldN - 1

        val newX = DoubleArray(model.n)
        newX[0]    = oldX[0]
        newX[bNew] = oldX[bOld]
        if (model.hasVelocity) {
            newX[1] = if (oldModel.hasVelocity) oldX[1]
                      else cascadeVz ?: 0.0
        }

        val newP = DoubleArray(model.n * model.n)
        newP[0]                             = oldP[0]
        newP[0            * model.n + bNew] = oldP[0    * oldN + bOld]
        newP[bNew         * model.n + 0]    = oldP[bOld * oldN + 0]
        newP[bNew         * model.n + bNew] = oldP[bOld * oldN + bOld]
        if (model.hasVelocity) {
            // P[1,1] for v_z. 2→3 has no state v_z to inherit: if the cascade
            // ran, its covariance carries over; if it never ran (no sample
            // pair differenced yet), fall back to the model's seed covariance
            // so the estimate (0) and its uncertainty (σ_v = 2 m/s) stay
            // consistent with seedCovariance().
            newP[1 * model.n + 1] = when {
                oldModel.hasVelocity -> oldP[1 * oldN + 1]
                cascadeVz == null    -> ThreeStateModel.SEED_VELOCITY_VARIANCE
                else -> cascadeVzCov.takeIf { it > 0.0 && it.isFinite() }
                    ?: ThreeStateModel.SEED_VELOCITY_VARIANCE
            }
            if (oldModel.hasVelocity) {
                // 3→3 would preserve cross-terms, but setModel's guard
                // prevents it from reaching here; kept for symmetry and in
                // case setModelAndTuning is ever relaxed.
                newP[0            * model.n + 1]    = oldP[0    * oldN + 1]
                newP[1            * model.n + 0]    = oldP[1    * oldN + 0]
                newP[1            * model.n + bNew] = oldP[1    * oldN + bOld]
                newP[bNew         * model.n + 1]    = oldP[bOld * oldN + 1]
            }
        }

        x           = newX
        P           = newP
        initialized = true
        lastMs      = oldLastMs
        clearDiagnostics()

        if (model.hasVelocity) {
            // 2→3: v_z now lives in the state vector; the cascade is
            // dormant. Clear its stale values so a future 3→2 swap starts
            // from the state's v_z, not from a leftover cascade sample.
            clearDerivedVelocity()
        } else {
            // 3→2: hand the old state's v_z to the cascade, with its P11
            // as the cascade's initial covariance. Re-anchor the h-
            // difference baseline so the first post-swap sample computes
            // v_z from the transplanted height, not a stale pre-swap one.
            if (oldModel.hasVelocity) {
                derivedVz    = oldX[1]
                derivedVzCov = oldP[1 * oldN + 1]
            }
            lastH     = newX[0]
            lastHTime = oldLastMs
            lastP00   = newP[0]
        }
    }

    /**
     * Whether the tuning change requires reinstantiating the model object.
     * This depends only on the model's constructor arguments — the process
     * noise that gets baked into the model. It is deliberately independent
     * of [derivedVelNoise], which lives on the filter, not the model.
     */
    private fun relevantTuningChange(old: KalmanTuning, new: KalmanTuning): Boolean =
        when (modelEnum) {
            KalmanModel.THREE_STATE ->
                old.accelNoiseQ != new.accelNoiseQ ||
                old.biasNoiseQ != new.biasNoiseQ
            KalmanModel.TWO_STATE ->
                old.qH != new.qH ||
                old.biasNoiseQ != new.biasNoiseQ
        }

    private fun seed(altM: Double) {
        x = model.seedState(altM)
        P = model.seedCovariance()
        clearDiagnostics()
        clearDerivedVelocity()
        initialized = true
        lastMs = System.currentTimeMillis()
        capturePrior()
        recordDerivedVelocity()
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
     *
     * Because R_v is derived from the main filter's own covariance, the
     * cascade is automatically less confident when the main filter is
     * uncertain (e.g. right after seed, or during a long baro-only stretch
     * where h has drifted), and more confident as the main filter
     * converges. Outlier rejection is inherited too: a rejected measurement
     * barely moves x[0], so the cascade sees only a small Δh — the robust
     * update rule acts as the outlier gate for v_z as well.
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
