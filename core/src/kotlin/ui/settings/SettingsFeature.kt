package de.perigon.companion.core.ui.settings

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import de.perigon.companion.core.feature.FeatureEntry
import de.perigon.companion.core.feature.MenuItem
import kotlinx.serialization.Serializable
import javax.inject.Inject

/**
 * [anchor] scrolls the settings screen to the [SettingsSection.id] it names,
 * or null for the natural top. The menu opens with no anchor; deep links from
 * feature screens pass the section id they want the user to land on.
 */
@Serializable data class SettingsRoute(val anchor: String? = null)

/** Core's own destination, registered like any feature. Always last in the menu. */
class SettingsFeature @Inject constructor() : FeatureEntry {
    override val id = "settings"
    override val order = 10_000
    override val menuItems = listOf(MenuItem("Settings", SettingsRoute(), order = 10_000))

    override fun register(graph: NavGraphBuilder, nav: NavHostController) {
        graph.composable<SettingsRoute> { entry ->
            val route = entry.toRoute<SettingsRoute>()
            SettingsScreen(navController = nav, anchor = route.anchor)
        }
    }

    override fun titleFor(route: String): String? =
        if (route.contains("SettingsRoute")) "Settings" else null
}
