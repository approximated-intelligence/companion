package de.perigon.companion.media.domain

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Log

private const val TAG = "ImageDecoding"

/**
 * The one image decode path in the app — editor, previews, publish and
 * consolidation all come through here.
 *
 * ImageDecoder (not BitmapFactory) because it:
 *  - decodes every format the platform knows — HEIC/HEIF, AVIF, PNG, JPEG,
 *    WebP — where BitmapFactory silently fails on several of them,
 *  - samples during decode via setTargetSampleSize, so a 48MP source never
 *    materializes at full size,
 *  - APPLIES EXIF ORIENTATION ITSELF. Callers must NOT rotate again; a
 *    decoded bitmap is already upright, and its width/height are the upright
 *    ones.
 */
sealed interface ImageLoad {
    data class Loaded(val bitmap: Bitmap) : ImageLoad
    data class Failed(val message: String) : ImageLoad
}

/**
 * Decode [uri] upright, with its long side sampled down towards
 * [maxDimension] but never below it (power-of-two sampling; the caller
 * does the exact final scale).
 *
 * The result is a SOFTWARE bitmap: callers feed it to Bitmap.createBitmap
 * with a Matrix (rotation, crop), which rejects HARDWARE bitmaps.
 * Throws on any failure. Blocking — call from a background dispatcher.
 */
fun decodeUpright(resolver: ContentResolver, uri: Uri, maxDimension: Int): Bitmap {
    val source = ImageDecoder.createSource(resolver, uri)
    return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.isMutableRequired = false
        val longSide = maxOf(info.size.width, info.size.height)
        if (longSide > maxDimension) {
            decoder.setTargetSampleSize(sampleSizeFor(longSide, maxDimension))
        }
    }
}

/**
 * [decodeUpright] with failure as a value, for UI callers: a decode that
 * cannot happen must reach the user as a message, not as a view that waits
 * forever for a bitmap that will never arrive.
 */
fun decodeBounded(resolver: ContentResolver, uri: Uri, maxDimension: Int): ImageLoad = try {
    ImageLoad.Loaded(decodeUpright(resolver, uri, maxDimension))
} catch (e: Exception) {
    // Unreadable URI, unsupported/corrupt bytes, out of memory — all end up
    // here, and all must be reportable.
    Log.w(TAG, "decode failed: $uri", e)
    ImageLoad.Failed(e.message ?: e.javaClass.simpleName)
}

/** Smallest power-of-two sample size keeping [longSide] at or above [maxDimension]. */
internal fun sampleSizeFor(longSide: Int, maxDimension: Int): Int {
    if (longSide <= maxDimension || maxDimension <= 0) return 1
    var sampleSize = 1
    while (longSide / (sampleSize * 2) >= maxDimension) sampleSize *= 2
    return sampleSize
}
