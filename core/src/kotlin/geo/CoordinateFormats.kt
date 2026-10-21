package de.perigon.companion.core.geo

import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Coordinate formatters and the format discriminator. All pure: no Context,
 * no I/O.
 *
 * Every numeric rendering uses [Locale.US] regardless of device locale.
 * Coordinates are data, not prose: a German phone that renders
 * "48,12345" makes the lat/lon separator ambiguous and breaks copy-paste
 * into Google Maps (which expects dot decimal).
 */

enum class CoordFormat {
    DD,
    DDM,
    DMS,
    UTM,
    PLUS_CODE,
}

private val NUM = Locale.US

private fun valid(lat: Double, lon: Double): Boolean =
    lat.isFinite() && lon.isFinite() &&
        lat in -90.0..90.0 && lon in -180.0..180.0

// ---- Decimal degrees (DD) ----

/** "48.12345° N, 11.57658° E" — 5 decimals ≈ 1 m at the equator. */
fun formatDd(lat: Double, lon: Double, decimals: Int = 5): String {
    if (!valid(lat, lon)) return "--"
    return "%.${decimals}f° %s, %.${decimals}f° %s".format(
        NUM,
        abs(lat), if (lat >= 0) "N" else "S",
        abs(lon), if (lon >= 0) "E" else "W",
    )
}

// ---- Degrees + decimal minutes (DDM) ----

private fun toDdm(value: Double, positive: String, negative: String): String {
    val hemisphere = if (value >= 0) positive else negative
    val v = abs(value)
    val deg = v.toInt()
    val min = (v - deg) * 60.0
    return "%d° %.3f' %s".format(NUM, deg, min, hemisphere)
}

fun formatDdm(lat: Double, lon: Double): String {
    if (!valid(lat, lon)) return "--"
    return "${toDdm(lat, "N", "S")}, ${toDdm(lon, "E", "W")}"
}

// ---- Degrees + minutes + seconds (DMS) ----

private fun toDms(value: Double, positive: String, negative: String): String {
    val hemisphere = if (value >= 0) positive else negative
    val v = abs(value)
    val deg = v.toInt()
    val tmp = (v - deg) * 60.0
    val min = tmp.toInt()
    val sec = (tmp - min) * 60.0
    return "%d° %d' %.1f\" %s".format(NUM, deg, min, sec, hemisphere)
}

fun formatDms(lat: Double, lon: Double): String {
    if (!valid(lat, lon)) return "--"
    return "${toDms(lat, "N", "S")}, ${toDms(lon, "E", "W")}"
}

// ---- UTM ----

data class Utm(
    val zone: Int,
    val hemisphere: Char,
    val easting: Long,
    val northing: Long,
)

fun latLonToUtm(lat: Double, lon: Double): Utm? {
    if (!valid(lat, lon)) return null
    if (lat < -80.0 || lat > 84.0) return null

    val deg2rad = PI / 180.0
    val a = 6378137.0
    val e2 = 0.00669438
    val k0 = 0.9996

    var lonTemp = lon
    while (lonTemp < -180.0) lonTemp += 360.0
    while (lonTemp >= 180.0) lonTemp -= 360.0

    var zone = ((lonTemp + 180.0) / 6.0).toInt() + 1
    if (lat >= 56.0 && lat < 64.0 && lonTemp >= 3.0 && lonTemp < 12.0) zone = 32
    if (lat >= 72.0 && lat < 84.0) {
        zone = when {
            lonTemp >= 0.0 && lonTemp < 9.0 -> 31
            lonTemp >= 9.0 && lonTemp < 21.0 -> 33
            lonTemp >= 21.0 && lonTemp < 33.0 -> 35
            lonTemp >= 33.0 && lonTemp < 42.0 -> 37
            else -> zone
        }
    }

    val latRad = lat * deg2rad
    val longRad = lonTemp * deg2rad
    val longOriginRad = ((zone - 1) * 6 - 180 + 3) * deg2rad

    val ePrime2 = e2 / (1 - e2)
    val sinLat = sin(latRad)
    val n = a / sqrt(1 - e2 * sinLat * sinLat)
    val t = tan(latRad).let { it * it }
    val c = ePrime2 * cos(latRad).let { it * it }
    val aCoef = cos(latRad) * (longRad - longOriginRad)

    val m = a * (
        (1 - e2 / 4 - 3 * e2 * e2 / 64 - 5 * e2 * e2 * e2 / 256) * latRad
            - (3 * e2 / 8 + 3 * e2 * e2 / 32 + 45 * e2 * e2 * e2 / 1024) * sin(2 * latRad)
            + (15 * e2 * e2 / 256 + 45 * e2 * e2 * e2 / 1024) * sin(4 * latRad)
            - (35 * e2 * e2 * e2 / 3072) * sin(6 * latRad)
    )

    val easting = k0 * n * (
        aCoef
            + (1 - t + c) * aCoef * aCoef * aCoef / 6
            + (5 - 18 * t + t * t + 72 * c - 58 * ePrime2) * aCoef * aCoef * aCoef * aCoef * aCoef / 120
    ) + 500_000.0

    var northing = k0 * (
        m + n * tan(latRad) * (
            aCoef * aCoef / 2
                + (5 - t + 9 * c + 4 * c * c) * aCoef * aCoef * aCoef * aCoef / 24
                + (61 - 58 * t + t * t + 600 * c - 330 * ePrime2) * aCoef * aCoef * aCoef * aCoef * aCoef * aCoef / 720
        )
    )

    val hemisphere = if (lat < 0) 'S' else 'N'
    if (lat < 0) northing += 10_000_000.0

    return Utm(
        zone = zone,
        hemisphere = hemisphere,
        easting = easting.roundToLong(),
        northing = northing.roundToLong(),
    )
}

fun formatUtm(utm: Utm): String =
    "%d%c %dE %dN".format(NUM, utm.zone, utm.hemisphere, utm.easting, utm.northing)

fun formatUtm(lat: Double, lon: Double): String? =
    latLonToUtm(lat, lon)?.let(::formatUtm)

// ---- Plus Code (Open Location Code) ----

/**
 * Open Location Code alphabet. 20 characters, excludes 0/O/I/L/U and
 * A/B/E/S/T/Z so a code is unambiguous when read aloud or copied by hand.
 */
private const val OLC_ALPHABET = "23456789CFGHJMPQRVWX"
private const val OLC_SEPARATOR = '+'

/**
 * Plus Code with 11 characters total (8 base + "+" + 2 refinement chars),
 * giving ≈14 m × 14 m precision at the equator.
 *
 * Format example: "8FVC9G8F+6W" (Zurich).
 *
 * The full spec allows 2-15 characters depending on desired precision;
 * 11 is the smallest length that reaches useful precision without going
 * below what a phone GPS can meaningfully contribute. Longer codes (12+)
 * add a subdivision below 1 m and were deliberately not included.
 *
 * The algorithm walks 5 pairs of (latitude, longitude) characters, each
 * pair dividing the remaining cell by 20. Pair resolutions are:
 *   20°, 1°, 0.05°, 0.0025°, 0.000125°
 * which is the standard OLC sequence. Returns "--" for non-finite or
 * out-of-range input.
 */
fun formatPlusCode(lat: Double, lon: Double): String {
    if (!valid(lat, lon)) return "--"

    // Clip to avoid edge cases exactly on the poles and the antimeridian.
    val latV = lat.coerceIn(-90.0, 90.0 - 1e-10)
    val lonV = lon.coerceIn(-180.0, 180.0 - 1e-10)

    var remLat = latV + 90.0      // 0 at south pole, 180 at north pole.
    var remLon = lonV + 180.0     // 0 at antimeridian west, 360 at east.
    var resolution = 20.0

    val sb = StringBuilder(11)
    for (pair in 0 until 5) {
        if (pair == 4) sb.append(OLC_SEPARATOR)
        val rowIdx = (remLat / resolution).toInt().coerceIn(0, 19)
        val colIdx = (remLon / resolution).toInt().coerceIn(0, 19)
        sb.append(OLC_ALPHABET[rowIdx])
        sb.append(OLC_ALPHABET[colIdx])
        remLat -= rowIdx * resolution
        remLon -= colIdx * resolution
        resolution /= 20.0
    }
    return sb.toString()
}
