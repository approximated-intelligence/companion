package de.perigon.companion.sensors.domain

/**
 * The state-space model behind [AltitudeKalmanFilter]. Two orthogonal axes:
 *
 *   [KalmanModel] (this file)  — state dimension, dynamics, measurement maps
 *   [KalmanMode]               — update rule (GAUSSIAN / HUBER / VBAKF / ISKF)
 *
 * Both are selectable at runtime. The filter delegates predict/seed/recalibrate
 * to the model and iterates its update rules to `model.n`. Gap-reset (dt > 60 s)
 * is filter-level, not model-level: both models share the "uninitialized"
 * semantics, so the §3 fix — re-check `initialized` after `predict()` — lives
 * in `updateGps`/`updateBaro`, not here.
 *
 * Models must be small, stateless-after-construction objects. The filter
 * swaps them by reset (dimensions change, nothing valid to preserve).
 */
interface AltitudeStateModel {
    val n: Int

    /**
     * True when v_z is a state variable of this model (3-state). False
     * means the filter still exposes v_z — through the cascade estimator
     * in [AltitudeKalmanFilter] — but not as a state. Callers that want
     * the velocity estimate should use [AltitudeKalmanFilter.verticalSpeed]
     * and not branch on this flag.
     */
    val hasVelocity: Boolean

    fun gpsH(): DoubleArray
    fun baroH(): DoubleArray

    /** In-place predict. Caller validates: dt > 0, dt <= 60. */
    fun predict(x: DoubleArray, P: DoubleArray, dtSec: Double)

    /** State at first GPS seed. */
    fun seedState(altM: Double): DoubleArray

    /** Covariance at first GPS seed (wide: we know almost nothing). */
    fun seedCovariance(): DoubleArray

    /**
     * Covariance at recalibration (`resetWithAltitude`). [oldP] is null when
     * the filter was not yet initialized; non-null when it was and the model
     * should preserve what remains meaningful (the 3-state keeps P11 so a
     * mid-recording recalibrate does not blank out the velocity estimate).
     */
    fun calibrateCovariance(oldP: DoubleArray?): DoubleArray

    /**
     * The state transition matrix F used by [predict] over [dtSec]. Row-major,
     * n×n. Used by the RTS smoother to reconstruct the backward-pass
     * cross-covariance `C_k = P_{k|k} Fᵀ P_{k+1|k}⁻¹`. Must match what
     * [predict] uses internally — the smoother and the forward filter must
     * agree about F or the smoothed trajectory will not be consistent with
     * the filtered one.
     */
    fun transitionMatrix(dtSec: Double): DoubleArray
}

/**
 * 3-state `[h, v_z, b]ᵀ`:
 *
 *   h   = MSL altitude (m)
 *   v_z = vertical velocity (m/s, positive = up)
 *   b   = barometric bias (m): rawBaro − trueAlt. Absorbs weather, aircon,
 *         and any residual calibration error.
 *
 * F = [[1, dt, 0], [0, 1, 0], [0, 0, 1]]
 * Q = acceleration-noise block + bias random walk
 *
 * GPS:  H = [1, 0, 0]
 * Baro: H = [1, 0, 1]
 *
 * v_z is observable from GPS but only weakly with these parameters; its
 * uncertainty integrates into h during outages, so σ_h grows as t^{3/2}.
 * Better than 2-state for short outages when velocity is well-tracked;
 * worse for long outages.
 *
 * Process noise is expressed as variance rate directly (m²/s³ for q_v,
 * m²/s for q_b), not as σ. The constructor parameters take q values; the
 * filter adds them to P as-is. Slider in the Advanced expander likewise
 * shows q, matching 2-state's q scales. See [KalmanTuning].
 */
class ThreeStateModel(
    /**
     * Vertical acceleration process noise q_v, m²/s³. Null → default
     * (0.04, historically 0.2² σ_a). Larger values let v_z respond faster
     * to real vertical acceleration; smaller values smooth more.
     */
    accelQ: Double? = null,
    /**
     * Bias random-walk process noise q_b, m²/s. Null → default (3e-4,
     * weather-drift rate). Larger values let b track faster aircon and
     * gust effects but absorb sensor noise; smaller values freeze b.
     *
     * Semantically IDENTICAL to [TwoStateModel.qB] — same field concept,
     * same units, same effect on the bias state. The only difference is
     * that the 3-state also has h–v coupling, which changes how much
     * baro information is available to the bias update indirectly.
     */
    biasQ: Double? = null,
) : AltitudeStateModel {
    private val qAccel: Double = accelQ ?: KalmanTuning.DEFAULT_ACCEL_NOISE_Q
    private val qBias: Double = biasQ ?: KalmanTuning.DEFAULT_BIAS_NOISE_Q

    override val n = 3
    override val hasVelocity = true

    override fun gpsH(): DoubleArray = doubleArrayOf(1.0, 0.0, 0.0)
    override fun baroH(): DoubleArray = doubleArrayOf(1.0, 0.0, 1.0)

    override fun predict(x: DoubleArray, P: DoubleArray, dtSec: Double) {
        x[0] += x[1] * dtSec

        val n00 = P[0] + dtSec * P[3] + dtSec * P[1] + dtSec * dtSec * P[4]
        val n01 = P[1] + dtSec * P[4]
        val n02 = P[2] + dtSec * P[5]
        val n10 = P[3] + dtSec * P[4]
        val n11 = P[4]
        val n12 = P[5]
        val n20 = P[6] + dtSec * P[7]
        val n21 = P[7]
        val n22 = P[8]

        // qAccel / qBias are already variance rates; add them directly.
        P[0] = n00 + qAccel * dtSec * dtSec * dtSec / 3.0
        P[1] = n01 + qAccel * dtSec * dtSec / 2.0
        P[2] = n02
        P[3] = n10 + qAccel * dtSec * dtSec / 2.0
        P[4] = n11 + qAccel * dtSec
        P[5] = n12
        P[6] = n20
        P[7] = n21
        P[8] = n22 + qBias * dtSec
    }

    override fun transitionMatrix(dtSec: Double): DoubleArray = doubleArrayOf(
        1.0, dtSec, 0.0,
        0.0, 1.0, 0.0,
        0.0, 0.0, 1.0,
    )

    override fun seedState(altM: Double): DoubleArray = doubleArrayOf(altM, 0.0, 0.0)

    override fun seedCovariance(): DoubleArray = doubleArrayOf(
        100.0, 0.0, 0.0,
        0.0, 4.0, 0.0,
        0.0, 0.0, 10000.0,
    )

    override fun calibrateCovariance(oldP: DoubleArray?): DoubleArray {
        // Preserve P11 when running: a mid-session recalibrate should not blank
        // the velocity estimate. h and b are always reset tight — that is the
        // point of calibration.
        val p11 = oldP?.get(4)?.takeIf { it > 0.0 } ?: 1.0
        return doubleArrayOf(
            1.0, 0.0, 0.0,
            0.0, p11, 0.0,
            0.0, 0.0, 0.25,
        )
    }
}

/**
 * 2-state `[h, b]ᵀ`:
 *
 *   h = MSL altitude (m)
 *   b = barometric bias (m)
 *
 * F = I₂ (no velocity to integrate)
 * Q = diag(q_h, q_b)·dt
 *
 * GPS:  H = [1, 0]
 * Baro: H = [1, 1]
 *
 * Random walk on h replaces CV dynamics. Baro drives h at full bandwidth set
 * by q_h and R_baro; no velocity state steals baro information during the
 * "learn b" phase. Without measurements, σ_h grows as t^{1/2} (random walk),
 * not the 3-state's t^{3/2}. Better for long outages; worse for short outages
 * on a moving vehicle where CV dynamics would coast.
 *
 * Process noise is expressed as variance rate directly, same convention as
 * the 3-state.
 */
class TwoStateModel(
    /** h random-walk process noise q_h, m²/s. Null → default (0.5). */
    qH: Double? = null,
    /**
     * b random-walk process noise q_b, m²/s. Null → default (3e-4).
     * Semantically IDENTICAL to [ThreeStateModel.biasQ] — same field
     * concept, same units, same effect on the bias state. The Advanced
     * panel passes the same tuning field to both models.
     */
    qB: Double? = null,
) : AltitudeStateModel {
    private val qHRate: Double = qH ?: KalmanTuning.DEFAULT_QH
    private val qBRate: Double = qB ?: KalmanTuning.DEFAULT_BIAS_NOISE_Q

    override val n = 2
    override val hasVelocity = false

    override fun gpsH(): DoubleArray = doubleArrayOf(1.0, 0.0)
    override fun baroH(): DoubleArray = doubleArrayOf(1.0, 1.0)

    override fun predict(x: DoubleArray, P: DoubleArray, dtSec: Double) {
        // F = I: x unchanged. Q = diag(qH, qB)·dt.
        P[0] += qHRate * dtSec
        P[3] += qBRate * dtSec
    }

    override fun transitionMatrix(dtSec: Double): DoubleArray = doubleArrayOf(
        1.0, 0.0,
        0.0, 1.0,
    )

    override fun seedState(altM: Double): DoubleArray = doubleArrayOf(altM, 0.0)

    override fun seedCovariance(): DoubleArray = doubleArrayOf(
        100.0, 0.0,
        0.0, 10000.0,
    )

    override fun calibrateCovariance(oldP: DoubleArray?): DoubleArray = doubleArrayOf(
        1.0, 0.0,
        0.0, 0.25,
    )
}

/** Model axis. Independent of [KalmanMode] (update-rule axis). */
enum class KalmanModel(val displayName: String, val hasVelocity: Boolean) {
    THREE_STATE("3-state", hasVelocity = true),
    TWO_STATE("2-state", hasVelocity = false);

    /**
     * Build this model. [tuning] supplies overrides; null fields fall back
     * to the model's built-in defaults.
     *
     * Note: `biasNoiseQ` is passed to BOTH models — same field, same
     * semantics, same units. The Advanced panel's "Bias process noise"
     * slider is 1:1 with q_b in the filter regardless of active model.
     */
    fun create(tuning: KalmanTuning = KalmanTuning()): AltitudeStateModel = when (this) {
        THREE_STATE -> ThreeStateModel(
            accelQ = tuning.accelNoiseQ,
            biasQ  = tuning.biasNoiseQ,
        )
        TWO_STATE -> TwoStateModel(
            qH = tuning.qH,
            qB = tuning.biasNoiseQ,
        )
    }
}
