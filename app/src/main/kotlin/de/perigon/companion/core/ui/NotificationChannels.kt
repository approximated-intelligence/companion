package de.perigon.companion.core.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import de.perigon.companion.core.feature.ChannelSpec

/**
 * Creates the channels contributed by the features of this build. Called
 * synchronously from Application.onCreate: a worker or service started in the
 * same process start must never post to a channel that doesn't exist yet.
 */
object NotificationChannels {
    fun ensure(context: Context, specs: Collection<ChannelSpec>) {
        val mgr = context.getSystemService(NotificationManager::class.java)
        for (spec in specs) {
            if (mgr.getNotificationChannel(spec.id) == null) {
                mgr.createNotificationChannel(NotificationChannel(spec.id, spec.name, spec.importance))
            }
        }
    }
}
