package de.perigon.companion.track.service

import android.location.GnssStatus

data class ConstellationCount(
    val used: Int,
    val visible: Int,
)

data class GnssInfo(
    val constellations: Map<Int, ConstellationCount>,
) {
    companion object {
        val EMPTY = GnssInfo(emptyMap())

        fun constellationLabel(type: Int): String = when (type) {
            GnssStatus.CONSTELLATION_GPS -> "GPS"
            GnssStatus.CONSTELLATION_GLONASS -> "GLO"
            GnssStatus.CONSTELLATION_GALILEO -> "GAL"
            GnssStatus.CONSTELLATION_BEIDOU -> "BDS"
            GnssStatus.CONSTELLATION_QZSS -> "QZS"
            GnssStatus.CONSTELLATION_SBAS -> "SBAS"
            GnssStatus.CONSTELLATION_IRNSS -> "IRNSS"
            else -> "?"
        }

        val DISPLAY_ORDER = listOf(
            GnssStatus.CONSTELLATION_GPS,
            GnssStatus.CONSTELLATION_GALILEO,
            GnssStatus.CONSTELLATION_GLONASS,
            GnssStatus.CONSTELLATION_BEIDOU,
            GnssStatus.CONSTELLATION_QZSS,
            GnssStatus.CONSTELLATION_SBAS,
            GnssStatus.CONSTELLATION_IRNSS,
        )
    }

    fun format(): String {
        if (constellations.isEmpty()) return ""
        return DISPLAY_ORDER
            .filter { constellations.containsKey(it) }
            .joinToString(" · ") { type ->
                val c = constellations[type]!!
                "${constellationLabel(type)} ${c.used}/${c.visible}"
            }
    }
}
