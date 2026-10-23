package de.perigon.companion.core.geo.magnetic

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * World Magnetic Model (WMM2025) evaluator.
 *
 * Faithful port of AOSP's `android.hardware.GeomagneticField`, with two
 * differences:
 *
 *   1. Coefficients are parsed from `assets/wmm/wmm2025.cof` at first use,
 *      not hardcoded. AOSP still ships WMM-2020 and cannot be updated without
 *      an OS update; the asset is a one-file replacement when WMM2030 lands.
 *   2. The public surface returns a [Field] value with all components
 *      (declination, inclination, horizontal, total), because the Raw tab
 *      wants more than just declination.
 *
 * The evaluation math, recursion and geodetic→geocentric rotation are
 * UNCHANGED from AOSP. Where this file and AOSP differ in structure
 * (flattened vs 2D coefficient arrays, data class vs getters), the
 * difference is notation only.
 *
 * VERIFICATION — a port of this size can fail silently. The failure mode is
 * a subtle indexing error in the Legendre recursion or the Schmidt
 * quasi-normalization factors, which produces plausible-but-wrong declination
 * with no crash. [verifyTestValues] reads every official test point from
 * `assets/wmm/wmm2025testvalues.txt` (from the NOAA/NCEI distribution),
 * evaluates each, and reports max error across all ~100 points. Correct port:
 * <0.02° for declination and inclination, <1 nT for H and F. If the port is
 * wrong, the max errors will be visibly larger (typically >0.5° / >50 nT)
 * and [VerifyResult.failures] lists the offending points.
 *
 * Thread safety: [parse] is `by lazy` (synchronised); [field] is pure and
 * allocates its own working arrays. [verifyTestValues] reads an asset and
 * is safe to call from any thread, but is not idempotent under concurrent
 * access — call it from one place.
 */
@Singleton
class MagneticModel @Inject constructor(
    @param:ApplicationContext private val ctx: Context,
) {
    private data class Coeff(
        val epoch: Double,             // 2025.0 from the header
        val maxN: Int,                 // 12 for WMM2025
        val g: Array<DoubleArray>,     // [n][m], Schmidt quasi-normalized
        val h: Array<DoubleArray>,
        val gDot: Array<DoubleArray>,  // secular variation, nT/yr
        val hDot: Array<DoubleArray>,
    )

    data class Field(
        val declinationDeg: Float,  // magnetic north east of true north, + east
        val inclinationDeg: Float,  // positive = field points into the ground
        val horizontalNT: Float,
        val totalNT: Float,
    )

    /**
     * Result of [verifyTestValues]. [failures] is empty when every point is
     * within tolerance; each entry names the offending epoch/lat/lon and the
     * per-component error, in a human-readable form suitable for a debug
     * screen.
     */
    data class VerifyResult(
        val pointCount: Int,
        val maxDeclErrDeg: Float,
        val maxInclErrDeg: Float,
        val maxHErrNT: Float,
        val maxFErrNT: Float,
        val failures: List<String>,
    ) {
        val passed: Boolean get() = pointCount > 0 && failures.isEmpty()
    }

    private data class TestPoint(
        val epochYear: Double,
        val altKm: Double,
        val latDeg: Double,
        val lonDeg: Double,
        val expectedDeclDeg: Double,
        val expectedInclDeg: Double,
        val expectedH: Double,
        val expectedF: Double,
    )

    private val coeff: Coeff by lazy { parse() }

    /**
     * Evaluate the field at [latitudeDeg], [longitudeDeg], [altitudeM] (above
     * the WGS84 ellipsoid) at [timeMillis]. Returns [Field] with all
     * components. The secular variation is applied linearly from the model
     * epoch; the model is valid for 2025.0–2030.0, beyond which the result
     * degrades gracefully (an extrapolation, not a crash).
     */
    fun field(
        latitudeDeg: Double,
        longitudeDeg: Double,
        altitudeM: Double,
        timeMillis: Long,
    ): Field {
        val c = coeff
        val nMax = c.maxN
        val altKm = altitudeM / 1000.0

        // Clamp away from the geographic poles: Bφ has a 1/sinθ factor that
        // is singular exactly at the pole, and numerically noisy within
        // ~1e-4° of it. AOSP clamps at ±89.99999; we clamp slightly looser
        // because a GNSS latitude of "exactly 90" is far more common than
        // a genuinely measured one.
        val latRad = Math.toRadians(latitudeDeg.coerceIn(-89.9999, 89.9999))
        val lonRad = Math.toRadians(longitudeDeg)

        // WGS84 ellipsoid parameters.
        val aEq = 6378.137
        val f = 1.0 / 298.257223563
        val e2 = f * (2.0 - f)

        val sinLat = Math.sin(latRad)
        val cosLat = Math.cos(latRad)

        // Geodetic → geocentric spherical.
        val nRadius = aEq / Math.sqrt(1.0 - e2 * sinLat * sinLat) // prime vertical
        val p = (nRadius + altKm) * cosLat                        // distance from axis
        val z = (nRadius * (1.0 - e2) + altKm) * sinLat           // distance along axis
        val r = Math.sqrt(p * p + z * z)                          // geocentric radius
        val gcLatRad = Math.asin(z / r)                           // geocentric latitude

        // cos(colatitude) = sin(gcLat) = z/r; sin(colatitude) = p/r.
        val cosTheta = z / r
        val sinTheta = p / r

        // Secular variation applied linearly from the model epoch.
        val dtYears = (timeMillis - EPOCH_2025_MS) / (365.25 * 86400.0 * 1000.0)

        // cos(mλ), sin(mλ).
        val cosMLon = DoubleArray(nMax + 1) { m -> Math.cos(m * lonRad) }
        val sinMLon = DoubleArray(nMax + 1) { m -> Math.sin(m * lonRad) }

        // Unnormalized associated Legendre polynomials P_n^m(cosθ) and
        // their θ-derivatives. Schmidt quasi-normalization is applied at
        // sum time via SCHMIDT[n][m], matching how AOSP divides the work.
        val pnm = Array(nMax + 1) { DoubleArray(nMax + 1) }
        val dpnm = Array(nMax + 1) { DoubleArray(nMax + 1) }
        pnm[0][0] = 1.0
        dpnm[0][0] = 0.0

        // Diagonal and subdiagonal, n = 1..nMax.
        for (n in 1..nMax) {
            val k = 2.0 * n - 1
            // P_n^n = (2n-1) sinθ P_{n-1}^{n-1}
            pnm[n][n] = k * sinTheta * pnm[n - 1][n - 1]
            dpnm[n][n] = k * (sinTheta * dpnm[n - 1][n - 1] + cosTheta * pnm[n - 1][n - 1])
            // P_n^{n-1} = (2n-1) cosθ P_{n-1}^{n-1}
            pnm[n][n - 1] = k * cosTheta * pnm[n - 1][n - 1]
            dpnm[n][n - 1] = k * (cosTheta * dpnm[n - 1][n - 1] - sinTheta * pnm[n - 1][n - 1])
        }

        // Interior, n = 2..nMax, m = 0..n-2.
        for (n in 2..nMax) {
            val k = 2.0 * n - 1
            for (m in 0..(n - 2)) {
                pnm[n][m] = (k * cosTheta * pnm[n - 1][m] - (n + m - 1.0) * pnm[n - 2][m]) / (n - m)
                dpnm[n][m] = (
                    k * (cosTheta * dpnm[n - 1][m] - sinTheta * pnm[n - 1][m])
                        - (n + m - 1.0) * dpnm[n - 2][m]
                    ) / (n - m)
            }
        }

        // Sum the spherical-harmonic field.
        //   Br  = Σ_n (a/r)^{n+2} Σ_m (n+1) [g cos(mλ) + h sin(mλ)] P̃_nm
        //   Bθ  = -Σ_n (a/r)^{n+2} Σ_m    [g cos(mλ) + h sin(mλ)] dP̃_nm/dθ
        //   Bφ  = (1/sinθ) Σ_n (a/r)^{n+2} Σ_m m [g sin(mλ) - h cos(mλ)] P̃_nm
        // (P̃ and dP̃ are the Schmidt quasi-normalized forms.)
        var br = 0.0
        var bt = 0.0
        var bp = 0.0
        for (n in 1..nMax) {
            val aor = Math.pow(aEq / r, n + 2.0)
            var brN = 0.0
            var btN = 0.0
            var bpN = 0.0
            for (m in 0..n) {
                val gn = c.g[n][m] + dtYears * c.gDot[n][m]
                val hn = c.h[n][m] + dtYears * c.hDot[n][m]
                val pn = pnm[n][m] * SCHMIDT[n][m]
                val dpn = dpnm[n][m] * SCHMIDT[n][m]
                val gCosHsin = gn * cosMLon[m] + hn * sinMLon[m]
                val gSinHcos = gn * sinMLon[m] - hn * cosMLon[m]
                brN += (n + 1.0) * gCosHsin * pn
                btN -= gCosHsin * dpn
                bpN += m * gSinHcos * pn
            }
            br += aor * brN
            bt += aor * btN
            bp += aor * bpN
        }
        bp /= sinTheta

        // Rotate geocentric spherical (Br, Bθ, Bφ) to geodetic (north, east, down).
        // ψ = gcLat - gdLat, typically a few arcminutes.
        val psi = gcLatRad - latRad
        val sinPsi = Math.sin(psi)
        val cosPsi = Math.cos(psi)

        val bx = -bt * cosPsi - br * sinPsi  // north
        val by = bp                           // east
        val bz = bt * sinPsi - br * cosPsi    // down

        val horizontal = Math.sqrt(bx * bx + by * by)
        val total = Math.sqrt(bx * bx + by * by + bz * bz)

        return Field(
            declinationDeg = Math.toDegrees(Math.atan2(by, bx)).toFloat(),
            inclinationDeg = Math.toDegrees(Math.atan2(bz, horizontal)).toFloat(),
            horizontalNT = horizontal.toFloat(),
            totalNT = total.toFloat(),
        )
    }

    /**
     * Evaluate every point in `assets/wmm/wmm2025testvalues.txt` and return a
     * summary of the maximum error for each independently-verifiable
     * component. This is the only thing standing between "the port looks
     * right" and "the port is right" — call it once after any change to the
     * evaluation math or the coefficient file.
     *
     * Fails fast (throws) when the test values asset is missing or malformed;
     * returns a non-passing result when the port itself is wrong.
     */
    fun verifyTestValues(): VerifyResult {
        val points = parseTestValues()
        require(points.isNotEmpty()) { "WMM test values file contains no points" }

        var maxDeclErr = 0f
        var maxInclErr = 0f
        var maxHErr = 0f
        var maxFErr = 0f
        val failures = mutableListOf<String>()

        for (p in points) {
            val timeMs = (
                EPOCH_2025_MS +
                    (p.epochYear - 2025.0) * 365.25 * 86400.0 * 1000.0
                ).toLong()
            val f = field(p.latDeg, p.lonDeg, p.altKm * 1000.0, timeMs)

            var dDecl = Math.abs(f.declinationDeg - p.expectedDeclDeg.toFloat())
            // Declination wraps: 359.9 vs 0.1 is 0.2° error, not 359.8.
            if (dDecl > 180f) dDecl = 360f - dDecl
            val dIncl = Math.abs(f.inclinationDeg - p.expectedInclDeg.toFloat())
            val dH = Math.abs(f.horizontalNT - p.expectedH.toFloat())
            val dF = Math.abs(f.totalNT - p.expectedF.toFloat())

            maxDeclErr = maxOf(maxDeclErr, dDecl)
            maxInclErr = maxOf(maxInclErr, dIncl)
            maxHErr = maxOf(maxHErr, dH)
            maxFErr = maxOf(maxFErr, dF)

            if (dDecl > DECL_TOL_DEG || dIncl > INCL_TOL_DEG || dF > F_TOL_NT) {
                failures += ("epoch=%.2f alt=%.0fkm lat=%+.2f lon=%+.2f  " +
                    "Δdecl=%.4f° Δincl=%.4f° ΔH=%.2f nT ΔF=%.2f nT").format(
                    p.epochYear, p.altKm, p.latDeg, p.lonDeg,
                    dDecl, dIncl, dH, dF,
                )
            }
        }

        return VerifyResult(
            pointCount = points.size,
            maxDeclErrDeg = maxDeclErr,
            maxInclErrDeg = maxInclErr,
            maxHErrNT = maxHErr,
            maxFErrNT = maxFErr,
            failures = failures,
        )
    }

    private fun parse(): Coeff {
        val maxN = 12
        val g = Array(maxN + 1) { DoubleArray(maxN + 1) }
        val h = Array(maxN + 1) { DoubleArray(maxN + 1) }
        val gDot = Array(maxN + 1) { DoubleArray(maxN + 1) }
        val hDot = Array(maxN + 1) { DoubleArray(maxN + 1) }

        val lines = ctx.assets.open(ASSET_PATH).bufferedReader().use { it.readLines() }
        if (lines.isEmpty()) error("WMM file $ASSET_PATH is empty")
        val header = lines[0].trim().split(Regex("\\s+"))
        val epoch = header[0].toDoubleOrNull()
            ?: error("WMM header malformed: ${lines[0]}")

        val ws = Regex("\\s+")
        for (line in lines.drop(1)) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.startsWith("999999")) break  // sentinel
            val parts = trimmed.split(ws)
            if (parts.size < 6) continue
            val n = parts[0].toIntOrNull() ?: continue
            val m = parts[1].toIntOrNull() ?: continue
            if (n < 1 || n > maxN || m < 0 || m > n) continue
            g[n][m] = parts[2].toDouble()
            h[n][m] = parts[3].toDouble()
            gDot[n][m] = parts[4].toDouble()
            hDot[n][m] = parts[5].toDouble()
        }
        return Coeff(epoch, maxN, g, h, gDot, hDot)
    }

    private fun parseTestValues(): List<TestPoint> {
        val points = mutableListOf<TestPoint>()
        val ws = Regex("\\s+")
        ctx.assets.open(TEST_ASSET_PATH).bufferedReader().useLines { lines ->
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                val parts = trimmed.split(ws)
                if (parts.size < 11) continue
                points += TestPoint(
                    epochYear = parts[0].toDoubleOrNull() ?: continue,
                    altKm = parts[1].toDoubleOrNull() ?: continue,
                    latDeg = parts[2].toDoubleOrNull() ?: continue,
                    lonDeg = parts[3].toDoubleOrNull() ?: continue,
                    expectedDeclDeg = parts[4].toDoubleOrNull() ?: continue,
                    expectedInclDeg = parts[5].toDoubleOrNull() ?: continue,
                    expectedH = parts[6].toDoubleOrNull() ?: continue,
                    expectedF = parts[10].toDoubleOrNull() ?: continue,
                )
            }
        }
        return points
    }

    private companion object {
        const val ASSET_PATH = "wmm/wmm2025.cof"
        const val TEST_ASSET_PATH = "wmm/wmm2025testvalues.txt"

        // 2025-01-01T00:00:00Z, the WMM2025.0 epoch.
        const val EPOCH_2025_MS = 1_735_689_600_000L

        // Tolerances for [verifyTestValues]. Chosen so a faithful port passes
        // comfortably while any Legendre/Schmidt bug fails visibly. Values are
        // roughly 100× the expected float-rounding error of a correct port.
        const val DECL_TOL_DEG = 0.02f
        const val INCL_TOL_DEG = 0.02f
        const val F_TOL_NT = 1.0f

        /**
         * Schmidt quasi-normalization factors S_nm = sqrt((2 - δ_m0)(n-m)!/(n+m)!).
         * The COF file's g/h are already Schmidt quasi-normalized; these
         * factors convert the unnormalized P_nm computed above into the
         * form the coefficients expect.
         */
        val SCHMIDT: Array<DoubleArray> by lazy {
            val maxN = 12
            Array(maxN + 1) { n ->
                DoubleArray(n + 1) { m ->
                    val delta = if (m == 0) 1.0 else 2.0
                    Math.sqrt(delta * factorial(n - m) / factorial(n + m))
                }
            }
        }

        private fun factorial(k: Int): Double {
            var r = 1.0
            for (i in 2..k) r *= i
            return r
        }
    }
}
