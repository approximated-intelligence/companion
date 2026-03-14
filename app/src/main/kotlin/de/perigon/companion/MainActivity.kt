package de.perigon.companion

import javax.inject.Inject
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import de.perigon.companion.core.prefs.AppPrefs
import de.perigon.companion.core.ui.NavGraph
import de.perigon.companion.core.ui.PermissionGate
import de.perigon.companion.core.ui.Route
import de.perigon.companion.core.ui.theme.CompanionTheme
import de.perigon.companion.track.service.BackgroundService
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var appPrefs: AppPrefs

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
        val navigateTo = intent.getStringExtra(BackgroundService.EXTRA_NAVIGATE_TO)
            ?: intent.getStringExtra(EXTRA_NAVIGATE_TO)
        if (navigateTo != null) {
            setIntent(intent)
            recreate()
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

private fun parseIntent(intent: Intent): ParsedIntent {
    val navigateTo = intent.getStringExtra(BackgroundService.EXTRA_NAVIGATE_TO)
        ?: intent.getStringExtra(MainActivity.EXTRA_NAVIGATE_TO)
    if (navigateTo != null) {
        val route = when (navigateTo) {
            BackgroundService.NAV_RECORDING -> Route.Recording
            "Backup"                        -> Route.Backup
            else                            -> Route.Posts
        }
        return ParsedIntent(route, emptyList())
    }

    val action = intent.action
    val mime   = intent.type ?: ""

    return when (action) {
        Intent.ACTION_SEND -> {
            val uri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            when {
                mime.contains("gpx") || mime.contains("octet") ->
                    ParsedIntent(Route.TrackRender, listOfNotNull(uri))
                else ->
                    ParsedIntent(Route.MediaPrep(), listOfNotNull(uri))
            }
        }
        Intent.ACTION_SEND_MULTIPLE -> {
            val uris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                ?: emptyList<Uri>()
            val mime2 = intent.type ?: ""
            if (mime2.contains("gpx") || mime2.contains("octet")) {
                ParsedIntent(Route.TrackRender, uris)
            } else {
                ParsedIntent(Route.MediaPrep(), uris)
            }
        }
        Intent.ACTION_VIEW -> {
            val uri = intent.data
            if (uri?.scheme == "companion" && uri.host == "oauth")
                ParsedIntent(Route.Settings, emptyList())
            else
                ParsedIntent(Route.Posts, emptyList())
        }
        else -> ParsedIntent(Route.Posts, emptyList())
    }
}
