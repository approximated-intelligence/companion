package de.perigon.companion.media.domain

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// =============================================================================
// Pixel-space crop rect — CENTER-ORIGIN
//
// All coordinates are relative to the image center = (0, 0).
// A full-frame crop on a 1000×800 rotated image is (-500, -400, 500, 400).
// The crop is axis-aligned in rotated space.
// Projection to unrotated space for display is a pure rotation around (0,0).
// Conversion to normalized CropRect(0..1) happens only at confirm time.
// =============================================================================

data class PixelCrop(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

fun rotatedDimensions(w: Float, h: Float, angleDeg: Float): Pair<Float, Float> {
    if (angleDeg == 0f) return w to h
    val rad = Math.toRadians(angleDeg.toDouble())
    val cosA = abs(cos(rad)).toFloat()
    val sinA = abs(sin(rad)).toFloat()
    return (w * cosA + h * sinA) to (h * cosA + w * sinA)
}

fun CropRect.toPixelCrop(rotW: Float, rotH: Float): PixelCrop = PixelCrop(
    left = (left - 0.5f) * rotW,
    top = (top - 0.5f) * rotH,
    right = (right - 0.5f) * rotW,
    bottom = (bottom - 0.5f) * rotH,
)

fun PixelCrop.toNormalized(rotW: Float, rotH: Float): CropRect = CropRect(
    left = (left / rotW + 0.5f).coerceIn(0f, 1f),
    top = (top / rotH + 0.5f).coerceIn(0f, 1f),
    right = (right / rotW + 0.5f).coerceIn(0f, 1f),
    bottom = (bottom / rotH + 0.5f).coerceIn(0f, 1f),
)

fun fullFramePixelCrop(rotW: Float, rotH: Float): PixelCrop =
    PixelCrop(-rotW / 2f, -rotH / 2f, rotW / 2f, rotH / 2f)

fun rotateVec(x: Float, y: Float, angleDeg: Float): Offset {
    if (angleDeg == 0f) return Offset(x, y)
    val rad = Math.toRadians(angleDeg.toDouble())
    val cosR = cos(rad).toFloat()
    val sinR = sin(rad).toFloat()
    return Offset(x * cosR - y * sinR, x * sinR + y * cosR)
}

fun projectCropCorners(crop: PixelCrop, fineRotation: Float): List<Offset> {
    val corners = listOf(
        Offset(crop.left, crop.top),
        Offset(crop.right, crop.top),
        Offset(crop.right, crop.bottom),
        Offset(crop.left, crop.bottom),
    )
    return corners.map { rotateVec(it.x, it.y, -fineRotation) }
}

fun centeredToCanvas(pt: Offset, imageRect: Rect, imgW: Float, imgH: Float): Offset {
    val scale = imageRect.width / imgW
    return Offset(
        imageRect.center.x + pt.x * scale,
        imageRect.center.y + pt.y * scale,
    )
}

fun canvasToCentered(pt: Offset, imageRect: Rect, imgW: Float, imgH: Float): Offset {
    val scale = imageRect.width / imgW
    return Offset(
        (pt.x - imageRect.center.x) / scale,
        (pt.y - imageRect.center.y) / scale,
    )
}

fun canvasDeltaToImage(dx: Float, dy: Float, imageRect: Rect, imgW: Float, imgH: Float): Offset {
    val scale = imageRect.width / imgW
    return Offset(dx / scale, dy / scale)
}

fun computeImageRect(w: Int, h: Int, canvasSize: IntSize): Rect {
    if (canvasSize.width <= 0 || canvasSize.height <= 0) return Rect.Zero
    val scale = min(canvasSize.width.toFloat() / w, canvasSize.height.toFloat() / h)
    val sw = w * scale; val sh = h * scale
    val x = (canvasSize.width - sw) / 2f; val y = (canvasSize.height - sh) / 2f
    return Rect(x, y, x + sw, y + sh)
}

// ===== Crop handle hit testing & drag =====

enum class CropHandle {
    NONE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT,
    TOP, BOTTOM, LEFT, RIGHT, MOVE,
}

fun hitTestCropHandle(touch: Offset, rect: Rect, slop: Float): CropHandle {
    val corners = listOf(
        CropHandle.TOP_LEFT to Offset(rect.left, rect.top),
        CropHandle.TOP_RIGHT to Offset(rect.right, rect.top),
        CropHandle.BOTTOM_LEFT to Offset(rect.left, rect.bottom),
        CropHandle.BOTTOM_RIGHT to Offset(rect.right, rect.bottom),
    )
    for ((handle, pos) in corners) if (dist(touch, pos) <= slop) return handle
    if (abs(touch.y - rect.top) <= slop && touch.x in rect.left..rect.right) return CropHandle.TOP
    if (abs(touch.y - rect.bottom) <= slop && touch.x in rect.left..rect.right) return CropHandle.BOTTOM
    if (abs(touch.x - rect.left) <= slop && touch.y in rect.top..rect.bottom) return CropHandle.LEFT
    if (abs(touch.x - rect.right) <= slop && touch.y in rect.top..rect.bottom) return CropHandle.RIGHT
    if (rect.contains(touch)) return CropHandle.MOVE
    return CropHandle.NONE
}

fun applyCropDrag(rect: Rect, handle: CropHandle, delta: Offset, bounds: Rect, minSize: Float): Rect {
    var l = rect.left; var t = rect.top; var r = rect.right; var b = rect.bottom
    when (handle) {
        CropHandle.TOP_LEFT -> { l += delta.x; t += delta.y }
        CropHandle.TOP_RIGHT -> { r += delta.x; t += delta.y }
        CropHandle.BOTTOM_LEFT -> { l += delta.x; b += delta.y }
        CropHandle.BOTTOM_RIGHT -> { r += delta.x; b += delta.y }
        CropHandle.TOP -> t += delta.y
        CropHandle.BOTTOM -> b += delta.y
        CropHandle.LEFT -> l += delta.x
        CropHandle.RIGHT -> r += delta.x
        CropHandle.MOVE -> {
            val w = r - l; val h = b - t
            var nl = l + delta.x; var nt = t + delta.y
            nl = nl.coerceIn(bounds.left, bounds.right - w)
            nt = nt.coerceIn(bounds.top, bounds.bottom - h)
            return Rect(nl, nt, nl + w, nt + h)
        }
        CropHandle.NONE -> return rect
    }
    if (r - l < minSize) {
        if (handle in listOf(CropHandle.TOP_LEFT, CropHandle.BOTTOM_LEFT, CropHandle.LEFT)) l = r - minSize else r = l + minSize
    }
    if (b - t < minSize) {
        if (handle in listOf(CropHandle.TOP_LEFT, CropHandle.TOP_RIGHT, CropHandle.TOP)) t = b - minSize else b = t + minSize
    }
    l = l.coerceIn(bounds.left, bounds.right - minSize)
    t = t.coerceIn(bounds.top, bounds.bottom - minSize)
    r = r.coerceIn(bounds.left + minSize, bounds.right)
    b = b.coerceIn(bounds.top + minSize, bounds.bottom)
    return Rect(l, t, r, b)
}

// ===== Shared crop drag logic for image and video overlays =====

fun handleCropDragStart(
    canvasOffset: Offset,
    frameRect: Rect,
    unrotW: Float, unrotH: Float,
    fineRotation: Float,
    crop: PixelCrop,
    hitSlop: Float,
): CropHandle {
    val imgPt = canvasToCentered(canvasOffset, frameRect, unrotW, unrotH)
    val rotPt = rotateVec(imgPt.x, imgPt.y, fineRotation)
    val cropRect = Rect(crop.left, crop.top, crop.right, crop.bottom)
    val scale = frameRect.width / unrotW
    return hitTestCropHandle(rotPt, cropRect, hitSlop / scale)
}

fun handleCropDrag(
    dragAmount: Offset,
    frameRect: Rect,
    unrotW: Float, unrotH: Float,
    rotW: Float, rotH: Float,
    fineRotation: Float,
    crop: PixelCrop,
    activeHandle: CropHandle,
    minCropPx: Float,
): PixelCrop {
    val imgDelta = canvasDeltaToImage(dragAmount.x, dragAmount.y, frameRect, unrotW, unrotH)
    val rotDelta = rotateVec(imgDelta.x, imgDelta.y, fineRotation)
    val cropRect = Rect(crop.left, crop.top, crop.right, crop.bottom)
    val bounds = Rect(-rotW / 2f, -rotH / 2f, rotW / 2f, rotH / 2f)
    val scale = frameRect.width / unrotW
    val minPx = minCropPx / scale
    val newRect = applyCropDrag(cropRect, activeHandle, rotDelta, bounds, minPx)
    return PixelCrop(newRect.left, newRect.top, newRect.right, newRect.bottom)
}

// ===== DrawScope extensions for crop overlay rendering =====

fun DrawScope.drawDimWithQuadCutout(imageRect: Rect, quad: List<Offset>, color: Color) {
    if (quad.size != 4) return
    val quadPath = Path().apply {
        moveTo(quad[0].x, quad[0].y); lineTo(quad[1].x, quad[1].y)
        lineTo(quad[2].x, quad[2].y); lineTo(quad[3].x, quad[3].y); close()
    }
    clipPath(quadPath, clipOp = androidx.compose.ui.graphics.ClipOp.Difference) {
        drawRect(color, Offset(imageRect.left, imageRect.top), Size(imageRect.width, imageRect.height))
    }
}

fun DrawScope.drawQuadrilateral(quad: List<Offset>, color: Color, strokeWidth: Float) {
    if (quad.size != 4) return
    for (i in quad.indices) drawLine(color, quad[i], quad[(i + 1) % quad.size], strokeWidth)
}

fun DrawScope.drawQuadGuideLines(quad: List<Offset>, color: Color) {
    if (quad.size != 4) return
    for (i in 1..2) {
        val f = i / 3f
        drawLine(color, lerp(quad[0], quad[3], f), lerp(quad[1], quad[2], f), 1f)
        drawLine(color, lerp(quad[0], quad[1], f), lerp(quad[3], quad[2], f), 1f)
    }
}

fun DrawScope.drawQuadCornerHandles(
    quad: List<Offset>, color: Color,
    len: Float, width: Float, dotRadius: Float,
) {
    if (quad.size != 4) return
    for (i in quad.indices) {
        val corner = quad[i]
        val toPrev = directionScaled(corner, quad[(i + 3) % 4], len)
        val toNext = directionScaled(corner, quad[(i + 1) % 4], len)
        drawLine(color, corner, Offset(corner.x + toPrev.x, corner.y + toPrev.y), width)
        drawLine(color, corner, Offset(corner.x + toNext.x, corner.y + toNext.y), width)
        drawCircle(color, dotRadius, corner)
    }
    for (i in quad.indices) {
        val mid = Offset((quad[i].x + quad[(i + 1) % 4].x) / 2f, (quad[i].y + quad[(i + 1) % 4].y) / 2f)
        drawCircle(color, dotRadius * 0.7f, mid)
    }
}

fun DrawScope.drawOrientationArrow(
    quad: List<Offset>, color: Color,
    strokeWidth: Float, orientationDegrees: Int = 0,
) {
    if (quad.size != 4) return
    val cx = quad.map { it.x }.average().toFloat()
    val cy = quad.map { it.y }.average().toFloat()
    val topMid = Offset((quad[0].x + quad[1].x) / 2f, (quad[0].y + quad[1].y) / 2f)
    val rightMid = Offset((quad[1].x + quad[2].x) / 2f, (quad[1].y + quad[2].y) / 2f)
    val upX = topMid.x - cx; val upY = topMid.y - cy
    val rightX = rightMid.x - cx; val rightY = rightMid.y - cy
    val rad = Math.toRadians(orientationDegrees.toDouble())
    val cosR = cos(rad).toFloat(); val sinR = sin(rad).toFloat()
    val dirX = upX * cosR + rightX * sinR
    val dirY = upY * cosR + rightY * sinR
    val perpX = -upX * sinR + rightX * cosR
    val perpY = -upY * sinR + rightY * cosR
    val len = sqrt(dirX * dirX + dirY * dirY)
    if (len == 0f) return
    val nx = dirX / len * len * 0.6f; val ny = dirY / len * len * 0.6f
    val px = perpX / len * len * 0.15f; val py = perpY / len * len * 0.15f
    val shaftStart = Offset(cx - nx * 0.3f, cy - ny * 0.3f)
    val shaftEnd = Offset(cx + nx, cy + ny)
    drawLine(color, shaftStart, shaftEnd, strokeWidth * 2f)
    val headLeft = Offset(shaftEnd.x - nx * 0.25f + px, shaftEnd.y - ny * 0.25f + py)
    val headRight = Offset(shaftEnd.x - nx * 0.25f - px, shaftEnd.y - ny * 0.25f - py)
    drawLine(color, shaftEnd, headLeft, strokeWidth * 2f)
    drawLine(color, shaftEnd, headRight, strokeWidth * 2f)
}

// ===== Geometry helpers =====

private fun dist(a: Offset, b: Offset): Float {
    val dx = a.x - b.x; val dy = a.y - b.y; return sqrt(dx * dx + dy * dy)
}

fun lerp(a: Offset, b: Offset, f: Float): Offset =
    Offset(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f)

fun directionScaled(from: Offset, to: Offset, length: Float): Offset {
    val dx = to.x - from.x; val dy = to.y - from.y
    val d = sqrt(dx * dx + dy * dy)
    if (d == 0f) return Offset.Zero
    return Offset(dx / d * length, dy / d * length)
}

fun formatTime(ms: Long): String {
    val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60)
}
