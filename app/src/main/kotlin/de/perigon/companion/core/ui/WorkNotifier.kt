package de.perigon.companion.core.ui

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import de.perigon.companion.MainActivity

/**
 * Foreground-service notification for a long-running worker.
 *
 * Any worker that talks to the network for more than a few seconds MUST run
 * in the foreground: a plain background worker is killed at WorkManager's
 * ten-minute execution cap, which on a slow uplink arrives mid-upload and
 * leaves the caller's state stranded with no error to report.
 *
 * Each worker gets its own notification id so a publish and a backup can run
 * concurrently without overwriting each other's progress.
 */
class WorkNotifier(
    private val context: Context,
    private val notificationId: Int,
    private val channelId: String,
    private val navigateTo: String,
    private val requestCode: Int,
    private val iconRes: Int,
) {
    fun foregroundInfo(title: String, sub: String = ""): ForegroundInfo {
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_NAVIGATE_TO, navigateTo)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPi = PendingIntent.getActivity(
            context, requestCode, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notif = NotificationCompat.Builder(context, channelId)
            .setContentTitle(title)
            .setContentText(sub)
            .setSmallIcon(iconRes)
            .setContentIntent(contentPi)
            .setOngoing(true)
            .build()
        return ForegroundInfo(notificationId, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
}
