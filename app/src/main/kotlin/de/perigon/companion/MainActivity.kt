package de.perigon.companion

import javax.inject.Inject
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import de.perigon.companion.audio.service.AudioRecordingService
import de.perigon.companion.core.prefs.AppPrefs
import de.perigon.companion.core.ui.AppAccessGate
import de.perigon.companion.core.ui.LaunchRequest
import de.perigon.companion.core.ui.NavGraph
import de.perigon.companion.core.ui.Route
import de.perigon.companion.core.ui.deliver
import de.perigon.companion.core.ui.shareTargetFor
import de.perigon.companion.core.ui.theme.CompanionTheme
import de.perigon.companion.track.service.BackgroundService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.channels.Channel

/**
 * launchMode="singleTask" (manifest): one app instance. Shares and
 * notification taps while running arrive in [onNewIntent], not as a second
 * MainActivity inside the sender's task.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var appPrefs: AppPrefs

    /**
     * Every external launch — cold (onCreate) or warm (onNewIntent), share or
     * notification tap — goes through this one queue into the nav graph.
     * A Channel, not a StateFlow: requests are events (two identical taps are
     * two requests) and nothing may be conflated away. Buffered, so requests
     * made while the access gate is showing wait there and are delivered once
     * the graph exists — which is what makes "grant access, then continue to
     * where you were going" work.
     */
    private val launchRequests = Channel<LaunchRequest>(Channel.UNLIMITED)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Only a genuinely new launch carries a request. On recreation
        // (rotation, process restore) the same intent is handed back — it was
        // already delivered, and the back stack already reflects it.
        if (savedInstanceState == null) parseIntent(intent)?.let { launchRequests.trySend(it) }

        setContent {
            CompanionTheme {
                // Hoisted ABOVE the gate: the gate can close mid-session (a
                // grant revoked in system settings) and reopen. Created inside
                // it, the controller — and the whole back stack with it — was
                // discarded every time, so the user never got back to where
                // they were.
                val navController = rememberNavController()

                AppAccessGate {
                    val backgroundGps by appPrefs.observeBackgroundGps()
                        .collectAsStateWithLifecycle(false)

                    // Inside the gate: navigate() needs the graph, and the
                    // graph only exists while NavGraph is composed. Requests
                    // arriving while the gate is closed stay in the channel.
                    LaunchedEffect(navController) {
                        for (request in launchRequests) navController.deliver(request)
                    }

                    NavGraph(
                        navController = navController,
                        backgroundGps = backgroundGps,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        parseIntent(intent)?.let { launchRequests.trySend(it) }
    }

    companion object {
        // Same value as BackgroundService.EXTRA_NAVIGATE_TO; producers use either.
        const val EXTRA_NAVIGATE_TO = "navigate_to"
    }
}

private fun routeFor(navigateTo: String): Any = when (navigateTo) {
    BackgroundService.NAV_RECORDING  -> Route.Recording
    AudioRecordingService.NAV_AUDIO  -> Route.Audio
    "Backup"                         -> Route.Backup
    "Consolidate"                    -> Route.Consolidate
    "Posts"                          -> Route.Posts
    else                             -> Route.Consolidate
}

/**
 * Intent → request, or null for a plain launcher start. minSdk 34: the
 * typed (String, Class) extra getters (API 33+) are always available.
 */
private fun parseIntent(intent: Intent): LaunchRequest? {
    intent.getStringExtra(MainActivity.EXTRA_NAVIGATE_TO)?.let { target ->
        return LaunchRequest.Navigate(routeFor(target))
    }

    val uris: List<Uri> = when (intent.action) {
        Intent.ACTION_SEND ->
            listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE ->
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        // ACTION_VIEW branch removed with the companion://oauth deep link.
        else -> return null
    }
    if (uris.isEmpty()) return null

    return LaunchRequest.Share(shareTargetFor(intent.type), uris)
}
