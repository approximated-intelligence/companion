package de.perigon.companion.sensors.domain

import kotlin.math.max

/**
 * Two-sided CUSUM on a N(0,1) sequence. Model-agnostic: takes
 * already-normalized innovations (z_k = ν_k / √S_k) and accumulates
 * evidence of drift. The caller is responsible for producing z_k under
 * whatever measurement model the filter is currently using.
 *
 * Deliberately NOT a field of [AltitudeKalmanFilter], so the detector
 * is orthogonal to the filter's internals: it doesn't care which state
 * model produced the innovation, only that the innovation is N(0,1)
 * under H0. Every well-specified model satisfies this, so the CUSUM
 * statistic remains valid across model swaps — see
 * [AltitudeKalmanFilter.transitionModel], which does NOT reset it.
 *
 * The only legitimate reset points are cold start and recalibration:
 * [AltitudeKalmanFilter.reset], [AltitudeKalmanFilter.seed], and
 * [AltitudeKalmanFilter.resetWithAltitude].
 *
 * Parameters:
 *   [k]       reference value: standard choice for detecting ~1σ shifts
 *             while tolerating small drifts under H0.
 *   [h]       decision threshold: ARL_0 ~ 10⁴ samples (≈ 33 min at 5 Hz
 *             baro); ARL_1 ~ 10 samples for a 1σ shift.
 *   [zClamp]  per-sample z clamp. Aircon produces a sustained bias, not
 *             a single spike; clamping prevents one sensor glitch from
 *             dumping a huge mass into CUSUM.
 */
internal class BaroInnovationCusum(
    private val k: Double = 0.5,
    private val h: Double = 8.0,
    private val zClamp: Double = 5.0,
) {
    private var pos = 0.0
    private var neg = 0.0

    fun feed(z: Double) {
        val zc = z.coerceIn(-zClamp, zClamp)
        pos = max(0.0, pos + zc - k)
        neg = max(0.0, neg - zc - k)
    }

    fun stats(): Pair<Float, Float> = pos.toFloat() to neg.toFloat()
    fun detected(): Boolean = pos > h || neg > h
    fun reset() { pos = 0.0; neg = 0.0 }
}
