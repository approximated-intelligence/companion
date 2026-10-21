package de.perigon.companion.sensors.domain

import de.perigon.companion.sensors.data.TrackPointEntity
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

object GpxExporter {

    private val ISO = DateTimeFormatter.ISO_DATE_TIME.withZone(ZoneOffset.UTC)
    private const val NS_OSMAND = "https://osmand.net"

    /**
     * 7 decimal places ≈ 1.1 cm at the equator. lat/lon are Double since DB
     * v3, so this captures the full source precision from Location; the
     * %.7f format also matches OsmAnd / Locus / GPXSee so external tools
     * read the same digits back. ele/undulation are Float in the entity, so
     * their formatters stay at their natural precision.
     */
    private const val COORD_FMT = "%.7f"

    /**
     * Export segments with their points to GPX.
     * Segments and points are already in insertion order from
     * [de.perigon.companion.sensors.data.TrackRepository.getSegmentsWithPoints].
     */
    fun export(trackName: String, segments: List<List<TrackPointEntity>>, output: OutputStream) {
        OutputStreamWriter(output, Charsets.UTF_8).use { w ->
            w.write("""<?xml version="1.0" encoding="UTF-8"?>""")
            w.write("\n")
            w.write("""<gpx version="1.1" creator="PerigonCompanion"""")
            w.write(""" xmlns="http://www.topografix.com/GPX/1/1"""")
            w.write(""" xmlns:osmand="$NS_OSMAND">""")
            w.write("\n")
            w.write("  <trk>\n")
            w.write("    <name>${escapeXml(trackName)}</name>\n")
            for (seg in segments) {
                if (seg.isEmpty()) continue
                w.write("    <trkseg>\n")
                for (pt in seg) writePoint(w, pt)
                w.write("    </trkseg>\n")
            }
            w.write("  </trk>\n")
            w.write("</gpx>\n")
        }
    }

    private fun writePoint(w: OutputStreamWriter, pt: TrackPointEntity) {
        val lat = String.format(Locale.US, COORD_FMT, pt.lat)
        val lon = String.format(Locale.US, COORD_FMT, pt.lon)
        w.write("""      <trkpt lat="$lat" lon="$lon">""")
        w.write("\n")
        if (pt.ele != null)
            w.write("        <ele>${String.format(Locale.US, "%.0f", pt.ele)}</ele>\n")
        if (pt.undulation != null)
            w.write("        <geoidheight>${String.format(Locale.US, "%.1f", pt.undulation)}</geoidheight>\n")
        w.write("        <time>${ISO.format(Instant.ofEpochMilli(pt.time))}</time>\n")
        if (pt.speedMs != null)
            w.write("        <speed>${String.format(Locale.US, "%.2f", pt.speedMs)}</speed>\n")
        if (pt.bearing != null)
            w.write("        <course>${String.format(Locale.US, "%.1f", pt.bearing)}</course>\n")
        if (pt.accuracyM != null) {
            val hdop = String.format(Locale.US, "%.2f", pt.accuracyM)
            w.write("        <extensions>\n")
            w.write("          <osmand:accuracy>$hdop</osmand:accuracy>\n")
            w.write("        </extensions>\n")
        }
        w.write("      </trkpt>\n")
    }

    private fun escapeXml(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
}
