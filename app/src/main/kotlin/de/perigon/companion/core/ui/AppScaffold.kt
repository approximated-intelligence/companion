package de.perigon.companion.core.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import de.perigon.companion.core.feature.FeatureEntry
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

/** The features of this build, ordered; provided once by the activity. */
val LocalFeatures = staticCompositionLocalOf<List<FeatureEntry>> { emptyList() }

/** Title and menu come from the features — the shell names none of them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(navController: NavController) {
    val features = LocalFeatures.current
    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route.orEmpty()
    val title = remember(features, route) {
        features.firstNotNullOfOrNull { it.titleFor(route) } ?: ""
    }
    val items = remember(features) { features.flatMap { it.menuItems }.sortedBy { it.order } }
    var menuOpen by remember { mutableStateOf(false) }

    TopAppBar(
        title = { Text(title) },
        actions = {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Default.Menu, contentDescription = "Menu")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                for (item in items) {
                    key(item.label) {
                        val visible by item.visible.collectAsState(initial = true)
                        if (visible) {
                            DropdownMenuItem(
                                text = { Text(item.label) },
                                onClick = {
                                    menuOpen = false
                                    navController.navigate(item.route) { launchSingleTop = true }
                                },
                            )
                        }
                    }
                }
            }
        },
    )
}

class SnackbarChannel {
    private val channel = Channel<String>(Channel.BUFFERED)
    val events = channel.receiveAsFlow()

    fun send(message: String) {
        channel.trySend(message)
    }
}
