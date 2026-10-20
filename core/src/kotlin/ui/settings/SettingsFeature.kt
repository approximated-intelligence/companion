package de.perigon.companion.core.ui.settings

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import de.perigon.companion.core.feature.FeatureEntry
import de.perigon.companion.core.feature.MenuItem
import kotlinx.serialization.Serializable
import javax.inject.Inject

@Serializable data object SettingsRoute

/** Core's own destination, registered like any feature. Always last in the menu. */
class SettingsFeature @Inject constructor() : FeatureEntry {
    override val id = "settings"
    override val order = 10_000
    override val menuItems = listOf(MenuItem("Settings", SettingsRoute, order = 10_000))

    override fun register(graph: NavGraphBuilder, nav: NavHostController) {
        graph.composable<SettingsRoute> { SettingsScreen(navController = nav) }
    }

    override fun titleFor(route: String): String? =
        if (route.endsWith("SettingsRoute")) "Settings" else null
}
