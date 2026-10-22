package de.perigon.companion.archive.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import de.perigon.companion.archive.ArchiveRoute
import de.perigon.companion.core.ui.AppTopBar

private data class ArchiveTab(val route: Any, val label: String)

private val archiveTabs = listOf(
    ArchiveTab(ArchiveRoute.Consolidate, "Consolidate"),
    ArchiveTab(ArchiveRoute.Restore, "Restore"),
    ArchiveTab(ArchiveRoute.Mirror, "Mirror"),
)

/**
 * Top bar shared by every tab under the Archive feature. Composes the app's
 * standard [AppTopBar] (title + overflow menu) and appends the tab row that
 * switches between Consolidate, Restore and Mirror.
 *
 * [selectionMode] hides the tab row entirely and renders [selectionBar] in
 * place of the standard top bar. Consolidate uses this for its multi-select
 * mode: the tab row must not be reachable mid-selection, or a stray tap
 * would throw away the user's selection.
 *
 * Tab navigation uses `popUpTo<Consolidate>` so the tab row behaves as a
 * horizontal set of siblings: back from any non-Consolidate tab goes to
 * Consolidate (or to whatever was before Archive). `saveState`/`restoreState`
 * preserves each tab's scroll position, form state and lazy-list position
 * across visits.
 *
 * [PrimaryTabRow], not the deprecated [androidx.compose.material3.TabRow]:
 * these are the screen's primary navigation (three peer tabs under one top
 * bar), which is exactly what Material3 designates the primary variant for.
 * SecondaryTabRow is for tabs nested inside an already-focused screen.
 */
@Composable
fun ArchiveTopBar(
    navController: NavController,
    current: Any,
    selectionMode: Boolean = false,
    selectionBar: @Composable () -> Unit = {},
) {
    if (selectionMode) {
        selectionBar()
        return
    }

    Column {
        AppTopBar(navController)
        val selectedIndex = archiveTabs.indexOfFirst { it.route == current }.coerceAtLeast(0)
        PrimaryTabRow(selectedTabIndex = selectedIndex) {
            archiveTabs.forEachIndexed { index, tab ->
                Tab(
                    selected = index == selectedIndex,
                    onClick = {
                        if (index != selectedIndex) {
                            navController.navigate(tab.route) {
                                popUpTo<ArchiveRoute.Consolidate> {
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
