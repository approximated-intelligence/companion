package de.perigon.companion.sensors.domain

/**
 * Per-measurement snapshot of the filter's forward pass, captured for the
 * RTS smoother. The filter produces these; the debug tab's ViewModel buffers
 * them and calls [AltitudeKalmanFilter.smoothBackward] to produce a smoothed
 * trajectory that can be compared against the live filtered state.
 *
 * Why all four matrices: the RTS backward pass needs P_{k|k}, P_{k+1|k}, and
 * x_{k|k}, x_{k+1|k}. x_{k+1|k} is the predicted state at step k+1 (after
 * predict, before update), while x_{k|k} is after update. Each record holds
 * both the prior (predicted) and post (filtered) states for the measurement
 * it represents.
 */
data class KfStepRecord(
    val timeMs: Long,
    val xPrior: DoubleArray,
    val PPrior: DoubleArray,
    val xPost: DoubleArray,
    val PPost: DoubleArray,
)

/**
 * One entry of the RTS smoother's output. [sigmaH] is the smoothed standard
 * deviation of h (not of b); the debug tab uses it as the "confidence
 * envelope" for the smoothed trajectory.
 */
data class SmoothedSample(
    val timeMs: Long,
    val h: Float,
    val sigmaH: Float,
    val b: Float,
    val sigmaB: Float,
)
