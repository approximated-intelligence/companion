package de.perigon.companion.sensors.domain

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign
import kotlin.math.sqrt

enum class KalmanMode {
    GAUSSIAN,
    HUBER,
    VBAKF,
    ISKF,
}

enum class UpdateSource { GPS, BARO }

/**
 * 3-state altitude Kalman filter with four selectable update modes.
 *
 *   state  x = [h, v_z, b]ᵀ
 *     h   = true MSL altitude (m)
 *     v_z = vertical velocity (m/s, positive = up)
 *     b   = barometric bias (m): the difference between what the baro
 *           reads (raw ISA + user offset) and the true altitude. Absorbs
 *           weather, aircon, and any residual calibration error.
 *
 *   process  F = [[1, dt, 0], [0, 1, 0], [0, 0, 1]]
 *            Q = CV block from acceleration noise + bias random walk
 *
 *   measure  GNSS:  z = h          + N(0, σ_gnss²)      H = [1, 0, 0]
 *            baro:  z = h + b      + N(0, σ_baro²)      H = [1, 0, 1]
 *
 * The prediction step is identical for all modes; only the measurement
 * update differs:
 *  - GAUSSIAN: standard Riccati.
 *  - HUBER: Huber M-estimation with effective covariance and Joseph form.
 *  - VBAKF: variational Bayes estimation of the scalar measurement noise.
 *  - ISKF: Iteratively Saturated KF (Yang & Boyd 2025), convex robust MAP
 *    with separate process and measurement outlier terms.
 *
 * [setMode] and [setIskfInverseHessian] mutate the filter in place; state
 * and covariance survive. This is important when the user switches modes
 * mid-recording — the state representation is the same for all modes, only
 * the update rule changes, so there is no reason to reset.
 *
 * Diagnostic accessors ([lastHuberWeight], [lastVbEstimatedR],
 * [lastIskfProcessOutlierNorm], [lastIskfMeasOutlier], [lastUpdateSource])
 * are for the KF debug tab. They are cheap to maintain (a handful of
 * doubles) and harmless to the recording pipeline.
 *
 * Gaps > 30 s reset the filter: either the caller paused or the phone was
 * off, and carrying stale velocity across a gap is worse than restarting.
 *
 * All internal math is Double; public surface is Float to match the rest
 * of the track module.
 */
class AltitudeKalmanFilter(
    initialMode: KalmanMode = KalmanMode.GAUSSIAN,
    initialIskfInverseHessian: Boolean = false,
    /** Vertical acceleration spectral density (m/s² / √Hz). 0.2 handles
     *  cyclist starts and aircraft pitch changes; smaller values smooth
     *  more, larger values track more aggressively. */
    private val accelNoiseMps2: Double = 0.2,
    /** Bias random walk (m / √s). Smaller = smoother bias but slower to
     *  track weather and aircon steps; larger = tracks faster but absorbs
     *  sensor noise into b. */
    private val biasNoiseMps: Double = 0.1,
    private val huberDelta: Double = 1.5,
    private val iskfLambdaX: Double = 1.0,
    private val iskfLambdaY: Double = 1.5,
    private val iskfIterations: Int = 2,
    private val vbakfIterations: Int = 3,
    private val vbakfPriorDof: Double = 4.0,
) {
    private var mode: KalmanMode = initialMode
    private var iskfInverseHessian: Boolean = initialIskfInverseHessian

    private val x = DoubleArray(3)
    private var P = DoubleArray(9)
    private var initialized = false
    private var lastMs = 0L
    private var lastGpsInnovation: Double? = null
    private var lastBaroInnovation: Double? = null

    // ---- Diagnostics (debug tab) ----

    private var lastSource: UpdateSource? = null
    private var lastHuberWeight: Double? = null
    private var lastVbEstimatedR: Double? = null
    private var lastIskfProcessOutlierNorm: Double? = null
    private var lastIskfMeasOutlier: Double? = null

    fun setMode(newMode: KalmanMode) {
        if (mode != newMode) mode = newMode
    }

    fun setIskfInverseHessian(enabled: Boolean) {
        iskfInverseHessian = enabled
    }

    fun isInitialized(): Boolean = initialized

    fun altitude(): Float? = if (initialized) x[0].toFloat() else null
    fun verticalSpeed(): Float? = if (initialized) x[1].toFloat() else null
    fun bias(): Float? = if (initialized) x[2].toFloat() else null

    fun altitudeSigma(): Float? = if (initialized) sqrt(max(P[0], 0.0)).toFloat() else null
    fun velocitySigma(): Float? = if (initialized) sqrt(max(P[4], 0.0)).toFloat() else null
    fun biasSigma(): Float? = if (initialized) sqrt(max(P[8], 0.0)).toFloat() else null

    fun lastGpsInnovationM(): Float? = lastGpsInnovation?.toFloat()
    fun lastBaroInnovationM(): Float? = lastBaroInnovation?.toFloat()

    fun lastUpdateSource(): UpdateSource? = lastSource
    fun lastHuberWeightValue(): Float? = lastHuberWeight?.toFloat()
    fun lastVbEstimatedRValue(): Float? = lastVbEstimatedR?.toFloat()
    fun lastIskfProcessOutlierNormValue(): Float? = lastIskfProcessOutlierNorm?.toFloat()
    fun lastIskfMeasOutlierValue(): Float? = lastIskfMeasOutlier?.toFloat()

    fun reset() {
        initialized = false
        lastMs = 0L
        lastGpsInnovation = null
        lastBaroInnovation = null
        lastSource = null
        lastHuberWeight = null
        lastVbEstimatedR = null
        lastIskfProcessOutlierNorm = null
        lastIskfMeasOutlier = null
    }

    /**
     * Seed / re-seed with a known altitude, typically from the one-shot
     * calibration dialog.
     *
     * Already initialized: snap h to [altM] and b to (baroZ − altM). Keep
     * v and P11 so mid-recording recalibration does not blank out the
     * velocity estimate. P00 and P22 are set tight (1 m², 0.25 m²).
     *
     * Not initialized: seed all three states. v starts at 0, P11 = 1.
     */
    fun resetWithAltitude(altM: Float, baroZ: Float?) {
        if (initialized) {
            x[0] = altM.toDouble()
            if (baroZ != null) x[2] = (baroZ - altM).toDouble()
            P = doubleArrayOf(
                1.0, 0.0, 0.0,
                0.0, P[4], 0.0,
                0.0, 0.0, 0.25,
            )
        } else {
            x[0] = altM.toDouble()
            x[1] = 0.0
            x[2] = if (baroZ != null) (baroZ - altM).toDouble() else 0.0
            P = doubleArrayOf(
                1.0, 0.0, 0.0,
                0.0, 1.0, 0.0,
                0.0, 0.0, 0.25,
            )
        }
        lastGpsInnovation = null
        lastBaroInnovation = null
        lastSource = null
        lastHuberWeight = null
        lastVbEstimatedR = null
        lastIskfProcessOutlierNorm = null
        lastIskfMeasOutlier = null
        initialized = true
        lastMs = System.currentTimeMillis()
    }

    /** GNSS altitude measurement. Seeds the filter on first call. */
    fun updateGps(altM: Float, sigmaM: Float) {
        if (!initialized) { seed(altM.toDouble()); lastSource = UpdateSource.GPS; return }
        predict()
        applyMeasurement(GPS_C, altM.toDouble(), sigmaM.toDouble().let { it * it }, isBaro = false)
    }

    /** Baro altitude measurement. No-op until GNSS has seeded the filter. */
    fun updateBaro(altM: Float, sigmaM: Float) {
        if (!initialized) return
        predict()
        applyMeasurement(BARO_C, altM.toDouble(), sigmaM.toDouble().let { it * it }, isBaro = true)
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

    private fun seed(altM: Double) {
        x[0] = altM
        x[1] = 0.0
        x[2] = 0.0
        P = doubleArrayOf(
            100.0, 0.0, 0.0,
            0.0, 4.0, 0.0,
            0.0, 0.0, 10000.0,
        )
        lastGpsInnovation = null
        lastBaroInnovation = null
        initialized = true
        lastMs = System.currentTimeMillis()
    }

    private fun predict() {
        val now = System.currentTimeMillis()
        val dt = (now - lastMs) / 1000.0
        if (dt <= 0.0) return
        // Gap too long — stale velocity is worse than a fresh start.
        if (dt > 30.0) { reset(); lastMs = now; return }

        x[0] += x[1] * dt

        val n00 = P[0] + dt * P[3] + dt * P[1] + dt * dt * P[4]
        val n01 = P[1] + dt * P[4]
        val n02 = P[2] + dt * P[5]
        val n10 = P[3] + dt * P[4]
        val n11 = P[4]
        val n12 = P[5]
        val n20 = P[6] + dt * P[7]
        val n21 = P[7]
        val n22 = P[8]

        val qa = accelNoiseMps2 * accelNoiseMps2
        val qb = biasNoiseMps * biasNoiseMps
        P[0] = n00 + qa * dt * dt * dt / 3.0
        P[1] = n01 + qa * dt * dt / 2.0
        P[2] = n02
        P[3] = n10 + qa * dt * dt / 2.0
        P[4] = n11 + qa * dt
        P[5] = n12
        P[6] = n20
        P[7] = n21
        P[8] = n22 + qb * dt

        lastMs = now
    }

    private fun applyMeasurement(c: DoubleArray, y: Double, r: Double, isBaro: Boolean) {
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

        val innovation = y - dot(c, x)
        val s = quadForm(c, P) + r
        if (s < 1e-12) return
        val k = matVec(P, c)
        for (i in 0..2) k[i] /= s
        for (i in 0..2) x[i] += k[i] * innovation
        P = joseph(P, k, c, r)
        if (isBaro) lastBaroInnovation = innovation else lastGpsInnovation = innovation
    }

    private fun huber(c: DoubleArray, y: Double, r: Double, isBaro: Boolean) {
        lastVbEstimatedR = null
        lastIskfProcessOutlierNorm = null
        lastIskfMeasOutlier = null

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
        for (i in 0..2) k[i] /= s
        for (i in 0..2) x[i] += k[i] * innovation
        P = joseph(P, k, c, rEff)
        if (isBaro) lastBaroInnovation = innovation else lastGpsInnovation = innovation
    }

    private fun vbakf(c: DoubleArray, y: Double, r: Double, isBaro: Boolean) {
        lastHuberWeight = null
        lastIskfProcessOutlierNorm = null
        lastIskfMeasOutlier = null

        val innovation = y - dot(c, x)
        val a0 = vbakfPriorDof / 2.0
        val b0 = a0 * r

        var rVb = r
        var k = matVec(P, c)
        var s = quadForm(c, P) + rVb
        if (s < 1e-12) return
        for (i in 0..2) k[i] /= s

        repeat(vbakfIterations) {
            val pPost = joseph(P, k, c, rVb)
            val cpc = quadForm(c, pPost)
            val bNew = b0 + 0.5 * (innovation * innovation + cpc)
            val aNew = a0 + 0.5
            rVb = bNew / aNew
            s = quadForm(c, P) + rVb
            k = matVec(P, c)
            for (i in 0..2) k[i] /= s
        }
        lastVbEstimatedR = rVb

        for (i in 0..2) x[i] += k[i] * innovation
        P = joseph(P, k, c, rVb)
        if (isBaro) lastBaroInnovation = innovation else lastGpsInnovation = innovation
    }

    private fun iskf(c: DoubleArray, y: Double, r: Double, isBaro: Boolean) {
        lastHuberWeight = null
        lastVbEstimatedR = null

        val x0 = x.copyOf()
        val l = cholesky(P)
        val s = quadForm(c, P) + r
        if (s < 1e-12) return
        val k = matVec(P, c)
        for (i in 0..2) k[i] /= s

        val ikc = DoubleArray(9)
        for (i in 0..2) for (j in 0..2) {
            ikc[i * 3 + j] = (if (i == j) 1.0 else 0.0) - k[i] * c[j]
        }

        val satY = iskfLambdaY * sqrt(r)
        var xk = x.copyOf()
        var lastSigmaVal = 0.0
        var lastRhoNorm = 0.0
        repeat(iskfIterations) {
            val innov = y - dot(c, xk)
            val sigmaVal = saturate(innov, satY)
            lastSigmaVal = sigmaVal

            val d = DoubleArray(3) { x0[it] - xk[it] }
            val w = solveLower(l, d)
            val nw = norm(w)
            lastRhoNorm = nw
            val rho = if (nw <= iskfLambdaX || nw < 1e-12) d else {
                val scale = iskfLambdaX / nw
                DoubleArray(3) { d[it] * scale }
            }

            val ikcRho = matVec(ikc, rho)
            for (i in 0..2) xk[i] += k[i] * sigmaVal + ikcRho[i]
        }
        x[0] = xk[0]; x[1] = xk[1]; x[2] = xk[2]

        lastIskfProcessOutlierNorm = lastRhoNorm
        lastIskfMeasOutlier = lastSigmaVal

        P = if (iskfInverseHessian) {
            inverseHessian(x0, x, c, y, r, l)
        } else {
            joseph(P, k, c, r)
        }

        val priorInnovation = y - dot(c, x0)
        if (isBaro) lastBaroInnovation = priorInnovation else lastGpsInnovation = priorInnovation
    }

    private fun inverseHessian(
        x0: DoubleArray,
        xStar: DoubleArray,
        c: DoubleArray,
        y: Double,
        r: Double,
        l: DoubleArray,
    ): DoubleArray {
        val sigmaInv = invertSpd(P)
        val d = DoubleArray(3) { xStar[it] - x0[it] }
        val w = solveLower(l, d)
        val nw = norm(w)

        val h0: DoubleArray
        if (nw <= iskfLambdaX || nw < 1e-12) {
            h0 = sigmaInv
        } else {
            val alpha = iskfLambdaX / nw
            val wHat = DoubleArray(3) { w[it] / nw }
            val u = solveUpper(l, wHat)
            h0 = DoubleArray(9) { sigmaInv[it] * alpha }
            for (i in 0..2) for (j in 0..2) h0[i * 3 + j] -= alpha * u[i] * u[j]
        }

        val h1 = DoubleArray(9)
        val innovStar = y - dot(c, xStar)
        if (abs(innovStar) <= iskfLambdaY * sqrt(r)) {
            for (i in 0..2) for (j in 0..2) h1[i * 3 + j] = c[i] * c[j] / r
        }

        val eps = 1e-4
        val h = DoubleArray(9) { h0[it] + h1[it] + if (it % 4 == 0) eps else 0.0 }
        return invertSpd(h)
    }

    private fun saturate(z: Double, threshold: Double): Double =
        if (abs(z) <= threshold) z else sign(z) * threshold

    private fun dot(a: DoubleArray, b: DoubleArray): Double =
        a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    private fun quadForm(c: DoubleArray, p: DoubleArray): Double {
        var s = 0.0
        for (i in 0..2) for (j in 0..2) s += c[i] * p[i * 3 + j] * c[j]
        return s
    }

    private fun matVec(p: DoubleArray, v: DoubleArray): DoubleArray =
        DoubleArray(3) { i -> p[i * 3] * v[0] + p[i * 3 + 1] * v[1] + p[i * 3 + 2] * v[2] }

    private fun matMul(a: DoubleArray, b: DoubleArray): DoubleArray {
        val r = DoubleArray(9)
        for (i in 0..2) for (j in 0..2) {
            var s = 0.0
            for (k in 0..2) s += a[i * 3 + k] * b[k * 3 + j]
            r[i * 3 + j] = s
        }
        return r
    }

    private fun transpose(a: DoubleArray): DoubleArray =
        doubleArrayOf(a[0], a[3], a[6], a[1], a[4], a[7], a[2], a[5], a[8])

    private fun norm(v: DoubleArray): Double =
        sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])

    private fun cholesky(a: DoubleArray): DoubleArray {
        val l = DoubleArray(9)
        for (i in 0..2) {
            for (j in 0..i) {
                var s = a[i * 3 + j]
                for (k in 0 until j) s -= l[i * 3 + k] * l[j * 3 + k]
                if (i == j) l[i * 3 + j] = sqrt(max(s, 1e-12))
                else l[i * 3 + j] = s / l[j * 3 + j]
            }
        }
        return l
    }

    private fun solveLower(l: DoubleArray, b: DoubleArray): DoubleArray {
        val y = DoubleArray(3)
        for (i in 0..2) {
            var s = b[i]
            for (j in 0 until i) s -= l[i * 3 + j] * y[j]
            y[i] = s / l[i * 3 + i]
        }
        return y
    }

    private fun solveUpper(l: DoubleArray, b: DoubleArray): DoubleArray {
        val y = DoubleArray(3)
        for (i in 2 downTo 0) {
            var s = b[i]
            for (j in i + 1..2) s -= l[j * 3 + i] * y[j]
            y[i] = s / l[i * 3 + i]
        }
        return y
    }

    private fun invertSpd(a: DoubleArray): DoubleArray {
        val l = cholesky(a)
        val inv = DoubleArray(9)
        for (col in 0..2) {
            val e = DoubleArray(3).also { it[col] = 1.0 }
            val y = solveLower(l, e)
            val v = solveUpper(l, y)
            for (row in 0..2) inv[row * 3 + col] = v[row]
        }
        return inv
    }

    private fun joseph(p: DoubleArray, k: DoubleArray, c: DoubleArray, r: Double): DoubleArray {
        val a = DoubleArray(9)
        for (i in 0..2) for (j in 0..2) {
            a[i * 3 + j] = (if (i == j) 1.0 else 0.0) - k[i] * c[j]
        }
        val ap = matMul(a, p)
        val apat = matMul(ap, transpose(a))
        val krk = DoubleArray(9) { k[it / 3] * k[it % 3] * r }
        return DoubleArray(9) { apat[it] + krk[it] }
    }

    private companion object {
        val GPS_C = doubleArrayOf(1.0, 0.0, 0.0)
        val BARO_C = doubleArrayOf(1.0, 0.0, 1.0)
    }
}
