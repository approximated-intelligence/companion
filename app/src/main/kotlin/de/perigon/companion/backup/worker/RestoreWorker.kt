package de.perigon.companion.backup.worker

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.goterl.lazysodium.LazySodiumAndroid
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import de.perigon.companion.backup.data.BackupFileEntity
import de.perigon.companion.backup.data.BackupStateRepository
import de.perigon.companion.backup.domain.BackupOrchestrator
import de.perigon.companion.backup.domain.BackupPartEncryptor
import de.perigon.companion.backup.domain.BackupPaxWriter
import de.perigon.companion.backup.domain.FileAccum
import de.perigon.companion.backup.domain.RestoreProgressListener
import de.perigon.companion.backup.domain.finalizeFileAccum
import de.perigon.companion.backup.domain.openFileAccum
import de.perigon.companion.backup.domain.resolveDestination
import de.perigon.companion.backup.domain.restoreSelectedFiles
import de.perigon.companion.core.data.UserNotificationDao
import de.perigon.companion.core.data.UserNotifications
import de.perigon.companion.core.prefs.AppPrefs
import de.perigon.companion.core.prefs.CredentialStore
import de.perigon.companion.util.fromHex
import de.perigon.companion.util.network.S3Backend
import de.perigon.companion.util.network.S3BackendFactory
import de.perigon.companion.util.toHex
import kotlinx.coroutines.delay
import kotlin.coroutines.cancellation.CancellationException

@HiltWorker
class RestoreWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val stateRepo: BackupStateRepository,
    private val s3Factory: S3BackendFactory,
    private val sodium: LazySodiumAndroid,
    private val appPrefs: AppPrefs,
    private val creds: CredentialStore,
    private val notificationDao: UserNotificationDao,
) : CoroutineWorker(ctx, params) {

    companion object {
        const val WORK_NAME                = "restore"
        const val MODE_REBUILD             = "REBUILD"
        const val MODE_RESTORE             = "RESTORE"
        const val MODE_REBUILD_AND_RESTORE = "REBUILD_AND_RESTORE"
        const val KEY_MODE                 = "mode"
        private const val TAG              = "RestoreWorker"
    }

    private val notifier = RestoreNotifier(applicationContext)

    private var trackedState      = RestoreNotifier.STATE_COUNTING
    private var trackedPack       = 0
    private var trackedPacksTotal = 0
    private var trackedFileIndex  = 0
    private var trackedFilesTotal = 0
    private var trackedFile       = ""

    override suspend fun doWork(): Result {
        val b2Config    = appPrefs.b2Config()
        val appKey      = creds.b2RwAppKey()
        val serverPkHex = appPrefs.naclPkHex()
        val phoneSkHex  = creds.phoneSecretKey()
        val phonePkHex  = appPrefs.phonePkHex()

        if (b2Config == null || appKey == null || serverPkHex == null ||
            phoneSkHex == null || phonePkHex == null) {
            return fail(RestoreNotifier.ERR_MISSING_CREDENTIALS, "Credentials not configured")
        }

        val mode        = inputData.getString(KEY_MODE) ?: MODE_REBUILD
        val b2          = s3Factory.create(b2Config.endpoint, b2Config.bucket, b2Config.keyId, appKey, b2Config.region)
        val senderPk    = serverPkHex.fromHex()
        val recipientSk = phoneSkHex.fromHex()
        val packPrefix  = phonePkHex

        setForeground(notifier.foregroundInfo("Restore starting…"))

        return try {
            when (mode) {
                MODE_REBUILD             -> doRebuild(b2, senderPk, recipientSk, packPrefix)
                MODE_RESTORE             -> doRestore(b2, senderPk, recipientSk, packPrefix)
                MODE_REBUILD_AND_RESTORE -> doRebuildAndRestore(b2, senderPk, recipientSk, packPrefix)
                else                     -> fail(RestoreNotifier.ERR_IO_ERROR, "Unknown mode: $mode")
            }
        } catch (_: CancellationException) {
            Log.i(TAG, "Restore cancelled")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Restore failed", e)
            fail(RestoreNotifier.ERR_IO_ERROR, e.message ?: "Unknown error")
        }
    }

    // ---------------------------------------------------------------------------
    // Modes
    // ---------------------------------------------------------------------------

    private suspend fun doRebuild(
        b2: S3Backend, senderPk: ByteArray, recipientSk: ByteArray, packPrefix: String,
    ): Result {
        val totalPacks = countPacks(b2, packPrefix)
        if (totalPacks == 0) return fail(RestoreNotifier.ERR_NO_PACKS, "No packs found")

        trackedPacksTotal = totalPacks
        for (packPos in 0 until totalPacks) {
            trackedPack  = packPos
            trackedState = RestoreNotifier.STATE_REBUILDING
            emitProgress()
            setForeground(notifier.foregroundInfo("Rebuilding index…", "Pack ${packPos + 1} / $totalPacks"))
            val packKey  = "$packPrefix/%010d".format(packPos)
            val wireData = retryWithBackoff { b2.getRange(packKey, 0L, BackupOrchestrator.PART_WIRE_SIZE - 1L) }
            scanPack(b2, senderPk, recipientSk, packPos, packKey, wireData, restoreInline = false)
        }

        trackedState = RestoreNotifier.STATE_DONE
        emitProgress()
        UserNotifications.success(notificationDao, "restore", "Index rebuilt: $totalPacks pack(s)")
        return Result.success()
    }

    private suspend fun doRestore(
        b2: S3Backend, senderPk: ByteArray, recipientSk: ByteArray, packPrefix: String,
    ): Result {
        val selections = stateRepo.getRestoreSelections()
        if (selections.isEmpty()) return fail(RestoreNotifier.ERR_IO_ERROR, "No files selected")

        trackedFilesTotal = selections.size
        trackedState      = RestoreNotifier.STATE_RESTORING
        val dcimUri       = appPrefs.dcimTreeUri()
        val safGrants     = buildSafGrants(dcimUri)

        val errors = restoreSelectedFiles(
            context      = applicationContext,
            b2           = b2,
            senderPk     = senderPk,
            recipientSk  = recipientSk,
            sodium       = sodium,
            stateRepo    = stateRepo,
            packPrefix   = packPrefix,
            selections   = selections,
            dcimUri      = dcimUri,
            safGrants    = safGrants,
            listener     = object : RestoreProgressListener {
                override suspend fun onFileStarted(index: Int, total: Int, path: String) {
                    trackedFileIndex = index
                    trackedFilesTotal = total
                    trackedFile = path
                    trackedState = RestoreNotifier.STATE_RESTORING
                    emitProgress()
                    setForeground(notifier.foregroundInfo("Restoring…", path.substringAfterLast('/')))
                }
                override suspend fun onPartFetched(packNumber: Int, partNumber: Int) {
                    trackedPack = packNumber
                    emitProgress()
                }
            },
        )

        stateRepo.clearRestoreSelections()
        trackedState = RestoreNotifier.STATE_DONE
        emitProgress()

        return if (errors.isEmpty()) {
            UserNotifications.success(notificationDao, "restore", "Restored ${selections.size} file(s)")
            Result.success()
        } else {
            UserNotifications.error(notificationDao, "restore", "Restored with ${errors.size} error(s)", errors.joinToString("\n"))
            Result.success(workDataOf(RestoreNotifier.KEY_ERROR_DETAIL to errors.joinToString("\n")))
        }
    }

    private suspend fun doRebuildAndRestore(
        b2: S3Backend, senderPk: ByteArray, recipientSk: ByteArray, packPrefix: String,
    ): Result {
        val totalPacks   = countPacks(b2, packPrefix)
        if (totalPacks == 0) return fail(RestoreNotifier.ERR_NO_PACKS, "No packs found")

        trackedPacksTotal = totalPacks
        val dcimUri       = appPrefs.dcimTreeUri()
        val safGrants     = buildSafGrants(dcimUri)
        val errors        = mutableListOf<String>()

        for (packPos in 0 until totalPacks) {
            trackedPack  = packPos
            trackedState = RestoreNotifier.STATE_REBUILDING
            emitProgress()
            setForeground(notifier.foregroundInfo("Rebuilding & restoring…", "Pack ${packPos + 1} / $totalPacks"))
            val packKey  = "$packPrefix/%010d".format(packPos)
            val wireData = retryWithBackoff { b2.getRange(packKey, 0L, BackupOrchestrator.PART_WIRE_SIZE - 1L) }
            errors += scanPack(b2, senderPk, recipientSk, packPos, packKey, wireData,
                restoreInline = true, dcimUri = dcimUri, safGrants = safGrants)
        }

        trackedState = RestoreNotifier.STATE_DONE
        emitProgress()

        return if (errors.isEmpty()) {
            UserNotifications.success(notificationDao, "restore", "Rebuild & restore complete: $totalPacks pack(s)")
            Result.success()
        } else {
            UserNotifications.error(notificationDao, "restore", "Rebuild & restore with ${errors.size} error(s)", errors.joinToString("\n"))
            Result.success(workDataOf(RestoreNotifier.KEY_ERROR_DETAIL to errors.joinToString("\n")))
        }
    }

    // ---------------------------------------------------------------------------
    // Pack scanning
    // ---------------------------------------------------------------------------

    private suspend fun scanPack(
        b2: S3Backend,
        senderPk: ByteArray,
        recipientSk: ByteArray,
        packPos: Int,
        packKey: String,
        firstPartWire: ByteArray,
        restoreInline: Boolean,
        dcimUri: String? = null,
        safGrants: Map<String, Uri> = emptyMap(),
    ): List<String> {
        val errors  = mutableListOf<String>()
        var partNum = 1
        var wire    = firstPartWire

        var currentFile: FileAccum? = null

        while (true) {
            val plaintext = BackupPartEncryptor.unseal(wire, senderPk, recipientSk, sodium)
            if (plaintext == null) {
                Log.e(TAG, "unseal FAILED pack=$packPos part=$partNum")
                errors += "Pack $packPos part $partNum: decryption failed"
                currentFile?.let { it.doc.delete(); errors += "${it.path}: abandoned due to decryption failure" }
                currentFile = null
                break
            }

            val paxEnd = BackupOrchestrator.PART_PAX_CAPACITY.toInt()
            var offset = 0

            while (offset + BackupPaxWriter.BLOCK <= paxEnd) {
                val block = plaintext.copyOfRange(offset, offset + BackupPaxWriter.BLOCK)
                val entry = BackupPaxWriter.parseEntry(block) ?: break
                val partOffset = offset.toLong()
                offset += BackupPaxWriter.BLOCK

                if (entry.path.isEmpty() && entry.sha256 == "0".repeat(64)) {
                    offset += BackupPaxWriter.paddedDataSize(entry.realSize).toInt()
                    continue
                }

                val dataInThisPart = minOf(entry.realSize - entry.offset, (paxEnd - offset).toLong())
                val paddedData     = BackupPaxWriter.paddedDataSize(dataInThisPart)

                val now = System.currentTimeMillis()
                stateRepo.insertScannedFiles(listOf(BackupFileEntity(
                    path = entry.path, uri = "", mtime = entry.mtime, size = entry.realSize,
                    createdAt = now, updatedAt = now,
                )))
                val fileEntity = stateRepo.findFileByPathMtimeSize(entry.path, entry.mtime, entry.realSize)
                if (fileEntity != null) {
                    stateRepo.upsertRestoredFileLocation(
                        fileId     = fileEntity.id, sha256 = entry.sha256,
                        packNumber = packPos,       partNumber = partNum,
                        partOffset = if (entry.offset == 0L) partOffset else 0L,
                        fileOffset = entry.offset,  chunkBytes = dataInThisPart,
                    )
                }

                if (restoreInline) {
                    if (entry.offset == 0L) {
                        currentFile?.let { acc ->
                            val err = finalizeFileAccum(applicationContext, acc)
                            if (err != null) errors += err
                        }
                        currentFile = null

                        val dest = resolveDestination(entry.path, dcimUri, safGrants)
                        if (dest == null) {
                            errors += "${entry.path}: No SAF grant for '${entry.path.split("/").first()}'"
                        } else {
                            currentFile = openFileAccum(
                                context  = applicationContext,
                                fileId   = fileEntity?.id ?: 0L,
                                path     = entry.path,
                                mtime    = entry.mtime,
                                sha256   = entry.sha256,
                                realSize = entry.realSize,
                                dest     = dest,
                            )
                            if (currentFile == null) {
                                errors += "${entry.path}: Cannot create output file"
                            }
                        }
                    }
                    currentFile?.let { acc ->
                        if (acc.sha256 == entry.sha256) {
                            try {
                                acc.out.write(plaintext, offset, dataInThisPart.toInt())
                                acc.written += dataInThisPart
                            } catch (e: Exception) {
                                acc.doc.delete()
                                errors += "${acc.path}: write failed: ${e.message}"
                                currentFile = null
                            }
                        }
                        if (currentFile != null && acc.written >= acc.realSize) {
                            trackedFileIndex++
                            trackedFile  = acc.path
                            trackedState = RestoreNotifier.STATE_RESTORING
                            emitProgress()
                            setForeground(notifier.foregroundInfo(
                                "Pack ${packPos + 1} / $trackedPacksTotal",
                                acc.path.substringAfterLast('/'),
                            ))
                            val err = finalizeFileAccum(applicationContext, acc)
                            if (err != null) errors += err
                            currentFile = null
                        }
                    }
                }

                offset += paddedData.toInt()
            }

            partNum++
            wire = try {
                retryWithBackoff {
                    val from = (partNum - 1).toLong() * BackupOrchestrator.PART_WIRE_SIZE
                    val to   = partNum.toLong()       * BackupOrchestrator.PART_WIRE_SIZE - 1
                    b2.getRange(packKey, from, to)
                }
            } catch (e: Exception) {
                Log.d(TAG, "part $partNum not found — end of pack $packPos")
                break
            }
        }

        currentFile?.let { acc ->
            val err = finalizeFileAccum(applicationContext, acc)
            if (err != null) errors += err
        }

        return errors
    }

    // ---------------------------------------------------------------------------
    // SAF grants
    // ---------------------------------------------------------------------------

    private fun buildSafGrants(dcimUri: String?): Map<String, Uri> {
        val grants = mutableMapOf<String, Uri>()

        if (dcimUri != null) {
            val uri = Uri.parse(dcimUri)
            val perms = applicationContext.contentResolver.persistedUriPermissions
            if (perms.any { it.uri == uri && it.isWritePermission }) {
                grants["DCIM"] = uri
            }
        }

        applicationContext.contentResolver.persistedUriPermissions
            .filter { it.isWritePermission }
            .forEach { perm ->
                val path = perm.uri.path ?: return@forEach
                val root = path.substringAfterLast(':').substringBefore('/')
                if (root.isNotEmpty()) grants.putIfAbsent(root, perm.uri)
            }

        return grants
    }

    // ---------------------------------------------------------------------------
    // Utilities
    // ---------------------------------------------------------------------------

    private suspend fun countPacks(b2: S3Backend, packPrefix: String): Int {
        trackedState = RestoreNotifier.STATE_COUNTING
        emitProgress()
        setForeground(notifier.foregroundInfo("Counting packs…"))
        var count = 0
        while (retryWithBackoff { b2.headObject("$packPrefix/%010d".format(count)) }) count++
        return count
    }

    private suspend fun <T> retryWithBackoff(block: suspend () -> T): T {
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try { return block() } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "attempt $attempt failed: ${e.message}")
                delay(1000L * (1 shl attempt))
            }
        }
        throw lastError!!
    }

    private suspend fun fail(errorType: String, detail: String): Result {
        UserNotifications.error(notificationDao, "restore", "Restore failed: $detail")
        return Result.failure(workDataOf(
            RestoreNotifier.KEY_ERROR_TYPE   to errorType,
            RestoreNotifier.KEY_ERROR_DETAIL to detail,
        ))
    }

    private suspend fun emitProgress() {
        setProgress(notifier.buildProgressData(
            restoreState = trackedState,
            packPosition = trackedPack,
            packsTotal   = trackedPacksTotal,
            fileIndex    = trackedFileIndex,
            filesTotal   = trackedFilesTotal,
            currentFile  = trackedFile,
        ))
    }
}
