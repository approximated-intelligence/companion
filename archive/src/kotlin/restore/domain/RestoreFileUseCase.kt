package de.perigon.companion.archive.restore.domain

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import de.perigon.companion.archive.data.BackupFileEntity
import de.perigon.companion.archive.backup.domain.BackupOrchestrator
import de.perigon.companion.archive.backup.domain.PACK_DRIVE_ROOT
import de.perigon.companion.archive.data.ArchiveStateRepository
import de.perigon.companion.archive.domain.ArchivePartEncryptor
import de.perigon.companion.archive.domain.ArchivePaxWriter
import de.perigon.companion.archive.data.RestoreSelectionEntity
import de.perigon.companion.core.network.S3Backend
import de.perigon.companion.core.saf.DriveInfo
import de.perigon.companion.core.saf.createDocument
import de.perigon.companion.core.saf.deleteDocument
import de.perigon.companion.core.saf.documentUriInTree
import de.perigon.companion.core.saf.findChildByName
import de.perigon.companion.core.saf.navigateOrCreateId
import de.perigon.companion.core.saf.openOutputStream
import de.perigon.companion.core.saf.readRangeFromFirstRoot
import de.perigon.companion.core.saf.renameDocument
import de.perigon.companion.core.saf.setMtime
import de.perigon.companion.core.util.toHex
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "Restore"

// ---------------------------------------------------------------------------
// SAF destination resolution
// ---------------------------------------------------------------------------

data class SafDest(
    val treeUri: Uri,
    val subfolders: List<String>,
    val displayName: String,
)

/**
 * Resolve where to write a restored file.
 *
 * All DCIM paths (including DCIM/PostMedia/) use the app-wide DCIM SAF grant.
 * Other paths fall back to explicit safGrants.
 */
fun resolveDestination(
    path: String,
    dcimUri: String?,
    safGrants: Map<String, Uri>,
): SafDest? {
    val parts       = path.split("/")
    val displayName = parts.last()

    return when {
        path.startsWith("DCIM/") -> {
            val uri = dcimUri?.toUri() ?: safGrants["DCIM"] ?: return null
            val subfolders = parts.drop(1).dropLast(1)
            SafDest(uri, subfolders, displayName)
        }
        else -> {
            val root = parts.first()
            val uri  = safGrants[root] ?: return null
            val subfolders = parts.drop(1).dropLast(1)
            SafDest(uri, subfolders, displayName)
        }
    }
}

// ---------------------------------------------------------------------------
// FileAccum
// ---------------------------------------------------------------------------

class FileAccum(
    val fileId: Long,
    val path: String,
    val mtime: Long,
    val sha256: String,
    val realSize: Long,
    val dest: SafDest,
    val docUri: Uri,
    val out: DigestOutputStream,
    var written: Long = 0,
)

fun openFileAccum(
    context: Context,
    fileId: Long,
    path: String,
    mtime: Long,
    sha256: String,
    realSize: Long,
    dest: SafDest,
): FileAccum? {
    val folderId = navigateOrCreateId(context, dest.treeUri, dest.subfolders)
    val tmpName = "${dest.displayName}.tmp"
    findChildByName(context, dest.treeUri, folderId, tmpName)?.let { existing ->
        deleteDocument(context, dest.treeUri, existing.documentId)
    }
    val docUri = createDocument(context, dest.treeUri, folderId, "application/octet-stream", tmpName)
        ?: run {
            Log.e(TAG, "$path: Cannot create tmp file")
            return null
        }
    val rawOut: OutputStream = openOutputStream(context, docUri) ?: run {
        deleteDocument(context, docUri)
        Log.e(TAG, "$path: Cannot open output stream")
        return null
    }
    return FileAccum(
        fileId   = fileId,
        path     = path,
        mtime    = mtime,
        sha256   = sha256,
        realSize = realSize,
        dest     = dest,
        docUri   = docUri,
        out      = DigestOutputStream(rawOut, MessageDigest.getInstance("SHA-256")),
    )
}

fun finalizeFileAccum(context: Context, acc: FileAccum): String? {
    try {
        acc.out.close()
    } catch (e: Exception) {
        deleteDocument(context, acc.docUri)
        return "${acc.path}: stream close failed: ${e.message}"
    }
    val actual = acc.out.messageDigest.digest().toHex()
    if (actual != acc.sha256) {
        deleteDocument(context, acc.docUri)
        return "${acc.path}: SHA-256 mismatch (expected ${acc.sha256.take(8)}… got ${actual.take(8)}…)"
    }
    val folderId = navigateOrCreateId(context, acc.dest.treeUri, acc.dest.subfolders)
    findChildByName(context, acc.dest.treeUri, folderId, acc.dest.displayName)?.let { existing ->
        deleteDocument(context, acc.dest.treeUri, existing.documentId)
    }
    val renamedUri = renameDocument(context, acc.docUri, acc.dest.displayName)
    if (renamedUri == null) {
        deleteDocument(context, acc.docUri)
        return "${acc.path}: rename failed"
    }
    val finalUri = findChildByName(context, acc.dest.treeUri, folderId, acc.dest.displayName)
        ?.let { documentUriInTree(acc.dest.treeUri, it.documentId) }
    setMtime(context, finalUri ?: renamedUri, acc.mtime)
    return null
}

// ---------------------------------------------------------------------------
// restoreSelectedFiles
// ---------------------------------------------------------------------------

data class RestoreChunk(
    val fileId: Long,
    val path: String,
    val mtime: Long,
    val sha256: String,
    val realSize: Long,
    val packNumber: Int,
    val partNumber: Int,
    val partOffset: Long,
    val fileOffset: Long,
    val chunkBytes: Long,
)

interface RestoreProgressListener {
    suspend fun onFileStarted(index: Int, total: Int, path: String)
    suspend fun onPartFetched(packNumber: Int, partNumber: Int)
}

suspend fun restoreSelectedFiles(
    context: Context,
    b2: S3Backend,
    drives: List<DriveInfo>,
    senderPk: ByteArray,
    recipientSk: ByteArray,
    sodium: com.goterl.lazysodium.LazySodiumAndroid,
    stateRepo: ArchiveStateRepository,
    packPrefix: String,
    selections: List<RestoreSelectionEntity>,
    dcimUri: String?,
    safGrants: Map<String, Uri>,
    listener: RestoreProgressListener,
): List<String> {
    val errors = mutableListOf<String>()

    val chunks = mutableListOf<RestoreChunk>()

    for (sel in selections) {
        val fileEntity = stateRepo.findFileByPathSha256(sel.path, sel.sha256)
        if (fileEntity == null) {
            errors += "${sel.path}: file not found in index"
            continue
        }
        val fileChunks = stateRepo.getChunksForFile(fileEntity.id)
        if (fileChunks.isEmpty()) {
            errors += "${sel.path}: no chunks found in index"
            continue
        }
        for (chunk in fileChunks) {
            chunks += RestoreChunk(
                fileId     = fileEntity.id,
                path       = sel.path,
                mtime      = sel.mtime,
                sha256     = sel.sha256,
                realSize   = sel.size,
                packNumber = chunk.packNumber,
                partNumber = chunk.partNumber,
                partOffset = chunk.partOffset,
                fileOffset = chunk.fileOffset,
                chunkBytes = chunk.chunkBytes,
            )
        }
    }

    val sorted = chunks.sortedWith(compareBy({ it.packNumber }, { it.partNumber }, { it.partOffset }))
    val byPart = sorted.groupBy { it.packNumber to it.partNumber }

    var currentAccum: FileAccum? = null
    var filesDone = 0
    val totalFiles = selections.size

    for ((packPart, partChunks) in byPart.entries.sortedWith(compareBy({ it.key.first }, { it.key.second }))) {
        val (packNumber, partNumber) = packPart
        val packKey = "$packPrefix/%010d".format(packNumber)
        val from    = (partNumber - 1).toLong() * BackupOrchestrator.PART_WIRE_SIZE
        val to      = partNumber.toLong()       * BackupOrchestrator.PART_WIRE_SIZE - 1

        val wireData = try {
            readRangeFromFirstRoot(context, drives.map { it.treeUri }, PACK_DRIVE_ROOT, packKey, from, to)
                ?: retryWithBackoff { b2.getRange(packKey, from, to) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch pack=$packNumber part=$partNumber: ${e.message}")
            currentAccum?.let { deleteDocument(context, it.docUri); errors += "${it.path}: abandoned due to fetch failure" }
            currentAccum = null
            for (chunk in partChunks) {
                if (chunk.fileOffset == 0L) {
                    errors += "${chunk.path}: fetch failed at pack $packNumber part $partNumber"
                }
            }
            continue
        }

        listener.onPartFetched(packNumber, partNumber)

        val plaintext = ArchivePartEncryptor.unseal(wireData, senderPk, recipientSk, sodium)
        if (plaintext == null) {
            Log.e(TAG, "unseal FAILED pack=$packNumber part=$partNumber")
            currentAccum?.let { deleteDocument(context, it.docUri); errors += "${it.path}: abandoned due to decryption failure" }
            currentAccum = null
            for (chunk in partChunks) {
                if (chunk.fileOffset == 0L) {
                    errors += "${chunk.path}: decryption failed at pack $packNumber part $partNumber"
                }
            }
            continue
        }

        for (chunk in partChunks) {
            if (chunk.fileOffset == 0L) {
                currentAccum?.let { acc ->
                    val err = finalizeFileAccum(context, acc)
                    if (err != null) errors += err
                }
                currentAccum = null

                filesDone++
                listener.onFileStarted(filesDone, totalFiles, chunk.path)

                val dest = resolveDestination(chunk.path, dcimUri, safGrants)
                if (dest == null) {
                    errors += "${chunk.path}: No SAF grant for '${chunk.path.split("/").first()}'"
                    continue
                }
                currentAccum = openFileAccum(
                    context  = context,
                    fileId   = chunk.fileId,
                    path     = chunk.path,
                    mtime    = chunk.mtime,
                    sha256   = chunk.sha256,
                    realSize = chunk.realSize,
                    dest     = dest,
                )
                if (currentAccum == null) {
                    errors += "${chunk.path}: Cannot create output file"
                    continue
                }
            }

            val acc = currentAccum ?: continue
            if (acc.fileId != chunk.fileId) {
                Log.e(TAG, "Chunk fileId mismatch: expected ${acc.fileId}, got ${chunk.fileId}")
                continue
            }

            val dataStart = (chunk.partOffset + ArchivePaxWriter.BLOCK).toInt()
            val dataEnd   = dataStart + chunk.chunkBytes.toInt()
            if (dataEnd > plaintext.size) {
                errors += "${chunk.path}: chunk extends beyond part boundary"
                deleteDocument(context, acc.docUri)
                currentAccum = null
                continue
            }

            try {
                acc.out.write(plaintext, dataStart, chunk.chunkBytes.toInt())
                acc.written += chunk.chunkBytes
            } catch (e: Exception) {
                deleteDocument(context, acc.docUri)
                errors += "${acc.path}: write failed: ${e.message}"
                currentAccum = null
                continue
            }

            if (acc.written >= acc.realSize) {
                val err = finalizeFileAccum(context, acc)
                if (err != null) errors += err
                currentAccum = null
            }
        }
    }

    currentAccum?.let { acc ->
        val err = finalizeFileAccum(context, acc)
        if (err != null) errors += err
    }

    return errors
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private suspend fun <T> retryWithBackoff(block: suspend () -> T): T {
    var lastError: Exception? = null
    repeat(3) { attempt ->
        try { return block() } catch (e: CancellationException) {
            throw e // cancellation is not a retryable failure
        } catch (e: Exception) {
            lastError = e
            Log.w(TAG, "attempt $attempt failed: ${e.message}")
            kotlinx.coroutines.delay(1000L * (1 shl attempt))
        }
    }
    throw lastError!!
}
