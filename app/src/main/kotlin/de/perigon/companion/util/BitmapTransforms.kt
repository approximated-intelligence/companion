package de.perigon.companion.util

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface

fun scaleToFit(bmp: Bitmap, maxPx: Int): Bitmap {
    if (bmp.width <= maxPx && bmp.height <= maxPx) return bmp
    val scale = maxPx.toFloat() / maxOf(bmp.width, bmp.height)
    return Bitmap.createScaledBitmap(
        bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true
    )
}

fun applyExifOrientation(bmp: Bitmap, orientation: Int): Bitmap {
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.postRotate(90f); matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.postRotate(-90f); matrix.postScale(-1f, 1f)
        }
        else -> return bmp
    }
    return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
}
