package de.perigon.companion

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import de.perigon.companion.core.feature.FeatureEntry

/**
 * The whole graph is assembled from the features of this build. External
 * launches arrive via [de.perigon.companion.core.ui.deliver], not as parameters.
 *
 * [startupFeatureId] overrides the natural start destination. It is read
 * once when NavHost is created; changing it mid-session has no effect
 * (a NavHost's start destination is fixed after the initial composition),
 * which is fine — "startup" means next cold start.
 */
@Composable
fun NavGraph(
    navController: NavHostController,
    features: List<FeatureEntry>,
    startupFeatureId: String? = null,
) {
    val start = remember(features, startupFeatureId) {
        features.firstOrNull { it.id == startupFeatureId && it.startRoute != null }?.startRoute
            ?: features.firstNotNullOf { it.startRoute }
    }
    NavHost(navController = navController, startDestination = start) {
        features.forEach { it.register(this, navController) }
    }
}
