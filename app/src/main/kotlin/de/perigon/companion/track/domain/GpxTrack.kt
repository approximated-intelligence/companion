package de.perigon.companion.track.domain

import de.perigon.companion.track.data.TrackWithSegments
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import java.io.InputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

data class LatLon(val lat: Float, val lon: Float)

data class GpxTrack(
    val points: List<LatLon>,
    val name: String = "",
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

    /** Total distance in metres using Haversine. */
    val distanceMetres: Float get() = points.zipWithNext().sumOf { (a, b) -> haversine(a, b) }.toFloat()

    data class Bounds(val minLat: Float, val maxLat: Float, val minLon: Float, val maxLon: Float)
}

fun TrackWithSegments.toGpxTrack(): GpxTrack = GpxTrack(
    points = segments
        .flatMap { it.points }
        .sortedBy { it.time }
        .map { LatLon(it.lat, it.lon) },
    name = track.name,
)


private fun haversine(a: LatLon, b: LatLon): Double {
    val r = 6_371_000.0

    val dLat = Math.toRadians((b.lat - a.lat).toDouble())
    val dLon = Math.toRadians((b.lon - a.lon).toDouble())

    val h = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(a.lat.toDouble())) *
            cos(Math.toRadians(b.lat.toDouble())) *
            sin(dLon / 2).pow(2)

    return (2 * r * atan2(sqrt(h), sqrt(1 - h)))
}
// private fun haversine(a: LatLon, b: LatLon): Float {
//     val r    = 6_371_000.0
//     val dLat = Math.toRadians(b.lat - a.lat)
//     val dLon = Math.toRadians(b.lon - a.lon)
//     val h    = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
//     return 2 * r * atan2(sqrt(h), sqrt(1 - h))
// }

// --- Merged from GpxParser.kt ---

/**
 * Parse a GPX input stream into a GpxTrack.
 * Pure function - no I/O beyond reading the stream.
 * Handles both <trkpt> (tracks) and <rtept> (routes) and <wpt> (waypoints).
 */
fun parseGpx(stream: InputStream): GpxTrack {
    val handler = GpxHandler()
    SAXParserFactory.newInstance().also { it.isNamespaceAware = false }
        .newSAXParser()
        .parse(stream, handler)
    return GpxTrack(points = handler.points, name = handler.name)
}

private class GpxHandler : DefaultHandler() {
    val points = mutableListOf<LatLon>()
    var name   = ""

    private var inName    = false
    private val chars     = StringBuilder()
    private val pointTags = setOf("trkpt", "rtept", "wpt")

    override fun startElement(uri: String?, local: String?, qName: String?, attrs: Attributes) {
        val tag = (qName ?: local ?: return).lowercase()
        if (tag in pointTags) {
            val lat = attrs.getValue("lat")?.toFloatOrNull() ?: return
            val lon = attrs.getValue("lon")?.toFloatOrNull() ?: return
            points.add(LatLon(lat, lon))
        }
        if (tag == "name") { inName = true; chars.clear() }
    }

    override fun characters(ch: CharArray?, start: Int, length: Int) {
        if (inName) chars.append(ch, start, length)
    }

    override fun endElement(uri: String?, local: String?, qName: String?) {
        if ((qName ?: local)?.lowercase() == "name" && inName) {
            if (name.isEmpty()) name = chars.toString().trim()
            inName = false
        }
    }
}

