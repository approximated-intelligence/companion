package de.perigon.companion.sensors.service

import de.perigon.companion.sensors.gnss.Constellation

data class ConstellationCount(val used: Int, val visible: Int)

data class GnssInfo(val constellations: Map<Constellation, ConstellationCount>) {
    companion object {
        val EMPTY = GnssInfo(emptyMap())
        val DISPLAY_ORDER = listOf(
            Constellation.GPS, Constellation.GALILEO, Constellation.GLONASS,
            Constellation.BEIDOU, Constellation.QZSS, Constellation.SBAS, Constellation.NAVIC,
        )
    }

    fun format(): String {
        if (constellations.isEmpty()) return ""
        return DISPLAY_ORDER
            .filter { constellations.containsKey(it) }
            .joinToString(" · ") { c ->
                val count = constellations[c]!!
                "${c.label} ${count.used}/${count.visible}"
            }
    }
}
