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

class BackupNotifier(private val context: Context) {

    companion object {
        const val NOTIF_ID = 42
        private const val CONTENT_REQUEST_CODE = 9020
    }

    fun foregroundInfo(title: String, sub: String = ""): ForegroundInfo {
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_NAVIGATE_TO, "Backup")
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
