package de.perigon.companion.media.transform

import android.graphics.Bitmap
import androidx.core.graphics.scale

/**
 * Scale [bmp] so its long side is at most [maxPx]; returns [bmp] itself when
 * it already fits. Bilinear: inputs arrive pre-sampled by the decoder to
 * within 2x of the target, so a wider kernel buys nothing visible.
 *
 * EXIF orientation helpers used to live here. They are gone on purpose:
 * all decoding goes through media.domain.decodeUpright, which applies EXIF
 * itself, so there is nothing left to rotate.
 */
fun scaleToFit(bmp: Bitmap, maxPx: Int): Bitmap {
    if (bmp.width <= maxPx && bmp.height <= maxPx) return bmp
    val scale = maxPx.toFloat() / maxOf(bmp.width, bmp.height)
    return bmp.scale(
        (bmp.width * scale).toInt().coerceAtLeast(1),
        (bmp.height * scale).toInt().coerceAtLeast(1),
    )
}
