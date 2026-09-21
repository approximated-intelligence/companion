package de.perigon.companion.core.ui

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import de.perigon.companion.R

/**
 * Foreground-service notification for a long-running worker.
 *
 * Any worker that talks to the network for more than a few seconds MUST run
 * in the foreground: a background worker is killed at WorkManager's ten-minute
 * cap, which on a slow uplink arrives mid-upload.
 *
 * [notificationId] and [requestCode] come from the module's range
 * (core.feature.NotificationIds / RequestCodes), so concurrent workers never
 * overwrite each other's notifications.
 */
class WorkNotifier(
    private val context: Context,
    private val notificationId: Int,
    private val channelId: String,
    private val navigateTo: String,
    private val requestCode: Int,
    private val iconRes: Int = R.drawable.ic_stat_companion,
) {
    fun foregroundInfo(title: String, sub: String = ""): ForegroundInfo {
        val notif = NotificationCompat.Builder(context, channelId)
            .setContentTitle(title)
            .setContentText(sub)
            .setSmallIcon(iconRes)
            .setContentIntent(navigatePendingIntent(context, navigateTo, requestCode))
            .setOngoing(true)
            .build()
        return ForegroundInfo(notificationId, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
}
