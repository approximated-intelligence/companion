package de.perigon.companion.media

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import de.perigon.companion.core.feature.FeatureEntry
import de.perigon.companion.core.feature.MenuItem
import de.perigon.companion.media.ui.queue.QueueScreen
import kotlinx.serialization.Serializable
import javax.inject.Inject

object MediaRoute {
    @Serializable data object Queue
}

/** The transform queue's own screen; everything else in media is used by other features. */
class MediaFeature @Inject constructor() : FeatureEntry {
    override val id = "media"
    override val order = 500
    override val menuItems = listOf(MenuItem("Queue", MediaRoute.Queue, order = 90))

    override fun register(graph: NavGraphBuilder, nav: NavHostController) {
        graph.composable<MediaRoute.Queue> { QueueScreen(navController = nav) }
    }

    override fun titleFor(route: String): String? =
        if (route.endsWith("MediaRoute.Queue")) "Queue" else null
}
