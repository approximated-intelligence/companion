package de.perigon.companion.media.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import de.perigon.companion.core.ui.AppTopBar
import de.perigon.companion.media.MediaRoute

private data class MediaTab(val route: Any, val label: String)

private val mediaTabs = listOf(
    MediaTab(MediaRoute.TrackRender, "Track Render"),
    MediaTab(MediaRoute.Queue,       "Queue"),
)

/**
 * Tab index for the current destination. Matches by class so both
 * MediaRoute.TrackRender (a data object today) and any future MediaRoute
 * variant carrying arguments light the correct tab by identity. Mirrors
 * ArchiveTopBar and PostsTopBar.
 */
private fun tabIndexFor(route: Any?): Int = when (route) {
    is MediaRoute.TrackRender -> 0
    is MediaRoute.Queue       -> 1
    else                      -> 0
}

/**
 * Tab-root top bar for Media. Composes [AppTopBar] and appends a
 * [PrimaryTabRow] over Track Render / Queue — same structure as Archive
 * and Posts.
 *
 * Track Render is the default tab: it is first in [mediaTabs], and it is
 * the target of the single "Media" menu item and of the GPX share intent
 * (see MediaFeature.shareRoute). Opening Media from the menu, a share, or
 * a notification lands there.
 *
 * Tab navigation pops back to Track Render (saveState) and pushes the tab
 * route with restoreState, so back from Queue goes to Track Render and
 * each tab keeps its own scroll/form state.
 *
 * The two tabs are MEDIA menu items, not Posts: Track Render is a Media
 * tool (GPX → JPEG) that happens to feed Posts via
 * [de.perigon.companion.core.feature.MediaInbox], and Queue is the shared
 * TransformQueue diagnostic showing publish AND consolidate jobs. Neither
 * belongs to Posts, which is why Posts only has Posts / Media Prep /
 * Assets and Media keeps its own tab row.
 */
@Composable
fun MediaTopBar(
    navController: NavController,
    current: Any,
) {
    Column {
        AppTopBar(navController)
        val selectedIndex = tabIndexFor(current)
        PrimaryTabRow(selectedTabIndex = selectedIndex) {
            mediaTabs.forEachIndexed { index, tab ->
                Tab(
                    selected = index == selectedIndex,
                    onClick = {
                        if (index != selectedIndex) {
                            navController.navigate(tab.route) {
                                popUpTo<MediaRoute.TrackRender> {
                                    inclusive = false
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    },
                    text = { Text(tab.label) },
                )
            }
        }
    }
}
