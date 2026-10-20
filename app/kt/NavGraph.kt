package de.perigon.companion

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import de.perigon.companion.core.feature.FeatureEntry

/**
 * The whole graph is assembled from the features of this build. External
 * launches arrive via LaunchRequest.deliver, not as parameters.
 */
@Composable
fun NavGraph(navController: NavHostController, features: List<FeatureEntry>) {
    val start = remember(features) { features.firstNotNullOf { it.startRoute } }
    NavHost(navController = navController, startDestination = start) {
        features.forEach { it.register(this, navController) }
    }
}
