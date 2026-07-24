package de.perigon.companion.media.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import de.perigon.companion.posts.data.PostMediaEntity
import de.perigon.companion.util.media.ImageTransformParams
import de.perigon.companion.util.media.ImageTransformer
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Generates preview thumbnails for media items with transform intents.
 * Delegates to [ImageTransformer.applyPipeline] for the actual transform,
 * then scales the result down to preview size.
 *
 * No state, no side effects beyond reading the source file.
 */
@Singleton
class TransformPreviewGenerator @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    companion object {
        private const val DEFAULT_PREVIEW_SIZE_PX = 256
    }

    /**
     * Generate a preview bitmap for a media entity with transforms applied.
     * Returns null if the transform is identity or generation fails.
     * Must be called on a background thread.
     */
    fun generate(entity: PostMediaEntity, previewSizePx: Int = DEFAULT_PREVIEW_SIZE_PX): Bitmap? {
        val intent = entity.toTransformIntent()
        if (intent.isIdentity) return null
        return if (entity.mimeType.startsWith("video")) {
            generateVideoPreview(entity, intent, previewSizePx)
        } else {
            generateImagePreview(entity, intent, previewSizePx)
        }
    }

    /**
     * Generate preview for an image using [ImageTransformer.applyPipeline].
     * Subsamples on decode to avoid loading full-resolution bitmaps for previews.
     */
    private fun generateImagePreview(
        entity: PostMediaEntity,
        intent: TransformIntent,
        previewSizePx: Int,
    ): Bitmap? {
        val uriStr = entity.sourceUri.ifEmpty { entity.mediaStoreUri }
        if (uriStr.isEmpty()) return null

        return try {
            val uri = Uri.parse(uriStr)

            // Read EXIF
            val exifOrientation = context.contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL

            // Decode subsampled
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
            val srcW = opts.outWidth
            val srcH = opts.outHeight
            if (srcW <= 0 || srcH <= 0) return null

            val maxDim = maxOf(srcW, srcH)
            var sampleSize = 1
            while (maxDim / sampleSize > previewSizePx * 2) sampleSize *= 2

            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            var bmp = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOpts)
            } ?: return null

            // Apply full transform pipeline via ImageTransformer
            val params = ImageTransformParams(
                orientationDegrees = intent.orientation.rotationDegrees,
                flipHorizontal = intent.orientation.flipHorizontal,
                fineRotationDegrees = intent.fineRotationDegrees,
                cropLeft = intent.cropRect.left,
                cropTop = intent.cropRect.top,
                cropRight = intent.cropRect.right,
                cropBottom = intent.cropRect.bottom,
            )
            val transformed = ImageTransformer.applyPipeline(bmp, exifOrientation, params)
            if (transformed !== bmp) bmp.recycle()
            bmp = transformed

            // Scale to preview size
            scaleToPreview(bmp, previewSizePx)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Generate preview for a video: extract a frame, apply transforms via
     * [ImageTransformer.applyPipeline], scale to preview size.
     */
    private fun generateVideoPreview(
        entity: PostMediaEntity,
        intent: TransformIntent,
        previewSizePx: Int,
    ): Bitmap? {
        val uriStr = entity.sourceUri.ifEmpty { entity.mediaStoreUri }
        if (uriStr.isEmpty()) return null

        return try {
            val uri = Uri.parse(uriStr)
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val timeUs = if (intent.trimStartMs > 0L) intent.trimStartMs * 1000L else 0L
                var bmp = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: return null

                // Video frames have no EXIF — pass ORIENTATION_NORMAL
                val params = ImageTransformParams(
                    orientationDegrees = intent.orientation.rotationDegrees,
                    flipHorizontal = intent.orientation.flipHorizontal,
                    fineRotationDegrees = intent.fineRotationDegrees,
                    cropLeft = intent.cropRect.left,
                    cropTop = intent.cropRect.top,
                    cropRight = intent.cropRect.right,
                    cropBottom = intent.cropRect.bottom,
                )
                val transformed = ImageTransformer.applyPipeline(
                    bmp, ExifInterface.ORIENTATION_NORMAL, params
                )
                if (transformed !== bmp) bmp.recycle()
                bmp = transformed

                scaleToPreview(bmp, previewSizePx)
            } finally {
                retriever.release()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun scaleToPreview(bmp: Bitmap, previewSizePx: Int): Bitmap {
        val scale = previewSizePx.toFloat() / maxOf(bmp.width, bmp.height)
        if (scale >= 1f) return bmp
        val scaled = Bitmap.createScaledBitmap(
            bmp,
            (bmp.width * scale).toInt().coerceAtLeast(1),
            (bmp.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== bmp) bmp.recycle()
        return scaled
    }
}
