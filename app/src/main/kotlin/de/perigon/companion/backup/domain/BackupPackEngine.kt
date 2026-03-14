package de.perigon.companion.backup.domain

import android.util.Log
import com.goterl.lazysodium.LazySodiumAndroid
import de.perigon.companion.util.toHex
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.FileNotFoundException
import java.security.MessageDigest
import de.perigon.companion.backup.network.b2.B2Backend
import com.goterl.lazysodium.interfaces.Box
import java.io.InputStream
import kotlinx.coroutines.delay

enum class BackupFilePhase { HASHING, STREAMING }

data class BackupPackState(
    val packPosition: Int,
    val b2UploadId: String,
    val startFileId: Long,
    val startFileOffset: Long,
    val numPartsTarget: Int,
)

data class BackupPartRecord(
    val partNumber: Int,
    val etag: String,
    val wireSize: Long,
)

data class BackupPlannedFile(
    val id: Long,
    val path: String,
    val uri: String,
    val mtime: Long,
    val size: Long,
    val sha256: String,
)

sealed class BackupStreamResult {
    data class AllDone(val partsTotal: Int) : BackupStreamResult()
    data class PackFull(
        val fileId: Long,
        val byteOffset: Long,
        val partsTotal: Int,
    ) : BackupStreamResult()
    data class Aborted(val issues: List<BackupIssueFile>) : BackupStreamResult()
}

data class BackupIssueFile(val id: Long, val path: String, val mtime: Long, val issue: String)

data class FilePartPosition(
    val partNumber: Int,
    val offsetInPart: Long,
)

interface BackupPackEngineCallbacks {
    suspend fun onPartUploaded(record: BackupPartRecord)
    suspend fun onFileStreaming(fileId: Long, packPosition: Int, packFileIndex: Int, partPosition: FilePartPosition)
    suspend fun onFileStreamed(fileId: Long, packPosition: Int)
    suspend fun onFileIssue(issue: BackupIssueFile)
    suspend fun onProgress(path: String, fileIndex: Int, totalFiles: Int, phase: BackupFilePhase)
    suspend fun onByteOffset(fileId: Long, offset: Long)
}

class BackupPackEngine(
    private val b2: B2Backend,
    private val fileSource: BackupFileSource,
    private val recipientPubKey: ByteArray,
    private val sodium: LazySodiumAndroid,
    private val maxPartRetries: Int = 3,
) {

    companion object {
        private const val TAG = "BackupPackEngine"

        const val PART_WIRE_SIZE = 8L * 1024 * 1024

        val SEAL_OVERHEAD = BackupPartEncryptor.SEAL_OVERHEAD

        val PART_PLAINTEXT_SIZE = PART_WIRE_SIZE - SEAL_OVERHEAD

        private val PAX_BLOCK_SIZE = BackupPaxWriter.BLOCK.toLong()

        private val PAX_HEADER_SIZE = BackupPaxWriter.entryOverhead()

        private const val IO_BUF = 64 * 1024

        fun plaintextCapacity(numParts: Int): Long =
            numParts.toLong() * PART_PLAINTEXT_SIZE
    }

    suspend fun openPack(
        packPosition: Int,
        startFileId: Long,
        startFileOffset: Long,
        numPartsTarget: Int,
    ): BackupPackState {
        val uploadId = b2.createMultipart(packKey(packPosition))

        Log.d(TAG, "openPack pos=$packPosition fileId=$startFileId offset=$startFileOffset " +
                "numParts=$numPartsTarget plainCapacity=${plaintextCapacity(numPartsTarget)}")

        return BackupPackState(
            packPosition = packPosition,
            b2UploadId = uploadId,
            startFileId = startFileId,
            startFileOffset = startFileOffset,
            numPartsTarget = numPartsTarget,
        )
    }

    suspend fun streamIntoPack(
        state: BackupPackState,
        files: List<BackupPlannedFile>,
        resumePartNumber: Int = 1,
        startFileOffset: Long = state.startFileOffset,
        resumeBytesInPack: Long = 0L,
        partPersistence: suspend (BackupPartRecord) -> Unit,
        callbacks: BackupPackEngineCallbacks,
    ): BackupStreamResult {
        val issues = mutableListOf<BackupIssueFile>()
        var partNum = resumePartNumber
        var bytesInPack = resumeBytesInPack
        val totalPlainCapacity = plaintextCapacity(state.numPartsTarget)
        val buffer = PlaintextBuffer(PART_PLAINTEXT_SIZE.toInt())

        suspend fun flushPart() {
            val plain = buffer.drain()
            if (plain.isEmpty()) return

            val cipher = BackupPartEncryptor.seal(plain, recipientPubKey, sodium)
            val wireSize = cipher.size.toLong()
            Log.d(TAG, "flush part=$partNum plainSize=${plain.size} wireSize=$wireSize")

            val etag = BackupPartUploader.upload(
                b2, packKey(state.packPosition), state.b2UploadId, partNum, cipher, maxPartRetries,
            )

            val record = BackupPartRecord(partNum, etag, wireSize)
            partPersistence(record)
            callbacks.onPartUploaded(record)
            partNum++
        }

        suspend fun feed(data: ByteArray) {
            var offset = 0
            while (offset < data.size) {
                val written = buffer.append(data, offset)
                offset += written
                bytesInPack += written.toLong()
                if (buffer.isFull()) {
                    flushPart()
                }
            }
        }

        fun currentPosition(): FilePartPosition = FilePartPosition(
            partNumber = partNum,
            offsetInPart = buffer.size().toLong(),
        )

        for ((idx, f) in files.withIndex()) {
            val fileOffset = if (idx == 0) startFileOffset else 0L
            val isContinuation = fileOffset > 0L

            callbacks.onProgress(f.path, idx + 1, files.size, BackupFilePhase.STREAMING)

            val actualSize = try {
                fileSource.size(f.uri)
            } catch (_: Exception) {
                issues += BackupIssueFile(f.id, f.path, f.mtime, "MISSING")
                callbacks.onFileIssue(issues.last())
                continue
            }
            if (actualSize != f.size) {
                issues += BackupIssueFile(f.id, f.path, f.mtime, "MODIFIED")
                callbacks.onFileIssue(issues.last())
                continue
            }

            val remainingFileBytes = f.size - fileOffset
            val tarRoomRemaining = totalPlainCapacity - bytesInPack
            val rawFileBudget = tarRoomRemaining - PAX_HEADER_SIZE
            val fileDataBudget = (rawFileBudget / PAX_BLOCK_SIZE) * PAX_BLOCK_SIZE

            Log.d(TAG, "file[$idx] path=${f.path} size=${f.size} fileOffset=$fileOffset " +
                    "remaining=$remainingFileBytes tarRoom=$tarRoomRemaining budget=$fileDataBudget")

            if (fileDataBudget <= 0L) {
                Log.w(TAG, "NO BUDGET - PackFull before file[$idx]")
                flushPart()
                return BackupStreamResult.PackFull(
                    f.id, if (isContinuation) fileOffset else 0L, partNum - 1,
                )
            }

            val chunkSize = minOf(remainingFileBytes, fileDataBudget)

            val filePosition = currentPosition()
            callbacks.onFileStreaming(f.id, state.packPosition, idx, filePosition)

            feed(BackupPaxWriter.buildEntry(
                path = f.path,
                realSize = f.size,
                offset = fileOffset,
                mtime = f.mtime,
                sha256Hex = f.sha256,
            ))

            var bytesWritten = 0L
            fileSource.open(f.uri).use { stream ->
                if (fileOffset > 0L) stream.skip(fileOffset)
                val ioBuf = ByteArray(IO_BUF)
                while (bytesWritten < chunkSize) {
                    currentCoroutineContext().ensureActive()
                    val want = minOf(IO_BUF.toLong(), chunkSize - bytesWritten).toInt()
                    val n = stream.read(ioBuf, 0, want)
                    if (n == -1) break
                    feed(ioBuf.copyOf(n))
                    bytesWritten += n
                    callbacks.onByteOffset(f.id, fileOffset + bytesWritten)
                }
            }

            val paddedSize = BackupPaxWriter.paddedDataSize(chunkSize)
            val padBytes = (paddedSize - chunkSize).toInt()
            if (padBytes > 0) feed(ByteArray(padBytes))

            Log.d(TAG, "file[$idx] done - written=$bytesWritten pad=$padBytes " +
                    "bytesInPack=$bytesInPack buffered=${buffer.size()}")

            if (chunkSize < remainingFileBytes) {
                Log.w(TAG, "SPLIT at file[$idx] offset=${fileOffset + bytesWritten}")
                flushPart()
                return BackupStreamResult.PackFull(
                    f.id, fileOffset + bytesWritten, partNum - 1,
                )
            }

            callbacks.onFileStreamed(f.id, state.packPosition)
        }

        if (issues.isNotEmpty()) {
            Log.w(TAG, "Aborting pack - ${issues.size} issues")
            flushPart()
            return BackupStreamResult.Aborted(issues)
        }

        if (buffer.size() > 0) {
            flushPart()
        }

        Log.d(TAG, "AllDone - bytesInPack=$bytesInPack parts=${partNum - 1}")
        return BackupStreamResult.AllDone(partNum - 1)
    }

    suspend fun completePack(state: BackupPackState, parts: List<BackupPartRecord>) {
        Log.d(TAG, "completePack pos=${state.packPosition} parts=${parts.size}")
        b2.completeMultipart(
            packKey(state.packPosition), state.b2UploadId,
            parts.map { B2Backend.Part(it.partNumber, it.etag) },
        )
    }

    suspend fun abortPack(state: BackupPackState) =
        runCatching { b2.abortMultipart(packKey(state.packPosition), state.b2UploadId) }

    suspend fun packExists(packPosition: Int): Boolean =
        runCatching { b2.headObject(packKey(packPosition)) }.isSuccess

    suspend fun listUploadedParts(state: BackupPackState): List<B2Backend.Part> =
        b2.listParts(packKey(state.packPosition), state.b2UploadId)

    fun hashFile(uri: String): HashResult = try {
        fileSource.open(uri).use { stream ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(IO_BUF)
            var n: Int
            while (stream.read(buf).also { n = it } != -1) digest.update(buf, 0, n)
            HashResult.Success(digest.digest().toHex())
        }
    } catch (_: FileNotFoundException) {
        HashResult.NotFound(uri)
    } catch (e: SecurityException) {
        HashResult.ReadError(uri, "Permission denied: ${e.message}")
    } catch (e: Exception) {
        HashResult.ReadError(uri, e.message ?: "Unknown read error")
    }

    fun fileSize(uri: String): Long = fileSource.size(uri)

    fun packKey(position: Int) = "packs/%010d".format(position)
}

// --- Merged from HashResult.kt ---

/**
 * Result of hashing a file by URI. Distinguishes between
 * file-not-found, read errors, and successful hashes so callers
 * can take appropriate action rather than guessing from null.
 */
sealed class HashResult {
    data class Success(val hex: String) : HashResult()
    data class NotFound(val uri: String) : HashResult()
    data class ReadError(val uri: String, val cause: String) : HashResult()
}


// --- Merged from PlaintextBuffer.kt ---

/**
 * Fixed-capacity byte buffer for accumulating plaintext before encryption.
 * Append data, drain when full. No I/O, no side effects.
 */
class PlaintextBuffer(private val capacity: Int) {
    private val data = ByteArray(capacity)
    private var pos = 0

    fun append(src: ByteArray, srcOffset: Int = 0): Int {
        val available = capacity - pos
        val toCopy = minOf(available, src.size - srcOffset)
        src.copyInto(data, pos, srcOffset, srcOffset + toCopy)
        pos += toCopy
        return toCopy
    }

    fun isFull(): Boolean = pos >= capacity

    fun size(): Int = pos

    fun drain(): ByteArray {
        val result = data.copyOf(pos)
        pos = 0
        return result
    }
}


// --- Merged from BackupPartEncryptor.kt ---

/**
 * Encrypts a plaintext part using NaCl sealed box.
 * Pure function - no state, no I/O.
 */
object BackupPartEncryptor {

    val SEAL_OVERHEAD = Box.SEALBYTES.toLong()

    fun seal(
        plaintext: ByteArray,
        recipientPubKey: ByteArray,
        sodium: LazySodiumAndroid,
    ): ByteArray {
        val cipher = ByteArray(plaintext.size + SEAL_OVERHEAD.toInt())
        sodium.cryptoBoxSeal(cipher, plaintext, plaintext.size.toLong(), recipientPubKey)
        return cipher
    }

    fun unseal(
        ciphertext: ByteArray,
        publicKey: ByteArray,
        secretKey: ByteArray,
        sodium: LazySodiumAndroid,
    ): ByteArray? {
        if (ciphertext.size <= Box.SEALBYTES) return null
        val plaintext = ByteArray(ciphertext.size - Box.SEALBYTES)
        val ok = sodium.cryptoBoxSealOpen(plaintext, ciphertext, ciphertext.size.toLong(), publicKey, secretKey)
        return if (ok) plaintext else null
    }
}


// --- Merged from BackupPartUploader.kt ---

/**
 * Uploads a single encrypted part to B2 with exponential backoff retry.
 * Stateless - each call is independent.
 */
object BackupPartUploader {

    private const val TAG = "BackupPartUploader"

    suspend fun upload(
        b2: B2Backend,
        key: String,
        uploadId: String,
        partNumber: Int,
        data: ByteArray,
        maxRetries: Int = 3,
    ): String {
        var lastError: Exception? = null
        repeat(maxRetries) { attempt ->
            try {
                return b2.uploadPart(key, uploadId, partNumber, data)
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "uploadPart $partNumber attempt $attempt failed: ${e.message}")
                delay(1000L * (1 shl attempt))
            }
        }
        throw lastError!!
    }
}


// --- Merged from BackupFileSource.kt ---

/**
 * Abstraction over file reading.
 * Callers stream bytes - never materialise the full file in memory.
 *
 * Implementations:
 *   AndroidBackupFileSource - reads via ContentResolver + Uri (worker module)
 */
interface BackupFileSource {
    /** Open a stream for the file identified by [uri]. Caller must close it. */
    fun open(uri: String): InputStream

    /** File size in bytes without reading content. */
    fun size(uri: String): Long
}

