package de.perigon.companion.backup.worker

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.goterl.lazysodium.LazySodiumAndroid
import de.perigon.companion.R
import de.perigon.companion.backup.data.BackupFileDao
import de.perigon.companion.backup.data.BackupSourceScanner
import de.perigon.companion.backup.data.BackupStateRepository
import de.perigon.companion.backup.data.BackupSchedulePrefs
import de.perigon.companion.backup.domain.BackupOrchestrator
import de.perigon.companion.backup.domain.BackupPackEngine
import de.perigon.companion.backup.domain.BackupFileSource
import de.perigon.companion.backup.network.b2.B2BackendFactory
import de.perigon.companion.core.data.UserNotificationDao
import de.perigon.companion.core.data.UserNotifications
import de.perigon.companion.core.prefs.AppPrefs
import de.perigon.companion.core.prefs.CredentialStore
import de.perigon.companion.util.fromHex
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.InputStream
import kotlin.coroutines.cancellation.CancellationException

private class AndroidBackupFileSource(private val resolver: ContentResolver) : BackupFileSource {
    override fun open(uri: String): InputStream =
        resolver.openInputStream(Uri.parse(uri))
            ?: error("Cannot open stream for $uri")

    override fun size(uri: String): Long {
        resolver.query(
            Uri.parse(uri),
            arrayOf(MediaStore.Files.FileColumns.SIZE),
            null, null, null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0)
        }
        return 0L
    }
}

@HiltWorker
class BackupWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val backupFileDao: BackupFileDao,
    private val stateRepo: BackupStateRepository,
    private val scanner: BackupSourceScanner,
    private val b2Factory: B2BackendFactory,
    private val lazySodium: LazySodiumAndroid,
    private val appPrefs: AppPrefs,
    private val credentialStore: CredentialStore,
    private val schedulePrefs: BackupSchedulePrefs,
    private val notificationDao: UserNotificationDao,
) : CoroutineWorker(ctx, params) {

    companion object {
        const val WORK_NAME          = "backup"

        const val KEY_PACK_POSITION = "pack_position"
        const val KEY_PACK_STATE    = "pack_state"
        const val KEY_PART_NUMBER   = "part_number"
        const val KEY_PARTS_TOTAL   = "parts_total"
        const val KEY_FILE_INDEX    = "file_index"
        const val KEY_FILES_TOTAL   = "files_total"
        const val KEY_CURRENT_FILE  = "current_file"
        const val KEY_ERROR_TYPE    = "error_type"
        const val KEY_ERROR_DETAIL  = "error_detail"
        const val KEY_PACK_PERCENT  = "pack_percent"

        const val STATE_PLANNING    = "PLANNING"
        const val STATE_HASHING     = "HASHING"
        const val STATE_RECOVERING  = "RECOVERING"
        const val STATE_UPLOADING   = "UPLOADING"
        const val STATE_COMPLETING  = "COMPLETING"
        const val STATE_CONFIRMING  = "CONFIRMING"
        const val STATE_DONE        = "DONE"

        const val ERR_NONE                = "NONE"
        const val ERR_MISSING_CREDENTIALS = "MISSING_CREDENTIALS"
        const val ERR_FILE_MISSING        = "FILE_MISSING"
        const val ERR_FILE_MODIFIED       = "FILE_MODIFIED"
        const val ERR_B2_ERROR            = "B2_ERROR"
        const val ERR_INCONSISTENT        = "INCONSISTENT"
    }

    private val notifier = BackupNotifier(applicationContext)

    private var trackedFile         = ""
    private var trackedFileIndex    = 0
    private var trackedFilesTotal   = 0
    private var trackedPackPosition = 0
    private var trackedPartNumber   = 0
    private var trackedPackPercent  = 0
    private var trackedNumPartsTarget = 0

    override suspend fun doWork(): Result {
        val b2Config = appPrefs.b2Config()
        val b2AppKey = credentialStore.b2AppKey()
        val naclHex  = appPrefs.naclPkHex()

        if (b2Config == null || b2AppKey == null || naclHex == null) {
            return fail(ERR_MISSING_CREDENTIALS, "B2 credentials or NaCl public key not configured")
        }

        setForeground(notifier.foregroundInfo("Starting backup…"))

        val numParts = schedulePrefs.numPartsPerPack()

        return try {
            val b2 = b2Factory.create(b2Config.endpoint, b2Config.bucket, b2Config.keyId, b2AppKey)
            val engine = BackupPackEngine(
                b2, AndroidBackupFileSource(applicationContext.contentResolver),
                naclHex.fromHex(), lazySodium,
            )
            val orchestrator = BackupOrchestrator(engine, scanner, stateRepo, backupFileDao, numParts)

            orchestrator.run(buildProgressListener())
            emitProgress(packState = STATE_DONE)
            UserNotifications.success(
                notificationDao, "backup",
                "Backup complete",
            )
            Result.success()
        } catch (_: CancellationException) {
            android.util.Log.i("BackupWorker", "Backup cancelled")
            Result.success()
        } catch (e: Exception) {
            android.util.Log.e("BackupWorker", "Backup failed", e)
            fail(ERR_B2_ERROR, e.message ?: "Unknown error")
        }
    }

    private fun buildProgressListener() = object : BackupOrchestrator.ProgressListener {
        override suspend fun onStateChange(state: String, packPos: Int, file: String, fileIdx: Int, total: Int) {
            if (trackedPackPosition != packPos) {
                trackedPartNumber = 0
                trackedPackPercent = 0
            }
            trackedPackPosition = packPos
            trackedFile = file
            trackedFileIndex = fileIdx
            trackedFilesTotal = total

            val workerState = when (state) {
                "PLANNING"   -> STATE_PLANNING
                "HASHING"    -> STATE_HASHING
                "RECOVERING" -> STATE_RECOVERING
                "UPLOADING"  -> STATE_UPLOADING
                "COMPLETING" -> STATE_COMPLETING
                "CONFIRMING" -> STATE_CONFIRMING
                else         -> state
            }
            emitProgress(packState = workerState)

            val label = when (state) {
                "PLANNING"  -> "Scanning files…"
                "HASHING"   -> "Hashing $fileIdx / $total"
                "UPLOADING" -> "Uploading $fileIdx / $total"
                else        -> state
            }
            setForeground(notifier.foregroundInfo(label, file.substringAfterLast('/')))
        }

        override suspend fun onPartUploaded(partNumber: Int, wireSize: Long, numPartsTarget: Int) {
            trackedPartNumber = partNumber
            trackedNumPartsTarget = numPartsTarget
            trackedPackPercent = if (numPartsTarget > 0) {
                (partNumber * 100 / numPartsTarget).coerceIn(0, 100)
            } else 0
            emitProgress(packState = STATE_UPLOADING)

            val partsLabel = applicationContext.resources.getQuantityString(
                R.plurals.parts_finished, partNumber, partNumber,
            )
            val sub = "$partsLabel - $trackedPackPercent%"
            setForeground(notifier.foregroundInfo(
                "Uploading ${trackedFileIndex} / ${trackedFilesTotal}",
                sub,
            ))
        }
    }

    private suspend fun fail(errorType: String, detail: String): Result {
        UserNotifications.error(
            notificationDao, "backup",
            "Backup failed: $detail",
        )
        return Result.failure(workDataOf(
            KEY_ERROR_TYPE to errorType,
            KEY_ERROR_DETAIL to detail,
        ))
    }

    private suspend fun emitProgress(
        packState: String = STATE_UPLOADING,
        errorType: String = ERR_NONE,
        errorDetail: String = "",
    ) {
        setProgress(notifier.buildProgressData(
            packState = packState,
            packPosition = trackedPackPosition,
            partNumber = trackedPartNumber,
            partsTotal = trackedNumPartsTarget,
            fileIndex = trackedFileIndex,
            filesTotal = trackedFilesTotal,
            currentFile = trackedFile,
            errorType = errorType,
            errorDetail = errorDetail,
            packPercent = trackedPackPercent,
        ))
    }
}
