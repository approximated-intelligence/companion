package de.perigon.companion.backup.mirror

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import de.perigon.companion.backup.mirror.ui.MirrorScreen
import de.perigon.companion.core.feature.CommonRequirements
import de.perigon.companion.core.feature.FeatureEntry
import de.perigon.companion.core.feature.MenuItem
import kotlinx.serialization.Serializable
import javax.inject.Inject

object MirrorRoute {
    @Serializable data object Mirror
}

object MirrorChannels {
    const val MIRROR = "mirror"
}

object MirrorTargets {
    const val MIRROR = "Mirror"
}

/**
 * One-way plain-file mirror of DCIM and configured source folders to every
 * connected thumb drive. rsync-style diff, no persistent phone-side state.
 * A SUMS/ directory on each drive records the hash of every file written.
 */
class MirrorFeature @Inject constructor() : FeatureEntry {
    override val id = "mirror"
    override val order = 110
    override val menuItems = listOf(MenuItem("Mirror", MirrorRoute.Mirror, order = 45))
    override val requirements = listOf(
        CommonRequirements.dcim,
        CommonRequirements.mediaAccess,
        CommonRequirements.notifications,
    )

    override fun register(graph: NavGraphBuilder, nav: NavHostController) {
        graph.composable<MirrorRoute.Mirror> { MirrorScreen(navController = nav) }
    }

    override fun titleFor(route: String): String? =
        if (route.endsWith("MirrorRoute.Mirror")) "Mirror" else null

    override fun routeFor(navigateTo: String): Any? =
        if (navigateTo == MirrorTargets.MIRROR) MirrorRoute.Mirror else null
}
