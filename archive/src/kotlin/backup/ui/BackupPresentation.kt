package de.perigon.companion.archive.backup.ui

import androidx.compose.runtime.Immutable
import androidx.work.WorkInfo
import de.perigon.companion.archive.backup.worker.BackupWorker

/**
 * The backup worker's progress as the UI sees it. One model and one mapping
 * for every screen showing it (Backup, Consolidate); previously each mapped
 * WorkInfo to its own copy of these eleven fields.
 */
@Immutable
data class BackupProgress(
    val isRunning: Boolean = false,
    val packState: String = BackupWorker.STATE_DONE,
    val packPosition: Int = 0,
    val partNumber: Int = 0,
    val partsTotal: Int = 0,
    val packPercent: Int = 0,
    val fileIndex: Int = 0,
    val filesTotal: Int = 0,
    val currentFile: String = "",
    val errorType: String = BackupWorker.ERR_NONE,
    val errorDetail: String = "",
    /**
     * The destination the worker is writing right now:
     * "B2" or "THUMBDRIVE:<treeUri>". Empty when nothing is running.
     * Drives not equal to this string are idle and ejectable.
     */
    val currentDestination: String = "",
) {
    val hasError: Boolean get() = errorType != BackupWorker.ERR_NONE
}

/** Pure: the info a unique-work list is represented by — the live one, else the latest. */
fun List<WorkInfo>.currentInfo(): WorkInfo? =
    firstOrNull { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
        ?: firstOrNull()

/** Pure: fold one WorkInfo snapshot into [prev]; absent progress keys keep previous values. */
fun WorkInfo.toBackupProgress(prev: BackupProgress): BackupProgress {
    val p = progress
    val out = outputData
    return prev.copy(
        isRunning    = state == WorkInfo.State.RUNNING,
        packState    = p.getString(BackupWorker.KEY_PACK_STATE)
                       ?: if (state == WorkInfo.State.SUCCEEDED) BackupWorker.STATE_DONE else prev.packState,
        packPosition = p.getInt(BackupWorker.KEY_PACK_POSITION, prev.packPosition),
        partNumber   = p.getInt(BackupWorker.KEY_PART_NUMBER, prev.partNumber),
        partsTotal   = p.getInt(BackupWorker.KEY_PARTS_TOTAL, prev.partsTotal),
        packPercent  = p.getInt(BackupWorker.KEY_PACK_PERCENT, prev.packPercent),
        fileIndex    = p.getInt(BackupWorker.KEY_FILE_INDEX, prev.fileIndex),
        filesTotal   = p.getInt(BackupWorker.KEY_FILES_TOTAL, prev.filesTotal),
        currentFile  = p.getString(BackupWorker.KEY_CURRENT_FILE) ?: prev.currentFile,
        errorType    = when (state) {
            WorkInfo.State.FAILED    -> out.getString(BackupWorker.KEY_ERROR_TYPE) ?: BackupWorker.ERR_B2_ERROR
            WorkInfo.State.SUCCEEDED -> BackupWorker.ERR_NONE
            else -> p.getString(BackupWorker.KEY_ERROR_TYPE) ?: prev.errorType
        },
        errorDetail  = when (state) {
            WorkInfo.State.FAILED    -> out.getString(BackupWorker.KEY_ERROR_DETAIL) ?: ""
            WorkInfo.State.SUCCEEDED -> ""
            else -> p.getString(BackupWorker.KEY_ERROR_DETAIL) ?: prev.errorDetail
        },
        currentDestination = p.getString(BackupWorker.KEY_CURRENT_DESTINATION)
                             ?: prev.currentDestination,
    )
}

/** Pure: one wording for pack states on every screen. */
fun backupStateLabel(state: String): String = when (state) {
    BackupWorker.STATE_PLANNING   -> "Planning…"
    BackupWorker.STATE_RECOVERING -> "Recovering interrupted upload…"
    BackupWorker.STATE_UPLOADING  -> "Uploading…"
    BackupWorker.STATE_COMPLETING -> "Completing upload…"
    BackupWorker.STATE_CONFIRMING -> "Confirming…"
    BackupWorker.STATE_DONE       -> "Last run complete"
    else                          -> state
}

/** Pure: one wording for error types on every screen. */
fun backupErrorLabel(type: String): String = when (type) {
    BackupWorker.ERR_MISSING_CREDENTIALS -> "Credentials not configured"
    BackupWorker.ERR_B2_ERROR            -> "Backup storage error"
    BackupWorker.ERR_INCONSISTENT        -> "Inconsistent state - pack was reset"
    BackupWorker.ERR_DRIVE_EJECTED       -> "Thumb drive was ejected"
    else                                 -> "Backup failed ($type)"
}
