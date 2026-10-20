package de.perigon.companion.core.saf

import android.content.Context
import androidx.core.net.toUri
import android.util.Log
import java.io.File

/**
 * Open-and-close probe: can [uriStr] be read right now? Never reads content,
 * so it is safe on multi-GB videos. The one copy of the probe previously
 * repeated in MediaPrep, PublishWorker, Consolidate and PostMediaFileStore.
 */
fun isReadable(ctx: Context, uriStr: String): Boolean {
    if (uriStr.isBlank()) return false
    val uri = uriStr.toUri()
    return try {
        when (uri.scheme) {
            "file" -> uri.path?.let { File(it).canRead() } ?: false
            else -> ctx.contentResolver.openInputStream(uri)?.use { true } ?: false
        }
    } catch (e: Exception) {
        Log.d("Readable", "not readable: $uriStr (${e.javaClass.simpleName})")
        false
    }
}
