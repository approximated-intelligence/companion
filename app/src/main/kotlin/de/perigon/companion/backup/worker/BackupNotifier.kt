package de.perigon.companion.backup.worker

import android.content.Context
import androidx.work.ForegroundInfo
import androidx.work.workDataOf
import de.perigon.companion.core.ui.NotificationChannels
import de.perigon.companion.core.ui.WorkNotifier

class BackupNotifier(context: Context) {

    companion object {
        const val NOTIF_ID = 42
        private const val CONTENT_REQUEST_CODE = 9020
    }

    private val notifier = WorkNotifier(
        context = context,
        notificationId = NOTIF_ID,
        channelId = NotificationChannels.BACKUP,
        navigateTo = "Backup",
        requestCode = CONTENT_REQUEST_CODE,
        iconRes = android.R.drawable.ic_menu_upload,
    )

    fun foregroundInfo(title: String, sub: String = ""): ForegroundInfo =
        notifier.foregroundInfo(title, sub)

    fun buildProgressData(
        packState: String,
        packPosition: Int,
        partNumber: Int,
        partsTotal: Int,
        fileIndex: Int,
        filesTotal: Int,
        currentFile: String,
        errorType: String,
        errorDetail: String,
        packPercent: Int = 0,
    ) = workDataOf(
        "pack_state" to packState,
        "pack_position" to packPosition,
        "part_number" to partNumber,
        "parts_total" to partsTotal,
        "file_index" to fileIndex,
        "files_total" to filesTotal,
        "current_file" to currentFile,
        "error_type" to errorType,
        "error_detail" to errorDetail,
        "pack_percent" to packPercent,
    )
}
