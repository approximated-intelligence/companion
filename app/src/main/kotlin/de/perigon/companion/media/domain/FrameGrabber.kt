package de.perigon.companion.media.domain

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import de.perigon.companion.util.media.ImageTransformParams
import de.perigon.companion.util.media.ImageTransformer
import java.io.File

private const val TAG = "FrameGrabber"

/**
 * Result of a frame extraction attempt. Top-level so `is GrabResult.Success` etc.
 * resolve cleanly from callers without fighting nested-in-object resolution.
 */
sealed class GrabResult {
    data class Success(val uri: Uri) : GrabResult()
    data class Failure(val reason: String, val cause: Throwable? = null) : GrabResult()
}

/**
 * Keyframe enumeration + single-frame extraction for videos.
 *
 * Keyframe enumeration uses MediaExtractor to walk the sample index — no
 * decoding, fast even on long videos. Frame extraction uses MediaMetadataRetriever
 * with OPTION_CLOSEST_SYNC to return the stored keyframe bitmap.
 *
 * Extracted frames are written as PNG to cache (lossless intermediate); the
 * transform pipeline will re-encode to JPEG later if/when the post is published.
 */
object FrameGrabber {

    /**
     * Enumerate keyframe timestamps (microseconds) for the first video track.
     * Returns an empty list if the URI has no video track or reading fails.
     */
    fun enumerateKeyframes(context: Context, uri: Uri): List<Long> {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = selectVideoTrack(extractor)
            if (trackIndex < 0) {
                Log.w(TAG, "enumerateKeyframes: no video track in $uri")
                return emptyList()
            }
            extractor.selectTrack(trackIndex)
            extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            val timestamps = mutableListOf<Long>()
            while (true) {
                val sampleTime = extractor.sampleTime
                if (sampleTime < 0) break
                val flags = extractor.sampleFlags
                if (flags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                    timestamps += sampleTime
                }
                if (!extractor.advance()) break
            }
            Log.d(TAG, "enumerateKeyframes: ${timestamps.size} keyframes in $uri")
            timestamps
        } catch (e: Exception) {
            Log.e(TAG, "enumerateKeyframes failed for $uri", e)
            emptyList()
        } finally {
            extractor.release()
        }
    }

    private fun selectVideoTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) return i
        }
        return -1
    }

    /**
     * Extract the keyframe at [timestampUs], apply [orientation] (coarse rotation
     * + horizontal flip) and [fineRotationDegrees] via [ImageTransformer.applyPipeline]
     * with identity crop, write as PNG into the app cache, return content URI.
     *
     * Errors are logged and returned as [GrabResult.Failure] so the UI can show them.
     */
    fun extractKeyframeToCache(
        context: Context,
        videoUri: Uri,
        timestampUs: Long,
        orientation: OrientationTransform,
        fineRotationDegrees: Float,
        filePrefix: String = "frame",
    ): GrabResult {
        val bitmap = try {
            extractKeyframeBitmap(context, videoUri, timestampUs)
        } catch (e: Exception) {
            Log.e(TAG, "extractKeyframeToCache: decode threw", e)
            return GrabResult.Failure("Decode failed: ${e.message}", e)
        }
        if (bitmap == null) {
            Log.w(TAG, "extractKeyframeToCache: decoder returned null at $timestampUs µs")
            return GrabResult.Failure("Decoder returned no frame at $timestampUs µs")
        }

        val transformed = try {
            val params = ImageTransformParams(
                orientationDegrees  = orientation.rotationDegrees,
                flipHorizontal      = orientation.flipHorizontal,
                fineRotationDegrees = fineRotationDegrees,
                cropLeft   = 0f,
                cropTop    = 0f,
                cropRight  = 1f,
                cropBottom = 1f,
                boxPx      = maxOf(bitmap.width, bitmap.height),
                quality    = 100,
            )
            ImageTransformer.applyPipeline(bitmap, ExifInterface.ORIENTATION_NORMAL, params)
        } catch (e: Exception) {
            Log.e(TAG, "extractKeyframeToCache: applyPipeline failed", e)
            bitmap.recycle()
            return GrabResult.Failure("Transform failed: ${e.message}", e)
        }

        val cacheDir = File(context.cacheDir, "frames").apply { mkdirs() }
        val file = File(cacheDir, "${filePrefix}_${System.currentTimeMillis()}_${timestampUs}.png")
        return try {
            file.outputStream().use { out ->
                transformed.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            if (transformed !== bitmap) bitmap.recycle()
            transformed.recycle()
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            Log.d(TAG, "extractKeyframeToCache: wrote ${file.length()} bytes → $uri")
            GrabResult.Success(uri)
        } catch (e: Exception) {
            Log.e(TAG, "extractKeyframeToCache: write/FileProvider failed for $file", e)
            GrabResult.Failure("Write failed: ${e.message}", e)
        }
    }

    /**
     * Decode the keyframe bitmap only (no transform, no write). Useful for preview.
     */
    fun extractKeyframeBitmap(context: Context, videoUri: Uri, timestampUs: Long): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, videoUri)
            retriever.getFrameAtTime(timestampUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (e: Exception) {
            Log.e(TAG, "extractKeyframeBitmap failed at $timestampUs µs for $videoUri", e)
            null
        } finally {
            retriever.release()
        }
    }
}
