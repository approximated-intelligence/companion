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
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import de.perigon.companion.audio.service.AudioRecordingService
import de.perigon.companion.core.prefs.AppPrefs
import de.perigon.companion.core.ui.NavGraph
import de.perigon.companion.core.ui.PermissionGate
import de.perigon.companion.core.ui.Route
import de.perigon.companion.core.ui.theme.CompanionTheme
import de.perigon.companion.track.service.BackgroundService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var appPrefs: AppPrefs

    /**
     * In-place navigation requests from [onNewIntent] (notification taps).
     * Previously handled with recreate(), which rebuilt the whole activity —
     * and destroyed every screen's UI state — just to switch route.
     */
    private val navigateTo = MutableStateFlow<Any?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val parsed = parseIntent(intent)
        setContent {
            CompanionTheme {
                PermissionGate {
                    val navController = rememberNavController()
                    val backgroundGps by appPrefs.observeBackgroundGps()
                        .collectAsStateWithLifecycle(false)

                    val pending by navigateTo.collectAsStateWithLifecycle()
                    LaunchedEffect(pending) {
                        val route = pending ?: return@LaunchedEffect
                        navigateTo.value = null
                        navController.navigate(route) { launchSingleTop = true }
                    }

                    NavGraph(
                        navController = navController,
                        startRoute    = parsed.startRoute,
                        incomingUris  = parsed.uris,
                        backgroundGps = backgroundGps,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val target = intent.getStringExtra(BackgroundService.EXTRA_NAVIGATE_TO)
            ?: intent.getStringExtra(EXTRA_NAVIGATE_TO)
        if (target != null) {
            setIntent(intent)
            navigateTo.value = routeFor(target)
        }
    }

    companion object {
        const val EXTRA_NAVIGATE_TO = "navigate_to"
    }
}

data class ParsedIntent(
    val startRoute: Any,
    val uris: List<Uri>,
)

private fun routeFor(navigateTo: String): Any = when (navigateTo) {
    BackgroundService.NAV_RECORDING  -> Route.Recording
    AudioRecordingService.NAV_AUDIO  -> Route.Audio
    "Backup"                         -> Route.Backup
    "Consolidate"                    -> Route.Consolidate
    "Posts"                          -> Route.Posts
    else                             -> Route.Consolidate
}

private fun parseIntent(intent: Intent): ParsedIntent {
    val navigateTo = intent.getStringExtra(BackgroundService.EXTRA_NAVIGATE_TO)
        ?: intent.getStringExtra(MainActivity.EXTRA_NAVIGATE_TO)
    if (navigateTo != null) {
        return ParsedIntent(routeFor(navigateTo), emptyList())
    }

    val action = intent.action
    val mime   = intent.type ?: ""

    return when (action) {
        Intent.ACTION_SEND -> {
            // IntentCompat: the (String, Class) overloads are API 33+, minSdk is 31.
            val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            when {
                mime.contains("gpx") || mime.contains("octet") ->
                    ParsedIntent(Route.TrackRender, listOfNotNull(uri))
                else ->
                    ParsedIntent(Route.MediaPrep(), listOfNotNull(uri))
            }
        }
        Intent.ACTION_SEND_MULTIPLE -> {
            val uris = IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?: emptyList<Uri>()
            val mime2 = intent.type ?: ""
            if (mime2.contains("gpx") || mime2.contains("octet")) {
                ParsedIntent(Route.TrackRender, uris)
            } else {
                ParsedIntent(Route.MediaPrep(), uris)
            }
        }
        // ACTION_VIEW branch removed with the companion://oauth deep link.
        else -> ParsedIntent(Route.Consolidate, emptyList())
    }
}
