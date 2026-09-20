package de.perigon.companion.track.domain

import android.util.Log
import de.perigon.companion.track.data.TrackPointEntity
import java.io.InputStream
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import kotlin.math.*

private const val TAG = "GpxTrack"

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
    val factory = SAXParserFactory.newInstance().apply {
        isNamespaceAware = false
        // Files arrive via ACTION_SEND from arbitrary apps — kill DOCTYPE /
        // entity processing (XXE, billion-laughs). Feature support varies by
        // parser, so each is set best-effort; secure-processing is the
        // backstop where the explicit features are unrecognized.
        setFeatureQuietly(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeatureQuietly("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeatureQuietly("http://xml.org/sax/features/external-general-entities", false)
        setFeatureQuietly("http://xml.org/sax/features/external-parameter-entities", false)
    }
    factory.newSAXParser().parse(stream, handler)
    return GpxTrack(
        points = handler.points,
        name = handler.name,
        firstTimestamp = handler.firstTimestamp,
    )
}

private fun SAXParserFactory.setFeatureQuietly(feature: String, value: Boolean) {
    try {
        setFeature(feature, value)
    } catch (e: Exception) {
        // Parser doesn't know this feature — the others still apply. Logged
        // because a silently unset hardening flag is worth noticing if a
        // malformed-GPX report ever comes in.
        Log.d(TAG, "SAX feature unsupported: $feature (${e.javaClass.simpleName})")
    }
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
                val raw = chars.toString().trim()
                try {
                    firstTimestamp = java.time.Instant.parse(raw).toEpochMilli()
                } catch (e: Exception) {
                    // Non-ISO-8601 timestamp: the track still renders, it just
                    // has no date. Logged so "why is my track undated" is
                    // answerable.
                    Log.d(TAG, "unparseable GPX time '$raw' (${e.javaClass.simpleName})")
                }
            }
            inTime = false
        }
    }
}
