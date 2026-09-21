package de.perigon.companion.media.domain

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import de.perigon.companion.media.transform.ImageTransformParams
import de.perigon.companion.media.transform.ImageTransformer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Preview thumbnails of a source with a transform intent applied.
 *
 * Knows nothing about posts: callers pass the source and the intent. Decoding
 * goes through [decodeBounded], the same path the editor and the pipeline
 * use, so the pipeline receives upright pixels.
 */
@Singleton
class TransformPreviewGenerator @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    companion object {
        private const val TAG = "TransformPreview"
        private const val DEFAULT_PREVIEW_SIZE_PX = 256
    }

    /** Null for identity intents, blank sources, or failure. Background thread only. */
    fun generate(
        sourceUri: String,
        isVideo: Boolean,
        intent: TransformIntent,
        previewSizePx: Int = DEFAULT_PREVIEW_SIZE_PX,
    ): Bitmap? {
        if (intent.isIdentity || sourceUri.isBlank()) return null
        val uri = Uri.parse(sourceUri)
        return if (isVideo) videoPreview(uri, intent, previewSizePx)
               else imagePreview(uri, intent, previewSizePx)
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

    private fun imagePreview(uri: Uri, intent: TransformIntent, previewSizePx: Int): Bitmap? {
        // Decode straight to roughly preview scale.
        val decoded = when (val load = decodeBounded(context.contentResolver, uri, previewSizePx * 2)) {
            is ImageLoad.Failed -> {
                Log.w(TAG, "preview decode failed for $uri: ${load.message}")
                return null
            }
            is ImageLoad.Loaded -> load.bitmap
        }
        return try {
            // applyPipeline consumes `decoded`.
            scaleToPreview(ImageTransformer.applyPipeline(decoded, paramsOf(intent)), previewSizePx)
        } catch (e: Exception) {
            Log.w(TAG, "preview transform failed for $uri", e)
            decoded.recycle()
            null
        }
    }

    private fun videoPreview(uri: Uri, intent: TransformIntent, previewSizePx: Int): Bitmap? = try {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val timeUs = if (intent.trimStartMs > 0L) intent.trimStartMs * 1000L else 0L
            val frame = retriever.getScaledFrameAtTime(
                timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                previewSizePx * 2, previewSizePx * 2,
            )
            frame?.let { scaleToPreview(ImageTransformer.applyPipeline(it, paramsOf(intent)), previewSizePx) }
        } finally {
            retriever.release()
        }
    } catch (e: Exception) {
        Log.w(TAG, "video preview failed for $uri", e)
        null
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
