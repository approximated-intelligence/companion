package de.perigon.companion.track.domain

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import de.perigon.companion.track.network.TileSource

private const val OUTPUT_PX    = 1080
private const val PADDING_FRAC = 0.05f
private const val STROKE_WIDTH = 6f
private const val DOT_RADIUS   = 14f

/**
 * Render one or more GPX tracks to a square Bitmap.
 * All tracks share the same projection (merged bounds).
 * If a TileSource is provided, stitches map tiles as background.
 */
fun renderTracks(
    tracks:     List<GpxTrack>,
    tileSource: TileSource? = null,
    outputPx:   Int = OUTPUT_PX,
): Bitmap {
    require(tracks.isNotEmpty() && tracks.any { !it.isEmpty }) { "Cannot render empty tracks" }
    val nonEmpty = tracks.filter { !it.isEmpty }
    return if (tileSource != null) renderWithTiles(nonEmpty, tileSource, outputPx)
           else renderWhiteBackground(nonEmpty, outputPx)
}

/** Convenience for single-track callers. */
fun renderTrack(track: GpxTrack, tileSource: TileSource? = null, outputPx: Int = OUTPUT_PX): Bitmap =
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

private fun renderWithTiles(tracks: List<GpxTrack>, tileSource: TileSource, outputPx: Int): Bitmap {
    val bounds = mergeBounds(tracks)
    val stitch = stitchTiles(tileSource, bounds, outputPx)
    val canvas = Canvas(stitch.bitmap)
    tracks.forEach { track ->
        drawTrackOverlay(canvas, track.points.map { stitch.project(it) })
    }
    return stitch.bitmap
}

private fun renderWhiteBackground(tracks: List<GpxTrack>, outputPx: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(outputPx, outputPx, Bitmap.Config.ARGB_8888)
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
        val scaled = track.points.map { it.toMercator().scaled() }
        drawTrackOverlay(canvas, scaled)
    }

    return bitmap
}

private fun drawTrackOverlay(canvas: Canvas, points: List<Pair<Float, Float>>) {
    if (points.isEmpty()) return

    val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = Color.parseColor("#2979FF")
        style       = Paint.Style.STROKE
        strokeWidth = STROKE_WIDTH
        strokeCap   = Paint.Cap.ROUND
        strokeJoin  = Paint.Join.ROUND
    }
    val path = Path().apply {
        points.forEachIndexed { i, (x, y) ->
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
    }
    canvas.drawPath(path, trackPaint)

    points.firstOrNull()?.let { (x, y) ->
        canvas.drawCircle(x, y, DOT_RADIUS, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#00C853") })
    }
    points.lastOrNull()?.let { (x, y) ->
        canvas.drawCircle(x, y, DOT_RADIUS, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#D50000") })
    }
}
