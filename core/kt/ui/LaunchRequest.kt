package de.perigon.companion.core.ui

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.navigation.NavController
import de.perigon.companion.core.feature.FeatureEntry

/** Extra naming a notification's target; resolved by FeatureEntry.routeFor. */
const val EXTRA_NAVIGATE_TO = "navigate_to"

/**
 * Everything an external launch (share, notification tap) can ask of the UI.
 * Produced from Intents — cold start and onNewIntent alike — and delivered
 * through one path into the nav graph.
 */
sealed interface LaunchRequest {
    data class Navigate(val target: String) : LaunchRequest
    data class Share(val mimeType: String?, val uris: List<Uri>) : LaunchRequest
}

/**
 * Intent → request, or null for a plain launcher start. minSdk 34: the typed
 * (String, Class) extra getters are always available.
 */
fun launchRequestOf(intent: Intent): LaunchRequest? {
    intent.getStringExtra(EXTRA_NAVIGATE_TO)?.let { return LaunchRequest.Navigate(it) }

    val uris: List<Uri> = when (intent.action) {
        Intent.ACTION_SEND ->
            listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE ->
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        else -> return null
    }
    if (uris.isEmpty()) return null
    return LaunchRequest.Share(intent.type, uris)
}

/**
 * Shares always open a FRESH entry (never launchSingleTop): shared media
 * starts a new post even when another post's media screen is on top. URIs
 * travel in the new entry's SavedStateHandle and are drained there once.
 */
fun NavController.deliver(request: LaunchRequest, features: Collection<FeatureEntry>) {
    when (request) {
        is LaunchRequest.Navigate -> {
            val route = features.firstNotNullOfOrNull { it.routeFor(request.target) }
                ?: return Log.w("LaunchRequest", "no feature handles navigate_to=${request.target}").let { }
            navigate(route) { launchSingleTop = true }
        }
        is LaunchRequest.Share -> {
            val route = features.firstNotNullOfOrNull { it.shareRoute(request.mimeType) }
                ?: return Log.w("LaunchRequest", "no feature accepts shared ${request.mimeType}").let { }
            navigate(route)
            currentBackStackEntry?.savedStateHandle
                ?.set(NavResultKeys.SHARED_URIS, encodeUris(request.uris))
        }
    }
}
