package de.perigon.companion.backup.worker

import android.content.ContentResolver
import android.content.Context
import androidx.core.net.toUri
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.goterl.lazysodium.LazySodiumAndroid
import de.perigon.companion.backup.R
import de.perigon.companion.backup.data.BackupSourceScanner
import de.perigon.companion.backup.data.BackupStateRepository
import de.perigon.companion.backup.data.BackupSchedulePrefs
import de.perigon.companion.backup.data.HashFailure
import de.perigon.companion.backup.data.userMessage
import de.perigon.companion.backup.domain.BackupFileSource
import de.perigon.companion.backup.domain.BackupOrchestrator
import de.perigon.companion.backup.data.FileHasher
import de.perigon.companion.core.network.S3BackendFactory
import de.perigon.companion.core.data.UserNotificationDao
import de.perigon.companion.core.data.UserNotifications
import de.perigon.companion.backup.data.BackupPrefs
import de.perigon.companion.backup.data.BackupSecrets
import de.perigon.companion.core.saf.openInputStream
import de.perigon.companion.core.util.fromHex
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.InputStream
import kotlin.coroutines.cancellation.CancellationException

private class AndroidBackupFileSource(private val context: Context) : BackupFileSource {
    override fun open(uri: String): InputStream =
        openInputStream(context, uri.toUri())
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
    private val appPrefs:        BackupPrefs,
    private val credentialStore: BackupSecrets,
    private val schedulePrefs:   BackupSchedulePrefs,
    private val notificationDao: UserNotificationDao,
    private val hasher:          FileHasher,
) : CoroutineWorker(ctx, params) {

    companion object {
        // The single backup EXECUTION slot. Both the manual button and the
        // periodic BackupTriggerWorker enqueue OneTime work under this name
        // with KEEP, so two backups can never overlap. Value is "backup_run"
        // (not "backup") so CompanionApp can unconditionally evict the
        // pre-funnel periodic that used to live under "backup".
        const val WORK_NAME          = "backup_run"

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

    /**
     * Files the pipeline could not include this run — accumulated from both
     * the scan phase (onFilesSkipped from run()) and the upload phase
     * (onFilesSkipped from execute()), and surfaced as a single ledger entry
     * before the terminal success/failure notification. Not cleared between
     * calls on purpose: the accumulator IS the run-scoped state, and
     * postSkipSummary() empties it.
     */
    private val skippedFiles = mutableListOf<HashFailure>()

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
                fileSource   = AndroidBackupFileSource(applicationContext),
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

            // Surface accumulated skips BEFORE "Backup complete", so the user
            // does not read "complete" and then discover a skip summary below.
            // postSkipSummary catches its own non-cancellation exceptions so a
            // notification DB failure cannot mask a successful run.
            postSkipSummary()

            UserNotifications.success(notificationDao, "backup", "Backup complete")
            Result.success()
        } catch (e: CancellationException) {
            // Rethrow: swallowing CE recorded a user cancel as SUCCEEDED,
            // which observers (Consolidate screen) treated as a clean run.
            android.util.Log.i("BackupWorker", "Backup cancelled")
            throw e
        } catch (e: Exception) {
            android.util.Log.e("BackupWorker", "Backup failed", e)

            // Surface what was skipped BEFORE the "Backup failed" notification
            // — a failed run with 5 skips and a failed run with 0 skips are
            // different stories to the user. postSkipSummary no-ops when the
            // accumulator is empty.
            postSkipSummary()

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

        override suspend fun onScanProgress(currentFile: String, hashed: Int, misses: Int) {
            // Hash-phase progress: fires (throttled to ~2s) per newly hashed
            // file. Steady-state scans have ~0 misses and stay in "Scanning…".
            trackedFile       = currentFile
            trackedFileIndex  = hashed
            trackedFilesTotal = misses
            trackedPackState  = STATE_PLANNING
            emitProgress()
            setForeground(notifier.foregroundInfo(
                "Hashing $hashed / $misses",
                currentFile.substringAfterLast('/'),
            ))
        }

        override suspend fun onFilesSkipped(failures: List<HashFailure>) {
            // Accumulate only; surfaced once at the terminal transition.
            // Called up to twice per run — once for scan skips, once for
            // upload skips — so a single notification avoids drowning the user
            // in near-identical entries.
            skippedFiles += failures
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

    /**
     * One ledger entry summarising every file the run could not include —
     * scan-phase skips (unknown size, unreadable, un-indexed) plus
     * upload-phase skips (changed on disk, plan row gone, stream failure).
     * Each line is presentation-ready via [userMessage]. Empties the
     * accumulator so a second call is a no-op — the caller invokes this
     * exactly once on the success path (before "Backup complete") and once
     * on the failure path (before "Backup failed"), never both with content.
     *
     * Swallows non-cancellation exceptions: a notification DB failure must
     * not turn a successful backup into a reported failure, and on the
     * failure path it must not mask the original error. The per-file reasons
     * are already in logcat from the call sites, so the console trail
     * survives a notification miss.
     */
    private suspend fun postSkipSummary() {
        if (skippedFiles.isEmpty()) return

        val shown = skippedFiles.take(10).joinToString("\n") { it.userMessage() }
        val detail = if (skippedFiles.size > 10) {
            "$shown\n… and ${skippedFiles.size - 10} more"
        } else shown

        try {
            UserNotifications.info(
                notificationDao, "backup",
                "${skippedFiles.size} file(s) skipped during backup",
                detail,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("BackupWorker", "failed to post skip summary", e)
        } finally {
            skippedFiles.clear()
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
