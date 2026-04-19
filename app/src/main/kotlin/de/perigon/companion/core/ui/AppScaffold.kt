package de.perigon.companion.core.ui
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.Serializable

private data class NavItem(
    val label: String,
    val destination: Any,
    val requiresRecording: Boolean = false,
)

private val NAV_ITEMS = listOf(
    NavItem("Posts",         Route.Posts),
    NavItem("Backup",        Route.Backup),
    NavItem("Restore",       Route.Restore),
    NavItem("Consolidate",   Route.Consolidate),
    NavItem("Media prep",    Route.MediaPrep()),
    NavItem("Track render",  Route.TrackRender),
    NavItem("Track",         Route.Recording, requiresRecording = true),
    NavItem("Queue",         Route.Queue),
    NavItem("Assets",        Route.Assets),
    NavItem("Settings",      Route.Settings),
)

val LocalBackgroundGps = compositionLocalOf { false }

/**
 * Quick synchronous check for background GPS setting.
 * Reads from the DataStore's cached preferences file on disk.
 * This is only used for nav menu filtering — not a hot path.
 */
private fun isBackgroundGpsEnabled(context: Context): Boolean {
    return try {
        context.getSharedPreferences("app_prefs_fallback_check", Context.MODE_PRIVATE)
            .getBoolean("background_gps", false)
    } catch (_: Exception) {
        false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(navController: NavController) {
    val backStack by navController.currentBackStackEntryAsState()
    val route     = backStack?.destination?.route ?: ""
    var menuOpen  by remember { mutableStateOf(false) }
    val context   = LocalContext.current

    val backgroundGps = LocalBackgroundGps.current

    TopAppBar(
        title   = { Text(routeTitle(route)) },
        actions = {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Default.Menu, contentDescription = "Menu")
            }
            DropdownMenu(
                expanded         = menuOpen,
                onDismissRequest = { menuOpen = false },
            ) {
                NAV_ITEMS
                    .filter { !it.requiresRecording || backgroundGps }
                    .forEach { item ->
                        DropdownMenuItem(
                            text    = { Text(item.label) },
                            onClick = {
                                menuOpen = false
                                navController.navigate(item.destination) {
                                    launchSingleTop = true
                                }
                            },
                        )
                    }
            }
        },
    )
}

private fun routeTitle(route: String) = when {
    route.contains("PostNew")      -> "New Post"
    route.contains("PostEdit")     -> "Post"
    route.contains("Posts")        -> "Posts"
    route.contains("MediaPicker")  -> "Select Media"
    route.contains("MediaPrep")    -> "Media Prep"
    route.contains("TrackRender")  -> "Track Render"
    route.contains("Recording")    -> "Run"
    route.contains("Queue")        -> "Queue"
    route.contains("Backup")       -> "Backup"
    route.contains("Restore")      -> "Restore"
    route.contains("Consolidate")  -> "Consolidate"
    route.contains("AssetDiff")    -> "Asset Diff"
    route.contains("AssetEdit")    -> "Edit Asset"
    route.contains("Assets")       -> "Assets"
    route.contains("Settings")     -> "Settings"
    else                           -> ""
}

object Route {
    @Serializable object Posts
    @Serializable object PostNew
    @Serializable data class PostEdit(val postId: Long)
    @Serializable data class MediaPrep(val postId: Long = 0L)
    @Serializable data class MediaPicker(val returnRoute: String = "MediaPrep")
    @Serializable object TrackRender
    @Serializable object Recording
    @Serializable object Settings
    @Serializable object Backup
    @Serializable object Restore
    @Serializable object Consolidate
    @Serializable object Queue
    @Serializable object Assets
    @Serializable data class AssetEdit(val assetId: Long)
    @Serializable data class AssetDiff(val assetId: Long)
}

class SnackbarChannel {
    private val channel = Channel<String>(Channel.BUFFERED)
    val events = channel.receiveAsFlow()

    fun send(message: String) {
        channel.trySend(message)
    }
}
