package de.perigon.companion.media.transform

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.heifwriter.AvifWriter
import de.perigon.companion.media.transform.scaleToFit
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeoutException
import kotlin.math.ceil

private const val TAG = "ImageTransformer"

/**
 * Still output formats. FORMAT fixes the encoder: JPEG → Bitmap.compress
 * (JPEG), PNG → Bitmap.compress (PNG), AVIF → AvifWriter. This is the
 * media-side twin of the user-facing OutputFormat in backup/; it exists
 * so media has no dependency on backup.
 */
enum class ImageOutputFormat { JPEG, PNG, AVIF }

/**
 * Image transform pipeline over UPRIGHT pixels:
 * orientation → fine rotation → crop → scale → encode.
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
    val outputFormat: ImageOutputFormat = ImageOutputFormat.JPEG,
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

    /** Transform, scale into the box, encode in [ImageTransformParams.outputFormat]. Consumes [source]. */
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

    /** Scale into params.boxPx and encode. Consumes [bitmap]. */
    fun encode(bitmap: Bitmap, params: ImageTransformParams): ByteArray {
        val scaled = scaleToFit(bitmap, params.boxPx)
        try {
            return when (params.outputFormat) {
                ImageOutputFormat.JPEG -> {
                    val out = ByteArrayOutputStream()
                    check(scaled.compress(Bitmap.CompressFormat.JPEG, params.quality, out)) {
                        "JPEG encode failed"
                    }
                    out.toByteArray()
                }
                ImageOutputFormat.PNG -> {
                    val out = ByteArrayOutputStream()
                    check(scaled.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                        "PNG encode failed"
                    }
                    out.toByteArray()
                }
                ImageOutputFormat.AVIF -> encodeAvif(scaled, params.quality)
            }
        } finally {
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
        }
    }

    /**
     * heifwriter 1.1.0 exposes only the FileDescriptor constructor of
     * AvifWriter.Builder; the String-path constructor arrives in 1.2.0.
     * Open a ParcelFileDescriptor on a temp file and pass its fd.
     *
     * stop() takes a timeout in MICROSECONDS (despite being named
     * timeoutMs in some docs) and may throw TimeoutException when the
     * writer did not finish; the docs warn the output may not be valid
     * in that case, so the temp file is deleted on any failure.
     */
    private fun encodeAvif(bitmap: Bitmap, quality: Int): ByteArray {
        val tempFile = File.createTempFile("avif_", ".avif")
        var writer: AvifWriter? = null
        try {
            val pfd = ParcelFileDescriptor.open(
                tempFile,
                ParcelFileDescriptor.MODE_READ_WRITE or
                    ParcelFileDescriptor.MODE_CREATE or
                    ParcelFileDescriptor.MODE_TRUNCATE,
            )
            try {
                writer = AvifWriter.Builder(
                    pfd.fileDescriptor,
                    bitmap.width,
                    bitmap.height,
                    AvifWriter.INPUT_MODE_BITMAP,
                )
                    .setQuality(quality.coerceIn(0, 100))
                    .build()

                writer.start()
                writer.addBitmap(bitmap)
                writer.stop(10_000_000L) // 10 seconds, in microseconds
            } finally {
                // close() on a writer that just failed stop() may also throw;
                // the temp file is deleted unconditionally below.
                try { writer?.close() } catch (_: Exception) {}
                try { pfd.close() } catch (_: Exception) {}
            }
            return tempFile.readBytes()
        } catch (e: TimeoutException) {
            Log.w(TAG, "AVIF encode timed out after 10 s", e)
            throw e
        } finally {
            tempFile.delete()
        }
    }

    private fun coarseMatrix(params: ImageTransformParams) = Matrix().apply {
        if (params.flipHorizontal) postScale(-1f, 1f)
        if (params.orientationDegrees != 0) postRotate(params.orientationDegrees.toFloat())
    }
}

private inline fun Bitmap.replacedBy(step: (Bitmap) -> Bitmap): Bitmap {
    val next = step(this)
    if (next !== this) recycle()
    return next
}
