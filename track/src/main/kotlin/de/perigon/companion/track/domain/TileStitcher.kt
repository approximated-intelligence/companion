package de.perigon.companion.track.domain

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import de.perigon.companion.track.domain.GpxTrack
import de.perigon.companion.track.domain.LatLon
import kotlin.math.*
import de.perigon.companion.track.network.TileSource

private const val TILE_SIZE = 256

/**
 * Result of stitching tiles: a background bitmap and a function
 * to project lat/lon to pixel coordinates within that bitmap.
 */
data class StitchResult(
    val bitmap:   Bitmap,
    val project:  (LatLon) -> Pair<Float, Float>,
)

/**
 * Pick the best zoom level so the track fits in roughly outputPx pixels.
 */
fun pickZoom(bounds: GpxTrack.Bounds, outputPx: Int, minZoom: Int, maxZoom: Int): Int {
    for (z in maxZoom downTo minZoom) {
        val (xMin, yMin) = lonLatToTileXY(bounds.minLon, bounds.maxLat, z)
        val (xMax, yMax) = lonLatToTileXY(bounds.maxLon, bounds.minLat, z)
        val widthPx  = (xMax - xMin + 1) * TILE_SIZE
        val heightPx = (yMax - yMin + 1) * TILE_SIZE
        if (widthPx <= outputPx * 2 && heightPx <= outputPx * 2) return z
    }
    return minZoom
}

/**
 * Expand bounds to be square in Mercator pixel space at the given zoom,
 * ensuring the tile grid covers a square area and the track never clips.
 */
private fun squarifyBounds(bounds: GpxTrack.Bounds, zoom: Int): GpxTrack.Bounds {
    val (pxLeft, pxTop) = lonLatToPixelXY(bounds.minLon, bounds.maxLat, zoom)
    val (pxRight, pxBottom) = lonLatToPixelXY(bounds.maxLon, bounds.minLat, zoom)

    val pxW = pxRight - pxLeft
    val pxH = pxBottom - pxTop

    if (pxW >= pxH) {
        val diff = (pxW - pxH) / 2.0
        return GpxTrack.Bounds(
            minLat = pixelYToLat(pxBottom + diff, zoom),
            maxLat = pixelYToLat(pxTop - diff, zoom),
            minLon = bounds.minLon,
            maxLon = bounds.maxLon,
        )
    } else {
        val diff = (pxH - pxW) / 2.0
        return GpxTrack.Bounds(
            minLat = bounds.minLat,
            maxLat = bounds.maxLat,
            minLon = pixelXToLon(pxLeft - diff, zoom),
            maxLon = pixelXToLon(pxRight + diff, zoom),
        )
    }
}

/**
 * Stitch tiles from the source into a single bitmap covering the track bounds
 * with padding. Returns the stitched bitmap and a projection function.
 */
fun stitchTiles(
    tileSource: TileSource,
    bounds:     GpxTrack.Bounds,
    outputPx:   Int,
    padFrac:    Float = 0.15f,
): StitchResult {
    val latSpan = bounds.maxLat - bounds.minLat
    val lonSpan = bounds.maxLon - bounds.minLon
    val padded  = GpxTrack.Bounds(
        minLat = bounds.minLat - latSpan * padFrac,
        maxLat = bounds.maxLat + latSpan * padFrac,
        minLon = bounds.minLon - lonSpan * padFrac,
        maxLon = bounds.maxLon + lonSpan * padFrac,
    )

    val zoom = pickZoom(padded, outputPx, tileSource.minZoom, tileSource.maxZoom)
    val squared = squarifyBounds(padded, zoom)

    val (tileXMin, tileYMin) = lonLatToTileXY(squared.minLon, squared.maxLat, zoom)
    val (tileXMax, tileYMax) = lonLatToTileXY(squared.maxLon, squared.minLat, zoom)

    val tilesW = tileXMax - tileXMin + 1
    val tilesH = tileYMax - tileYMin + 1
    val rawW   = tilesW * TILE_SIZE
    val rawH   = tilesH * TILE_SIZE

    val raw    = Bitmap.createBitmap(rawW, rawH, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(raw)

    for (ty in tileYMin..tileYMax) {
        for (tx in tileXMin..tileXMax) {
            val tile = tileSource.getTile(zoom, tx, ty) ?: continue
            val px   = (tx - tileXMin) * TILE_SIZE
            val py   = (ty - tileYMin) * TILE_SIZE
            canvas.drawBitmap(tile, px.toFloat(), py.toFloat(), null)
            tile.recycle()
        }
    }

    val originX = tileXMin.toDouble() * TILE_SIZE
    val originY = tileYMin.toDouble() * TILE_SIZE

    val (cropLd, cropTd) = lonLatToPixelXY(squared.minLon, squared.maxLat, zoom)
    val (cropRd, cropBd) = lonLatToPixelXY(squared.maxLon, squared.minLat, zoom)

    val cropL = (cropLd - originX).roundToInt().coerceIn(0, rawW)
    val cropT = (cropTd - originY).roundToInt().coerceIn(0, rawH)
    val cropR = (cropRd - originX).roundToInt().coerceIn(0, rawW)
    val cropB = (cropBd - originY).roundToInt().coerceIn(0, rawH)

    val output       = Bitmap.createBitmap(outputPx, outputPx, Bitmap.Config.ARGB_8888)
    val outputCanvas = Canvas(output)
    outputCanvas.drawBitmap(
        raw,
        Rect(cropL, cropT, cropR, cropB),
        Rect(0, 0, outputPx, outputPx),
        null,
    )
    raw.recycle()

    val cropW = (cropR - cropL).toFloat().coerceAtLeast(1f)
    val cropH = (cropB - cropT).toFloat().coerceAtLeast(1f)

    val project = { ll: LatLon ->
        val (wx, wy) = lonLatToPixelXY(ll.lon, ll.lat, zoom)
        val x = ((wx - originX - cropL) / cropW * outputPx).toFloat()
        val y = ((wy - originY - cropT) / cropH * outputPx).toFloat()
        x to y
    }

    return StitchResult(output, project)
}

// --- Slippy map math (EPSG:3857 tile scheme) ---

/** Fractional world pixel coordinates for lon/lat at zoom z. */
fun lonLatToPixelXY(lon: Float, lat: Float, z: Int): Pair<Double, Double> {
    val n    = (1 shl z).toDouble()
    val x    = (lon + 180.0) / 360.0 * n * TILE_SIZE
    val latR = Math.toRadians(lat.coerceIn(-85.051129f, 85.051129f).toDouble())
    val y    = (1.0 - ln(tan(latR) + 1.0 / cos(latR)) / PI) / 2.0 * n * TILE_SIZE
    return x to y
}

/** Tile coordinates (integer) for lon/lat at zoom z. */
fun lonLatToTileXY(lon: Float, lat: Float, z: Int): Pair<Int, Int> {
    val (px, py) = lonLatToPixelXY(lon, lat, z)
    val n = 1 shl z
    return (px / TILE_SIZE).toInt().coerceIn(0, n - 1) to
           (py / TILE_SIZE).toInt().coerceIn(0, n - 1)
}

/** Inverse: world pixel X → longitude. */
fun pixelXToLon(px: Double, z: Int): Float {
    val n = (1 shl z).toDouble()
    return ((px / TILE_SIZE / n) * 360.0 - 180.0).toFloat()
}

/** Inverse: world pixel Y → latitude. */
fun pixelYToLat(py: Double, z: Int): Float {
    val n = (1 shl z).toDouble()
    return Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * py / TILE_SIZE / n)))).toFloat()
}

private fun Double.roundToInt(): Int = kotlin.math.round(this).toInt()
