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
import de.perigon.companion.backup.data.TAIL_PART_REF
import de.perigon.companion.backup.data.TailPartInfo
import de.perigon.companion.util.FileHasher
import de.perigon.companion.util.network.S3Backend
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.InputStream
import java.security.SecureRandom
import kotlin.coroutines.cancellation.CancellationException

interface BackupFileSource {
    fun open(uri: String): InputStream
}

data class PackPlan(
    val parts: List<BackupPartEntity>,
    val chunks: List<BackupChunkEntity>,
)

/**
 * The planning loop's suspended state, derived by looking at the existing
 * plan (see BackupOrchestrator.planResumePoint). A fresh backup is simply a
 * resume of the empty plan — planBackup is agnostic to which case it's in.
 *
 * When [tailPartId] is non-null, planning continues inside that existing
 * part with [partRemaining] bytes of PAX capacity left; chunks placed there
 * reference TAIL_PART_REF and are resolved on insert.
 */
data class PlanResumePoint(
    val packNumber: Int,
    val partNumber: Int,
    val partRemaining: Long,
    val tailPartId: Long?,
)

fun planBackup(
    files: List<BackupFileStatusView>,
    resume: PlanResumePoint,
    partsPerPack: Int,
    partPaxCapacity: Long,
    blockSize: Int,
): PackPlan {
    val parts = mutableListOf<BackupPartEntity>()
    val chunks = mutableListOf<BackupChunkEntity>()

    var packNumber = resume.packNumber
    var partNumber = resume.partNumber
    var partRemaining = resume.partRemaining
    // Chunk part reference: index into `parts` for parts created by this
    // plan, or TAIL_PART_REF for the pre-existing tail part being extended.
    var currentPartRef = TAIL_PART_REF

    fun rollToNextPart() {
        partNumber++
        if (partNumber > partsPerPack) {
            packNumber++
            partNumber = 1
        }
    }

    fun newPart() {
        parts += BackupPartEntity(packNumber = packNumber, partNumber = partNumber)
        currentPartRef = (parts.size - 1).toLong()
        partRemaining = partPaxCapacity
    }

    if (resume.tailPartId == null) newPart()

    for (file in files) {
        var fileOffset = 0L

        // do/while: runs once even for size-0 files, which yield exactly one
        // header-only chunk (chunkBytes = 0, consuming one header block).
        do {
            if (partRemaining < blockSize) {
                rollToNextPart()
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
                backupPartId = currentPartRef,
                fileOffset = fileOffset,
                chunkBytes = chunkBytes,
                partOffset = partOffset,
            )

            val paddedChunk = BackupPaxWriter.paddedDataSize(chunkBytes)
            partRemaining -= blockSize + paddedChunk
            fileOffset += chunkBytes

            if (!isFinalChunk) {
                // file spans into next part — continuation chunk starts at offset 0
                rollToNextPart()
                newPart()
            }
        } while (fileOffset < file.size)
    }

    // Drop a trailing part this plan created but never filled (can only
    // happen when `files` is effectively empty).
    if (parts.isNotEmpty() && chunks.none { it.backupPartId == (parts.size - 1).toLong() }) {
        parts.removeAt(parts.size - 1)
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
    private val hasher: FileHasher,
    private val b2: S3Backend,
    private val partsPerPack: Int,
    private val packPrefix: String,
) {

    interface ProgressListener {
        suspend fun onPackStateChange(state: String, packNumber: Int)
        suspend fun onScanProgress(currentFile: String, hashed: Int, misses: Int)
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
        val scanned = scanner.scan(onProgress = { hashed, misses, key ->
            listener.onScanProgress(key.path, hashed, misses)
        })
        if (scanned.isNotEmpty()) {
            val now = System.currentTimeMillis()
            stateRepo.insertScannedFiles(scanned.map { sf ->
                BackupFileEntity(
                    path = sf.path, uri = sf.uri,
                    mtime = sf.mtime, size = sf.size, sha256 = sf.sha256,
                    createdAt = now, updatedAt = now,
                )
            })
        }

        // One planning path, agnostic to fresh vs. crash-resume: files without
        // a header chunk are planned from wherever the existing plan left off.
        val unplanned = stateRepo.getUnplannedFiles()
        if (unplanned.isNotEmpty()) {
            createPlan(unplanned, planResumePoint())
        }

        if (stateRepo.getFirstPendingPart() == null) {
            listener.onPackStateChange("DONE", 0)
            return
        }

        execute(listener)
    }

    /**
     * Look at the existing plan and derive where planning left off. Only two
     * facts are irreversible and force stepping past the tail part:
     *  - sealed: the pack's multipart upload is completed → fresh pack
     *  - uploaded: the part's bytes are on the wire (etag) → next part
     * Otherwise planning continues inside the tail part, mid-capacity.
     */
    private suspend fun planResumePoint(): PlanResumePoint {
        val tail = stateRepo.getTailPart()
            ?: return PlanResumePoint((stateRepo.maxSealedPack() ?: -1) + 1, 1, PART_PAX_CAPACITY, null)
        return when {
            tail.sealed -> PlanResumePoint(
                maxOf(tail.packNumber, stateRepo.maxSealedPack() ?: -1) + 1,
                1, PART_PAX_CAPACITY, null,
            )
            tail.uploaded ->
                if (tail.partNumber >= partsPerPack)
                    PlanResumePoint(tail.packNumber + 1, 1, PART_PAX_CAPACITY, null)
                else
                    PlanResumePoint(tail.packNumber, tail.partNumber + 1, PART_PAX_CAPACITY, null)
            else -> PlanResumePoint(
                tail.packNumber, tail.partNumber, remainingInTailPart(tail), tail.id,
            )
        }
    }

    private suspend fun remainingInTailPart(tail: TailPartInfo): Long {
        val consumed = stateRepo.getChunksForPart(tail.packNumber, tail.partNumber)
            .sumOf { BLOCK + BackupPaxWriter.paddedDataSize(it.chunkBytes) }
        return PART_PAX_CAPACITY - consumed
    }

    private suspend fun createPlan(files: List<BackupFileStatusView>, resume: PlanResumePoint) {
        val plan = planBackup(
            files = files,
            resume = resume,
            partsPerPack = partsPerPack,
            partPaxCapacity = PART_PAX_CAPACITY,
            blockSize = BLOCK,
        )
        stateRepo.insertPackPlan(plan.parts, plan.chunks, resume.tailPartId)
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

                // Network first, persist second: a crash in between leaves an
                // orphaned multipart on B2 (harmless; abort via lifecycle rule)
                // and the DB never ahead of the remote. Never hold a Room
                // transaction across a network call — it starves every other
                // writer and freezes UI Flows.
                val id = b2.createMultipart(packKey(currentPackNumber))
                currentUploadId = id
                stateRepo.persistOpenPack(BackupOpenPackEntity(
                    packNumber = currentPackNumber,
                    b2UploadId = id,
                    numPartsTarget = partsPerPack,
                    createdAt = System.currentTimeMillis(),
                ))
            }

            val chunks = stateRepo.getChunksForPart(part.packNumber, part.partNumber)
            val buffer = PartBuffer(PART_PAX_CAPACITY.toInt())

            // Re-verify file content at upload time (first chunk only). On
            // mismatch or read failure we still honour the plan: emit a pad
            // entry for the reserved bytes so the part stays aligned.
            // The hash-matched case uses the planned sha256 from the file
            // record, never a newly-computed one.
            val verifiedFileIds = mutableSetOf<Long>()
            val badFileIds = mutableSetOf<Long>()

            for (chunk in chunks) {
                currentCoroutineContext().ensureActive()

                val file = stateRepo.getFileById(chunk.backupFileId) ?: run {
                    writePadChunk(buffer, chunk, secureRandom)
                    Log.w(TAG, "file ${chunk.backupFileId} missing at upload; padded")
                    continue
                }
                currentFileName = file.path

                if (chunk.fileOffset == 0L) filesDone++
                listener.onFileProgress(currentPackNumber, currentFileName, filesDone, totalFiles)

                // Verify once per file at its first chunk
                if (chunk.fileOffset == 0L && file.id !in verifiedFileIds) {
                    val rehashed = hasher.rehash(file.path, file.mtime) {
                        fileSource.open(file.uri)
                    }
                    val ok = rehashed != null &&
                        rehashed.first == file.sha256 &&
                        rehashed.second == file.size
                    if (!ok) {
                        badFileIds += file.id
                        Log.w(TAG, "${file.path}: content changed or unreadable at upload; padding")
                    }
                    verifiedFileIds += file.id
                }

                if (file.id in badFileIds) {
                    writePadChunk(buffer, chunk, secureRandom)
                    continue
                }

                buffer.appendExact(BackupPaxWriter.buildEntry(
                    path = file.path,
                    realSize = file.size,
                    offset = chunk.fileOffset,
                    mtime = file.mtime,
                    sha256Hex = file.sha256,
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
                } catch (e: CancellationException) {
                    throw e
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

            // Network first, then a single-row upsert. A crash in between
            // leaves the part without an etag; it re-uploads next run and B2
            // overwrites the part number (documented semantics). The stored
            // etag therefore always matches the latest bytes on the remote.
            val etag = uploadWithRetry(packKey(currentPackNumber), currentUploadId!!, part.partNumber, cipher)
            stateRepo.confirmPart(part.packNumber, part.partNumber, part.id, etag)

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

        // Network first, then seal locally (sealPack keeps its own internal
        // transaction — those writes belong together). If a previous run
        // completed the multipart but crashed before sealing, B2 answers
        // NoSuchUpload for the finalized upload: treat that as already done.
        try {
            b2.completeMultipart(packKey(packNumber), uploadId, b2Parts)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e.message?.contains("NoSuchUpload", ignoreCase = true) == true) {
                Log.i(TAG, "pack $packNumber multipart already completed remotely; sealing locally")
            } else {
                throw e
            }
        }
        stateRepo.sealPack(packNumber)
    }

    /**
     * Fill a chunk's reserved slot with a valid pad entry when the file can't
     * be written for real. Size matches what the plan reserved: BLOCK for the
     * header + paddedDataSize(chunkBytes) for the data region. The pad entry's
     * declared realsize covers the data region so a decoder skips it cleanly.
     */
    private fun writePadChunk(
        buffer: PartBuffer,
        chunk: BackupChunkEntity,
        random: SecureRandom,
    ) {
        val paddedSize = BackupPaxWriter.paddedDataSize(chunk.chunkBytes)
        buffer.appendExact(BackupPaxWriter.buildPadEntry(paddedSize))
        if (paddedSize > 0) {
            val pad = ByteArray(paddedSize.toInt())
            random.nextBytes(pad)
            buffer.appendExact(pad)
        }
    }

    private suspend fun uploadWithRetry(key: String, uploadId: String, partNumber: Int, data: ByteArray): String {
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                return b2.uploadPart(key, uploadId, partNumber, data)
            } catch (e: CancellationException) {
                throw e // cancellation is not a retryable failure
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "uploadPart $partNumber attempt $attempt failed: ${e.message}")
                delay(1000L * (1 shl attempt))
            }
        }
        throw lastError!!
    }
}
