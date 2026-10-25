package de.perigon.companion.sensors.nmea

import de.perigon.companion.sensors.gnss.Constellation
import de.perigon.companion.sensors.gnss.FixDimension
import de.perigon.companion.sensors.gnss.FixQuality
import de.perigon.companion.sensors.gnss.Position
import de.perigon.companion.sensors.gnss.UTCDate
import de.perigon.companion.sensors.gnss.UTCTime

/**
 * Hand-rolled NMEA 0183 parser. Pure, stateless, no I/O.
 *
 * Design notes:
 *
 *  - NOT a combinator library. NMEA is a flat, positional protocol — no
 *    recursion, no ambiguity, no context-sensitivity. The grammar is a
 *    `split(',')` plus a bitwise XOR checksum; combinators would add a
 *    dependency without abstracting any real complexity.
 *
 *  - Type dispatch, not field bag. Each sentence type has a dedicated
 *    extractor with the field indices inlined as comments. A GGA
 *    extractor cannot populate a VDOP; the compiler enforces it via the
 *    sealed hierarchy.
 *
 *  - Checksum is a trust boundary. A bad checksum means every byte is
 *    suspect (including the type), so the sentence is returned as
 *    [NmeaSentence.Corrupt] with no fields. The log keeps it; the parsed
 *    snapshot never sees it.
 *
 *  - Empty optional fields are handled once, via `List<String>.valueAt`.
 *    Every extractor reads through it, so a chipset that omits a
 *    trailing field cannot drop the sentence.
 *
 * Talker prefix (GP, GN, GL, GA, GB, BD, …) is two chars and is ignored
 * beyond that length; the design §6.1 says "accept any talker prefix".
 */
object NmeaParser {

    /**
     * Parse [rawLine] as an NMEA sentence. Returns null when the line is
     * not structurally a sentence (`$` prefix, at least one comma beyond
     * the 2-char talker + 3-char type). Any line that reaches the type
     * extractor is returned as some [NmeaSentence] variant — including
     * corrupt sentences, whose [NmeaSentence.type] is the untrusted
     * best-effort extraction the log shows.
     *
     * [id] is assigned by [NmeaStream] and used as a monotonic Compose
     * key. Never inferred here.
     */
    fun parse(rawLine: String, id: Long): NmeaSentence? {
        // Strip ALL carriage returns and newlines to prevent empty lines in the UI.
        val line = rawLine.replace("\r", "").replace("\n", "").trim()
        if (line.isEmpty()) return null
        
        if (!line.startsWith("$")) return null
        val comma = line.indexOf(',')
        if (comma < 6) return null  // room for 2-char talker + 3-char type + comma
        val talkerAndType = line.substring(1, comma)
        if (talkerAndType.length < 5) return null
        val talker = talkerAndType.substring(0, 2)
        val type = talkerAndType.substring(talkerAndType.length - 3)

        // Checksum: '*' followed by two hex digits at the very end.
        // Missing checksum is accepted (rare, older firmware).
        val star = line.lastIndexOf('*')
        if (star >= 0 && star + 3 <= line.length) {
            val expected = line.substring(star + 1, star + 3).toIntOrNull(16)
            if (expected == null || xorChecksum(line, 1, star) != expected) {
                return NmeaSentence.Corrupt(id = id, raw = line, type = type)
            }
        }

        val body = if (star >= 0) line.substring(1, star) else line.substring(1)
        val fields = body.split(',')

        return when (type) {
            "GGA" -> parseGga(fields, line, id)
            "RMC" -> parseRmc(fields, line, id)
            "GSA" -> parseGsa(fields, line, talker, id)
            "GNS" -> parseGns(fields, line, id)
            "VTG" -> parseVtg(fields, line, id)
            "GST" -> parseGst(fields, line, id)
            "GSV" -> parseGsv(fields, line, talker, id)
            "GLL" -> parseGll(fields, line, id)
            // GBS, GRS, TXT, ZDA, and any unrecognised type: type
            // extracted, no fields. Still logged.
            else -> NmeaSentence.Other(id = id, raw = line, type = type)
        }
    }

    // ---- Per-type extractors ----
    // Field indices refer to design §6.1's 1-based numbering. After
    // split(','), index 0 is talker+type and index N is field N.

    private fun parseGga(f: List<String>, raw: String, id: Long): NmeaSentence.Gga =
        NmeaSentence.Gga(
            id = id,
            raw = raw,
            utcTime = parseTime(f.valueAt(1)),
            position = parsePosition(f.valueAt(2), f.valueAt(3), f.valueAt(4), f.valueAt(5)),
            fixQuality = FixQuality.fromCode(f.valueAt(6)?.toIntOrNull()),
            satellitesUsed = f.valueAt(7)?.toIntOrNull(),
            hdop = f.valueAt(8)?.toDoubleOrNull(),
            altitudeMslM = f.valueAt(9)?.toDoubleOrNull(),
            geoidSepM = f.valueAt(11)?.toDoubleOrNull(),
            diffAgeS = f.valueAt(13)?.toDoubleOrNull(),
            diffStationId = f.valueAt(14)?.toIntOrNull(),
        )

    private fun parseRmc(f: List<String>, raw: String, id: Long): NmeaSentence.Rmc {
        // Status 'V' = invalid: the position/speed fields in this sentence
        // are stale. Null them so a merge can't resurrect old data.
        val valid = f.valueAt(2) != "V"
        return NmeaSentence.Rmc(
            id = id,
            raw = raw,
            utcTime = parseTime(f.valueAt(1)),
            utcDate = parseDate(f.valueAt(9)),
            position = if (valid) parsePosition(f.valueAt(3), f.valueAt(4), f.valueAt(5), f.valueAt(6)) else null,
            speedMps = if (valid) f.valueAt(7)?.toDoubleOrNull()?.times(KNOTS_TO_MPS) else null,
            courseDeg = if (valid) f.valueAt(8)?.toDoubleOrNull() else null,
            magVarDeg = if (valid) parseMagVar(f.valueAt(10), f.valueAt(11)) else null,
            modeIndicator = f.valueAt(12),
        )
    }

    private fun parseGsa(f: List<String>, raw: String, talker: String, id: Long): NmeaSentence.Gsa {
        val systemId = f.valueAt(18)?.toIntOrNull()
        val constellation: Constellation? = when {
            talker == "GN" && systemId != null -> Constellation.fromSystemId(systemId)
            else -> Constellation.fromTalker(talker)
        }.takeUnless { it == Constellation.UNKNOWN }
        return NmeaSentence.Gsa(
            id = id,
            raw = raw,
            constellation = constellation,
            fixDimension = FixDimension.fromMode(f.valueAt(2)?.toIntOrNull()),
            pdop = f.valueAt(15)?.toDoubleOrNull(),
            hdop = f.valueAt(16)?.toDoubleOrNull(),
            vdop = f.valueAt(17)?.toDoubleOrNull(),
            activePrns = (3..14).mapNotNull { f.valueAt(it)?.toIntOrNull() },
            systemId = systemId,
        )
    }

    /**
     * GNS is a multi-constellation fallback for GGA. Only UTC and position
     * are taken: GGA's field numbering is stable across revisions, but
     * GNS's HDOP/altitude slots sometimes shift behind diff-age fields
     * that not all chipsets emit. GGA is emitted by every chipset that
     * emits GNS, so its fields are authoritative; GNS is a position+time
     * backup.
     */
    private fun parseGns(f: List<String>, raw: String, id: Long): NmeaSentence.Gns =
        NmeaSentence.Gns(
            id = id,
            raw = raw,
            utcTime = parseTime(f.valueAt(1)),
            position = parsePosition(f.valueAt(2), f.valueAt(3), f.valueAt(4), f.valueAt(5)),
            modeIndicators = f.valueAt(6),
            satellitesUsed = f.valueAt(7)?.toIntOrNull(),
        )

    private fun parseVtg(f: List<String>, raw: String, id: Long): NmeaSentence.Vtg {
        // Speed: prefer knots (f5) when present, else convert km/h (f7).
        val knots = f.valueAt(5)?.toDoubleOrNull()
            ?: f.valueAt(7)?.toDoubleOrNull()?.div(3.6)?.div(KNOTS_TO_MPS)
        return NmeaSentence.Vtg(
            id = id,
            raw = raw,
            courseDeg = f.valueAt(1)?.toDoubleOrNull(),
            speedMps = knots?.times(KNOTS_TO_MPS),
            modeIndicator = f.valueAt(9),
        )
    }

    private fun parseGst(f: List<String>, raw: String, id: Long): NmeaSentence.Gst =
        NmeaSentence.Gst(
            id = id,
            raw = raw,
            rms = f.valueAt(2)?.toDoubleOrNull(),
            semiMajorM = f.valueAt(3)?.toDoubleOrNull(),
            semiMinorM = f.valueAt(4)?.toDoubleOrNull(),
            orientationDeg = f.valueAt(5)?.toDoubleOrNull(),
            latSigmaM = f.valueAt(6)?.toDoubleOrNull(),
            lonSigmaM = f.valueAt(7)?.toDoubleOrNull(),
            altSigmaM = f.valueAt(8)?.toDoubleOrNull(),
        )

    private fun parseGsv(f: List<String>, raw: String, talker: String, id: Long): NmeaSentence.Gsv {
        val sats = mutableListOf<SatelliteInView>()
        var i = 4
        while (i + 3 < f.size) {
            val prn = f.valueAt(i)?.toIntOrNull()
            if (prn != null) {
                sats += SatelliteInView(
                    prn = prn,
                    elevationDeg = f.valueAt(i + 1)?.toIntOrNull(),
                    azimuthDeg = f.valueAt(i + 2)?.toIntOrNull(),
                    snrDbHz = f.valueAt(i + 3)?.toIntOrNull(),
                )
            }
            i += 4
        }
        val expectedSatFields = 3 + sats.size * 4
        val signalId = if (f.size > expectedSatFields + 1) f.lastOrNull()?.toIntOrNull() else null
        return NmeaSentence.Gsv(
            id = id,
            raw = raw,
            constellation = Constellation.fromTalker(talker),
            totalMessages = f.valueAt(1)?.toIntOrNull(),
            messageNumber = f.valueAt(2)?.toIntOrNull(),
            satellitesInView = f.valueAt(3)?.toIntOrNull(),
            satellites = sats,
            signalId = signalId,
        )
    }

    private fun parseGll(f: List<String>, raw: String, id: Long): NmeaSentence.Gll {
        val valid = f.valueAt(6) != "V"
        return NmeaSentence.Gll(
            id = id,
            raw = raw,
            utcTime = parseTime(f.valueAt(5)),
            position = if (valid) parsePosition(f.valueAt(1), f.valueAt(2), f.valueAt(3), f.valueAt(4)) else null,
            modeIndicator = f.valueAt(7),
        )
    }

    // ---- Field helpers ----

    /**
     * Position from NMEA's DDMM.MMMM / DDDMM.MMMM (2-digit or 3-digit
     * degrees) plus a separate hemisphere char. Returns null when either
     * angle is empty or malformed — including the "no fix" encoding where
     * both lat and lon slots are empty strings. The pair-or-null invariant
     * on [Position] is enforced here, not at every call site.
     */
    private fun parsePosition(lat: String?, latHemi: String?, lon: String?, lonHemi: String?): Position? {
        val latV = parseAngle(lat, latHemi, 90.0) ?: return null
        val lonV = parseAngle(lon, lonHemi, 180.0) ?: return null
        return Position(latV, lonV)
    }

    private fun parseAngle(value: String?, hemi: String?, maxDeg: Double): Double? {
        if (value.isNullOrBlank() || hemi.isNullOrBlank()) return null
        val dot = value.indexOf('.')
        if (dot < 3) return null
        val deg = value.substring(0, dot - 2).toDoubleOrNull() ?: return null
        val min = value.substring(dot - 2).toDoubleOrNull() ?: return null
        if (deg > maxDeg || min >= 60.0) return null
        val sign = when (hemi.uppercase()) {
            "N", "E" -> 1.0; "S", "W" -> -1.0; else -> return null
        }
        return sign * (deg + min / 60.0)
    }

    /** HHMMSS(.ss) → [UTCTime]. Fractional seconds truncated. */
    private fun parseTime(value: String?): UTCTime? {
        if (value == null || value.length < 6) return null
        val h = value.substring(0, 2).toIntOrNull() ?: return null
        val m = value.substring(2, 4).toIntOrNull() ?: return null
        val s = value.substring(4, 6).toIntOrNull() ?: return null
        return UTCTime.of(h, m, s)
    }

    /** DDMMYY → [UTCDate], assuming 2000-2099 (GPS era). */
    private fun parseDate(value: String?): UTCDate? {
        if (value == null || value.length != 6) return null
        val d = value.substring(0, 2).toIntOrNull() ?: return null
        val m = value.substring(2, 4).toIntOrNull() ?: return null
        val y = 2000 + (value.substring(4, 6).toIntOrNull() ?: return null)
        return UTCDate.of(y, m, d)
    }

    /** Magnetic variation: magnitude + hemisphere → signed degrees. */
    private fun parseMagVar(value: String?, hemi: String?): Double? {
        val v = value?.toDoubleOrNull() ?: return null
        return when (hemi) { "W" -> -v; "E" -> v; else -> null }
    }

    /** XOR of chars between `$` and `*` (exclusive of both). */
    private fun xorChecksum(line: String, from: Int, toExclusive: Int): Int {
        var x = 0
        for (i in from until toExclusive) x = x xor line[i].code
        return x
    }

    /**
     * Null for empty or out-of-range. Every extractor reads through this
     * so a chipset that omits trailing fields can't drop the sentence.
     * NMEA's "empty optional field" is `""` between commas — this is the
     * one place that gets interpreted as `null`.
     */
    private fun List<String>.valueAt(i: Int): String? =
        if (i in indices && this[i].isNotEmpty()) this[i] else null
}
