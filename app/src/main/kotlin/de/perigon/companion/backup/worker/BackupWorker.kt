package de.perigon.companion.backup.worker

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.goterl.lazysodium.LazySodiumAndroid
import de.perigon.companion.R
import de.perigon.companion.backup.data.BackupSourceScanner
import de.perigon.companion.backup.data.BackupStateRepository
import de.perigon.companion.backup.data.BackupSchedulePrefs
import de.perigon.companion.backup.domain.BackupFileSource
import de.perigon.companion.backup.domain.BackupOrchestrator
import de.perigon.companion.util.FileHasher
import de.perigon.companion.util.network.S3BackendFactory
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
}

@HiltWorker
class BackupWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val stateRepo:       BackupStateRepository,
    private val scanner:         BackupSourceScanner,
    private val s3Factory:       S3BackendFactory,
    private val lazySodium:      LazySodiumAndroid,
    private val appPrefs:        AppPrefs,
    private val credentialStore: CredentialStore,
    private val schedulePrefs:   BackupSchedulePrefs,
    private val notificationDao: UserNotificationDao,
    private val hasher:          FileHasher,
) : CoroutineWorker(ctx, params) {

    companion object {
        const val WORK_NAME          = "backup"

        const val KEY_PACK_POSITION  = "pack_position"
        const val KEY_PACK_STATE     = "pack_state"
        const val KEY_PART_NUMBER    = "part_number"
        const val KEY_PARTS_TOTAL    = "parts_total"
        const val KEY_FILE_INDEX     = "file_index"
        const val KEY_FILES_TOTAL    = "files_total"
        const val KEY_CURRENT_FILE   = "current_file"
        const val KEY_ERROR_TYPE     = "error_type"
        const val KEY_ERROR_DETAIL   = "error_detail"
        const val KEY_PACK_PERCENT   = "pack_percent"

        const val STATE_PLANNING     = "PLANNING"
        const val STATE_RECOVERING   = "RECOVERING"
        const val STATE_UPLOADING    = "UPLOADING"
        const val STATE_COMPLETING   = "COMPLETING"
        const val STATE_CONFIRMING   = "CONFIRMING"
        const val STATE_DONE         = "DONE"

        const val ERR_NONE                = "NONE"
        const val ERR_MISSING_CREDENTIALS = "MISSING_CREDENTIALS"
        const val ERR_B2_ERROR            = "B2_ERROR"
        const val ERR_INCONSISTENT        = "INCONSISTENT"
    }

    private val notifier = BackupNotifier(applicationContext)

    private var trackedFile          = ""
    private var trackedFileIndex     = 0
    private var trackedFilesTotal    = 0
    private var trackedPackPosition  = 0
    private var trackedPackState     = STATE_PLANNING
    private var trackedPartNumber    = 0
    private var trackedPackPercent   = 0
    private var trackedPartsTotal    = 0

    override suspend fun doWork(): Result {
        val b2Config     = appPrefs.b2Config()
        val b2RwAppKey   = credentialStore.b2RwAppKey()
        val serverPkHex  = appPrefs.naclPkHex()
        val phoneSkHex   = credentialStore.phoneSecretKey()
        val phonePkHex   = appPrefs.phonePkHex()

        if (b2Config == null || b2RwAppKey == null || serverPkHex == null ||
            phoneSkHex == null || phonePkHex == null) {
            return fail(ERR_MISSING_CREDENTIALS, "B2 credentials or NaCl keys not configured")
        }

        setForeground(notifier.foregroundInfo("Starting backup…"))

        val numParts = schedulePrefs.numPartsPerPack()

        return try {
            val b2 = s3Factory.create(b2Config.endpoint, b2Config.bucket, b2Config.keyId, b2RwAppKey, b2Config.region)
            val orchestrator = BackupOrchestrator(
                fileSource   = AndroidBackupFileSource(applicationContext.contentResolver),
                serverPk     = serverPkHex.fromHex(),
                phoneSk      = phoneSkHex.fromHex(),
                sodium       = lazySodium,
                scanner      = scanner,
                stateRepo    = stateRepo,
                hasher       = hasher,
                b2           = b2,
                partsPerPack = numParts,
                packPrefix   = phonePkHex,
            )

            orchestrator.run(buildProgressListener())
            emitProgress(packState = STATE_DONE)
            UserNotifications.success(notificationDao, "backup", "Backup complete")
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
        override suspend fun onPackStateChange(state: String, packNumber: Int) {
            if (trackedPackPosition != packNumber) {
                trackedPartNumber  = 0
                trackedPackPercent = 0
            }
            trackedPackPosition = packNumber
            trackedPackState = when (state) {
                "PLANNING"   -> STATE_PLANNING
                "RECOVERING" -> STATE_RECOVERING
                "UPLOADING"  -> STATE_UPLOADING
                "COMPLETING" -> STATE_COMPLETING
                "CONFIRMING" -> STATE_CONFIRMING
                else         -> state
            }
            emitProgress()
            val label = when (state) {
                "PLANNING"   -> "Scanning files…"
                "RECOVERING" -> "Recovering…"
                "COMPLETING" -> "Completing pack…"
                "CONFIRMING" -> "Confirming pack…"
                else         -> state
            }
            setForeground(notifier.foregroundInfo(label))
        }

        override suspend fun onFileProgress(packNumber: Int, file: String, fileIdx: Int, totalFiles: Int) {
            if (trackedPackPosition != packNumber) {
                trackedPartNumber  = 0
                trackedPackPercent = 0
            }
            trackedPackPosition = packNumber
            trackedFile         = file
            trackedFileIndex    = fileIdx
            trackedFilesTotal   = totalFiles
            trackedPackState    = STATE_UPLOADING
            emitProgress()
            setForeground(notifier.foregroundInfo(
                "Uploading $fileIdx / $totalFiles",
                file.substringAfterLast('/'),
            ))
        }

        override suspend fun onPartUploaded(partsDone: Int, partsTotal: Int) {
            trackedPartNumber  = partsDone
            trackedPartsTotal  = partsTotal
            trackedPackPercent = if (partsTotal > 0) (partsDone * 100 / partsTotal).coerceIn(0, 100) else 0
            trackedPackState   = STATE_UPLOADING
            emitProgress()
            val partsLabel = applicationContext.resources.getQuantityString(
                R.plurals.parts_finished, partsDone, partsDone,
            )
            setForeground(notifier.foregroundInfo(
                "Uploading $trackedFileIndex / $trackedFilesTotal",
                "$partsLabel - $trackedPackPercent%",
            ))
        }
    }

    private suspend fun fail(errorType: String, detail: String): Result {
        UserNotifications.error(notificationDao, "backup", "Backup failed: $detail")
        return Result.failure(workDataOf(
            KEY_ERROR_TYPE   to errorType,
            KEY_ERROR_DETAIL to detail,
        ))
    }

    private suspend fun emitProgress(
        packState:   String = trackedPackState,
        errorType:   String = ERR_NONE,
        errorDetail: String = "",
    ) {
        setProgress(notifier.buildProgressData(
            packState    = packState,
            packPosition = trackedPackPosition,
            partNumber   = trackedPartNumber,
            partsTotal   = trackedPartsTotal,
            fileIndex    = trackedFileIndex,
            filesTotal   = trackedFilesTotal,
            currentFile  = trackedFile,
            errorType    = errorType,
            errorDetail  = errorDetail,
            packPercent  = trackedPackPercent,
        ))
    }
}
