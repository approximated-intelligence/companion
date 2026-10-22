package de.perigon.companion.archive.worker

import android.content.Context
import androidx.work.ForegroundInfo
import androidx.work.workDataOf
import de.perigon.companion.archive.BackupChannels
import de.perigon.companion.archive.BackupTargets
import de.perigon.companion.core.feature.NotificationIds
import de.perigon.companion.core.feature.RequestCodes
import de.perigon.companion.core.ui.WorkNotifier

class RestoreNotifier(private val context: Context) {

    companion object {
        const val NOTIF_ID = NotificationIds.BACKUP + 1
        private const val CONTENT_REQUEST_CODE = RequestCodes.BACKUP + 1

        const val KEY_RESTORE_STATE   = "restore_state"
        const val KEY_PACK_POSITION   = "pack_position"
        const val KEY_PACKS_TOTAL     = "packs_total"
        const val KEY_FILE_INDEX      = "file_index"
        const val KEY_FILES_TOTAL     = "files_total"
        const val KEY_CURRENT_FILE    = "current_file"
        const val KEY_ERROR_TYPE      = "error_type"
        const val KEY_ERROR_DETAIL    = "error_detail"

        const val STATE_COUNTING      = "COUNTING"
        const val STATE_REBUILDING    = "REBUILDING"
        const val STATE_RESTORING     = "RESTORING"
        const val STATE_DONE          = "DONE"

        const val ERR_NONE                = "NONE"
        const val ERR_MISSING_CREDENTIALS = "MISSING_CREDENTIALS"
        const val ERR_DECRYPTION_FAILED   = "DECRYPTION_FAILED"
        const val ERR_HASH_MISMATCH       = "HASH_MISMATCH"
        const val ERR_IO_ERROR            = "IO_ERROR"
        const val ERR_NO_PACKS            = "NO_PACKS"
    }

    private val notifier = WorkNotifier(
        context = context,
        notificationId = NOTIF_ID,
        channelId = BackupChannels.BACKUP,
        navigateTo = BackupTargets.RESTORE,
        requestCode = CONTENT_REQUEST_CODE,
    )

    fun foregroundInfo(title: String, sub: String = ""): ForegroundInfo =
        notifier.foregroundInfo(title, sub)

    fun buildProgressData(
        restoreState: String,
        packPosition: Int,
        packsTotal: Int,
        fileIndex: Int,
        filesTotal: Int,
        currentFile: String,
        errorType: String = ERR_NONE,
        errorDetail: String = "",
    ) = workDataOf(
        KEY_RESTORE_STATE to restoreState,
        KEY_PACK_POSITION to packPosition,
        KEY_PACKS_TOTAL   to packsTotal,
        KEY_FILE_INDEX    to fileIndex,
        KEY_FILES_TOTAL   to filesTotal,
        KEY_CURRENT_FILE  to currentFile,
        KEY_ERROR_TYPE    to errorType,
        KEY_ERROR_DETAIL  to errorDetail,
    )
}
