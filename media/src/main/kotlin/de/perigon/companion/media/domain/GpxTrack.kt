package de.perigon.companion.media.domain

import android.util.Log
import de.perigon.companion.core.geo.LatLon
import java.io.InputStream
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import kotlin.math.*

private const val TAG = "GpxTrack"

/**
 * Segment-aware track. [segments] is the source of truth: one list per
 * <trkseg> (or <rte>) in the GPX. [points] is derived and kept for callers
 * that only want to iterate lat/lon.
 *
 * Why segments: a gap in a recorded track is a *real* gap (user paused, the
 * phone was off, the segment was split at midnight). Drawing a single
 * polyline across it implies continuous travel. Distances also must not be
 * summed across a gap — the two endpoints are not adjacent.
 */
data class GpxTrack(
    val segments: List<List<LatLon>>,
    val name: String = "",
    val firstTimestamp: Long? = null,
) {
    val points: List<LatLon> get() = segments.flatten()

    val isEmpty: Boolean get() = segments.all { it.isEmpty() }

    val bounds: Bounds? get() {
        val all = points
        if (all.isEmpty()) return null
        return Bounds(
            minLat = all.minOf { it.lat },
            maxLat = all.maxOf { it.lat },
            minLon = all.minOf { it.lon },
            maxLon = all.maxOf { it.lon },
        )
    }

    /**
     * Total distance, summed *within* segments. Cross-segment pairs are the
     * gap and must not contribute.
     */
    val distanceMetres: Float get() {
        var sum = 0.0
        for (seg in segments) {
            for (i in 1 until seg.size) {
                sum += haversine(seg[i - 1], seg[i])
            }
        }
        return sum.toFloat()
    }

    data class Bounds(val minLat: Float, val maxLat: Float, val minLon: Float, val maxLon: Float)
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

/**
 * Parse a GPX from an untrusted share into a segment-aware [GpxTrack].
 *
 * Segment semantics:
 *  - <trkseg> starts a new segment.
 *  - <rte> starts a new segment (routes have their own point stream).
 *  - <trkpt> / <rtept> inside the current segment appends to it.
 *  - A <trkpt> with no open segment (GPX 1.0 files sometimes omit <trkseg>)
 *    implicitly opens one.
 *
 * DOCTYPE / entity processing is disabled — files arrive via ACTION_SEND
 * from arbitrary apps, so XXE and billion-laughs are live risks.
 */
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
        segments = handler.segments.map { it.toList() },
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
    val segments = mutableListOf<MutableList<LatLon>>()
    var name     = ""
    var firstTimestamp: Long? = null

    private var currentSegment: MutableList<LatLon>? = null
    private var inName    = false
    private var inTime    = false
    private val chars     = StringBuilder()
    private val pointTags = setOf("trkpt", "rtept") // , "wpt")

    override fun startElement(uri: String?, local: String?, qName: String?, attrs: Attributes) {
        val tag = (qName ?: local ?: return).lowercase()
        when (tag) {
            "trkseg", "rte" -> {
                currentSegment = mutableListOf()
                segments.add(currentSegment!!)
            }
            in pointTags -> {
                val lat = attrs.getValue("lat")?.toFloatOrNull() ?: return
                val lon = attrs.getValue("lon")?.toFloatOrNull() ?: return
                val seg = currentSegment ?: mutableListOf<LatLon>().also {
                    currentSegment = it
                    segments.add(it)
                }
                seg.add(LatLon(lat, lon))
            }
            "name" -> { inName = true; chars.clear() }
            "time" -> { inTime = true; chars.clear() }
        }
    }

    override fun characters(ch: CharArray?, start: Int, length: Int) {
        if (inName || inTime) chars.append(ch, start, length)
    }

    override fun endElement(uri: String?, local: String?, qName: String?) {
        val tag = (qName ?: local)?.lowercase()
        when {
            tag == "trkseg" || tag == "rte" -> currentSegment = null
            tag == "name" && inName -> {
                if (name.isEmpty()) name = chars.toString().trim()
                inName = false
            }
            tag == "time" && inTime -> {
                if (firstTimestamp == null) {
                    val raw = chars.toString().trim()
                    try {
                        firstTimestamp = java.time.Instant.parse(raw).toEpochMilli()
                    } catch (e: Exception) {
                        // Non-ISO-8601 timestamp: the track still renders, it
                        // just has no date. Logged so "why is my track undated"
                        // is answerable.
                        Log.d(TAG, "unparseable GPX time '$raw' (${e.javaClass.simpleName})")
                    }
                }
                inTime = false
            }
        }
    }
}
