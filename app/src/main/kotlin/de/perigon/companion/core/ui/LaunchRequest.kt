package de.perigon.companion.core.ui

import android.net.Uri
import androidx.navigation.NavController

/**
 * Everything an external launch (share, notification tap) can ask of the UI.
 * Produced by MainActivity from Intents — cold start and onNewIntent alike —
 * and delivered here, so there is exactly one path into the nav graph.
 */
sealed interface LaunchRequest {
    /** Notification tap etc.: show this destination. */
    data class Navigate(val route: Any) : LaunchRequest

    /** ACTION_SEND / SEND_MULTIPLE into the app. */
    data class Share(val target: ShareTarget, val uris: List<Uri>) : LaunchRequest
}

enum class ShareTarget { MEDIA, GPX }

/** Pure: which destination a shared MIME type belongs to. */
fun shareTargetFor(mimeType: String?): ShareTarget {
    val mime = mimeType.orEmpty()
    return if (mime.contains("gpx") || mime.contains("octet")) ShareTarget.GPX else ShareTarget.MEDIA
}

/**
 * Shares always open a FRESH entry (never launchSingleTop): a shared set of
 * media starts a new post even when MediaPrep for another post is on top.
 * The URIs travel in the new entry's SavedStateHandle and are drained there
 * exactly once — same inbox mechanism as the in-app picker.
 */
fun NavController.deliver(request: LaunchRequest) {
    when (request) {
        is LaunchRequest.Navigate -> navigate(request.route) { launchSingleTop = true }
        is LaunchRequest.Share -> {
            val route: Any = when (request.target) {
                ShareTarget.MEDIA -> Route.MediaPrep()
                ShareTarget.GPX   -> Route.TrackRender
            }
            navigate(route)
            currentBackStackEntry?.savedStateHandle
                ?.set(NavResultKeys.SHARED_URIS, encodeUris(request.uris))
        }
    }
}
