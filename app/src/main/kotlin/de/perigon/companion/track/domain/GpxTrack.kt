package de.perigon.companion.track.domain

import de.perigon.companion.track.data.TrackPointEntity
import java.io.InputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import kotlin.math.*

data class LatLon(val lat: Float, val lon: Float)

data class GpxTrack(
    val points: List<LatLon>,
    val name: String = "",
    val firstTimestamp: Long? = null,
) {
    val isEmpty: Boolean get() = points.isEmpty()

    val bounds: Bounds? get() {
        if (points.isEmpty()) return null
        return Bounds(
            minLat = points.minOf { it.lat },
            maxLat = points.maxOf { it.lat },
            minLon = points.minOf { it.lon },
            maxLon = points.maxOf { it.lon },
        )
    }

    val distanceMetres: Float get() =
        points.zipWithNext().sumOf { (a, b) -> haversine(a, b) }.toFloat()

    data class Bounds(val minLat: Float, val maxLat: Float, val minLon: Float, val maxLon: Float)
}

/** Build a [GpxTrack] for rendering — flattens all segments, preserves point order. */
fun List<List<TrackPointEntity>>.toGpxTrack(name: String): GpxTrack {
    val flat = flatten()
    return GpxTrack(
        points = flat.map { LatLon(it.lat, it.lon) },
        name   = name,
        firstTimestamp = flat.firstOrNull()?.time,
    )
}

private fun haversine(a: LatLon, b: LatLon): Double {
    val r    = 6_371_000.0
    val dLat = Math.toRadians((b.lat - a.lat).toDouble())
    val dLon = Math.toRadians((b.lon - a.lon).toDouble())
    val h    = sin(dLat / 2).pow(2) +
               cos(Math.toRadians(a.lat.toDouble())) *
               cos(Math.toRadians(b.lat.toDouble())) *
               sin(dLon / 2).pow(2)
    return 2 * r * atan2(sqrt(h), sqrt(1 - h))
}

fun parseGpx(stream: InputStream): GpxTrack {
    val handler = GpxHandler()
    SAXParserFactory.newInstance().also { it.isNamespaceAware = false }
        .newSAXParser()
        .parse(stream, handler)
    return GpxTrack(
        points = handler.points,
        name = handler.name,
        firstTimestamp = handler.firstTimestamp,
    )
}

private class GpxHandler : DefaultHandler() {
    val points = mutableListOf<LatLon>()
    var name   = ""
    var firstTimestamp: Long? = null

    private var inName    = false
    private var inTime    = false
    private val chars     = StringBuilder()
    private val pointTags = setOf("trkpt", "rtept") // , "wpt")

    override fun startElement(uri: String?, local: String?, qName: String?, attrs: Attributes) {
        val tag = (qName ?: local ?: return).lowercase()
        if (tag in pointTags) {
            val lat = attrs.getValue("lat")?.toFloatOrNull() ?: return
            val lon = attrs.getValue("lon")?.toFloatOrNull() ?: return
            points.add(LatLon(lat, lon))
        }
        if (tag == "name") { inName = true; chars.clear() }
        if (tag == "time") { inTime = true; chars.clear() }
    }

    override fun characters(ch: CharArray?, start: Int, length: Int) {
        if (inName || inTime) chars.append(ch, start, length)
    }

    override fun endElement(uri: String?, local: String?, qName: String?) {
        val tag = (qName ?: local)?.lowercase()
        if (tag == "name" && inName) {
            if (name.isEmpty()) name = chars.toString().trim()
            inName = false
        }
        if (tag == "time" && inTime) {
            if (firstTimestamp == null) {
                try {
                    firstTimestamp = java.time.Instant.parse(chars.toString().trim()).toEpochMilli()
                } catch (_: Exception) { }
            }
            inTime = false
        }
    }
}
