package de.perigon.companion.media.domain

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.graphics.createBitmap
import de.perigon.companion.core.geo.LatLon
import de.perigon.companion.media.network.TileSource

private const val OUTPUT_PX    = 960
private const val PADDING_FRAC = 0.05f
private const val STROKE_WIDTH = 6f
private const val DOT_RADIUS   = 14f
private const val TRACK_COLOR  = 0xFF2979FF.toInt()
private const val START_COLOR  = 0xFF00C853.toInt()
private const val END_COLOR    = 0xFFD50000.toInt()

/**
 * What [renderTracks] produced, plus how much of the background is missing.
 * Callers surface [failedTiles] to the user; [missingTiles] alone is a
 * coverage gap (normal for a bounded tile source) and stays silent. See
 * [de.perigon.companion.media.domain.StitchResult] for the same split.
 */
data class RenderResult(
    val bitmap: Bitmap,
    val missingTiles: Int = 0,
    val failedTiles:  Int = 0,
)

/**
 * Render one or more GPX tracks to a square Bitmap.
 * All tracks share the same projection (merged bounds).
 * If a TileSource is provided, stitches map tiles as background.
 *
 * Segment handling: each segment is drawn as its own polyline, so a gap in
 * the recording is a visible break rather than a false straight line. Only
 * the global first and last points of the whole track get the start/end
 * markers — per-segment markers would clutter a multi-segment track and are
 * not needed to read the gap (the line break says it).
 */
fun renderTracks(
    tracks:     List<GpxTrack>,
    tileSource: TileSource? = null,
    outputPx:   Int = OUTPUT_PX,
): RenderResult {
    require(tracks.isNotEmpty() && tracks.any { !it.isEmpty }) { "Cannot render empty tracks" }
    val nonEmpty = tracks.filter { !it.isEmpty }
    return if (tileSource != null) renderWithTiles(nonEmpty, tileSource, outputPx)
           else RenderResult(renderWhiteBackground(nonEmpty, outputPx))
}

/** Convenience for single-track callers. */
fun renderTrack(track: GpxTrack, tileSource: TileSource? = null, outputPx: Int = OUTPUT_PX): RenderResult =
    renderTracks(listOf(track), tileSource, outputPx)

private fun mergeBounds(tracks: List<GpxTrack>): GpxTrack.Bounds {
    val allPoints = tracks.flatMap { it.points }
    return GpxTrack.Bounds(
        minLat = allPoints.minOf { it.lat },
        maxLat = allPoints.maxOf { it.lat },
        minLon = allPoints.minOf { it.lon },
        maxLon = allPoints.maxOf { it.lon },
    )
}

private fun renderWithTiles(tracks: List<GpxTrack>, tileSource: TileSource, outputPx: Int): RenderResult {
    val bounds = mergeBounds(tracks)
    val stitch = stitchTiles(tileSource, bounds, outputPx)
    val canvas = Canvas(stitch.bitmap)
    tracks.forEach { track ->
        val projectedSegments = track.segments.map { seg -> seg.map { stitch.project(it) } }
        drawTrackOverlay(canvas, projectedSegments)
    }
    return RenderResult(stitch.bitmap, stitch.missingTiles, stitch.failedTiles)
}

private fun renderWhiteBackground(tracks: List<GpxTrack>, outputPx: Int): Bitmap {
    val bitmap = createBitmap(outputPx, outputPx)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.WHITE)

    val bounds = mergeBounds(tracks)
    val pad    = outputPx * PADDING_FRAC

    fun LatLon.toMercator(): Pair<Float, Float> {
        val x       = (lon - bounds.minLon).toDouble() / (bounds.maxLon - bounds.minLon).toDouble().coerceAtLeast(1e-9)
        val latR    = Math.toRadians(lat.toDouble())
        val minR    = Math.toRadians(bounds.minLat.toDouble())
        val maxR    = Math.toRadians(bounds.maxLat.toDouble())
        val mercY   = Math.log(Math.tan(Math.PI / 4 + latR / 2))
        val mercMin = Math.log(Math.tan(Math.PI / 4 + minR / 2))
        val mercMax = Math.log(Math.tan(Math.PI / 4 + maxR / 2))
        val y       = if (mercMax > mercMin) 1.0 - (mercY - mercMin) / (mercMax - mercMin) else 0.5
        return Pair(
            (pad + x * (outputPx - 2 * pad)).toFloat(),
            (pad + y * (outputPx - 2 * pad)).toFloat(),
        )
    }

    // Compute unified scale from all tracks combined
    val allPixels = tracks.flatMap { t -> t.points.map { it.toMercator() } }
    val usedRect  = RectF(
        allPixels.minOf { it.first }, allPixels.minOf { it.second },
        allPixels.maxOf { it.first }, allPixels.maxOf { it.second },
    )
    val scale   = minOf(
        (outputPx - 2 * pad) / usedRect.width().coerceAtLeast(1f),
        (outputPx - 2 * pad) / usedRect.height().coerceAtLeast(1f),
    )
    val offsetX = pad + ((outputPx - 2 * pad) - usedRect.width() * scale) / 2 - usedRect.left * scale
    val offsetY = pad + ((outputPx - 2 * pad) - usedRect.height() * scale) / 2 - usedRect.top * scale

    fun Pair<Float, Float>.scaled() = Pair(
        (first * scale + offsetX),
        (second * scale + offsetY),
    )

    tracks.forEach { track ->
        val projectedSegments = track.segments.map { seg ->
            seg.map { it.toMercator().scaled() }
        }
        drawTrackOverlay(canvas, projectedSegments)
    }

    return bitmap
}

private fun drawTrackOverlay(canvas: Canvas, segments: List<List<Pair<Float, Float>>>) {
    val allPoints = segments.flatten()
    if (allPoints.isEmpty()) return

    val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = TRACK_COLOR
        style       = Paint.Style.STROKE
        strokeWidth = STROKE_WIDTH
        strokeCap   = Paint.Cap.ROUND
        strokeJoin  = Paint.Join.ROUND
    }

    // One path per segment: a gap is a visible break, not a straight line
    // across empty space.
    for (seg in segments) {
        if (seg.isEmpty()) continue
        val path = Path().apply {
            seg.forEachIndexed { i, (x, y) ->
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
        canvas.drawPath(path, trackPaint)
    }

    // Global start and end only. Per-segment markers were considered and
    // rejected: the polyline already shows where the gap is, and a marker
    // at every segment boundary clutters a track with many short segments.
    allPoints.firstOrNull()?.let { (x, y) ->
        canvas.drawCircle(x, y, DOT_RADIUS, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = START_COLOR })
    }
    allPoints.lastOrNull()?.let { (x, y) ->
        canvas.drawCircle(x, y, DOT_RADIUS, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = END_COLOR })
    }
}
