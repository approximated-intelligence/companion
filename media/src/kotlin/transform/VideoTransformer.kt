package de.perigon.companion.media.transform

import android.content.Context
import android.net.Uri
import android.os.Handler
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Crop
import androidx.media3.effect.LanczosResample
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max

/**
 * Video output codec. FORMAT fixes the encoder: H264 → platform H.264
 * encoder (default), AV1 → AV1 encoder with the AV1 bitrate ladder.
 * Bitrate is content-adaptive-sized by output long-side; matches the
 * python-tools -crf 20 (H.264) / -crf 30 (AV1) starting points.
 */
enum class VideoCodec {
    H264, AV1;

    fun bitrateFor(longSidePx: Int): Int = when (this) {
        H264 -> when {
            longSidePx <= 480 ->   800_000
            longSidePx <= 720 -> 1_500_000
            else              -> 2_500_000
        }
        AV1 -> when {
            longSidePx <= 480 ->   400_000
            longSidePx <= 720 ->   800_000
            else              -> 1_500_000
        }
    }
}

/**
 * Video transform pipeline: trim → orientation → fine rotation → crop → scale → encode.
 * Wraps Media3 Transformer. Each effect is only added when not identity.
 *
 * Stateless — requires a Handler/Looper for Transformer execution.
 * Used by TransformQueue, which serves both post publish and consolidation.
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
    /** Source dimensions — used to compute scale so the longer side is capped at boxPx. */
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    val videoCodec: VideoCodec = VideoCodec.H264,
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
     *
     * Output is scaled so the longer side is at most boxPx, preserving aspect
     * ratio — no black bars. If source dimensions are unknown (0,0), no
     * Presentation effect is added and source resolution passes through.
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
                val builder = Transformer.Builder(context)
                    .setLooper(handler.looper)
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

                when (params.videoCodec) {
                    VideoCodec.H264 -> {
                        builder.setVideoMimeType(MimeTypes.VIDEO_H264)
                    }
                    VideoCodec.AV1 -> {
                        // MIME type goes on the Transformer, not on the
                        // encoder settings; DefaultEncoderFactory picks the
                        // encoder that matches this format.
                        builder.setVideoMimeType(MimeTypes.VIDEO_AV1)
                        val longSide = max(params.sourceWidth, params.sourceHeight).coerceAtLeast(1)
                        val settings = VideoEncoderSettings.Builder()
                            .setBitrate(params.videoCodec.bitrateFor(longSide))
                            .build()
                        val factory = DefaultEncoderFactory.Builder(context)
                            .setRequestedVideoEncoderSettings(settings)
                            .build()
                        builder.setEncoderFactory(factory)
                    }
                }

                val transformer = builder.build()
                transformer.start(editedItem, outFile.absolutePath)
                cont.invokeOnCancellation { handler.post { transformer.cancel() } }
            }
        }
    }

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

        // Scale: constrain the longer side to boxPx, preserve aspect ratio, no black bars.
        // Only added when source dimensions are known AND downscaling is needed.
        val w = params.sourceWidth
        val h = params.sourceHeight
        if (w > 0 && h > 0) {
            val longerSide = max(w, h)
            if (longerSide > params.boxPx) {
                val scale = params.boxPx.toFloat() / longerSide
                // Ensure even dimensions required by H.264
                val outW = (w * scale).toInt().let { if (it % 2 != 0) it + 1 else it }.coerceAtLeast(2)
                val outH = (h * scale).toInt().let { if (it % 2 != 0) it + 1 else it }.coerceAtLeast(2)
                // Lanczos-windowed sinc: same kernel family python-tools uses
                // via ffmpeg scale=lanczos. Presentation's default is bilinear,
                // which aliases on sharp edges at large downscale ratios.
                // effects += Presentation.createForWidthAndHeight(
                //    outW, outH, Presentation.LAYOUT_SCALE_TO_FIT
                // )
                effects += LanczosResample.scaleToFit(outW, outH)
            }
        }

        return effects
    }
}
