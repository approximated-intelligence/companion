package de.perigon.companion.sensors.domain

/**
 * 3-state Kalman filter for altitude, fusing GNSS and barometric altimeters.
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
 * Behaviour:
 *   - Baro dominates high-frequency changes (it is sampled ~5 Hz, GNSS at
 *     fix rate). GNSS pins the slow drift of h and b, so baro weather
 *     bias is absorbed within a few tens of seconds.
 *   - After calibration, b starts at 0 with small covariance, so QNH is
 *     available almost immediately. Without calibration, b starts with
 *     huge covariance and converges from GNSS/baro disagreement (slower,
 *     but QNH is still computed correctly once converged — QNH is
 *     invariant to the baro offset).
 *   - Gaps > 30 s reset the filter: either the caller paused or the phone
 *     was off, and carrying stale velocity across a gap is worse than
 *     restarting.
 *   - Not robust against outliers (Huber / innovation clamping comes
 *     later). A single 20 m car-door spike is absorbed partially and the
 *     filter takes ~10 s to return; this is acceptable for now.
 *
 * All internal math is Double; public surface is Float to match the rest
 * of the track module.
 */
class AltitudeKalmanFilter(
    /** Vertical acceleration spectral density (m/s² / √Hz). 0.2 handles
     *  cyclist starts and aircraft pitch changes; smaller values smooth
     *  more, larger values track more aggressively. */
    private val accelNoiseMps2: Double = 0.2,
    /** Bias random walk (m / √s). 0.5 tracks weather (≈8 m/h) and aircon
     *  steps over tens of seconds without absorbing sensor noise into b. */
    private val biasNoiseMps: Double = 0.5,
) {
    private var h = 0.0
    private var v = 0.0
    private var b = 0.0

    // Covariance matrix, upper and lower triangles kept symmetric.
    private var P00 = 0.0; private var P01 = 0.0; private var P02 = 0.0
    private var P10 = 0.0; private var P11 = 0.0; private var P12 = 0.0
    private var P20 = 0.0; private var P21 = 0.0; private var P22 = 0.0

    private var initialized = false
    private var lastMs = 0L

    fun isInitialized(): Boolean = initialized
    fun altitude(): Float? = if (initialized) h.toFloat() else null
    fun verticalSpeed(): Float? = if (initialized) v.toFloat() else null
    fun bias(): Float? = if (initialized) b.toFloat() else null

    fun reset() {
        initialized = false
        lastMs = 0L
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
            h = altM.toDouble()
            b = if (baroZ != null) (baroZ - altM).toDouble() else b
            P00 = 1.0
            P22 = 0.25
            P01 = 0.0; P10 = 0.0
            P02 = 0.0; P20 = 0.0
            P12 = 0.0; P21 = 0.0
        } else {
            h = altM.toDouble()
            v = 0.0
            b = if (baroZ != null) (baroZ - altM).toDouble() else 0.0
            P00 = 1.0
            P11 = 1.0
            P22 = 0.25
            P01 = 0.0; P10 = 0.0
            P02 = 0.0; P20 = 0.0
            P12 = 0.0; P21 = 0.0
        }
        initialized = true
        lastMs = System.currentTimeMillis()
    }

    /** GNSS altitude measurement. Seeds the filter on first call. */
    fun updateGps(altM: Float, sigmaM: Float) {
        if (!initialized) {
            // First fix: h from GNSS, v = 0 (unknown), b = 0 with huge
            // uncertainty (baro will pin it once it disagrees with GNSS).
            h = altM.toDouble()
            v = 0.0
            b = 0.0
            P00 = 100.0   // h uncertain by ±10 m (GNSS raw)
            P11 = 4.0     // v uncertain by ±2 m/s
            P22 = 10000.0 // b totally unknown
            P01 = 0.0; P10 = 0.0
            P02 = 0.0; P20 = 0.0
            P12 = 0.0; P21 = 0.0
            initialized = true
            lastMs = System.currentTimeMillis()
            return
        }
        predict(System.currentTimeMillis())
        applyUpdate(altM.toDouble(), sigmaM.toDouble(), withBias = false)
    }

    /** Baro altitude measurement. No-op until GNSS has seeded the filter. */
    fun updateBaro(altM: Float, sigmaM: Float) {
        if (!initialized) return
        predict(System.currentTimeMillis())
        applyUpdate(altM.toDouble(), sigmaM.toDouble(), withBias = true)
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
        val base = 1.0 - L * h / T0
        if (base < 0.001) return null
        return (pressureHpa / Math.pow(base, n)).toFloat()
    }

    // ---- Internal ----

    private fun predict(nowMs: Long) {
        if (!initialized) { lastMs = nowMs; return }
        val dt = (nowMs - lastMs) / 1000.0
        if (dt <= 0.0) return
        if (dt > 30.0) {
            // Gap too long — stale velocity is worse than a fresh start.
            reset()
            lastMs = nowMs
            return
        }

        // State: h += v·dt (v and b hold).
        h += v * dt

        // P = F P Fᵀ, F = [[1, dt, 0], [0, 1, 0], [0, 0, 1]]
        val n00 = P00 + dt * P10 + dt * P01 + dt * dt * P11
        val n01 = P01 + dt * P11
        val n02 = P02 + dt * P12
        val n10 = P10 + dt * P11
        val n11 = P11
        val n12 = P12
        val n20 = P20 + dt * P21
        val n21 = P21
        val n22 = P22

        // Q: constant-velocity block from acceleration noise, plus bias walk.
        val qa = accelNoiseMps2 * accelNoiseMps2
        val qb = biasNoiseMps * biasNoiseMps
        P00 = n00 + qa * dt * dt * dt / 3.0
        P01 = n01 + qa * dt * dt / 2.0
        P02 = n02
        P10 = n10 + qa * dt * dt / 2.0
        P11 = n11 + qa * dt
        P12 = n12
        P20 = n20
        P21 = n21
        P22 = n22 + qb * dt

        lastMs = nowMs
    }

    private fun applyUpdate(z: Double, sigma: Double, withBias: Boolean) {
        val hb = if (withBias) 1.0 else 0.0
        val r = sigma * sigma

        // Predicted measurement, innovation.
        val hPred = h + b * hb
        val innovation = z - hPred

        // P Hᵀ
        val ph0 = P00 + P02 * hb
        val ph1 = P10 + P12 * hb
        val ph2 = P20 + P22 * hb

        // S = H P Hᵀ + R
        val s = ph0 + hb * ph2 + r
        if (s < 1e-12) return

        // K = P Hᵀ / S
        val k0 = ph0 / s
        val k1 = ph1 / s
        val k2 = ph2 / s

        // x += K · innovation
        h += k0 * innovation
        v += k1 * innovation
        b += k2 * innovation

        // P = P − K · (H P), and (H P)_j = (P Hᵀ)_j by symmetry.
        P00 -= k0 * ph0; P01 -= k0 * ph1; P02 -= k0 * ph2
        P10 -= k1 * ph0; P11 -= k1 * ph1; P12 -= k1 * ph2
        P20 -= k2 * ph0; P21 -= k2 * ph1; P22 -= k2 * ph2

        // Enforce symmetry (numerical drift only).
        P01 = (P01 + P10) / 2.0; P10 = P01
        P02 = (P02 + P20) / 2.0; P20 = P02
        P12 = (P12 + P21) / 2.0; P21 = P12
    }
}
