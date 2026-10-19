package de.perigon.companion.media.transform

import android.graphics.Bitmap
import android.graphics.Matrix
import de.perigon.companion.media.transform.scaleToFit
import java.io.ByteArrayOutputStream
import kotlin.math.ceil

/**
 * Image transform pipeline over UPRIGHT pixels:
 * orientation → fine rotation → crop → scale → JPEG.
 *
 * No decoding here. Every caller decodes through media.domain.decodeUpright
 * or decodeBounded (ImageDecoder, EXIF already applied), so this pipeline has
 * no EXIF step — and no way to apply orientation twice.
 */
data class ImageTransformParams(
    val orientationDegrees: Int = 0,
    val flipHorizontal: Boolean = false,
    val fineRotationDegrees: Float = 0f,
    val cropLeft: Float = 0f,
    val cropTop: Float = 0f,
    val cropRight: Float = 1f,
    val cropBottom: Float = 1f,
    val boxPx: Int = 960,
    val quality: Int = 85,
) {
    val hasCrop: Boolean
        get() = cropLeft != 0f || cropTop != 0f || cropRight != 1f || cropBottom != 1f

    val hasOrientation: Boolean
        get() = orientationDegrees != 0 || flipHorizontal

    val hasFineRotation: Boolean
        get() = fineRotationDegrees != 0f

    val hasAnyTransform: Boolean
        get() = hasCrop || hasOrientation || hasFineRotation
}

private const val MIN_CROP_FRACTION = 0.01f

/**
 * Pure: the long side to decode at so the output still reaches [boxPx]
 * after cropping.
 *
 * Decoding straight to boxPx and then cropping would shrink the result (a
 * 50% crop landing at 540px). Dividing by the smaller crop fraction is
 * sufficient on every axis: whichever axis the decoded long side ends up on
 * after rotation, that axis keeps at least that fraction, and fine rotation
 * only enlarges the bounds. +1 absorbs the integer truncation of the crop.
 */
fun ImageTransformParams.decodeLongSide(): Int {
    if (!hasCrop) return boxPx
    val minFraction = minOf(cropRight - cropLeft, cropBottom - cropTop)
        .coerceIn(MIN_CROP_FRACTION, 1f)
    return ceil(boxPx / minFraction).toInt() + 1
}

object ImageTransformer {

    /** Transform, scale into the box, encode JPEG. Consumes [source]. */
    fun render(source: Bitmap, params: ImageTransformParams): ByteArray =
        encode(applyPipeline(source, params), params)

    /**
     * Coarse orientation, fine rotation, crop — on upright pixels.
     * Consumes [source]: every intermediate that is replaced is recycled,
     * [source] included. Returns [source] itself when nothing applies.
     */
    fun applyPipeline(source: Bitmap, params: ImageTransformParams): Bitmap {
        var bmp = source

        if (params.hasOrientation) {
            bmp = bmp.replacedBy { b ->
                Bitmap.createBitmap(b, 0, 0, b.width, b.height, coarseMatrix(params), true)
            }
        }

        if (params.hasFineRotation) {
            bmp = bmp.replacedBy { b ->
                val m = Matrix().apply { postRotate(params.fineRotationDegrees) }
                Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
            }
        }

        if (params.hasCrop) {
            bmp = bmp.replacedBy { b ->
                val x = (params.cropLeft * b.width).toInt().coerceIn(0, b.width - 1)
                val y = (params.cropTop * b.height).toInt().coerceIn(0, b.height - 1)
                val w = ((params.cropRight - params.cropLeft) * b.width).toInt()
                    .coerceIn(1, b.width - x)
                val h = ((params.cropBottom - params.cropTop) * b.height).toInt()
                    .coerceIn(1, b.height - y)
                Bitmap.createBitmap(b, x, y, w, h)
            }
        }

        return bmp
    }

    /** Scale into params.boxPx and encode JPEG. Consumes [bitmap]. */
    fun encode(bitmap: Bitmap, params: ImageTransformParams): ByteArray {
        val scaled = scaleToFit(bitmap, params.boxPx)
        try {
            val out = ByteArrayOutputStream()
            check(scaled.compress(Bitmap.CompressFormat.JPEG, params.quality, out)) {
                "JPEG encode failed"
            }
            return out.toByteArray()
        } finally {
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
        }
    }

    private fun coarseMatrix(params: ImageTransformParams) = Matrix().apply {
        if (params.flipHorizontal) postScale(-1f, 1f)
        if (params.orientationDegrees != 0) postRotate(params.orientationDegrees.toFloat())
    }
}

/** Apply [step]; recycle the receiver if it was replaced. */
private inline fun Bitmap.replacedBy(step: (Bitmap) -> Bitmap): Bitmap {
    val next = step(this)
    if (next !== this) recycle()
    return next
}
