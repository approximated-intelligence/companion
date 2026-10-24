package de.perigon.companion.core.feature

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.reflect.KClass

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

    /**
     * When [route] is a tab root of this feature, returns the class of the
     * feature's default tab. Null when [route] is not a tab root of this
     * feature, or the feature has no tab row.
     *
     * Used by [de.perigon.companion.core.ui.AppTopBar]'s menu click and by
     * [de.perigon.companion.core.ui.deliver]: navigating to a tab root should
     * pop back to the feature's default tab rather than stacking a new entry
     * on top of the current tab. Without this, tapping "Posts" from the
     * MediaPrep tab produced `[Posts, MediaPrep, Posts]`, and back-press
     * walked through the duplicate before exiting.
     *
     * Only the class is returned (not the instance) because every tabbed
     * feature's default tab is a `data object` — a singleton route with no
     * arguments to distinguish. That is why `popUpTo(route: KClass<*>)`
     * suffices here and no per-feature instance is needed.
     */
    fun defaultTabClassFor(route: Any): KClass<*>? = null
}
