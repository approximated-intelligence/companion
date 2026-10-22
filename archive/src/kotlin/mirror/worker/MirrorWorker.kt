package de.perigon.companion.archive.mirror.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import de.perigon.companion.archive.data.HashFailure
import de.perigon.companion.archive.data.HashKey
import de.perigon.companion.archive.data.userMessage
import de.perigon.companion.archive.mirror.domain.MirrorEngine
import de.perigon.companion.core.data.UserNotificationDao
import de.perigon.companion.core.data.UserNotifications
import de.perigon.companion.core.saf.DriveEjectedException
import de.perigon.companion.core.saf.DriveInfo
import de.perigon.companion.core.saf.ExternalDriveEjectBus
import de.perigon.companion.core.saf.driveUuidOf
import de.perigon.companion.core.saf.enumerateConnectedDrives
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "MirrorWorker"

@HiltWorker
class MirrorWorker @AssistedInject constructor(
    @Assisted private val ctx: Context,
    @Assisted params: WorkerParameters,
    private val engine: MirrorEngine,
    private val notificationDao: UserNotificationDao,
    private val ejectBus: ExternalDriveEjectBus,
) : CoroutineWorker(ctx, params) {

    companion object {
        const val KEY_MODE = "mode"
        const val MODE_SYNC = "SYNC"
        const val MODE_VERIFY = "VERIFY"

        const val WORK_SYNC = "mirror_sync"
        const val WORK_VERIFY = "mirror_verify"

        const val KEY_PHASE = "phase"
        const val KEY_DEST_INDEX = "dest_index"
        const val KEY_DEST_TOTAL = "dest_total"
        const val KEY_DEST_LABEL = "dest_label"
        /**
         * treeUri of the drive the worker is currently touching. Read by the
         * Mirror screen to disable that drive's eject button while writes
         * are in flight; drives whose URI is not this stay ejectable.
         */
        const val KEY_DEST_URI = "dest_uri"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_CURRENT = "current"
    }

    private val notifier = MirrorNotifier(ctx)

    override suspend fun doWork(): Result {
        val mode = inputData.getString(KEY_MODE) ?: return Result.failure()
        val drives = enumerateConnectedDrives(ctx)
        if (drives.isEmpty()) {
            UserNotifications.error(notificationDao, "mirror", "No drives connected")
            return Result.success()
        }
        return when (mode) {
            MODE_SYNC -> runSync(drives)
            MODE_VERIFY -> runVerify(drives)
            else -> Result.failure()
        }
    }

    private suspend fun runSync(drives: List<DriveInfo>): Result {
        var copied = 0; var unchanged = 0
        var sourceFiles = 0; var unreadable = 0; var enabledFolders = 0
        var anyMissingDcim = false
        var ejectedCount = 0
        var firstSkip: HashFailure? = null
        val allFailures = mutableListOf<HashFailure>()
        val sumsWrites = mutableListOf<String>()

        for ((idx, drive) in drives.withIndex()) {
            val driveUuid = driveUuidOf(drive.treeUri)
            if (driveUuid != null && ejectBus.isEjecting(driveUuid)) {
                ejectedCount++
                allFailures += HashFailure(
                    HashKey("(drive ${drive.label})", 0L, 0L),
                    DriveEjectedException("drive ${drive.label} was ejected before sync"),
                )
                continue
            }

            setForeground(notifier.sync.foregroundInfo(
                "Mirroring ${idx + 1}/${drives.size}: ${drive.label}", "Scanning…"))
            try {
                val outcome = engine.sync(drive.treeUri) { phase, current, done, total ->
                    setProgress(workDataOf(
                        KEY_PHASE to phase,
                        KEY_DEST_INDEX to idx + 1,
                        KEY_DEST_TOTAL to drives.size,
                        KEY_DEST_LABEL to drive.label,
                        KEY_DEST_URI to drive.treeUri.toString(),
                        KEY_DONE to done,
                        KEY_TOTAL to total,
                        KEY_CURRENT to current,
                    ))
                    setForeground(notifier.sync.foregroundInfo(
                        "Mirroring ${idx + 1}/${drives.size}: ${drive.label}",
                        when (phase) {
                            "scan" -> "Hashing $done / $total"
                            "copy" -> "$done / $total — ${current.substringAfterLast('/')}"
                            else -> phase
                        },
                    ))
                }
                copied += outcome.copied
                unchanged += outcome.unchanged
                sourceFiles = maxOf(sourceFiles, outcome.sourceFiles)
                unreadable = maxOf(unreadable, outcome.unreadable)
                enabledFolders = maxOf(enabledFolders, outcome.enabledFolders)
                anyMissingDcim = anyMissingDcim || outcome.missingDcimGrant
                outcome.sumsFile?.let { sumsWrites += "${drive.label}/$it" }
                if (firstSkip == null) firstSkip = outcome.failures.firstOrNull()
                allFailures += outcome.failures
            } catch (e: DriveEjectedException) {
                Log.i(TAG, "drive ${drive.label} ejected mid-sync")
                ejectedCount++
                allFailures += HashFailure(
                    HashKey("(drive ${drive.label})", 0L, 0L), e,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "sync failed for ${drive.label}", e)
                allFailures += HashFailure(HashKey("(drive ${drive.label})", 0L, 0L), e)
            }
        }

        val msg = buildString {
            when {
                anyMissingDcim -> append("Mirror: DCIM grant is stale — re-grant in Settings")
                enabledFolders == 0 -> append("Mirror: no source folders configured")
                unreadable > 0 && sourceFiles == 0 ->
                    append("Mirror: $unreadable source file(s) exist but are unreadable — ")
                        .append(shortReason(firstSkip))
                sourceFiles == 0 -> append("Mirror: no source files found (DCIM empty)")
                ejectedCount == drives.size -> append("Mirror: all drives were ejected")
                copied == 0 -> append("Mirror: already up to date ($unchanged files)")
                else -> append("Mirror: $copied copied, $unchanged unchanged " +
                    "across ${drives.size - ejectedCount} drive(s)")
            }
            if (ejectedCount > 0 && ejectedCount < drives.size) {
                append(" — $ejectedCount drive(s) ejected")
            }
            if (unreadable > 0 && sourceFiles > 0) {
                append(" — $unreadable source file(s) unreadable")
            }
            if (sumsWrites.isNotEmpty()) {
                append(" (sums: ${sumsWrites.joinToString()})")
            }
        }

        when {
            anyMissingDcim -> UserNotifications.error(notificationDao, "mirror", msg)
            unreadable > 0 && sourceFiles == 0 -> UserNotifications.error(notificationDao, "mirror", msg)
            ejectedCount == drives.size -> UserNotifications.error(notificationDao, "mirror", msg)
            else -> UserNotifications.success(notificationDao, "mirror", msg)
        }

        if (allFailures.isNotEmpty()) {
            val detail = allFailures.take(10).joinToString("\n") { it.userMessage() }
            UserNotifications.info(
                notificationDao, "mirror",
                "${allFailures.size} file(s) not mirrored",
                if (allFailures.size > 10) "$detail\n… and ${allFailures.size - 10} more" else detail,
            )
        }
        return Result.success()
    }

    private suspend fun runVerify(drives: List<DriveInfo>): Result {
        var okSrc = 0; var okSum = 0; var mismatch = 0; var unverifiable = 0; var notMirrored = 0
        var sourceFiles = 0; var sourceUnreadable = 0; var enabledFolders = 0
        var missingFolders = 0; var anyMissingDcim = false; var ejectedCount = 0
        val allMismatches = mutableListOf<String>()
        val allUnverifiableReasons = mutableMapOf<String, Int>()

        for ((idx, drive) in drives.withIndex()) {
            val driveUuid = driveUuidOf(drive.treeUri)
            if (driveUuid != null && ejectBus.isEjecting(driveUuid)) {
                ejectedCount++
                continue
            }

            setForeground(notifier.verify.foregroundInfo(
                "Checking ${idx + 1}/${drives.size}: ${drive.label}", "Scanning…"))
            try {
                val outcome = engine.verify(drive.treeUri) { phase, current, done, total ->
                    setProgress(workDataOf(
                        KEY_PHASE to phase,
                        KEY_DEST_INDEX to idx + 1,
                        KEY_DEST_TOTAL to drives.size,
                        KEY_DEST_LABEL to drive.label,
                        KEY_DEST_URI to drive.treeUri.toString(),
                        KEY_DONE to done,
                        KEY_TOTAL to total,
                        KEY_CURRENT to current,
                    ))
                    setForeground(notifier.verify.foregroundInfo(
                        "Checking ${idx + 1}/${drives.size}: ${drive.label}",
                        if (total > 0) "$done / $total — ${current.substringAfterLast('/')}"
                        else phase,
                    ))
                }
                anyMissingDcim = anyMissingDcim || outcome.missingDcimGrant
                if (outcome.mirrorFolderMissing) {
                    missingFolders++
                } else if (!outcome.missingDcimGrant) {
                    okSrc += outcome.okFromSource
                    okSum += outcome.okFromShaSum
                    mismatch += outcome.mismatch
                    unverifiable += outcome.unverifiable
                    notMirrored += outcome.notMirrored
                    sourceFiles = maxOf(sourceFiles, outcome.sourceFiles)
                    sourceUnreadable = maxOf(sourceUnreadable, outcome.sourceUnreadable)
                    enabledFolders = maxOf(enabledFolders, outcome.enabledFolders)
                    allMismatches += outcome.mismatchPaths
                    outcome.unverifiableReasons.forEach { (k, v) ->
                        allUnverifiableReasons[k] = (allUnverifiableReasons[k] ?: 0) + v
                    }
                }
            } catch (e: DriveEjectedException) {
                Log.i(TAG, "drive ${drive.label} ejected mid-verify")
                ejectedCount++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "verify failed for ${drive.label}", e)
                unverifiable++
            }
        }

        val totalOk = okSrc + okSum
        val msg = when {
            anyMissingDcim ->
                "Check: DCIM grant is stale — cannot compare against source. Re-grant in Settings."
            missingFolders == drives.size ->
                "Check: no Mirror folder on any connected drive"
            ejectedCount == drives.size ->
                "Check: all drives were ejected before they could be checked"
            sourceFiles == 0 && sourceUnreadable > 0 ->
                "Check: $sourceUnreadable source file(s) unreadable — re-check after indexing"
            sourceFiles == 0 && enabledFolders == 0 ->
                "Check: no source folders configured"
            sourceFiles == 0 ->
                "Check: DCIM is empty — nothing to compare"
            else -> buildString {
                append("Check: $totalOk OK ($okSrc vs source, $okSum vs SHA256SUM), ")
                append("$mismatch mismatch, $unverifiable unverifiable, $notMirrored not mirrored")
                if (sourceUnreadable > 0) append(", $sourceUnreadable source unreadable")
                if (missingFolders > 0) append(" — $missingFolders drive(s) no Mirror folder")
                if (ejectedCount > 0) append(" — $ejectedCount drive(s) ejected")
            }
        }

        if (anyMissingDcim || (sourceFiles == 0 && sourceUnreadable > 0)
            || mismatch > 0 || unverifiable > 0 || ejectedCount == drives.size) {
            val detailParts = mutableListOf<String>()
            if (allMismatches.isNotEmpty()) {
                detailParts += "Mismatched:\n" + allMismatches.take(10).joinToString("\n") +
                    if (allMismatches.size > 10) "\n… and ${allMismatches.size - 10} more" else ""
            }
            if (allUnverifiableReasons.isNotEmpty()) {
                detailParts += "Unverifiable: " + allUnverifiableReasons.entries
                    .joinToString(", ") { (r, c) -> "$c × $r" }
            }
            UserNotifications.error(notificationDao, "mirror", msg,
                detailParts.joinToString("\n\n").ifEmpty { null })
        } else {
            UserNotifications.success(notificationDao, "mirror", msg)
        }
        return Result.success()
    }

    private fun shortReason(f: HashFailure?): String {
        val msg = f?.userMessage() ?: return "unknown"
        val colon = msg.indexOf(':')
        return if (colon >= 0 && colon < msg.length - 2) msg.substring(colon + 2) else msg
    }
}
