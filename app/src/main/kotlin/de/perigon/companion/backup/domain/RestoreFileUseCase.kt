package de.perigon.companion.backup.domain

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import de.perigon.companion.backup.data.BackupFileEntity
import de.perigon.companion.backup.data.BackupStateRepository
import de.perigon.companion.backup.data.RestoreSelectionEntity
import de.perigon.companion.util.network.S3Backend
import de.perigon.companion.util.saf.navigateOrCreate
import de.perigon.companion.util.saf.setMtime
import de.perigon.companion.util.toHex
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
            val uri = dcimUri?.let { Uri.parse(it) } ?: safGrants["DCIM"] ?: return null
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
    val doc: DocumentFile,
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
    val folder  = navigateOrCreate(context, dest.treeUri, dest.subfolders)
    val tmpName = "${dest.displayName}.tmp"
    folder.findFile(tmpName)?.delete()
    val doc = folder.createFile("application/octet-stream", tmpName) ?: run {
        Log.e(TAG, "$path: Cannot create tmp file")
        return null
    }
    val rawOut: OutputStream = context.contentResolver.openOutputStream(doc.uri) ?: run {
        doc.delete()
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
        doc      = doc,
        out      = DigestOutputStream(rawOut, MessageDigest.getInstance("SHA-256")),
    )
}

fun finalizeFileAccum(context: Context, acc: FileAccum): String? {
    try {
        acc.out.close()
    } catch (e: Exception) {
        acc.doc.delete()
        return "${acc.path}: stream close failed: ${e.message}"
    }
    val actual = acc.out.messageDigest.digest().toHex()
    if (actual != acc.sha256) {
        acc.doc.delete()
        return "${acc.path}: SHA-256 mismatch (expected ${acc.sha256.take(8)}… got ${actual.take(8)}…)"
    }
    val folder = navigateOrCreate(context, acc.dest.treeUri, acc.dest.subfolders)
    folder.findFile(acc.dest.displayName)?.delete()
    try {
        DocumentsContract.renameDocument(
            context.contentResolver,
            acc.doc.uri,
            acc.dest.displayName,
        )
    } catch (e: Exception) {
        acc.doc.delete()
        return "${acc.path}: rename failed: ${e.message}"
    }
    setMtime(context, folder.findFile(acc.dest.displayName) ?: acc.doc, acc.mtime)
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
    senderPk: ByteArray,
    recipientSk: ByteArray,
    sodium: com.goterl.lazysodium.LazySodiumAndroid,
    stateRepo: BackupStateRepository,
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
            retryWithBackoff { b2.getRange(packKey, from, to) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch pack=$packNumber part=$partNumber: ${e.message}")
            currentAccum?.let { it.doc.delete(); errors += "${it.path}: abandoned due to fetch failure" }
            currentAccum = null
            for (chunk in partChunks) {
                if (chunk.fileOffset == 0L) {
                    errors += "${chunk.path}: fetch failed at pack $packNumber part $partNumber"
                }
            }
            continue
        }

        listener.onPartFetched(packNumber, partNumber)

        val plaintext = BackupPartEncryptor.unseal(wireData, senderPk, recipientSk, sodium)
        if (plaintext == null) {
            Log.e(TAG, "unseal FAILED pack=$packNumber part=$partNumber")
            currentAccum?.let { it.doc.delete(); errors += "${it.path}: abandoned due to decryption failure" }
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

            val dataStart = (chunk.partOffset + BackupPaxWriter.BLOCK).toInt()
            val dataEnd   = dataStart + chunk.chunkBytes.toInt()
            if (dataEnd > plaintext.size) {
                errors += "${chunk.path}: chunk extends beyond part boundary"
                acc.doc.delete()
                currentAccum = null
                continue
            }

            try {
                acc.out.write(plaintext, dataStart, chunk.chunkBytes.toInt())
                acc.written += chunk.chunkBytes
            } catch (e: Exception) {
                acc.doc.delete()
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
