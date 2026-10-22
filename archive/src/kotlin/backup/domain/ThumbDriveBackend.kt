package de.perigon.companion.archive.backup.domain

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import de.perigon.companion.core.network.S3Backend
import de.perigon.companion.core.saf.DriveEjectedException
import de.perigon.companion.core.saf.navigateOrCreate
import de.perigon.companion.core.saf.navigateTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "ThumbDriveBackend"
private const val ROOT_FOLDER = "CompanionBackup"

/**
 * `S3Backend` over a SAF tree. Same pack bytes, same key layout as B2
 * (`<tree>/CompanionBackup/<key>` mirrors `<bucket>/<key>`), so the drive is
 * a faithful copy and a future restore-from-drive can read it with the same
 * pack walker.
 *
 * Parts are buffered in memory per pack and concatenated into one file on
 * `completeMultipart`. Etags are fabricated — B2 needs them for its
 * multipart API, this backend does not. If any part is missing from the
 * buffer (a pack partially uploaded elsewhere), the pack is NOT written:
 * a truncated pack on the drive would be worse than a missing one.
 *
 * No DB state on purpose. The drive's file listing IS the state:
 * `objectExists(key)` returns true iff the pack is on the drive.
 *
 * Read paths throw. This backend is write-only by contract.
 *
 * [ejectGuard] is required and narrowed to [EjectGuard.ForDrive]: this
 * backend is only ever constructed for a thumb drive, so a missing eject
 * check is a compile error, not a silent no-op. The check runs before every
 * `writePack` — once the system has asked us to release the volume, a write
 * is racing the unmount.
 */
class ThumbDriveBackend(
    private val ctx: Context,
    private val treeUri: Uri,
    private val ejectGuard: EjectGuard.ForDrive,
) : S3Backend {

    private val buffers = ConcurrentHashMap<String, ConcurrentHashMap<Int, ByteArray>>()

    private fun checkEject() = ejectGuard.check()

    override suspend fun createMultipart(key: String): String = withContext(Dispatchers.IO) {
        checkEject()
        buffers[key] = ConcurrentHashMap()
        "thumbdrive:$key"
    }

    override suspend fun uploadPart(
        key: String,
        uploadId: String,
        partNumber: Int,
        data: ByteArray,
    ): String = withContext(Dispatchers.IO) {
        checkEject()
        buffers.getOrPut(key) { ConcurrentHashMap() }[partNumber] = data
        "part-$partNumber"
    }

    override suspend fun completeMultipart(
        key: String,
        uploadId: String,
        parts: List<S3Backend.Part>,
    ) = withContext(Dispatchers.IO) {
        val buf = buffers.remove(key) ?: return@withContext
        try {
            val ordered = parts.sortedBy { it.number }.map { it.number }
            // Explicit containsKey — `in` on ConcurrentHashMap resolves to
            // containsValue (KT-18053), which is not what we mean here.
            if (ordered.any { !buf.containsKey(it) }) {
                Log.w(TAG, "completeMultipart($key): parts missing from buffer, skipping")
                return@withContext
            }
            checkEject()
            writePack(key, ordered.map { buf.getValue(it) })
        } catch (e: DriveEjectedException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "completeMultipart($key) failed", e)
        }
    }

    override suspend fun abortMultipart(key: String, uploadId: String) {
        buffers.remove(key)
    }

    override suspend fun headObject(key: String): Boolean = withContext(Dispatchers.IO) {
        try {
            checkEject()
            folderFor(key, create = false)?.findFile(fileName(key)) != null
        } catch (e: DriveEjectedException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "headObject($key) failed", e)
            false
        }
    }

    override suspend fun deleteMedia(key: String): Boolean = withContext(Dispatchers.IO) {
        try {
            checkEject()
            folderFor(key, create = false)?.findFile(fileName(key))?.delete() ?: true
        } catch (e: DriveEjectedException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "deleteMedia($key) failed", e)
            false
        }
    }

    override suspend fun putObject(key: String, data: ByteArray, contentType: String?) {
        withContext(Dispatchers.IO) {
            try {
                checkEject()
                writePack(key, listOf(data))
            } catch (e: DriveEjectedException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "putObject($key) failed", e)
            }
        }
    }

    override suspend fun listParts(key: String, uploadId: String): List<S3Backend.Part> =
        throw UnsupportedOperationException("ThumbDriveBackend is write-only")

    override suspend fun listObjects(prefix: String, maxKeys: Int): List<String> =
        throw UnsupportedOperationException("ThumbDriveBackend is write-only")

    override suspend fun getRange(key: String, from: Long, to: Long): ByteArray =
        throw UnsupportedOperationException("ThumbDriveBackend is write-only")

    override suspend fun getRangeOrNull(key: String, from: Long, to: Long): ByteArray? =
        throw UnsupportedOperationException("ThumbDriveBackend is write-only")

    private fun writePack(key: String, chunks: List<ByteArray>) {
        checkEject()
        val folder = folderFor(key, create = true) ?: error("no folder for $key")
        val name = fileName(key)
        val tmpName = "$name.partial"

        folder.findFile(tmpName)?.delete()
        val tmp = folder.createFile("application/octet-stream", tmpName)
            ?: error("cannot create $tmpName")
        try {
            ctx.contentResolver.openOutputStream(tmp.uri)?.use { out ->
                for (chunk in chunks) out.write(chunk)
                out.flush()
            } ?: error("cannot open stream for $tmpName")

            folder.findFile(name)?.delete()
            if (!tmp.renameTo(name)) error("cannot rename $tmpName to $name")
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    private fun folderFor(key: String, create: Boolean): DocumentFile? {
        val parent = key.substringBeforeLast('/', "")
        val dirs = listOf(ROOT_FOLDER) + parent.split('/').filter { it.isNotEmpty() }
        return if (create) navigateOrCreate(ctx, treeUri, dirs)
               else navigateTo(ctx, treeUri, dirs)
    }

    private fun fileName(key: String): String = key.substringAfterLast('/')
}
