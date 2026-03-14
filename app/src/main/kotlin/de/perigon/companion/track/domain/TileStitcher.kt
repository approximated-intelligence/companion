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
 * Stitch tiles from the source into a single bitmap covering the track bounds
 * with padding. Returns the stitched bitmap and a projection function.
 */
fun stitchTiles(
    tileSource: TileSource,
    bounds:     GpxTrack.Bounds,
    outputPx:   Int,
    padFrac:    Float = 0.15f,
): StitchResult {
    // Expand bounds by padding fraction
    val latSpan = bounds.maxLat - bounds.minLat
    val lonSpan = bounds.maxLon - bounds.minLon
    val padded  = GpxTrack.Bounds(
        minLat = bounds.minLat - latSpan * padFrac,
        maxLat = bounds.maxLat + latSpan * padFrac,
        minLon = bounds.minLon - lonSpan * padFrac,
        maxLon = bounds.maxLon + lonSpan * padFrac,
    )

    val zoom = pickZoom(padded, outputPx, tileSource.minZoom, tileSource.maxZoom)

    val (tileXMin, tileYMin) = lonLatToTileXY(padded.minLon, padded.maxLat, zoom)
    val (tileXMax, tileYMax) = lonLatToTileXY(padded.maxLon, padded.minLat, zoom)

    val tilesW = tileXMax - tileXMin + 1
    val tilesH = tileYMax - tileYMin + 1
    val rawW   = tilesW * TILE_SIZE
    val rawH   = tilesH * TILE_SIZE

    // Stitch tiles into raw bitmap
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

    // Crop/scale to outputPx × outputPx
    // Compute pixel bounds of the padded geo-bounds within the stitched image
    val pxLeft   = lonToPixelX(padded.minLon, zoom) - tileXMin * TILE_SIZE
    val pxRight  = lonToPixelX(padded.maxLon, zoom) - tileXMin * TILE_SIZE
    val pxTop    = latToPixelY(padded.maxLat, zoom) - tileYMin * TILE_SIZE
    val pxBottom = latToPixelY(padded.minLat, zoom) - tileYMin * TILE_SIZE

    // Make square crop centered on the geo-bounds
    val cropW    = (pxRight - pxLeft).coerceAtLeast(1.0)
    val cropH    = (pxBottom - pxTop).coerceAtLeast(1.0)
    val cropSize = maxOf(cropW, cropH)
    val cropCx   = (pxLeft + pxRight) / 2.0
    val cropCy   = (pxTop + pxBottom) / 2.0
    val cropL    = (cropCx - cropSize / 2).roundToInt().coerceIn(0, rawW)
    val cropT    = (cropCy - cropSize / 2).roundToInt().coerceIn(0, rawH)
    val cropR    = (cropCx + cropSize / 2).roundToInt().coerceIn(0, rawW)
    val cropB    = (cropCy + cropSize / 2).roundToInt().coerceIn(0, rawH)

    val output       = Bitmap.createBitmap(outputPx, outputPx, Bitmap.Config.ARGB_8888)
    val outputCanvas = Canvas(output)
    outputCanvas.drawBitmap(
        raw,
        Rect(cropL, cropT, cropR, cropB),
        Rect(0, 0, outputPx, outputPx),
        null,
    )
    raw.recycle()

    // Build projection: LatLon → output pixel
    val project = { ll: LatLon ->
        val worldX = lonToPixelX(ll.lon, zoom) - tileXMin * TILE_SIZE
        val worldY = latToPixelY(ll.lat, zoom) - tileYMin * TILE_SIZE
        val x = ((worldX - cropL) / (cropR - cropL).toFloat() * outputPx).toFloat()
        val y = ((worldY - cropT) / (cropB - cropT).toFloat() * outputPx).toFloat()
        x to y
    }

    return StitchResult(output, project)
}

// --- Slippy map math (EPSG:3857 tile scheme) ---

/** Returns (tileX, tileY) for the given lon/lat at zoom level z. */
fun lonLatToTileXY(lon: Float, lat: Float, z: Int): Pair<Int, Int> {
    val n    = 1 shl z
    val x    = ((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
    val latR = Math.toRadians(lat.coerceIn(-85.051129f, 85.051129f).toDouble())
    val y    = ((1.0 - ln(tan(latR) + 1.0 / cos(latR)) / PI) / 2.0 * n).toInt().coerceIn(0, n - 1)
    return x to y
}

/** Fractional pixel X within the full tile grid at zoom z. */
fun lonToPixelX(lon: Float, z: Int): Double {
    val n = (1 shl z).toFloat()
    return (lon + 180.0) / 360.0 * n * TILE_SIZE
}

/** Fractional pixel Y within the full tile grid at zoom z. */
fun latToPixelY(lat: Float, z: Int): Double {
    val n    = (1 shl z).toFloat()
    val latR = Math.toRadians(lat.coerceIn(-85.051129f, 85.051129f).toDouble())
    return (1.0 - ln(tan(latR) + 1.0 / cos(latR)) / PI) / 2.0 * n * TILE_SIZE
}

private fun Float.roundToInt(): Int = kotlin.math.round(this).toInt()