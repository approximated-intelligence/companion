package de.perigon.companion.core.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/**
 * Central registration of all notification channels.
 * Called once from [CompanionApp.onCreate].
 *
 * Individual services and workers no longer create channels themselves —
 * they reference the channel IDs defined here.
 */
object NotificationChannels {

    const val BACKUP = "backup"
    const val CONSOLIDATE = "consolidate"
    const val TRACK_RECORDER = "track_recorder"
    const val AUDIO = "audio_recorder"

    fun ensureAll(context: Context) {
        val mgr = context.getSystemService(NotificationManager::class.java)

        ensureChannel(mgr, BACKUP, "Backup", NotificationManager.IMPORTANCE_LOW)
        ensureChannel(mgr, CONSOLIDATE, "Consolidation", NotificationManager.IMPORTANCE_LOW)
        ensureChannel(mgr, TRACK_RECORDER, "Background Track", NotificationManager.IMPORTANCE_LOW)
        ensureChannel(mgr, AUDIO, "Audio Recording", NotificationManager.IMPORTANCE_LOW)
    }

    private fun ensureChannel(
        mgr: NotificationManager,
        id: String,
        name: String,
        importance: Int,
    ) {
        if (mgr.getNotificationChannel(id) == null) {
            mgr.createNotificationChannel(NotificationChannel(id, name, importance))
        }
    }
}
