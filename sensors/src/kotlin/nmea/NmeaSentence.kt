package de.perigon.companion.sensors.nmea

import de.perigon.companion.sensors.gnss.Constellation
import de.perigon.companion.sensors.gnss.FixDimension
import de.perigon.companion.sensors.gnss.FixQuality
import de.perigon.companion.sensors.gnss.Position
import de.perigon.companion.sensors.gnss.UTCDate
import de.perigon.companion.sensors.gnss.UTCDateTime
import de.perigon.companion.sensors.gnss.UTCTime
import de.perigon.companion.sensors.gnss.isSbasPrn
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
 * [id], [raw] and [type] are carried on every variant: [id] is the
 * monotonic Compose key from [NmeaStream], [raw] is what the log displays,
 * [type] is non-null because the parser refuses to emit a sentence whose
 * type it can't isolate.
 */
sealed interface NmeaSentence {
    val id: Long
    val raw: String
    val type: String

    /**
     * Checksum mismatch. [type] is a best-effort extraction from the raw
     * bytes and may itself be corrupt — the log preserves it so the user
     * can see *what* arrived, not because it is trusted.
     */
    data class Corrupt(
        override val id: Long,
        override val raw: String,
        override val type: String,
    ) : NmeaSentence

    /**
     * Valid checksum, type extracted, fields not represented. Covers the
     * seven-type vocabulary's GSV (arrival matters for the chip, fields
     * don't) and every other sentence type the app doesn't parse (GLL,
     * GBS, GRS, TXT, ZDA, …).
     */
    data class Other(
        override val id: Long,
        override val raw: String,
        override val type: String,
    ) : NmeaSentence

    /** GGA — fix data. Field order per design §6.1 (f1 = UTC, f9 = MSL alt). */
    data class Gga(
        override val id: Long,
        override val raw: String,
        val utcTime: UTCTime?,
        val position: Position?,
        val fixQuality: FixQuality?,
        val satellitesUsed: Int?,
        val hdop: Double?,
        val altitudeMslM: Double?,
        val geoidSepM: Double?,
        val diffAgeS: Double?,
        val diffStationId: Int?,
    ) : NmeaSentence {
        override val type: String get() = "GGA"
        val altitudeWgs84M: Double?
            get() = if (altitudeMslM != null && geoidSepM != null) altitudeMslM + geoidSepM else null
    }

    /**
     * RMC — minimum navigation. A status of "V" means the position fields
     * are stale; they are nulled on parse so a merge cannot resurrect them.
     */
    data class Rmc(
        override val id: Long,
        override val raw: String,
        val utcTime: UTCTime?,
        val utcDate: UTCDate?,
        val position: Position?,
        val speedMps: Double?,
        val courseDeg: Double?,
        val magVarDeg: Double?,
        val modeIndicator: String?,
    ) : NmeaSentence {
        override val type: String get() = "RMC"
        val timestamp: UTCDateTime?
            get() = if (utcTime != null && utcDate != null) UTCDateTime(utcDate, utcTime) else null
    }

    /** GSA — DOP + 2D/3D indicator (mode: 1 = no fix, 2 = 2D, 3 = 3D). */
    data class Gsa(
        override val id: Long,
        override val raw: String,
        val constellation: Constellation?,
        val fixDimension: FixDimension?,
        val pdop: Double?,
        val hdop: Double?,
        val vdop: Double?,
        val activePrns: List<Int>,
        val systemId: Int?,
    ) : NmeaSentence {
        override val type: String get() = "GSA"
    }

    /** GNS — multi-constellation fallback for position + UTC. */
    data class Gns(
        override val id: Long,
        override val raw: String,
        val utcTime: UTCTime?,
        val position: Position?,
        val modeIndicators: String?,
        val satellitesUsed: Int?,
    ) : NmeaSentence {
        override val type: String get() = "GNS"
    }

    /** VTG — course/speed. Speed conversion knots→m/s handled in parser. */
    data class Vtg(
        override val id: Long,
        override val raw: String,
        val courseDeg: Double?,
        val speedMps: Double?,
        val modeIndicator: String?,
    ) : NmeaSentence {
        override val type: String get() = "VTG"
    }

    /**
     * GST — statistical error estimates from solution residuals. Preferred
     * over `Location.getAccuracy()` in NMEA mode (design §4).
     */
    data class Gst(
        override val id: Long,
        override val raw: String,
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
                latSigmaM != null && lonSigmaM != null -> sqrt(latSigmaM * latSigmaM + lonSigmaM * lonSigmaM)
                rms != null -> rms
                else -> null
            }
    }

    data class Gsv(
        override val id: Long,
        override val raw: String,
        val constellation: Constellation,
        val totalMessages: Int?,
        val messageNumber: Int?,
        val satellitesInView: Int?,
        val satellites: List<SatelliteInView>,
        val signalId: Int?,
    ) : NmeaSentence {
        override val type: String get() = "GSV"
        val signalLabel: String? get() = signalId?.let { signalLabel(constellation, it) }
    }

    data class Gll(
        override val id: Long,
        override val raw: String,
        val utcTime: UTCTime?,
        val position: Position?,
        val modeIndicator: String?,
    ) : NmeaSentence {
        override val type: String get() = "GLL"
    }
}

data class SatelliteInView(
    val prn: Int,
    val elevationDeg: Int?,
    val azimuthDeg: Int?,
    val snrDbHz: Int?,
) {
    fun constellation(talker: Constellation): Constellation =
        if (talker == Constellation.GPS && isSbasPrn(prn)) Constellation.SBAS else talker
}

fun signalLabel(constellation: Constellation, id: Int): String = when (constellation) {
    Constellation.GPS -> when (id) { 1 -> "L1 C/A"; 5 -> "L2C"; 8 -> "L5-Q"; else -> "GPS signal $id" }
    Constellation.GLONASS -> when (id) { 1 -> "G1 C/A"; 3 -> "G2 C/A"; else -> "GLONASS signal $id" }
    Constellation.GALILEO -> when (id) { 1 -> "E5a"; 2 -> "E5b"; 6 -> "E1"; 7 -> "E5 AltBOC"; else -> "Galileo signal $id" }
    Constellation.BEIDOU -> when (id) { 1 -> "B1I"; 5 -> "B2a"; 11 -> "B2I"; else -> "BeiDou signal $id" }
    Constellation.QZSS -> when (id) { 1 -> "L1 C/A"; 5 -> "L2C"; 8 -> "L5-Q"; else -> "QZSS signal $id" }
    Constellation.NAVIC -> when (id) { 1 -> "L5"; else -> "NavIC signal $id" }
    Constellation.SBAS -> "SBAS"
    Constellation.UNKNOWN -> "signal $id"
}

fun modeIndicatorLabel(indicator: String?): String = when (indicator) {
    "A" -> "Autonomous"
    "D" -> "Differential"
    "E" -> "Estimated"
    "M" -> "Manual"
    "N" -> "No fix"
    "P" -> "Precise"
    "R" -> "RTK fixed"
    "F" -> "RTK float"
    null -> "--"
    else -> indicator
}

internal const val KNOTS_TO_MPS: Double = 0.514444
