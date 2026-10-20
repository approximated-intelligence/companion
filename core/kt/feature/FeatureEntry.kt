package de.perigon.companion.core.feature

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** One entry in the top-bar menu. [visible] lets a feature hide itself (e.g. Track without GPS). */
data class MenuItem(
    val label: String,
    val route: Any,
    val order: Int,
    val visible: Flow<Boolean> = flowOf(true),
)

/**
 * A feature module's contribution to the app shell, bound with @IntoSet.
 * The shell knows no feature by name: nav graph, menu, titles, notification
 * targets, share handling and the start destination all come from these.
 */
interface FeatureEntry {
    /** Stable id, e.g. "posts". */
    val id: String

    /** Feature order; the lowest-ordered feature with a [startRoute] is the start destination. */
    val order: Int

    val startRoute: Any? get() = null

    val menuItems: List<MenuItem> get() = emptyList()

    /** Union of what this feature's destinations need; requested on the setup screen. */
    val requirements: List<AccessRequirement> get() = emptyList()

    /** Add this feature's destinations. Destinations needing optional access wrap themselves in RequireAccess. */
    fun register(graph: NavGraphBuilder, nav: NavHostController)

    /** Top-bar title for a destination route string, or null if not ours. */
    fun titleFor(route: String): String? = null

    /** Notification "navigate_to" target → route, or null if not ours. */
    fun routeFor(navigateTo: String): Any? = null

    /** Route that accepts shared content of [mimeType], or null. */
    fun shareRoute(mimeType: String?): Any? = null
}
