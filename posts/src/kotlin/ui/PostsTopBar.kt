package de.perigon.companion.posts.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import de.perigon.companion.core.ui.AppTopBar
import de.perigon.companion.posts.PostsRoute

private data class PostsTab(val route: Any, val label: String)

private val postsTabs = listOf(
    PostsTab(PostsRoute.Posts,       "Posts"),
    PostsTab(PostsRoute.MediaPrep(), "Media Prep"),
    PostsTab(PostsRoute.Assets,      "Assets"),
)

/**
 * Tab index for the current destination. Matches by class, so both
 * MediaPrep(postId = 0) (tab) and MediaPrep(postId = N) (child of an editor)
 * light the "Media Prep" tab. Child screens that use [AppTopBar] directly
 * never call this, so the fallback index is only ever hit for the tab roots.
 */
private fun tabIndexFor(route: Any?): Int = when (route) {
    is PostsRoute.Posts     -> 0
    is PostsRoute.MediaPrep -> 1
    is PostsRoute.Assets    -> 2
    else                    -> 0
}

/**
 * Tab-root top bar for Posts. Composes [AppTopBar] and appends a
 * [PrimaryTabRow] over Posts / Media Prep / Assets — the same structure
 * ArchiveTopBar uses for Consolidate / Restore / Mirror.
 *
 * Tab navigation pops back to Posts (saveState) and pushes the tab route
 * with restoreState, so back from a tab goes to Posts and each tab keeps its
 * own scroll/form state.
 *
 * The transform Queue is deliberately NOT a tab here: it is a shared
 * pipeline diagnostic (publish + consolidate jobs alike), not a surface of
 * this feature. It stays reachable from the top-bar menu → "Queue"
 * (Media feature), where it correctly shows every consumer of TransformQueue.
 */
@Composable
fun PostsTopBar(
    navController: NavController,
    current: Any,
) {
    Column {
        AppTopBar(navController)
        val selectedIndex = tabIndexFor(current)
        PrimaryTabRow(selectedTabIndex = selectedIndex) {
            postsTabs.forEachIndexed { index, tab ->
                Tab(
                    selected = index == selectedIndex,
                    onClick = {
                        if (index != selectedIndex) {
                            navController.navigate(tab.route) {
                                popUpTo<PostsRoute.Posts> {
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
