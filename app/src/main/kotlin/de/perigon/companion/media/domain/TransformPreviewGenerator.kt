package de.perigon.companion.media.domain

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import de.perigon.companion.posts.data.PostMediaEntity
import de.perigon.companion.util.media.ImageTransformParams
import de.perigon.companion.util.media.ImageTransformer
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Generates preview thumbnails for media items with transform intents.
 *
 * Decoding goes through [decodeBounded], the same path the editor uses — one
 * decoder, one set of format support, one failure mode. Because that decoder
 * applies EXIF itself, the pipeline below is told ORIENTATION_NORMAL: the
 * bitmap it receives is already upright.
 *
 * No state, no side effects beyond reading the source file.
 */
@Singleton
class TransformPreviewGenerator @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    companion object {
        private const val TAG = "TransformPreview"
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

    private fun paramsOf(intent: TransformIntent) = ImageTransformParams(
        orientationDegrees = intent.orientation.rotationDegrees,
        flipHorizontal = intent.orientation.flipHorizontal,
        fineRotationDegrees = intent.fineRotationDegrees,
        cropLeft = intent.cropRect.left,
        cropTop = intent.cropRect.top,
        cropRight = intent.cropRect.right,
        cropBottom = intent.cropRect.bottom,
    )

    private fun generateImagePreview(
        entity: PostMediaEntity,
        intent: TransformIntent,
        previewSizePx: Int,
    ): Bitmap? {
        val uriStr = entity.sourceUri.ifEmpty { entity.mediaStoreUri }
        if (uriStr.isEmpty()) return null

        // Decode straight to roughly preview scale: the sampler picks the
        // smallest power of two that still covers the target.
        val load = decodeBounded(context.contentResolver, Uri.parse(uriStr), previewSizePx * 2)
        val decoded = when (load) {
            is ImageLoad.Failed -> {
                Log.w(TAG, "preview decode failed for media ${entity.id}: ${load.message}")
                return null
            }
            is ImageLoad.Loaded -> load.bitmap
        }

        return try {
            // ORIENTATION_NORMAL: decodeBounded already applied EXIF.
            val transformed = ImageTransformer.applyPipeline(
                decoded, ExifInterface.ORIENTATION_NORMAL, paramsOf(intent)
            )
            if (transformed !== decoded) decoded.recycle()
            scaleToPreview(transformed, previewSizePx)
        } catch (e: Exception) {
            Log.w(TAG, "preview transform failed for media ${entity.id}", e)
            decoded.recycle()
            null
        }
    }

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
                // Scaled extraction — a full-resolution frame only to shrink it
                // to 256px is pure waste.
                var bmp = retriever.getScaledFrameAtTime(
                    timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    previewSizePx * 2, previewSizePx * 2,
                ) ?: return null

                // Video frames have no EXIF — pass ORIENTATION_NORMAL
                val transformed = ImageTransformer.applyPipeline(
                    bmp, ExifInterface.ORIENTATION_NORMAL, paramsOf(intent)
                )
                if (transformed !== bmp) bmp.recycle()
                bmp = transformed

                scaleToPreview(bmp, previewSizePx)
            } finally {
                retriever.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "video preview failed for media ${entity.id}", e)
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
