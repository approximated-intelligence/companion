package de.perigon.companion.util.media

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Crop
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Video transform pipeline: trim → orientation → fine rotation → crop → scale → encode.
 * Wraps Media3 Transformer. Each effect is only added when not identity.
 *
 * Stateless — requires a Handler/Looper for Transformer execution.
 * Used by TransformQueue (post publish) and ConsolidateWorker (DCIM compression).
 */
data class VideoTransformParams(
    val orientationDegrees: Int = 0,
    val flipHorizontal: Boolean = false,
    val fineRotationDegrees: Float = 0f,
    val cropLeft: Float = 0f,
    val cropTop: Float = 0f,
    val cropRight: Float = 1f,
    val cropBottom: Float = 1f,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = Long.MAX_VALUE,
    val boxPx: Int = 640,
) {
    val hasCrop: Boolean
        get() = cropLeft != 0f || cropTop != 0f || cropRight != 1f || cropBottom != 1f

    val hasOrientation: Boolean
        get() = orientationDegrees != 0 || flipHorizontal

    val hasFineRotation: Boolean
        get() = fineRotationDegrees != 0f

    val hasTrim: Boolean
        get() = trimStartMs > 0L || trimEndMs != Long.MAX_VALUE

    val hasAnyTransform: Boolean
        get() = hasCrop || hasOrientation || hasFineRotation || hasTrim
}

object VideoTransformer {

    /**
     * Transform a video file. Writes result to [outFile].
     * Must be called from a coroutine — suspends until Transformer completes.
     *
     * [handler] provides the Looper for Media3 Transformer.
     */
    suspend fun transform(
        context: Context,
        sourceUri: Uri,
        outFile: File,
        params: VideoTransformParams,
        handler: Handler,
    ) {
        val mediaItem = buildMediaItem(sourceUri, params)
        val videoEffects = buildEffects(params)

        val editedItem = EditedMediaItem.Builder(mediaItem)
            .setEffects(Effects(emptyList(), videoEffects))
            .build()

        suspendCancellableCoroutine { cont ->
            handler.post {
                val transformer = Transformer.Builder(context)
                    .setLooper(handler.looper)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, result: ExportResult) =
                            cont.resume(Unit)
                        override fun onError(
                            composition: Composition,
                            result: ExportResult,
                            e: ExportException,
                        ) = cont.resumeWithException(e)
                    })
                    .build()
                transformer.start(editedItem, outFile.absolutePath)
                cont.invokeOnCancellation { handler.post { transformer.cancel() } }
            }
        }
    }

    /**
     * Simple scale-and-encode with no user transforms. Used for consolidation.
     */
    suspend fun scaleAndEncode(
        context: Context,
        sourceUri: Uri,
        outFile: File,
        boxPx: Int = 640,
        handler: Handler,
    ) = transform(
        context = context,
        sourceUri = sourceUri,
        outFile = outFile,
        params = VideoTransformParams(boxPx = boxPx),
        handler = handler,
    )

    private fun buildMediaItem(sourceUri: Uri, params: VideoTransformParams): MediaItem {
        val builder = MediaItem.Builder().setUri(sourceUri)

        if (params.hasTrim) {
            builder.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(params.trimStartMs)
                    .apply { if (params.trimEndMs != Long.MAX_VALUE) setEndPositionMs(params.trimEndMs) }
                    .build()
            )
        }

        return builder.build()
    }

    private fun buildEffects(params: VideoTransformParams): List<androidx.media3.common.Effect> {
        val effects = mutableListOf<androidx.media3.common.Effect>()

        // Coarse orientation (90° steps + flip)
        if (params.hasOrientation) {
            val builder = ScaleAndRotateTransformation.Builder()
            if (params.orientationDegrees != 0) {
                builder.setRotationDegrees(-params.orientationDegrees.toFloat())
            }
            if (params.flipHorizontal) {
                builder.setScale(-1f, 1f)
            }
            effects += builder.build()
        }

        // Fine rotation
        if (params.hasFineRotation) {
            effects += ScaleAndRotateTransformation.Builder()
                .setRotationDegrees(-params.fineRotationDegrees)
                .build()
        }

        // Crop — Media3 coordinates: X [-1,+1] left-to-right, Y [-1,+1] bottom-to-top
        if (params.hasCrop) {
            val cropLeftM3   =  2f * params.cropLeft - 1f
            val cropRightM3  =  2f * params.cropRight - 1f
            val cropTopM3    =  1f - 2f * params.cropTop
            val cropBottomM3 =  1f - 2f * params.cropBottom
            effects += Crop(cropLeftM3, cropRightM3, cropBottomM3, cropTopM3)
        }

        // Scale to fit box
        effects += Presentation.createForWidthAndHeight(
            params.boxPx, params.boxPx, Presentation.LAYOUT_SCALE_TO_FIT
        )

        return effects
    }
}
