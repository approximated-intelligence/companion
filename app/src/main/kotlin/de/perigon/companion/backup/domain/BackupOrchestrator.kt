package de.perigon.companion.backup.domain

import android.util.Log
import com.goterl.lazysodium.LazySodiumAndroid
import de.perigon.companion.backup.data.BackupChunkEntity
import de.perigon.companion.backup.data.BackupFileEntity
import de.perigon.companion.backup.data.BackupFileStatusView
import de.perigon.companion.backup.data.BackupOpenPackEntity
import de.perigon.companion.backup.data.BackupPartEntity
import de.perigon.companion.backup.data.BackupSourceScanner
import de.perigon.companion.backup.data.BackupStateRepository
import de.perigon.companion.util.network.S3Backend
import de.perigon.companion.util.toHex
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.FileNotFoundException
import java.io.InputStream
import java.security.MessageDigest
import java.security.SecureRandom

interface BackupFileSource {
    fun open(uri: String): InputStream
}

sealed class HashResult {
    data class Success(val hex: String, val size: Long) : HashResult()
    data class NotFound(val uri: String) : HashResult()
    data class ReadError(val uri: String, val cause: String) : HashResult()
}

data class PackPlan(
    val parts: List<BackupPartEntity>,
    val chunks: List<BackupChunkEntity>,
)

fun planBackup(
    files: List<BackupFileStatusView>,
    startPackNumber: Int,
    partsPerPack: Int,
    partPaxCapacity: Long,
    blockSize: Int,
): PackPlan {
    val parts = mutableListOf<BackupPartEntity>()
    val chunks = mutableListOf<BackupChunkEntity>()

    var packNumber = startPackNumber
    var partNumber = 1
    var partRemaining = partPaxCapacity
    var currentPartIndex = -1

    fun newPart(): Int {
        parts += BackupPartEntity(packNumber = packNumber, partNumber = partNumber)
        currentPartIndex = parts.size - 1
        partRemaining = partPaxCapacity
        return currentPartIndex
    }

    newPart()

    for (file in files) {
        if (file.size == 0L) continue

        var fileOffset = 0L

        while (fileOffset < file.size) {
            if (partRemaining < blockSize) {
                partNumber++
                if (partNumber > partsPerPack) {
                    packNumber++
                    partNumber = 1
                }
                newPart()
            }

            val remainingFileBytes = file.size - fileOffset
            val availableForData = partRemaining - blockSize
            val isFinalChunk = remainingFileBytes <= availableForData
            val chunkBytes = if (isFinalChunk) remainingFileBytes else availableForData

            // partOffset: position of this chunk's PAX header within its part's plaintext.
            // Continuation chunks (fileOffset > 0) always start at byte 0 of a fresh part.
            val partOffset = if (fileOffset == 0L) partPaxCapacity - partRemaining else 0L

            chunks += BackupChunkEntity(
                backupFileId = file.id,
                backupPartId = currentPartIndex.toLong(),
                fileOffset = fileOffset,
                chunkBytes = chunkBytes,
                partOffset = partOffset,
            )

            val paddedChunk = BackupPaxWriter.paddedDataSize(chunkBytes)
            partRemaining -= blockSize + paddedChunk
            fileOffset += chunkBytes

            if (isFinalChunk) break

            // file spans into next part — continuation chunk starts at offset 0
            partNumber++
            if (partNumber > partsPerPack) {
                packNumber++
                partNumber = 1
            }
            newPart()
        }
    }

    if (currentPartIndex >= 0 && chunks.none { it.backupPartId == currentPartIndex.toLong() }) {
        parts.removeAt(currentPartIndex)
    }

    return PackPlan(parts, chunks)
}

class PartBuffer(private val capacity: Int) {
    private val data = ByteArray(capacity)
    private var pos = 0

    fun append(src: ByteArray, srcOffset: Int = 0, length: Int = src.size - srcOffset): Int {
        val available = capacity - pos
        val toCopy = minOf(available, length)
        src.copyInto(data, pos, srcOffset, srcOffset + toCopy)
        pos += toCopy
        return toCopy
    }

    fun appendExact(src: ByteArray, srcOffset: Int = 0, length: Int = src.size - srcOffset) {
        check(remaining() >= length) { "appendExact: need $length, have ${remaining()}" }
        src.copyInto(data, pos, srcOffset, srcOffset + length)
        pos += length
    }

    fun isFull(): Boolean = pos >= capacity
    fun size(): Int = pos
    fun remaining(): Int = capacity - pos

    fun drain(): ByteArray {
        val result = data.copyOf(pos)
        pos = 0
        return result
    }
}

class BackupOrchestrator(
    private val fileSource: BackupFileSource,
    private val serverPk: ByteArray,
    private val phoneSk: ByteArray,
    private val sodium: LazySodiumAndroid,
    private val scanner: BackupSourceScanner,
    private val stateRepo: BackupStateRepository,
    private val b2: S3Backend,
    private val partsPerPack: Int,
    private val packPrefix: String,
) {

    interface ProgressListener {
        suspend fun onPackStateChange(state: String, packNumber: Int)
        suspend fun onFileProgress(packNumber: Int, file: String, fileIdx: Int, totalFiles: Int)
        suspend fun onPartUploaded(partsDone: Int, partsTotal: Int)
    }

    companion object {
        private const val TAG = "BackupOrchestrator"
        private const val IO_BUF = 64 * 1024

        val PART_WIRE_SIZE = 8L * 1024 * 1024
        val PART_PAX_CAPACITY = PART_WIRE_SIZE - BackupPaxWriter.BLOCK.toLong()
        private val PLAINTEXT_SIZE = PART_WIRE_SIZE - BackupPartEncryptor.OVERHEAD
        private val BLOCK = BackupPaxWriter.BLOCK
    }

    private fun packKey(number: Int) = "$packPrefix/%010d".format(number)

    suspend fun run(listener: ProgressListener) {
        listener.onPackStateChange("PLANNING", 0)
        val scanned = scanner.scan()
        if (scanned.isNotEmpty()) {
            val now = System.currentTimeMillis()
            stateRepo.insertScannedFiles(scanned.map { sf ->
                BackupFileEntity(path = sf.path, uri = sf.uri, mtime = sf.mtime, size = sf.size, createdAt = now, updatedAt = now)
            })
        }

        val firstPending = stateRepo.getFirstPendingPart()
        if (firstPending == null) {
            val pending = stateRepo.getPendingFiles()
            if (pending.isEmpty()) {
                listener.onPackStateChange("DONE", 0)
                return
            }
            val startPackNumber = (stateRepo.maxSealedPack() ?: -1) + 1
            createPlan(pending, startPackNumber)
        }

        execute(listener)
    }

    private suspend fun createPlan(files: List<BackupFileStatusView>, startPackNumber: Int) {
        val plan = planBackup(
            files = files,
            startPackNumber = startPackNumber,
            partsPerPack = partsPerPack,
            partPaxCapacity = PART_PAX_CAPACITY,
            blockSize = BLOCK,
        )
        stateRepo.insertPackPlan(plan.parts, plan.chunks)
    }

    private suspend fun execute(listener: ProgressListener) {
        var currentPackNumber = -1
        var currentUploadId: String? = null
        var partsDone = 0
        var filesDone = 0
        var currentFileName = ""
        val secureRandom = SecureRandom()

        val openPack = stateRepo.getOpenPack()
        if (openPack != null) {
            listener.onPackStateChange("RECOVERING", openPack.packNumber)
            val uploadedParts = stateRepo.getUploadedPartsForPack(openPack.packNumber)
            currentPackNumber = openPack.packNumber
            currentUploadId = openPack.b2UploadId
            partsDone = uploadedParts.size
        }

        val firstPending = stateRepo.getFirstPendingPart() ?: run {
            listener.onPackStateChange("DONE", currentPackNumber)
            return
        }

        if (currentPackNumber >= 0 && firstPending.packNumber != currentPackNumber) {
            completePack(currentPackNumber, currentUploadId!!, listener)
            currentPackNumber = -1
            currentUploadId = null
        }

        val allPendingParts = buildList {
            var part = stateRepo.getFirstPendingPart()
            while (part != null) {
                add(part)
                part = stateRepo.getNextPendingPart(part.packNumber, part.partNumber)
            }
        }

        val totalFiles = allPendingParts.flatMap { part ->
            stateRepo.getChunksForPart(part.packNumber, part.partNumber)
        }.filter { it.fileOffset == 0L }.map { it.backupFileId }.distinct().size

        val totalParts = partsDone + allPendingParts.size
        val lastPart = allPendingParts.last()

        for (part in allPendingParts) {
            currentCoroutineContext().ensureActive()

            if (part.packNumber != currentPackNumber) {
                if (currentPackNumber >= 0) {
                    completePack(currentPackNumber, currentUploadId!!, listener)
                }
                currentPackNumber = part.packNumber
                listener.onPackStateChange("UPLOADING", currentPackNumber)

                stateRepo.inTransaction {
                    val id = b2.createMultipart(packKey(currentPackNumber))
                    currentUploadId = id
                    persistOpenPack(BackupOpenPackEntity(
                        packNumber = currentPackNumber,
                        b2UploadId = id,
                        numPartsTarget = partsPerPack,
                        createdAt = System.currentTimeMillis(),
                    ))
                }
            }

            val chunks = stateRepo.getChunksForPart(part.packNumber, part.partNumber)
            val buffer = PartBuffer(PART_PAX_CAPACITY.toInt())

            for (chunk in chunks) {
                currentCoroutineContext().ensureActive()

                val file = stateRepo.getFileById(chunk.backupFileId) ?: continue
                currentFileName = file.path

                if (chunk.fileOffset == 0L) filesDone++
                listener.onFileProgress(currentPackNumber, currentFileName, filesDone, totalFiles)

                if (chunk.fileOffset == 0L) {
                    when (val result = hashFileWithSize(file.uri)) {
                        is HashResult.Success -> {
                            if (result.size != file.size) { writeZeroChunk(buffer, chunk); continue }
                            stateRepo.persistFileHash(file.id, result.hex)
                        }
                        is HashResult.NotFound,
                        is HashResult.ReadError -> { writeZeroChunk(buffer, chunk); continue }
                    }
                }

                val hash = stateRepo.getHashForFile(file.id)?.sha256 ?: run {
                    writeZeroChunk(buffer, chunk); continue
                }

                buffer.appendExact(BackupPaxWriter.buildEntry(
                    path = file.path,
                    realSize = file.size,
                    offset = chunk.fileOffset,
                    mtime = file.mtime,
                    sha256Hex = hash,
                ))

                var bytesStreamed = 0L
                try {
                    fileSource.open(file.uri).use { stream ->
                        if (chunk.fileOffset > 0) {
                            var skipped = 0L
                            while (skipped < chunk.fileOffset) {
                                val s = stream.skip(chunk.fileOffset - skipped)
                                if (s <= 0) break
                                skipped += s
                            }
                        }
                        var remaining = chunk.chunkBytes
                        val ioBuf = ByteArray(IO_BUF)
                        while (remaining > 0) {
                            currentCoroutineContext().ensureActive()
                            val want = minOf(IO_BUF.toLong(), remaining).toInt()
                            val n = stream.read(ioBuf, 0, want)
                            if (n <= 0) break
                            buffer.appendExact(ioBuf, 0, n)
                            bytesStreamed += n
                            remaining -= n
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error streaming ${file.path}: ${e.message}")
                }

                val shortfall = chunk.chunkBytes - bytesStreamed
                if (shortfall > 0) buffer.appendExact(ByteArray(shortfall.toInt()))

                val paddedSize = BackupPaxWriter.paddedDataSize(chunk.chunkBytes)
                val padBytes = (paddedSize - chunk.chunkBytes).toInt()
                if (padBytes > 0) buffer.appendExact(ByteArray(padBytes))
            }

            if (!buffer.isFull()) {
                check(part === lastPart) { "Part buffer not full before upload: ${buffer.size()} of $PART_PAX_CAPACITY" }
                if (buffer.remaining() >= BLOCK) {
                    val padDataSize = (buffer.remaining() - BLOCK).toLong()
                    buffer.appendExact(BackupPaxWriter.buildPadEntry(padDataSize))
                    if (padDataSize > 0) {
                        val pad = ByteArray(padDataSize.toInt())
                        secureRandom.nextBytes(pad)
                        buffer.appendExact(pad)
                    }
                }
            }

            val paxData = buffer.drain()
            val plaintext = ByteArray(PLAINTEXT_SIZE.toInt())
            paxData.copyInto(plaintext)
            if (paxData.size < plaintext.size) {
                val tailPad = ByteArray(plaintext.size - paxData.size)
                secureRandom.nextBytes(tailPad)
                tailPad.copyInto(plaintext, paxData.size)
            }
            val cipher = BackupPartEncryptor.seal(plaintext, serverPk, phoneSk, sodium)

            stateRepo.inTransaction {
                val etag = uploadWithRetry(packKey(currentPackNumber), currentUploadId!!, part.partNumber, cipher)
                confirmPart(part.packNumber, part.partNumber, part.id, etag)
            }

            partsDone++
            listener.onPartUploaded(partsDone, totalParts)
        }

        if (currentPackNumber >= 0 && currentUploadId != null) {
            completePack(currentPackNumber, currentUploadId, listener)
        }

        listener.onPackStateChange("DONE", currentPackNumber)
    }

    private suspend fun completePack(packNumber: Int, uploadId: String, listener: ProgressListener) {
        listener.onPackStateChange("COMPLETING", packNumber)
        val etags = stateRepo.getEtagsForPack(packNumber)
        val b2Parts = etags.map { S3Backend.Part(it.partNumber, it.etag) }
        stateRepo.inTransaction {
            b2.completeMultipart(packKey(packNumber), uploadId, b2Parts)
            sealPack(packNumber)
        }
    }

    private fun writeZeroChunk(buffer: PartBuffer, chunk: BackupChunkEntity) {
        val paddedSize = BackupPaxWriter.paddedDataSize(chunk.chunkBytes)
        buffer.appendExact(ByteArray(BLOCK + paddedSize.toInt()))
    }

    private suspend fun uploadWithRetry(key: String, uploadId: String, partNumber: Int, data: ByteArray): String {
        var lastError: Exception? = null
        repeat(3) { attempt ->
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

    private fun hashFileWithSize(uri: String): HashResult = try {
        fileSource.open(uri).use { stream ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(IO_BUF)
            var size = 0L
            var n: Int
            while (stream.read(buf).also { n = it } != -1) {
                digest.update(buf, 0, n)
                size += n
            }
            HashResult.Success(digest.digest().toHex(), size)
        }
    } catch (_: FileNotFoundException) {
        HashResult.NotFound(uri)
    } catch (e: SecurityException) {
        HashResult.ReadError(uri, "Permission denied: ${e.message}")
    } catch (e: Exception) {
        HashResult.ReadError(uri, e.message ?: "Unknown read error")
    }
}
