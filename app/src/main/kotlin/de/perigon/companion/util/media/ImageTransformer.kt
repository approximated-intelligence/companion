package de.perigon.companion.util.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import de.perigon.companion.util.applyExifOrientation
import de.perigon.companion.util.scaleToFit
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Pure image transform pipeline: decode → EXIF → orientation → fine rotation → crop → scale → compress.
 * No state, no Android context dependency beyond InputStream.
 *
 * Used by TransformQueue (post publish transforms) and ConsolidateWorker
 * (DCIM compression). Caller provides the source stream and parameters.
 */
data class ImageTransformParams(
    val orientationDegrees: Int = 0,
    val flipHorizontal: Boolean = false,
    val fineRotationDegrees: Float = 0f,
    val cropLeft: Float = 0f,
    val cropTop: Float = 0f,
    val cropRight: Float = 1f,
    val cropBottom: Float = 1f,
    val boxPx: Int = 1080,
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

object ImageTransformer {

    /**
     * Full transform pipeline. Reads EXIF from [exifStream], decodes pixels
     * from [pixelStream], applies transforms, returns JPEG bytes.
     *
     * Two separate streams because EXIF reading consumes the stream.
     * Caller is responsible for closing streams.
     */
    fun transform(
        exifStream: InputStream,
        pixelStream: InputStream,
        params: ImageTransformParams,
    ): ByteArray {
        val exifOrientation = ExifInterface(exifStream).getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
        )
        return transformWithExif(pixelStream, exifOrientation, params)
    }

    /**
     * Transform with a known EXIF orientation value.
     * Useful when the caller has already read EXIF separately.
     */
    fun transformWithExif(
        pixelStream: InputStream,
        exifOrientation: Int,
        params: ImageTransformParams,
    ): ByteArray {
        var bmp = BitmapFactory.decodeStream(pixelStream)
            ?: error("Failed to decode image")

        bmp = applyPipeline(bmp, exifOrientation, params)

        val scaled = scaleToFit(bmp, params.boxPx)
        if (scaled !== bmp) bmp.recycle()

        val bytes = ByteArrayOutputStream()
            .also { scaled.compress(Bitmap.CompressFormat.JPEG, params.quality, it) }
            .toByteArray()
        scaled.recycle()

        return bytes
    }

    /**
     * Apply the transform pipeline to an already-decoded bitmap.
     * Returns a new bitmap (or the same instance if no transforms needed).
     * Caller must manage recycling of the input if the output differs.
     */
    fun applyPipeline(
        source: Bitmap,
        exifOrientation: Int,
        params: ImageTransformParams,
    ): Bitmap {
        var bmp = source

        // 1. EXIF orientation
        val exifApplied = applyExifOrientation(bmp, exifOrientation)
        if (exifApplied !== bmp) { bmp.recycle(); bmp = exifApplied }

        // 2. Coarse orientation (90° steps + flip)
        if (params.hasOrientation) {
            val m = Matrix()
            if (params.flipHorizontal) m.postScale(-1f, 1f)
            if (params.orientationDegrees != 0) m.postRotate(params.orientationDegrees.toFloat())
            val oriented = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (oriented !== bmp) { bmp.recycle(); bmp = oriented }
        }

        // 3. Fine rotation
        if (params.hasFineRotation) {
            val m = Matrix()
            m.postRotate(params.fineRotationDegrees)
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (rotated !== bmp) { bmp.recycle(); bmp = rotated }
        }

        // 4. Crop
        if (params.hasCrop) {
            val x = (params.cropLeft * bmp.width).toInt().coerceIn(0, bmp.width - 1)
            val y = (params.cropTop * bmp.height).toInt().coerceIn(0, bmp.height - 1)
            val w = ((params.cropRight - params.cropLeft) * bmp.width).toInt()
                .coerceIn(1, bmp.width - x)
            val h = ((params.cropBottom - params.cropTop) * bmp.height).toInt()
                .coerceIn(1, bmp.height - y)
            val cropped = Bitmap.createBitmap(bmp, x, y, w, h)
            if (cropped !== bmp) { bmp.recycle(); bmp = cropped }
        }

        return bmp
    }

    /**
     * Simple scale-and-compress with no transforms. Used for consolidation
     * where the source is already correctly oriented.
     */
    fun scaleAndCompress(
        pixelStream: InputStream,
        boxPx: Int = 1080,
        quality: Int = 85,
    ): ByteArray {
        val exifOrientation = ExifInterface(pixelStream).getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
        )
        // Need a fresh stream for decoding — caller must handle this.
        // This overload exists for the case where caller can provide two streams.
        error("Use the two-stream overload for scaleAndCompress")
    }

    /**
     * Scale and compress from two streams (EXIF + pixels).
     * Applies only EXIF correction, scale, and compression — no user transforms.
     */
    fun scaleAndCompress(
        exifStream: InputStream,
        pixelStream: InputStream,
        boxPx: Int = 1080,
        quality: Int = 85,
    ): ByteArray = transform(
        exifStream = exifStream,
        pixelStream = pixelStream,
        params = ImageTransformParams(boxPx = boxPx, quality = quality),
    )
}
