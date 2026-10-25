package de.perigon.companion.sensors.gnss

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

@JvmInline
value class UTCTime private constructor(val value: LocalTime) {
    companion object {
        fun of(hour: Int, minute: Int, second: Int): UTCTime? {
            if (hour !in 0..23 || minute !in 0..59 || second !in 0..60) return null
            return UTCTime(LocalTime.of(hour, minute, second.coerceAtMost(59)))
        }
        fun fromLocal(t: LocalTime): UTCTime = UTCTime(t)
    }
}

@JvmInline
value class UTCDate private constructor(val value: LocalDate) {
    companion object {
        fun of(year: Int, month: Int, day: Int): UTCDate? = try {
            UTCDate(LocalDate.of(year, month, day))
        } catch (_: java.time.DateTimeException) {
            null
        }
        fun fromLocalDate(date: LocalDate): UTCDate = UTCDate(date)
    }
}

data class UTCDateTime(val date: UTCDate, val time: UTCTime) {
    fun toInstant(): Instant = date.value.atTime(time.value).toInstant(ZoneOffset.UTC)
    companion object {
        fun fromInstant(instant: Instant): UTCDateTime {
            val z = instant.atZone(ZoneOffset.UTC)
            return UTCDateTime(
                date = UTCDate.of(z.year, z.monthValue, z.dayOfMonth)!!,
                time = UTCTime.fromLocal(z.toLocalTime()),
            )
        }
        fun fromEpochMillis(ms: Long): UTCDateTime = fromInstant(Instant.ofEpochMilli(ms))
    }
}

enum class Constellation(val talkerPrefix: String, val label: String) {
    GPS("GP", "GPS"),
    GLONASS("GL", "GLONASS"),
    GALILEO("GA", "Galileo"),
    BEIDOU("GB", "BeiDou"),
    QZSS("GQ", "QZSS"),
    NAVIC("GI", "NavIC"),
    SBAS("", "SBAS"),
    UNKNOWN("", "Unknown"),
    ;

    companion object {
        fun fromTalker(talker: String): Constellation = when (talker) {
            "GP" -> GPS
            "GL" -> GLONASS
            "GA" -> GALILEO
            "GB", "BD" -> BEIDOU
            "GQ" -> QZSS
            "GI" -> NAVIC
            else -> UNKNOWN
        }

        fun fromSystemId(id: Int?): Constellation = when (id) {
            1 -> GPS
            2 -> GLONASS
            3 -> GALILEO
            4 -> BEIDOU
            5 -> QZSS
            6 -> NAVIC
            else -> UNKNOWN
        }

        fun fromAndroidConstellation(type: Int): Constellation = when (type) {
            android.location.GnssStatus.CONSTELLATION_GPS -> GPS
            android.location.GnssStatus.CONSTELLATION_GLONASS -> GLONASS
            android.location.GnssStatus.CONSTELLATION_GALILEO -> GALILEO
            android.location.GnssStatus.CONSTELLATION_BEIDOU -> BEIDOU
            android.location.GnssStatus.CONSTELLATION_QZSS -> QZSS
            android.location.GnssStatus.CONSTELLATION_IRNSS -> NAVIC
            android.location.GnssStatus.CONSTELLATION_SBAS -> SBAS
            else -> UNKNOWN
        }
    }
}

enum class FixQuality(val code: Int, val label: String) {
    NONE(0, "None"),
    GPS(1, "GPS"),
    DGPS(2, "DGPS"),
    PPS(3, "PPS"),
    RTK_FIXED(4, "RTK fixed"),
    RTK_FLOAT(5, "RTK float"),
    ESTIMATED(6, "Estimated"),
    MANUAL(7, "Manual"),
    SIMULATION(8, "Simulation"),
    WAAS(9, "WAAS/SBAS"),
    ;

    companion object {
        fun fromCode(code: Int?): FixQuality? = entries.firstOrNull { it.code == code }
    }
}

enum class FixDimension(val mode: Int, val label: String) {
    NO_FIX(1, "no fix"),
    TWO_D(2, "2D"),
    THREE_D(3, "3D"),
    ;

    companion object {
        fun fromMode(mode: Int?): FixDimension? = entries.firstOrNull { it.mode == mode }
    }
}

data class Position(val lat: Double, val lon: Double)

data class SatelliteObservation(
    val prn: Int,
    val constellation: Constellation,
    val signalId: Int?,
    val elevationDeg: Int?,
    val azimuthDeg: Int?,
    val snrDbHz: Int?,
    val usedInFix: Boolean,
) {
    val isSbas: Boolean get() = constellation == Constellation.SBAS
}

fun isSbasPrn(prn: Int): Boolean = prn in 33..64

fun sbasPrnFromNmeaId(prn: Int): Int = prn + 87
