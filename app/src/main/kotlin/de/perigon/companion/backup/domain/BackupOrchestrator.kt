package de.perigon.companion.backup.domain

import android.util.Log
import de.perigon.companion.backup.data.BackupFileDao
import de.perigon.companion.backup.data.BackupFileEntity
import de.perigon.companion.backup.data.BackupFileStatus
import de.perigon.companion.backup.data.BackupOpenPackEntity
import de.perigon.companion.backup.data.BackupStateRepository
import de.perigon.companion.backup.data.BackupSourceScanner

/**
 * Backup state machine. Owns recovery, planning, and upload logic.
 */
class BackupOrchestrator(
    private val engine: BackupPackEngine,
    private val scanner: BackupSourceScanner,
    private val stateRepo: BackupStateRepository,
    private val backupFileDao: BackupFileDao,
    private val numPartsPerPack: Int,
) {

    interface ProgressListener {
        suspend fun onStateChange(state: String, packPos: Int, file: String, fileIdx: Int, total: Int)
        suspend fun onPartUploaded(partNumber: Int, wireSize: Long, numPartsTarget: Int)
    }

    companion object {
        private const val TAG = "BackupOrchestrator"
    }

    suspend fun run(listener: ProgressListener) {
        recoverOpenPack(listener)
        planAndUpload(listener)
    }

    private suspend fun recoverOpenPack(listener: ProgressListener) {
        val open = stateRepo.getOpenPack() ?: return

        listener.onStateChange("RECOVERING", open.packPosition, "", 0, 0)

        if (engine.packExists(open.packPosition)) {
            listener.onStateChange("CONFIRMING", open.packPosition, "", 0, 0)
            stateRepo.sealPack(open.packPosition)
            return
        }

        val b2Parts = try {
            engine.listUploadedParts(BackupPackState(
                packPosition = open.packPosition,
                b2UploadId = open.b2UploadId,
                startFileId = open.startFileId,
                startFileOffset = open.startFileOffset,
                numPartsTarget = open.numPartsTarget,
            ))
        } catch (e: Exception) {
            val msg = e.message ?: ""
            if (msg.contains("404") || msg.contains("NoSuchUpload")) {
                stateRepo.resetPack(open.packPosition)
                return
            }
            throw e
        }

        val b2Numbers = b2Parts.map { it.number }.sorted()
        if (b2Numbers.isNotEmpty() && !isContiguous(b2Numbers)) {
            abortAndReset(open)
            return
        }

        val dbParts = stateRepo.getPartsForPack(open.packPosition)
        val lastDbPartNumber = dbParts.maxOfOrNull { it.partNumber } ?: 0
        val maxB2Part = b2Numbers.maxOrNull() ?: 0

        if (maxB2Part > lastDbPartNumber + 1) {
            abortAndReset(open)
            return
        }

        val currentFile = stateRepo.getCurrentFile()
        if (currentFile != null) {
            val bf = stateRepo.getRecoveryFileById(currentFile.backupFileId)
            if (bf != null && bf.sha256.isNotEmpty()) {
                when (val result = engine.hashFile(bf.uri)) {
                    is HashResult.Success -> {
                        if (result.hex != bf.sha256) {
                            stateRepo.markFileIssue(bf.id, "MODIFIED during recovery")
                            abortAndReset(open)
                            return
                        }
                    }
                    is HashResult.NotFound -> {
                        stateRepo.markFileIssue(bf.id, "MISSING during recovery")
                        abortAndReset(open)
                        return
                    }
                    is HashResult.ReadError -> {
                        stateRepo.markFileIssue(bf.id, "READ_ERROR during recovery: ${result.cause}")
                        abortAndReset(open)
                        return
                    }
                }
            }
        }

        val resumePartNumber = lastDbPartNumber + 1
        val resumeBytesInPack = stateRepo.getBytesInPack(open.packPosition)

        val files = backupFileDao.getRecoveryFiles(open.startFileId).map { it.toPlanned() }
        if (files.isEmpty()) {
            abortAndReset(open)
            return
        }

        val allParts = dbParts.map {
            BackupPartRecord(it.partNumber, it.etag, it.wireSize)
        }.toMutableList()

        val packState = BackupPackState(
            packPosition = open.packPosition,
            b2UploadId = open.b2UploadId,
            startFileId = open.startFileId,
            startFileOffset = open.startFileOffset,
            numPartsTarget = open.numPartsTarget,
        )

        val callbacks = buildCallbacks(open.packPosition, open.numPartsTarget, listener)
        val result = engine.streamIntoPack(
            state = packState,
            files = files,
            resumePartNumber = resumePartNumber,
            startFileOffset = open.startFileOffset,
            resumeBytesInPack = resumeBytesInPack,
            partPersistence = { record ->
                stateRepo.persistPart(open.packPosition, record)
                allParts += record
            },
            callbacks = callbacks,
        )

        handleStreamResult(engine, packState, result, allParts, listener)
    }

    private suspend fun abortAndReset(open: BackupOpenPackEntity) {
        val packState = BackupPackState(
            packPosition = open.packPosition,
            b2UploadId = open.b2UploadId,
            startFileId = open.startFileId,
            startFileOffset = open.startFileOffset,
            numPartsTarget = open.numPartsTarget,
        )
        stateRepo.markPackForAbort(open.packPosition)
        engine.abortPack(packState)
        stateRepo.finalizeAbort(open.packPosition)
    }

    private suspend fun planAndUpload(listener: ProgressListener) {
        listener.onStateChange("PLANNING", 0, "", 0, 0)

        val scanned = scanner.scan()
        val existing = backupFileDao.getByStatus(BackupFileStatus.CONFIRMED)
            .map { it.path to it.mtime }.toSet()

        val newFiles = scanned.filter { (it.path to it.mtime) !in existing }
        if (newFiles.isEmpty()) return

        val now = System.currentTimeMillis()
        val entities = newFiles.map { sf ->
            BackupFileEntity(
                path = sf.path,
                uri = sf.uri,
                mtime = sf.mtime,
                size = sf.size,
                createdAt = now,
                updatedAt = now,
            )
        }
        backupFileDao.insertAll(entities)

        listener.onStateChange("HASHING", 0, "", 0, 0)
        val pending = backupFileDao.getByStatus(BackupFileStatus.PENDING)
        for ((idx, bf) in pending.withIndex()) {
            listener.onStateChange("HASHING", 0, bf.path, idx + 1, pending.size)
            val hashNow = System.currentTimeMillis()
            when (val result = engine.hashFile(bf.uri)) {
                is HashResult.Success -> {
                    backupFileDao.updateHashed(bf.id, result.hex, now = hashNow)
                }
                is HashResult.NotFound -> {
                    backupFileDao.markIssue(bf.id, "MISSING during hash", now = hashNow)
                }
                is HashResult.ReadError -> {
                    backupFileDao.markIssue(bf.id, "READ_ERROR during hash: ${result.cause}", now = hashNow)
                }
            }
        }

        val ready = (backupFileDao.getByStatus(BackupFileStatus.PENDING) +
                     backupFileDao.getByStatus(BackupFileStatus.STREAMING))
            .filter { it.sha256.isNotEmpty() }
            .distinctBy { it.id }
        if (ready.isEmpty()) return

        val packPos = nextAvailablePackPos()
        uploadBatch(packPos, ready, listener)
    }

    private suspend fun nextAvailablePackPos(): Int {
        val maxConfirmed = backupFileDao.maxEndPack() ?: 0
        var pos = maxConfirmed + 1
        while (engine.packExists(pos)) {
            Log.w(TAG, "Pack $pos already exists on B2 - skipping")
            pos++
        }
        return pos
    }

    private suspend fun uploadBatch(
        startPackPos: Int,
        files: List<BackupFileEntity>,
        listener: ProgressListener,
    ): Int {
        var packPos = startPackPos
        var fileOffset = 0L
        var remaining = files.map { it.toPlanned() }

        while (remaining.isNotEmpty()) {
            while (engine.packExists(packPos)) {
                Log.w(TAG, "Pack $packPos already exists on B2 - skipping")
                packPos++
            }

            val firstFile = remaining.first()
            val state = engine.openPack(packPos, firstFile.id, fileOffset, numPartsPerPack)

            stateRepo.persistOpenPack(BackupOpenPackEntity(
                packPosition = state.packPosition,
                b2UploadId = state.b2UploadId,
                startFileId = state.startFileId,
                startFileOffset = state.startFileOffset,
                numPartsTarget = state.numPartsTarget,
                createdAt = System.currentTimeMillis(),
            ))

            listener.onStateChange("UPLOADING", packPos, "", 0, remaining.size)

            val allParts = mutableListOf<BackupPartRecord>()
            val callbacks = buildCallbacks(packPos, state.numPartsTarget, listener)

            val result = engine.streamIntoPack(
                state = state,
                files = remaining,
                startFileOffset = fileOffset,
                partPersistence = { record ->
                    stateRepo.persistPart(packPos, record)
                    allParts += record
                },
                callbacks = callbacks,
            )

            when (result) {
                is BackupStreamResult.AllDone -> {
                    completePack(state, allParts, packPos, listener)
                    remaining = emptyList()
                    fileOffset = 0L
                    packPos++
                }
                is BackupStreamResult.PackFull -> {
                    completePack(state, allParts, packPos, listener)
                    val splitIdx = remaining.indexOfFirst { it.id == result.fileId }
                    fileOffset = result.byteOffset
                    remaining = remaining.subList(splitIdx, remaining.size)
                    packPos++
                }
                is BackupStreamResult.Aborted -> {
                    stateRepo.markPackForAbort(packPos)
                    engine.abortPack(state)
                    stateRepo.finalizeAbort(packPos)
                    val issueIds = result.issues.map { it.id }.toSet()
                    remaining = remaining.filter { it.id !in issueIds }
                    fileOffset = 0L
                    packPos++
                }
            }
        }

        return packPos
    }

    private suspend fun completePack(
        state: BackupPackState,
        parts: List<BackupPartRecord>,
        packPos: Int,
        listener: ProgressListener,
    ) {
        listener.onStateChange("COMPLETING", packPos, "", 0, 0)
        engine.completePack(state, parts)

        listener.onStateChange("CONFIRMING", packPos, "", 0, 0)
        stateRepo.sealPack(packPos)
    }

    private suspend fun handleStreamResult(
        engine: BackupPackEngine,
        state: BackupPackState,
        result: BackupStreamResult,
        allParts: MutableList<BackupPartRecord>,
        listener: ProgressListener,
    ) {
        val now = System.currentTimeMillis()
        when (result) {
            is BackupStreamResult.AllDone -> {
                completePack(state, allParts, state.packPosition, listener)
            }
            is BackupStreamResult.PackFull -> {
                completePack(state, allParts, state.packPosition, listener)
                backupFileDao.resetPackFiles(state.packPosition, now = now)
            }
            is BackupStreamResult.Aborted -> {
                stateRepo.markPackForAbort(state.packPosition)
                engine.abortPack(state)
                stateRepo.finalizeAbort(state.packPosition)
            }
        }
    }

    private fun buildCallbacks(
        packPos: Int,
        numPartsTarget: Int,
        listener: ProgressListener,
    ): BackupPackEngineCallbacks = object : BackupPackEngineCallbacks {

        override suspend fun onPartUploaded(record: BackupPartRecord) {
            listener.onPartUploaded(record.partNumber, record.wireSize, numPartsTarget)
        }

        override suspend fun onFileStreaming(
            fileId: Long,
            packPosition: Int,
            packFileIndex: Int,
            partPosition: FilePartPosition,
        ) {
            stateRepo.markFileStreaming(
                fileId, packPosition, packFileIndex,
                startPart = partPosition.partNumber,
                startPartOffset = partPosition.offsetInPart,
            )
        }

        override suspend fun onFileStreamed(fileId: Long, packPosition: Int) {
            stateRepo.markFileStreamed(fileId, packPosition)
        }

        override suspend fun onFileIssue(issue: BackupIssueFile) {
            stateRepo.markFileIssue(issue.id, issue.issue)
        }

        override suspend fun onProgress(path: String, fileIndex: Int, totalFiles: Int, phase: BackupFilePhase) {
            listener.onStateChange(
                when (phase) {
                    BackupFilePhase.HASHING -> "HASHING"
                    BackupFilePhase.STREAMING -> "UPLOADING"
                },
                packPos, path, fileIndex, totalFiles,
            )
        }

        override suspend fun onByteOffset(fileId: Long, offset: Long) {
            stateRepo.updateByteOffset(offset)
        }
    }

    private fun isContiguous(sorted: List<Int>): Boolean {
        if (sorted.isEmpty()) return true
        return sorted.first() == 1 && sorted.last() == sorted.size
    }
}

internal fun BackupFileEntity.toPlanned() = BackupPlannedFile(
    id = id,
    path = path,
    uri = uri,
    mtime = mtime,
    size = size,
    sha256 = sha256,
)
