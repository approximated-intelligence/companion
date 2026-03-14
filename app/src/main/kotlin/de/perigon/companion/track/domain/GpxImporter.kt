package de.perigon.companion.track.domain

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.time.Instant

data class ImportedTrack(
    val name: String,
    val segments: List<ImportedSegment>,
)

data class ImportedSegment(
    val points: List<ImportedPoint>,
)

data class ImportedPoint(
    val lat: Float,
    val lon: Float,
    val ele: Float? = null,
    val time: Long? = null,
    val accuracyM: Float? = null,
)

object GpxImporter {

    fun import(input: InputStream): ImportedTrack {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(input, "UTF-8")

        var trackName = ""
        val segments = mutableListOf<ImportedSegment>()
        var currentPoints = mutableListOf<ImportedPoint>()
        var inTrkSeg = false
        var inTrkPt = false
        var inExtensions = false

        var ptLat = 0.0f
        var ptLon = 0.0f
        var ptEle: Float? = null
        var ptTime: Long? = null
        var ptAccuracy: Float? = null
        var currentTag = ""

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name
                    when (parser.name) {
                        "trkseg" -> {
                            inTrkSeg = true
                            currentPoints = mutableListOf()
                        }
                        "trkpt" -> {
                            inTrkPt = true
                            ptLat = parser.getAttributeValue(null, "lat").toFloat()
                            ptLon = parser.getAttributeValue(null, "lon").toFloat()
                            ptEle = null
                            ptTime = null
                            ptAccuracy = null
                        }
                        "extensions" -> inExtensions = true
                    }
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim() ?: ""
                    if (text.isNotEmpty() && inTrkPt) {
                        when (currentTag) {
                            "ele" -> ptEle = text.toFloatOrNull()
                            "time" -> ptTime = parseTime(text)
                            "accuracy" -> if (inExtensions) ptAccuracy = text.toFloatOrNull()
                        }
                    }
                    if (text.isNotEmpty() && currentTag == "name" && !inTrkPt) {
                        trackName = text
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "trkpt" -> {
                            currentPoints.add(ImportedPoint(ptLat, ptLon, ptEle, ptTime, ptAccuracy))
                            inTrkPt = false
                        }
                        "trkseg" -> {
                            if (currentPoints.isNotEmpty()) {
                                segments.add(ImportedSegment(currentPoints))
                            }
                            inTrkSeg = false
                        }
                        "extensions" -> inExtensions = false
                    }
                    currentTag = ""
                }
            }
            event = parser.next()
        }

        return ImportedTrack(
            name = trackName.ifEmpty { "Imported track" },
            segments = segments,
        )
    }

    private fun parseTime(text: String): Long? = try {
        Instant.parse(text).toEpochMilli()
    } catch (_: Exception) {
        null
    }
}
