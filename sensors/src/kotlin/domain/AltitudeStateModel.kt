package de.perigon.companion.sensors.domain

/**
 * The state-space model behind [AltitudeKalmanFilter]. Two orthogonal axes:
 *
 *   [KalmanModel] (this file)  — state dimension, dynamics, measurement maps
 *   [KalmanMode]               — update rule (GAUSSIAN / HUBER / VBAKF / ISKF)
 *
 * Four models:
 *   [TwoStateModel]      [h, b]           — random walk on h, no velocity
 *   [ThreeStateModel]    [h, v_z, b]      — CV on h, v_z as state
 *   [FourStateModel]     [h, v_z, b, v_b] — CV on both h and b
 *   [GpsTwoStateModel]   [h, v_z]         — CV on h, GPS only, no bias state
 *
 * `v_b = db/dt` is the bias rate. In the 4-state it's a state; in the 2/3-
 * state it's exposed via a cascade estimator on b (see
 * AltitudeKalmanFilter.biasRate / recordDerivedBiasRate). The 4-state is
 * the tracking form; the cascade is the diagnostic form. `v_b` absorbs
 * aircon, weather drift, and thermal effects as a rate rather than as
 * process noise on b.
 *
 * [GpsTwoStateModel] has no bias state: baro is not in its measurement
 * map. [hasBaroMeasurement] is false, and [AltitudeKalmanFilter.updateBaro]
 * is a no-op for this model. Its h is still fed MSL (geoid-corrected),
 * same as every other model; see BackgroundService.emitLocation.
 *
 * Both axes are selectable at runtime. The filter delegates
 * predict/seed/recalibrate to the model and iterates its update rules to
 * `model.n`. Gap-reset (dt > 60 s) is filter-level, not model-level: every
 * model shares the "uninitialized" semantics, so the §3 fix — re-check
 * `initialized` after `predict()` — lives in `updateGps`/`updateBaro`, not
 * here.
 *
 * Models must be small, stateless-after-construction objects. The filter
 * swaps them by reinstantiation (constructing under new tuning is lossless
 * for the state) or by [seedFrom] (carries compatible state across a
 * dimension change — see [CompatibleState]).
 *
 * INVARIANT on the four feature flags ([n], [hasVelocity], [hasBiasRate],
 * [hasBias], [hasBaroMeasurement]): they describe the model's *shape*,
 * and must not depend on any constructor parameter. Concretely, a model
 * constructed with `KalmanTuning()` and `airplaneMode = false` must
 * report the same values as one constructed with user overrides — the
 * q_v variant of [GpsTwoStateModel] differs between ground and airplane,
 * but its *shape* is the same. [KalmanModel]'s lazily-constructed
 * canonical instance relies on this; violating the invariant would make
 * `KalmanModel.hasBias` (and siblings) return stale answers for any
 * caller reading the enum without first constructing the model.
 */
interface AltitudeStateModel {
    val n: Int

    /**
     * True when v_z is a state variable of this model (3-state, 4-state,
     * GPS-only 2-state). False means the filter still exposes v_z —
     * through the cascade estimator in [AltitudeKalmanFilter] — but not
     * as a state. Callers that want the velocity estimate should use
     * [AltitudeKalmanFilter.verticalSpeed] and not branch on this flag.
     *
     * Must not depend on constructor parameters — see the interface's
     * INVARIANT note.
     */
    val hasVelocity: Boolean

    /**
     * True when v_b is a state variable (4-state only). When false, the
     * filter exposes v_b through a cascade estimator on b — same call
     * pattern, lower quality (differentiation amplifies noise).
     *
     * Must not depend on constructor parameters — see the interface's
     * INVARIANT note.
     */
    val hasBiasRate: Boolean get() = false

    /**
     * True when b is a state variable. False for [GpsTwoStateModel]:
     * there is no baro measurement in its map, so a bias state would be
     * unobservable and its covariance would only grow. Callers reading
     * [AltitudeKalmanFilter.bias] / [AltitudeKalmanFilter.biasSigma]
     * should treat null as "no bias in this model", not "no estimate yet".
     *
     * Must not depend on constructor parameters — see the interface's
     * INVARIANT note.
     */
    val hasBias: Boolean get() = true

    /**
     * True when [AltitudeKalmanFilter.updateBaro] has any effect for this
     * model. False for [GpsTwoStateModel]; the caller should skip calling
     * updateBaro entirely. Defaults to [hasBias] because the models that
     * have a bias state are exactly the ones that have a baro measurement.
     *
     * Must not depend on constructor parameters — see the interface's
     * INVARIANT note.
     */
    val hasBaroMeasurement: Boolean get() = hasBias

    /**
     * Index of `b` in the state vector. Default `n-1` is correct for
     * 2-state and 3-state. [FourStateModel] overrides to `n-2` because
     * `v_b` occupies the last slot. Meaningless when [hasBias] is false.
     */
    val biasIndex: Int get() = n - 1

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
     * should preserve what remains meaningful (the 3/4/GPS-2-state keep P11
     * so a mid-recording recalibrate does not blank out the velocity
     * estimate).
     */
    fun calibrateCovariance(oldP: DoubleArray?): DoubleArray

    /**
     * The state transition matrix F used by [predict] over [dtSec].
     * Row-major, n×n. Used by the RTS smoother to reconstruct the
     * backward-pass cross-covariance `C_k = P_{k|k} Fᵀ P_{k+1|k}⁻¹`. Must
     * match what [predict] uses internally — the smoother and the forward
     * filter must agree about F or the smoothed trajectory will not be
     * consistent with the filtered one.
     */
    fun transitionMatrix(dtSec: Double): DoubleArray

    /**
     * Seed this model's state and covariance from a compatible snapshot
     * of another model's state. Used by [AltitudeKalmanFilter]'s
     * transitionModel on a dimension change.
     *
     * The default implementation is universal for the fusion models: h
     * lives at index 0, b at [biasIndex], and v_z / v_b (when present as
     * states) at indices 1 and n-1. [GpsTwoStateModel] overrides this to
     * skip the bias copy (it has no bias state). A model that ever needed
     * a different layout would override this; none do today.
     *
     * Null velocity / bias-rate fields in [src] mean "no estimate
     * available from the source" (either the source model did not have
     * the component as a state, or the cascade had not produced one yet).
     * The destination uses its own seed default for those slots.
     */
    fun seedFrom(src: CompatibleState): Pair<DoubleArray, DoubleArray> {
        val x = DoubleArray(n)
        val P = DoubleArray(n * n)

        x[0] = src.h
        P[0 * n + 0] = src.hVar
        if (hasBias) {
            x[biasIndex] = src.b
            P[0 * n + biasIndex] = src.hbCov
            P[biasIndex * n + 0] = src.hbCov
            P[biasIndex * n + biasIndex] = src.bVar
        }

        if (hasVelocity) {
            x[1] = src.vZ ?: 0.0
            P[1 * n + 1] = src.vZVar?.takeIf { it > 0.0 && it.isFinite() }
                ?: KalmanModel.SEED_VELOCITY_VARIANCE
        }
        if (hasBiasRate) {
            val vb = n - 1
            x[vb] = src.vB ?: 0.0
            P[vb * n + vb] = src.vBVar?.takeIf { it > 0.0 && it.isFinite() }
                ?: KalmanModel.SEED_BIAS_RATE_VARIANCE
        }
        return x to P
    }
}

/**
 * Snapshot of the state components that are compatible across model
 * transitions. Assembled by [AltitudeKalmanFilter] from its x/P (and its
 * cascade estimators where a component is not a state), consumed by the
 * destination model's [AltitudeStateModel.seedFrom].
 *
 * Every velocity / bias-rate field is nullable: null means "no estimate
 * available from the source" (either the source model did not have the
 * component as a state, or the corresponding cascade had not run yet).
 * Models that don't use a field ignore it; models that do use a field
 * fall back to their seed default when it is null.
 *
 * h and b are always available from the fusion models. [GpsTwoStateModel]
 * ignores b on the way in and leaves it null on the way out.
 *
 * Why this shape rather than per-transition code: every pair of models
 * wants the same thing — carry whatever is compatible across the
 * dimension change. Enumerating them individually invites one of them
 * being wrong; a shared snapshot that each model consumes selectively
 * keeps every transition on the same path and pushes the "what do I know
 * how to receive?" decision into the destination model, which is the only
 * place that knows it.
 */
data class CompatibleState(
    val h: Double,
    val hVar: Double,
    val b: Double,
    val bVar: Double,
    val hbCov: Double,
    val vZ: Double?,
    val vZVar: Double?,
    val vB: Double?,
    val vBVar: Double?,
)

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
        0.0, KalmanModel.SEED_VELOCITY_VARIANCE, 0.0,
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

/**
 * 4-state `[h, v_z, b, v_b]ᵀ`. Block-diagonal CV:
 *
 *   - (h, v_z) block: constant-velocity with accel noise q_a (same as 3-state)
 *   - (b, v_b) block: constant-velocity with bias-rate jerk noise q_vb
 *
 * No random walk on b — all bias motion goes through v_b. This is the
 * key structural difference from 3-state. Consequences:
 *
 *   - Steady-state b is smoother (v_b absorbs rate-of-change, so q_vb can
 *     be tight).
 *   - Aircon's step change in v_b is a *model violation* under the tight
 *     q_vb prior: the filter's predictions stay on the old rate, the baro
 *     innovation accumulates, and the CUSUM detector fires. The 3-state's
 *     q_b would just absorb the step as noise.
 *   - During a long GPS outage, Var(b) grows as q_vb·t³/3 (integrated
 *     random walk), not linearly as in 3-state. The 4-state is more
 *     confident about b during outages than 3-state — correct only if
 *     v_b is really constant. This is the tracking cost of the extra state;
 *     it is not paid under fused GPS+baro, which is the designed use case.
 *
 * F = [[1, dt, 0, 0], [0, 1, 0, 0], [0, 0, 1, dt], [0, 0, 0, 1]]
 *
 *   GPS:  H = [1, 0, 0, 0]
 *   Baro: H = [1, 0, 1, 0]
 *
 * v_b is unobservable in baro-only stretches (no independent altitude
 * reference to difference against). During a long outage, v_b diffuses
 * forward and h extrapolates along the last known rate — the same
 * tradeoff noted above.
 */
class FourStateModel(
    accelQ: Double? = null,
    biasRateQ: Double? = null,
) : AltitudeStateModel {
    private val qAccel: Double = accelQ ?: KalmanTuning.DEFAULT_ACCEL_NOISE_Q
    private val qBiasRate: Double = biasRateQ ?: KalmanTuning.DEFAULT_BIAS_RATE_Q

    override val n = 4
    override val hasVelocity = true
    override val hasBiasRate = true
    override val biasIndex = 2

    override fun gpsH(): DoubleArray = doubleArrayOf(1.0, 0.0, 0.0, 0.0)
    override fun baroH(): DoubleArray = doubleArrayOf(1.0, 0.0, 1.0, 0.0)

    override fun predict(x: DoubleArray, P: DoubleArray, dtSec: Double) {
        x[0] += x[1] * dtSec
        x[2] += x[3] * dtSec

        // P' = F P Fᵀ, applied as row then column transform (F is sparse).
        val fp = DoubleArray(16)
        for (j in 0 until 4) {
            fp[0 * 4 + j] = P[0 * 4 + j] + dtSec * P[1 * 4 + j]
            fp[1 * 4 + j] = P[1 * 4 + j]
            fp[2 * 4 + j] = P[2 * 4 + j] + dtSec * P[3 * 4 + j]
            fp[3 * 4 + j] = P[3 * 4 + j]
        }
        for (i in 0 until 4) {
            P[i * 4 + 0] = fp[i * 4 + 0] + dtSec * fp[i * 4 + 1]
            P[i * 4 + 1] = fp[i * 4 + 1]
            P[i * 4 + 2] = fp[i * 4 + 2] + dtSec * fp[i * 4 + 3]
            P[i * 4 + 3] = fp[i * 4 + 3]
        }

        // Q: accel noise on (h, v_z); bias-rate jerk on (b, v_b).
        val qa3 = qAccel * dtSec * dtSec * dtSec / 3.0
        val qa2 = qAccel * dtSec * dtSec / 2.0
        val qa1 = qAccel * dtSec
        P[0 * 4 + 0] += qa3
        P[0 * 4 + 1] += qa2
        P[1 * 4 + 0] += qa2
        P[1 * 4 + 1] += qa1

        val qb3 = qBiasRate * dtSec * dtSec * dtSec / 3.0
        val qb2 = qBiasRate * dtSec * dtSec / 2.0
        val qb1 = qBiasRate * dtSec
        P[2 * 4 + 2] += qb3
        P[2 * 4 + 3] += qb2
        P[3 * 4 + 2] += qb2
        P[3 * 4 + 3] += qb1
    }

    override fun transitionMatrix(dtSec: Double): DoubleArray = doubleArrayOf(
        1.0, dtSec, 0.0, 0.0,
        0.0, 1.0,   0.0, 0.0,
        0.0, 0.0,   1.0, dtSec,
        0.0, 0.0,   0.0, 1.0,
    )

    override fun seedState(altM: Double): DoubleArray =
        doubleArrayOf(altM, 0.0, 0.0, 0.0)

    override fun seedCovariance(): DoubleArray = doubleArrayOf(
        100.0, 0.0,                                    0.0,    0.0,
        0.0,   KalmanModel.SEED_VELOCITY_VARIANCE,     0.0,    0.0,
        0.0,   0.0,                                    10000.0, 0.0,
        0.0,   0.0,                                    0.0,    KalmanModel.SEED_BIAS_RATE_VARIANCE,
    )

    override fun calibrateCovariance(oldP: DoubleArray?): DoubleArray {
        val pVz = oldP?.get(1 * 4 + 1)?.takeIf { it > 0.0 } ?: 1.0
        val pVb = oldP?.get(3 * 4 + 3)?.takeIf { it > 0.0 } ?: KalmanModel.SEED_BIAS_RATE_VARIANCE
        return doubleArrayOf(
            1.0, 0.0, 0.0, 0.0,
            0.0, pVz, 0.0, 0.0,
            0.0, 0.0, 0.25, 0.0,
            0.0, 0.0, 0.0, pVb,
        )
    }
}

/**
 * GPS-only 2-state `[h, v_z]ᵀ`:
 *
 *   h   = altitude (m), same ellipsoid/geoid convention as the caller
 *   v_z = vertical velocity (m/s, positive = up)
 *
 * F = [[1, dt], [0, 1]]
 * Q = accel-noise block on (h, v_z), variance rate q_v
 *
 * GPS:  H = [1, 0]
 * Baro: not in the measurement model. [baroH] is defined for interface
 *       compatibility but [AltitudeKalmanFilter.updateBaro] is a no-op for
 *       this model: there is no bias state to absorb a baro measurement
 *       against, and feeding one would corrupt h by injecting an unknown
 *       offset.
 *
 * WHY THIS EXISTS: consumer GPS altitude is quantized (~1 m steps on many
 * chips) and noisy at the 5–15 m level. A raw `(h_t − h_{t−1})/Δt` is
 * unusable for slope display — the quantization alone produces ±50%
 * spikes at 1 Hz and freezes at 0 between steps. A CV model on `[h, v_z]`
 * gives a continuous altitude and a defensible vertical speed: the
 * velocity state *coasts* over the quantization steps instead of
 * differentiating them.
 *
 * This is a SMOOTHER, not a fuser. It replaces the baro-fused filter as
 * the source of `h`, `v_z`, and slope *only when that filter is disabled*
 * or the user explicitly selects this model. Its tuning is independent of
 * the fusion filter's: q_v here is a few orders of magnitude smaller (GPS
 * vertical acceleration over a short window is much gentler than the
 * process model assumes for baro-fused tracking), and there is no q_b at
 * all.
 *
 * R (measurement noise) is deliberately NOT taken from
 * verticalAccuracyMeters. It is the per-sample noise of the reported
 * series, which is quantization-limited — see KalmanTuning.gpsOnlySigmaM.
 *
 * The `q_v` switch (ground vs airplane) is selected at construction from
 * [KalmanModel.create]. The filter reinstantiates the model when the
 * recorder's airplane-mode flag changes; see AltitudeKalmanFilter's
 * setAirplaneMode.
 *
 * h is fed MSL (geoid-corrected), same as every other model. See
 * BackgroundService.emitLocation.
 *
 * [hasBias] is false. [AltitudeKalmanFilter.bias] / [biasSigma] return
 * null for this model, and the live card hides the bias-related rows.
 */
class GpsTwoStateModel(
    /**
     * Vertical acceleration process noise q_v, m²/s³. Null → default
     * (0.01, `DEFAULT_GPS_ONLY_ACCEL_Q`). The tighter default is
     * deliberate: the goal is a smooth, differentiation-stable altitude
     * for slope, not tracking of real vertical accelerations over
     * seconds. Raise if descents lag.
     */
    accelQ: Double? = null,
) : AltitudeStateModel {
    private val qAccel: Double = accelQ ?: KalmanTuning.DEFAULT_GPS_ONLY_ACCEL_Q

    override val n = 2
    override val hasVelocity = true
    override val hasBias = false

    override fun gpsH(): DoubleArray = doubleArrayOf(1.0, 0.0)
    override fun baroH(): DoubleArray = doubleArrayOf(1.0, 0.0)  // unused

    override fun predict(x: DoubleArray, P: DoubleArray, dtSec: Double) {
        x[0] += x[1] * dtSec

        val n00 = P[0] + dtSec * P[2] + dtSec * P[1] + dtSec * dtSec * P[3]
        val n01 = P[1] + dtSec * P[3]
        val n10 = P[2] + dtSec * P[3]
        val n11 = P[3]

        P[0] = n00 + qAccel * dtSec * dtSec * dtSec / 3.0
        P[1] = n01 + qAccel * dtSec * dtSec / 2.0
        P[2] = n10 + qAccel * dtSec * dtSec / 2.0
        P[3] = n11 + qAccel * dtSec
    }

    override fun transitionMatrix(dtSec: Double): DoubleArray = doubleArrayOf(
        1.0, dtSec,
        0.0, 1.0,
    )

    override fun seedState(altM: Double): DoubleArray = doubleArrayOf(altM, 0.0)

    override fun seedCovariance(): DoubleArray = doubleArrayOf(
        100.0, 0.0,
        0.0,   KalmanModel.SEED_VELOCITY_VARIANCE,
    )

    override fun calibrateCovariance(oldP: DoubleArray?): DoubleArray {
        val p11 = oldP?.get(3)?.takeIf { it > 0.0 } ?: 1.0
        return doubleArrayOf(
            1.0, 0.0,
            0.0, p11,
        )
    }

    /** GPS-only: nothing to receive except h / v_z. */
    override fun seedFrom(src: CompatibleState): Pair<DoubleArray, DoubleArray> {
        val x = DoubleArray(n)
        val P = DoubleArray(n * n)
        x[0] = src.h
        P[0] = src.hVar
        x[1] = src.vZ ?: 0.0
        P[3] = src.vZVar?.takeIf { it > 0.0 && it.isFinite() }
            ?: KalmanModel.SEED_VELOCITY_VARIANCE
        return x to P
    }
}

/**
 * Model axis. Independent of [KalmanMode] (update-rule axis).
 *
 * Feature flags (`hasVelocity`, `hasBiasRate`, `hasBias`,
 * `hasBaroMeasurement`) are read from a lazily-constructed canonical
 * instance of the corresponding [AltitudeStateModel] class. The class is
 * the single source of truth — the enum does not redeclare them, because
 * a parallel declaration would be free to drift from the class and would
 * require a guard test to stay in sync. Construction is cheap (a few
 * field assignments) and lazy, so the cost is paid at most once per enum
 * entry per process, and only if a caller actually reads a feature.
 *
 * The canonical instance is built with `create()` under default tuning
 * and `airplaneMode = false`. This is only safe because the four feature
 * flags describe the model's *shape* and do not depend on any constructor
 * parameter — see [AltitudeStateModel]'s INVARIANT note. The q_v variant
 * of [GpsTwoStateModel] differs between ground and airplane, but its
 * shape does not, so `hasBias` reads the same for both variants.
 */
enum class KalmanModel(val displayName: String) {
    TWO_STATE("2-state"),
    THREE_STATE("3-state"),
    FOUR_STATE("4-state"),
    GPS_TWO_STATE("GPS only");

    private val canonical: AltitudeStateModel by lazy { create() }

    val hasVelocity: Boolean        get() = canonical.hasVelocity
    val hasBiasRate: Boolean        get() = canonical.hasBiasRate
    val hasBias: Boolean            get() = canonical.hasBias
    val hasBaroMeasurement: Boolean get() = canonical.hasBaroMeasurement

    /**
     * Build this model. [tuning] supplies overrides; null fields fall back
     * to the model's built-in defaults. [airplaneMode] selects the q_v
     * variant for [GPS_TWO_STATE]; it is ignored by the fusion models
     * (their airplane-mode effect lives in the service, gating baro feed).
     *
     * Note: `biasNoiseQ` is passed to BOTH 2/3-state models — same field,
     * same semantics, same units. The Advanced panel's "Bias process noise"
     * slider is 1:1 with q_b in the filter for either of those models.
     * 4-state uses `biasRateQ` instead — it has no random walk on b.
     * GPS_TWO_STATE uses `gpsOnlyAccelQ` — it has neither.
     */
    fun create(tuning: KalmanTuning = KalmanTuning(), airplaneMode: Boolean = false): AltitudeStateModel = when (this) {
        THREE_STATE -> ThreeStateModel(
            accelQ = tuning.accelNoiseQ,
            biasQ  = tuning.biasNoiseQ,
        )
        TWO_STATE -> TwoStateModel(
            qH = tuning.qH,
            qB = tuning.biasNoiseQ,
        )
        FOUR_STATE -> FourStateModel(
            accelQ    = tuning.accelNoiseQ,
            biasRateQ = tuning.biasRateQ,
        )
        GPS_TWO_STATE -> GpsTwoStateModel(
            accelQ = if (airplaneMode) tuning.gpsOnlyAccelQAirplane
                     else tuning.gpsOnlyAccelQ,
        )
    }

    companion object {
        /**
         * P[v_z,v_z] at seed: σ_v = 2 m/s, so var = 4.0 (m/s)². Referenced
         * by [AltitudeStateModel.seedFrom] when a 2→3 or 2→4 swap has no
         * cascade velocity to inherit.
         */
        const val SEED_VELOCITY_VARIANCE = 4.0
        /**
         * P[v_b,v_b] at seed: σ_vb = 0.01 m/s = the aircon-rate magnitude.
         * At seed we don't know whether the user is in aircon or weather,
         * so allow up to ±3 cm/s (3σ). q_vb then decays this toward the
         * tight prior as data accumulates.
         */
        const val SEED_BIAS_RATE_VARIANCE = 1e-4
    }
}
