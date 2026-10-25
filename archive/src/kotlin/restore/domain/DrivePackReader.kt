package de.perigon.companion.archive.restore.domain

import android.content.Context
import android.net.Uri
import android.util.Log
import de.perigon.companion.core.saf.DriveInfo
import de.perigon.companion.core.saf.navigateTo
import java.io.FileInputStream
import java.nio.ByteBuffer

private const val TAG = "DrivePackReader"
private const val ROOT_FOLDER = "CompanionBackup"

suspend fun readPackRangeFromDrives(
    context: Context,
    drives: List<DriveInfo>,
    key: String,
    from: Long,
    to: Long,
): ByteArray? {
    if (drives.isEmpty() || to < from) return null
    val parent = key.substringBeforeLast('/', "")
    val name = key.substringAfterLast('/')
    val subfolders = listOf(ROOT_FOLDER) + parent.split('/').filter { it.isNotEmpty() }
    for (drive in drives) {
        val folder = try { navigateTo(context, drive.treeUri, subfolders) }
                     catch (_: Exception) { null } ?: continue
        val file = try { folder.findFile(name) }
                   catch (_: Exception) { null } ?: continue
        readRange(context, file.uri, from, to)?.let { return it }
    }
    return null
}

private fun readRange(context: Context, uri: Uri, from: Long, to: Long): ByteArray? {
    val len = (to - from + 1).toInt()
    if (len <= 0) return null
    return try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            FileInputStream(pfd.fileDescriptor).use { fis ->
                val ch = fis.channel
                ch.position(from)
                val buf = ByteBuffer.allocate(len)
                while (buf.hasRemaining()) if (ch.read(buf) <= 0) break
                if (buf.hasRemaining()) null else buf.array()
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "range read failed for $uri [$from..$to]", e)
        null
    }
}
