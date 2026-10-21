package de.perigon.companion.backup.worker

import android.content.Context
import androidx.work.ForegroundInfo
import androidx.work.workDataOf
import de.perigon.companion.backup.BackupChannels
import de.perigon.companion.backup.BackupTargets
import de.perigon.companion.core.feature.NotificationIds
import de.perigon.companion.core.feature.RequestCodes
import de.perigon.companion.core.ui.WorkNotifier

class BackupNotifier(context: Context) {

    companion object {
        const val NOTIF_ID = NotificationIds.BACKUP
        private const val CONTENT_REQUEST_CODE = RequestCodes.BACKUP
    }

    private val notifier = WorkNotifier(
        context = context,
        notificationId = NOTIF_ID,
        channelId = BackupChannels.BACKUP,
        navigateTo = BackupTargets.BACKUP,
        requestCode = CONTENT_REQUEST_CODE,
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
        currentDestination: String = "",
    ) = workDataOf(
        BackupWorker.KEY_PACK_STATE to packState,
        BackupWorker.KEY_PACK_POSITION to packPosition,
        BackupWorker.KEY_PART_NUMBER to partNumber,
        BackupWorker.KEY_PARTS_TOTAL to partsTotal,
        BackupWorker.KEY_FILE_INDEX to fileIndex,
        BackupWorker.KEY_FILES_TOTAL to filesTotal,
        BackupWorker.KEY_CURRENT_FILE to currentFile,
        BackupWorker.KEY_ERROR_TYPE to errorType,
        BackupWorker.KEY_ERROR_DETAIL to errorDetail,
        BackupWorker.KEY_PACK_PERCENT to packPercent,
        BackupWorker.KEY_CURRENT_DESTINATION to currentDestination,
    )
}
