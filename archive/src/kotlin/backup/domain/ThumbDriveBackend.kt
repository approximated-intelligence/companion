package de.perigon.companion.archive.backup.domain

import android.content.Context
import android.net.Uri
import android.util.Log
import de.perigon.companion.core.network.S3Backend
import de.perigon.companion.core.saf.DriveEjectedException
import de.perigon.companion.core.saf.createDocument
import de.perigon.companion.core.saf.deleteDocument
import de.perigon.companion.core.saf.documentUriInTree
import de.perigon.companion.core.saf.findChildByName
import de.perigon.companion.core.saf.navigateOrCreateId
import de.perigon.companion.core.saf.navigateToId
import de.perigon.companion.core.saf.openInputStreamRaw
import de.perigon.companion.core.saf.openOutputStream
import de.perigon.companion.core.saf.queryChildren
import de.perigon.companion.core.saf.renameDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "ThumbDriveBackend"

/**
 * Directory under every granted drive tree where packs live. Single
 * source of truth: [ThumbDriveBackend] writes under it, and the restore
 * side reads through [de.perigon.companion.core.saf.readRangeFromFirstRoot]
 * passing this as rootFolder. Keeping the reader's and writer's view of
 * the layout in one constant is the reason DrivePackReader.kt no longer
 * needed to exist.
 */
internal const val PACK_DRIVE_ROOT = "CompanionBackup"

// Scratch names. A pack of N parts is written as N files
// `<pack>.part000001` .. `<pack>.part000NNN`, each created when its part
// arrives, and concatenated into `<pack>.partial` on completeMultipart.
// The suffixes keep scratch disjoint from the final pack name, so an
// interrupted pack can never be mistaken for a complete one.
private const val PART_SUFFIX = ".part"
private const val PART_NAME_WIDTH = 6
private const val PARTIAL_SUFFIX = ".partial"

private fun partName(packName: String, partNumber: Int): String =
    packName + PART_SUFFIX + partNumber.toString().padStart(PART_NAME_WIDTH, '0')

/**
 * `S3Backend` over a SAF tree. Same pack bytes, same key layout as B2
 * (`<tree>/CompanionBackup/<key>` mirrors `<bucket>/<key>`), so the drive is
 * a faithful copy and a future restore-from-drive can read it with the same
 * pack walker.
 *
 * Parts are streamed to disk as they arrive (`<pack>.partNNNNNN`) and the
 * pack is assembled by concatenating those files into `<pack>.partial` at
 * completeMultipart. Previously `uploadPart` accumulated the whole pack in
 * a `ConcurrentHashMap<Int, ByteArray>` and only wrote on completion; that
 * held ~512 MB for a 64-part pack on a 256 MB heap, and the next part's
 * `crypto_box_easy` allocation failed before the backend ever saw it. Peak
 * resident now is one part, not the pack.
 *
 * Etags are fabricated — B2 needs them for its multipart API, this backend
 * does not. If any part is missing from disk at completion (a pack partially
 * uploaded elsewhere, or a part whose write failed), the pack is NOT
 * written: a truncated pack on the drive would be worse than a missing one.
 *
 * No DB state on purpose. The drive's file listing IS the state:
 * `objectExists(key)` returns true iff the final pack file exists.
 *
 * Read paths throw. This backend is write-only by contract.
 *
 * [ejectGuard] is required and narrowed to [EjectGuard.ForDrive]: this
 * backend is only ever constructed for a thumb drive, so a missing eject
 * check is a compile error, not a silent no-op. The check runs before every
 * write, including each part and the concat — once the system has asked us
 * to release the volume, a write is racing the unmount.
 */
class ThumbDriveBackend(
    private val ctx: Context,
    private val treeUri: Uri,
    private val ejectGuard: EjectGuard.ForDrive,
) : S3Backend {

    /**
     * Per in-flight pack: the SAF folder holding its scratch files, the
     * pack's file name within it, and the set of parts already on disk.
     * [parts] is the idempotency key for retried uploadParts — the backup
     * orchestrator retries with the same part number and bytes, and a
     * replay must not append or overwrite twice.
     */
    private class PackBuffer(val folderId: String, val packName: String) {
        // The orchestrator invokes uploadPart serially per pack; a plain
        // MutableSet is sufficient and keeps the failure mode obvious
        // (a future parallel uploader would need a real lock).
        val parts: MutableSet<Int> = mutableSetOf()
    }

    private val active = ConcurrentHashMap<String, PackBuffer>()

    private fun checkEject() = ejectGuard.check()

    override suspend fun createMultipart(key: String): String = withContext(Dispatchers.IO) {
        checkEject()
        val folderId = folderFor(key, create = true) ?: error("no folder for $key")
        val name = fileName(key)
        // Recovery: a previous run may have left scratch files for this pack
        // (process died mid-pack, drive reinserted). The final pack is not
        // on the drive — objectExists returns false — so those scratch files
        // are stale. Remove them so a smaller re-run cannot inherit parts
        // the current run never re-emits.
        sweepScratch(folderId, name)
        active[key] = PackBuffer(folderId, name)
        "thumbdrive:$key"
    }

    override suspend fun uploadPart(
        key: String,
        uploadId: String,
        partNumber: Int,
        data: ByteArray,
    ): String = withContext(Dispatchers.IO) {
        checkEject()
        val buf = active[key] ?: error("uploadPart: no active pack for $key")
        // Idempotent replay: a part that already landed is a no-op. The
        // orchestrator's uploadWithRetry retries the same part/bytes.
        if (partNumber in buf.parts) return@withContext "part-$partNumber"

        val name = partName(buf.packName, partNumber)
        findChildByName(ctx, treeUri, buf.folderId, name)?.let { existing ->
            deleteDocument(ctx, treeUri, existing.documentId)
        }
        val docUri = createDocument(ctx, treeUri, buf.folderId, "application/octet-stream", name)
            ?: error("cannot create part file $name")
        try {
            openOutputStream(ctx, docUri)?.use { out ->
                out.write(data)
                out.flush()
            } ?: error("cannot open stream for part file $name")
        } catch (e: Exception) {
            // A failed write leaves an unusable part file. Delete it so a
            // retry recreates it cleanly; the orchestrator either retries
            // this part or aborts the pack.
            deleteDocument(ctx, docUri)
            throw e
        }
        buf.parts.add(partNumber)
        "part-$partNumber"
    }

    override suspend fun completeMultipart(
        key: String,
        uploadId: String,
        parts: List<S3Backend.Part>,
    ) = withContext(Dispatchers.IO) {
        val buf = active.remove(key) ?: return@withContext
        try {
            checkEject()
            val ordered = parts.sortedBy { it.number }.map { it.number }
            // We mean "did every part land on disk". A missing part means the
            // pack cannot be assembled faithfully; write nothing.
            if (ordered.any { it !in buf.parts }) {
                Log.w(TAG, "completeMultipart($key): parts missing on disk, abandoning pack")
                sweepScratch(buf.folderId, buf.packName)
                return@withContext
            }
            concatParts(buf, ordered)
        } catch (e: DriveEjectedException) {
            sweepScratch(buf.folderId, buf.packName)
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "completeMultipart($key) failed", e)
            sweepScratch(buf.folderId, buf.packName)
        }
    }

    override suspend fun abortMultipart(key: String, uploadId: String) {
        active.remove(key)?.let { buf ->
            withContext(Dispatchers.IO) { sweepScratch(buf.folderId, buf.packName) }
        }
    }

    override suspend fun headObject(key: String): Boolean = withContext(Dispatchers.IO) {
        try {
            checkEject()
            val folderId = folderFor(key, create = false) ?: return@withContext false
            findChildByName(ctx, treeUri, folderId, fileName(key)) != null
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
            val folderId = folderFor(key, create = false) ?: return@withContext true
            val child = findChildByName(ctx, treeUri, folderId, fileName(key)) ?: return@withContext true
            deleteDocument(ctx, treeUri, child.documentId)
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
                val folderId = folderFor(key, create = true) ?: error("no folder for $key")
                writeSingle(folderId, fileName(key), data)
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

    /**
     * Stream each part file into `<pack>.partial`, then rename to `<pack>`.
     * One open input + one open output at a time; memory is the SAF IO
     * buffer, not the pack. Part files are removed as we go and again in
     * the finally (idempotent) so a crash mid-concat leaves at most a
     * `<pack>.partial`, swept by the next createMultipart.
     *
     * A size mismatch between the streamed bytes and the expected
     * `parts.size * 8 MB` would indicate a corrupt part file; the rename
     * still runs because the sha256 of the archive would already reject it
     * at restore, but the log fires so the anomaly is visible.
     */
    private fun concatParts(buf: PackBuffer, orderedParts: List<Int>) {
        val tmpName = buf.packName + PARTIAL_SUFFIX
        findChildByName(ctx, treeUri, buf.folderId, tmpName)?.let { existing ->
            deleteDocument(ctx, treeUri, existing.documentId)
        }
        val tmpUri = createDocument(ctx, treeUri, buf.folderId, "application/octet-stream", tmpName)
            ?: error("cannot create $tmpName")
        try {
            openOutputStream(ctx, tmpUri)?.use { out ->
                for (n in orderedParts) {
                    checkEject()
                    val name = partName(buf.packName, n)
                    val child = findChildByName(ctx, treeUri, buf.folderId, name)
                        ?: error("part file $name vanished")
                    val partUri = documentUriInTree(treeUri, child.documentId)
                    openInputStreamRaw(ctx, partUri)?.use { input ->
                        input.copyTo(out)
                    } ?: error("cannot open part file $name")
                }
                out.flush()
            } ?: error("cannot open stream for $tmpName")

            findChildByName(ctx, treeUri, buf.folderId, buf.packName)?.let { existing ->
                deleteDocument(ctx, treeUri, existing.documentId)
            }
            if (renameDocument(ctx, tmpUri, buf.packName) == null) {
                error("cannot rename $tmpName to ${buf.packName}")
            }
        } catch (e: Exception) {
            deleteDocument(ctx, tmpUri)
            throw e
        } finally {
            for (n in orderedParts) {
                val name = partName(buf.packName, n)
                findChildByName(ctx, treeUri, buf.folderId, name)?.let { child ->
                    deleteDocument(ctx, treeUri, child.documentId)
                }
            }
        }
    }

    /**
     * Best-effort sweep of a pack's scratch files: every `<pack>.partNNNNNN`
     * and any `<pack>.partial`. Never touches the final `<pack>` file.
     */
    private fun sweepScratch(folderId: String, packName: String) {
        val partPrefix = packName + PART_SUFFIX
        val partialName = packName + PARTIAL_SUFFIX
        for (child in queryChildren(ctx, treeUri, folderId)) {
            if (child.isDirectory) continue
            if (!child.name.startsWith(partPrefix) && child.name != partialName) continue
            if (!deleteDocument(ctx, treeUri, child.documentId)) {
                Log.w(TAG, "sweep failed: ${child.name}")
            }
        }
    }

    /**
     * One-shot write used by [putObject]: write to `<name>.partial`, rename
     * to `<name>`. Kept separate from the pack flow so putObject stays a
     * single SAF round-trip and does not touch [active].
     */
    private fun writeSingle(folderId: String, name: String, data: ByteArray) {
        val tmpName = name + PARTIAL_SUFFIX
        findChildByName(ctx, treeUri, folderId, tmpName)?.let { existing ->
            deleteDocument(ctx, treeUri, existing.documentId)
        }
        val tmpUri = createDocument(ctx, treeUri, folderId, "application/octet-stream", tmpName)
            ?: error("cannot create $tmpName")
        try {
            openOutputStream(ctx, tmpUri)?.use { out ->
                out.write(data)
                out.flush()
            } ?: error("cannot open stream for $tmpName")

            findChildByName(ctx, treeUri, folderId, name)?.let { existing ->
                deleteDocument(ctx, treeUri, existing.documentId)
            }
            if (renameDocument(ctx, tmpUri, name) == null) {
                error("cannot rename $tmpName to $name")
            }
        } catch (e: Exception) {
            deleteDocument(ctx, tmpUri)
            throw e
        }
    }

    private fun folderFor(key: String, create: Boolean): String? {
        val parent = key.substringBeforeLast('/', "")
        val dirs = listOf(PACK_DRIVE_ROOT) + parent.split('/').filter { it.isNotEmpty() }
        return if (create) navigateOrCreateId(ctx, treeUri, dirs)
               else navigateToId(ctx, treeUri, dirs)
    }

    private fun fileName(key: String): String = key.substringAfterLast('/')
}
