package de.perigon.companion.core.feature

import android.app.NotificationManager
import android.net.Uri
import androidx.navigation.NavController

/** Startup work a module needs, run once per process from the app scope. */
interface AppInitializer {
    val order: Int get() = 0
    suspend fun run()
}

/** A notification channel a module posts to; created at startup by core. */
data class ChannelSpec(
    val id: String,
    val name: String,
    val importance: Int = NotificationManager.IMPORTANCE_LOW,
)

/**
 * Per-module ranges. Modules add small offsets to their base, so ids can
 * never collide across modules (publish and restore both used 43).
 */
object NotificationIds {
    const val CORE = 1000
    const val MEDIA = 1100
    const val BACKUP = 1200
    const val POSTS = 1300
    const val TRACK = 1400
    const val AUDIO = 1500
}

/** Same scheme for PendingIntent request codes. */
object RequestCodes {
    const val CORE = 9000
    const val MEDIA = 9100
    const val BACKUP = 9200
    const val POSTS = 9300
    const val TRACK = 9400
    const val AUDIO = 9500
}

/**
 * Accepts media from another feature (track renders, frame grabs) and opens
 * wherever that media is used. Optional: absent when posts is not in the app;
 * callers then fall back to the system share sheet.
 */
interface MediaInbox {
    fun offer(nav: NavController, uris: List<Uri>)
}
