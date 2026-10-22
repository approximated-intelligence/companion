package de.perigon.companion.archive.backup.worker

import android.content.Context
import androidx.core.net.toUri
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.goterl.lazysodium.LazySodiumAndroid
import de.perigon.companion.archive.R
import de.perigon.companion.archive.backup.data.BackupSchedulePrefs
import de.perigon.companion.archive.backup.domain.BackupFileSource
import de.perigon.companion.archive.backup.domain.BackupOrchestrator
import de.perigon.companion.archive.backup.domain.EjectGuard
import de.perigon.companion.archive.backup.domain.ThumbDriveBackend
import de.perigon.companion.archive.data.ArchivePrefs
import de.perigon.companion.archive.data.ArchiveSecrets
import de.perigon.companion.archive.data.ArchiveSourceScanner
import de.perigon.companion.archive.data.ArchiveStateRepository
import de.perigon.companion.archive.data.FileHasher
import de.perigon.companion.archive.data.HashFailure
import de.perigon.companion.archive.data.userMessage
import de.perigon.companion.core.data.UserNotificationDao
import de.perigon.companion.core.data.UserNotifications
import de.perigon.companion.core.network.S3Backend
import de.perigon.companion.core.network.S3BackendFactory
import de.perigon.companion.core.saf.DriveEjectedException
import de.perigon.companion.core.saf.ExternalDriveEjectBus
import de.perigon.companion.core.saf.driveUuidOf
import de.perigon.companion.core.saf.folderLabel
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
    private val stateRepo:       ArchiveStateRepository,
    private val scanner:         ArchiveSourceScanner,
    private val s3Factory:       S3BackendFactory,
    private val lazySodium:      LazySodiumAndroid,
    private val appPrefs:        ArchivePrefs,
    private val credentialStore: ArchiveSecrets,
    private val schedulePrefs:   BackupSchedulePrefs,
    private val notificationDao: UserNotificationDao,
    private val hasher:          FileHasher,
    private val ejectBus:        ExternalDriveEjectBus,
) : CoroutineWorker(ctx, params) {

    companion object {
        // The single backup EXECUTION slot. Both the manual button and the
        // periodic BackupTriggerWorker enqueue OneTime work under this name
        // with KEEP, so two backups can never overlap. Value is "backup_run"
        // (not "backup") so CompanionApp can unconditionally evict the
        // pre-funnel periodic that used to live under "backup".
        const val WORK_NAME          = "backup_run"

        /** Explicit destination list, e.g. ["THUMBDRIVE:content://…", "B2"]. */
        const val KEY_DESTINATIONS   = "destinations"

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
        /**
         * The destination string the worker is writing right now. Consumed by
         * the destination picker to decide which drive (if any) is busy and so
         * must not be "ejected". Empty when nothing is running.
         */
        const val KEY_CURRENT_DESTINATION = "current_destination"

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
        const val ERR_DRIVE_EJECTED       = "DRIVE_EJECTED"
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
     * Destination string currently being written ("B2" or "THUMBDRIVE:…").
     * Advertised through emitProgress() so the eject UI knows which drive is
     * busy; drives with a different string are idle and ejectable.
     */
    private var trackedDestination   = ""

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
        val destinations: List<String> = inputData.getNullableStringArray(KEY_DESTINATIONS)
            ?.filterNotNull()
            ?: listOf("B2")

        val serverPkHex  = appPrefs.naclPkHex()
        val phoneSkHex   = credentialStore.phoneSecretKey()
        val phonePkHex   = appPrefs.phonePkHex()

        // NaCl keys required for BOTH destinations (same pack format).
        if (serverPkHex == null || phoneSkHex == null || phonePkHex == null) {
            return fail(ERR_MISSING_CREDENTIALS, "NaCl keys not configured")
        }
        // B2 credentials required iff B2 is one of the destinations.
        val b2Selected = destinations.any { it == "B2" }
        val b2Config = if (b2Selected) appPrefs.b2Config() else null
        val b2RwAppKey = if (b2Selected) credentialStore.b2RwAppKey() else null
        if (b2Selected && (b2Config == null || b2RwAppKey == null)) {
            return fail(ERR_MISSING_CREDENTIALS, "B2 credentials not configured")
        }

        setForeground(notifier.foregroundInfo("Starting backup…"))
        val numParts = schedulePrefs.numPartsPerPack()

        return try {
            val results = mutableListOf<Pair<String, String>>()

            for (dest in destinations) {
                // Advertise the active destination before any sub-progress, so
                // the eject UI can disable only the busy drive's button and
                // keep every other drive (and B2) idle-friendly.
                trackedDestination = dest
                emitProgress()

                // Construct the eject guard ONCE per destination. `ForDrive`
                // is required for a THUMBDRIVE destination — a destination
                // with a tree URI but no volume UUID is a configuration error,
                // not a silent no-op.
                val driveUuid: String? = if (dest.startsWith("THUMBDRIVE:")) {
                    driveUuidOf(dest.removePrefix("THUMBDRIVE:").toUri())
                } else null

                if (dest.startsWith("THUMBDRIVE:") && driveUuid == null) {
                    results += dest to "FAILED: no volume UUID for drive"
                    continue
                }

                val driveGuard: EjectGuard.ForDrive? =
                    driveUuid?.let { EjectGuard.ForDrive(ejectBus, it) }
                val orchestratorGuard: EjectGuard = driveGuard ?: EjectGuard.None

                // Pre-flight: skip a destination the platform has already
                // begun tearing down, before opening a multipart upload.
                try {
                    orchestratorGuard.check()
                } catch (e: DriveEjectedException) {
                    results += dest to "SKIPPED: drive ejected"
                    continue
                }

                val (backend, useDbState, label) = when {
                    dest == "B2" -> Triple(
                        s3Factory.create(b2Config!!.endpoint, b2Config.bucket, b2Config.keyId, b2RwAppKey!!, b2Config.region),
                        true,
                        "B2",
                    )
                    dest.startsWith("THUMBDRIVE:") -> {
                        val treeUri = dest.removePrefix("THUMBDRIVE:").toUri()
                        Triple(
                            ThumbDriveBackend(applicationContext, treeUri, requireNotNull(driveGuard)) as S3Backend,
                            false,
                            folderLabel(treeUri),
                        )
                    }
                    else -> continue
                }

                setForeground(notifier.foregroundInfo("Backing up to $label…"))

                try {
                    BackupOrchestrator(
                        fileSource   = AndroidBackupFileSource(applicationContext),
                        serverPk     = serverPkHex.fromHex(),
                        phoneSk      = phoneSkHex.fromHex(),
                        sodium       = lazySodium,
                        scanner      = scanner,
                        stateRepo    = stateRepo,
                        hasher       = hasher,
                        partsPerPack = numParts,
                        packPrefix   = phonePkHex,
                        ejectGuard   = orchestratorGuard,
                    ).run(backend, buildProgressListener(label), useDbState)
                    results += label to "OK"
                } catch (e: DriveEjectedException) {
                    // The drive went away mid-transfer. Not a failure of the
                    // run as a whole: the B2 leg (if any) still proceeds, and
                    // the user needs to know the drive needs re-inserting.
                    android.util.Log.i("BackupWorker", "drive ejected mid-run: ${e.message}")
                    results += label to "EJECTED: ${e.message}"
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w("BackupWorker", "destination '$label' failed", e)
                    results += label to "FAILED: ${e.message ?: e.javaClass.simpleName}"
                }
            }

            // Surface accumulated skips BEFORE "Backup complete", so the user
            // does not read "complete" and then discover a skip summary below.
            // postSkipSummary catches its own non-cancellation exceptions so a
            // notification DB failure cannot mask a successful run.
            postSkipSummary()

            val failed = results.filter { it.second.startsWith("FAILED") }
            val ejected = results.filter {
                it.second.startsWith("SKIPPED") || it.second.startsWith("EJECTED")
            }
            val ok = results.filter { it.second == "OK" }
            val summary = results.joinToString { "${it.first}: ${it.second}" }

            when {
                results.isEmpty() -> fail(ERR_B2_ERROR, "No destinations")
                ok.isNotEmpty() -> {
                    UserNotifications.success(notificationDao, "backup", "Backup complete — $summary")
                    Result.success()
                }
                // All outcomes were eject-related (pre-flight SKIPPED or
                // mid-run EJECTED) — a user action, not a transport failure.
                ejected.size == results.size -> fail(ERR_DRIVE_EJECTED, summary)
                // Some FAILED, no OK: a real failure.
                else -> fail(ERR_B2_ERROR, "No destination succeeded — $summary")
            }
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

    private fun buildProgressListener(label: String) = object : BackupOrchestrator.ProgressListener {
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
            val statusLabel = when (state) {
                "PLANNING"   -> "Scanning files…"
                "RECOVERING" -> "Recovering…"
                "COMPLETING" -> "Completing pack…"
                "CONFIRMING" -> "Confirming pack…"
                else         -> state
            }
            setForeground(notifier.foregroundInfo("$label: $statusLabel"))
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
                "$label: Hashing $hashed / $misses",
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
                "$label: Uploading $fileIdx / $totalFiles",
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
                "$label: Uploading $trackedFileIndex / $trackedFilesTotal",
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
            currentDestination = trackedDestination,
        ))
    }
}