package de.perigon.companion.sensors.nmea

import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.sqrt

/**
 * One NMEA 0183 sentence as received from the LocationManager's NMEA
 * listener, sealed by parse outcome.
 *
 * The hierarchy encodes three facts at once:
 *
 *  1. Checksum valid?       [Corrupt] = no; all other variants = yes.
 *  2. Type field extracted? [Corrupt] and [Other] both carry it, so yes,
 *                           always — except we never emit a sentence whose
 *                           type couldn't be isolated (parser returns null).
 *  3. Fields extracted?     Only the six typed variants; nothing else.
 *
 * Consequence: "a GGA carrying a VDOP", "an RMC with lat but no lon",
 * "parsed fields despite a bad checksum" are not representable. The
 * compiler enforces what the protocol cannot express.
 *
 * Why not `Unknown`/`Known` variants: every valid-checksum sentence
 * either matches a typed variant (fields we care about) or falls into
 * [Other] (type extracted, fields discarded — GSV, GLL, GBS, GRS, ZDA,
 * TXT, …). The chip-row membership check is done by the UI against the
 * fixed 8-type set; the parser doesn't need to know which types have chips.
 *
 * [raw] and [wallMs] are carried on every variant because the raw log
 * needs them; [type] is non-null because the parser refuses to emit a
 * sentence whose type it can't isolate.
 */
sealed interface NmeaSentence {
    val raw: String
    val wallMs: Long
    val type: String

    /**
     * Checksum mismatch. [type] is a best-effort extraction from the raw
     * bytes and may itself be corrupt — the log preserves it so the user
     * can see *what* arrived, not because it is trusted.
     */
    data class Corrupt(
        override val raw: String,
        override val wallMs: Long,
        override val type: String,
    ) : NmeaSentence

    /**
     * Valid checksum, type extracted, fields not represented. Covers the
     * seven-type vocabulary's GSV (arrival matters for the chip, fields
     * don't) and every other sentence type the app doesn't parse (GLL,
     * GBS, GRS, TXT, ZDA, …).
     */
    data class Other(
        override val raw: String,
        override val wallMs: Long,
        override val type: String,
    ) : NmeaSentence

    /** GGA — fix data. Field order per design §6.1 (f1 = UTC, f9 = MSL alt). */
    data class Gga(
        override val raw: String,
        override val wallMs: Long,
        val utc: LocalTime?,
        val position: Position?,
        val fixQuality: Int?,
        val satellitesUsed: Int?,
        val hdop: Double?,
        val altitudeMslM: Double?,
        val geoidSepM: Double?,
    ) : NmeaSentence {
        override val type: String get() = "GGA"
    }

    /**
     * RMC — minimum navigation. A status of "V" means the position fields
     * are stale; they are nulled on parse so a merge cannot resurrect them.
     */
    data class Rmc(
        override val raw: String,
        override val wallMs: Long,
        val utc: LocalTime?,
        val date: LocalDate?,
        val position: Position?,
        val speedKnots: Double?,
        val courseDeg: Double?,
        val magVarDeg: Double?,
    ) : NmeaSentence {
        override val type: String get() = "RMC"
    }

    /** GSA — DOP + 2D/3D indicator (mode: 1 = no fix, 2 = 2D, 3 = 3D). */
    data class Gsa(
        override val raw: String,
        override val wallMs: Long,
        val fixDimension: Int?,
        val pdop: Double?,
        val hdop: Double?,
        val vdop: Double?,
    ) : NmeaSentence {
        override val type: String get() = "GSA"
    }

    /** GNS — multi-constellation fallback for position + UTC. */
    data class Gns(
        override val raw: String,
        override val wallMs: Long,
        val utc: LocalTime?,
        val position: Position?,
        val fixDimension: Int?,
        val satellitesUsed: Int?,
    ) : NmeaSentence {
        override val type: String get() = "GNS"
    }

    /** VTG — course/speed. Speed conversion km/h→knots handled in parser. */
    data class Vtg(
        override val raw: String,
        override val wallMs: Long,
        val courseDeg: Double?,
        val speedKnots: Double?,
    ) : NmeaSentence {
        override val type: String get() = "VTG"
    }

    /**
     * GST — statistical error estimates from solution residuals. Preferred
     * over `Location.getAccuracy()` in NMEA mode (design §4).
     */
    data class Gst(
        override val raw: String,
        override val wallMs: Long,
        val rms: Double?,
        val semiMajorM: Double?,
        val semiMinorM: Double?,
        val orientationDeg: Double?,
        val latSigmaM: Double?,
        val lonSigmaM: Double?,
        val altSigmaM: Double?,
    ) : NmeaSentence {
        override val type: String get() = "GST"

        /**
         * Horizontal position σ. Prefers the RSS of GST f6/f7 (lat, lon
         * — the axis-aligned scatter the recorder actually experiences)
         * over the ellipse major axis (the worst-case direction).
         * Falls back to f2 (RMS of position error) when only RMS is
         * present — some chipsets emit f2 alone.
         */
        val horizontalSigmaM: Double?
            get() = when {
                latSigmaM != null && lonSigmaM != null ->
                    sqrt(latSigmaM * latSigmaM + lonSigmaM * lonSigmaM)
                rms != null -> rms
                else -> null
            }
    }
}

/**
 * WGS84 coordinate pair. Both-or-neither: a "half position" is not a
 * physical state, and NMEA encodes the no-fix case with empty lat/lon
 * fields in both slots. Represented here as `null` at the call site, not
 * as a Position with sentinels.
 */
data class Position(val lat: Double, val lon: Double)
