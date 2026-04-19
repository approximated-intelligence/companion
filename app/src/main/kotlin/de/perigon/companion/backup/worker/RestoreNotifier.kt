package de.perigon.companion.backup.worker

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import androidx.work.workDataOf
import de.perigon.companion.MainActivity
import de.perigon.companion.core.ui.NotificationChannels

class RestoreNotifier(private val context: Context) {

    companion object {
        const val NOTIF_ID = 43
        private const val CONTENT_REQUEST_CODE = 9021

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

    fun foregroundInfo(title: String, sub: String = ""): ForegroundInfo {
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_NAVIGATE_TO, "Restore")
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPi = PendingIntent.getActivity(
            context, CONTENT_REQUEST_CODE, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = NotificationCompat.Builder(context, NotificationChannels.BACKUP)
            .setContentTitle(title)
            .setContentText(sub)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentIntent(contentPi)
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            ForegroundInfo(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIF_ID, notif)
    }

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
