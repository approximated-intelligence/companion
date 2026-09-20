package de.perigon.companion.core.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.*
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.*
import de.perigon.companion.audio.ui.recording.AudioRecordingScreen
import de.perigon.companion.posts.site.ui.editor.AssetEditScreen
import de.perigon.companion.posts.site.ui.diff.AssetDiffScreen
import de.perigon.companion.posts.site.ui.list.AssetListScreen
import de.perigon.companion.backup.ui.BackupScreen
import de.perigon.companion.media.ui.consolidate.ConsolidateScreen
import de.perigon.companion.media.ui.picker.MediaPickerScreen
import de.perigon.companion.media.ui.prep.MediaPrepScreen
import de.perigon.companion.media.ui.prep.MediaPrepViewModel
import de.perigon.companion.posts.ui.list.PostListScreen
import de.perigon.companion.posts.ui.editor.PostScreen
import de.perigon.companion.media.ui.queue.QueueScreen
import de.perigon.companion.backup.ui.restore.RestoreScreen
import de.perigon.companion.core.ui.settings.SettingsScreen
import de.perigon.companion.core.ui.LocalBackgroundGps
import de.perigon.companion.track.ui.render.TrackRenderScreen
import de.perigon.companion.track.ui.recording.RecordingScreen

/**
 * External launches (shares, notification taps) do not enter here as
 * parameters — they arrive as [LaunchRequest]s via [deliver], which navigates
 * and hands data over through the target entry's SavedStateHandle.
 */
@Composable
fun NavGraph(
    navController: NavHostController,
    startRoute:    Any = Route.Consolidate,
    backgroundGps: Boolean = false,
) {
    CompositionLocalProvider(LocalBackgroundGps provides backgroundGps) {
        NavHost(navController = navController, startDestination = startRoute) {
            composable<Route.MediaPrep> { backStackEntry ->
                // Same instance MediaPrepScreen would get: both scope to this entry.
                val vm: MediaPrepViewModel = hiltViewModel(backStackEntry)

                // Inbox for everything handed to this destination (share,
                // picker, track render). Runs on each committed entry into
                // composition — first open AND every return from the picker
                // (the destination leaves composition while the picker is
                // shown). take() makes each hand-over one-shot; LaunchedEffect
                // never runs for a discarded composition.
                LaunchedEffect(backStackEntry) {
                    vm.addItems(backStackEntry.savedStateHandle.takeHandedOverUris())
                }

                MediaPrepScreen(
                    navController = navController,
                    onDone        = { postId -> navController.finishMediaPrep(postId) },
                    viewModel     = vm,
                )
            }
            composable<Route.MediaPicker> {
                MediaPickerScreen(
                    navController = navController,
                    onConfirm = { uris ->
                        navController.previousBackStackEntry
                            ?.savedStateHandle
                            ?.set(NavResultKeys.PICKED_URIS, encodeUris(uris))
                        navController.popBackStack()
                    },
                    onCancel = { navController.popBackStack() },
                )
            }
            composable<Route.TrackRender> { backStackEntry ->
                // Shared GPX files, drained once. After recreation this is
                // empty again, but the ViewModel has already left Idle, and
                // TrackRenderScreen only loads incoming files while Idle.
                var gpxUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
                LaunchedEffect(backStackEntry) {
                    val shared = backStackEntry.savedStateHandle.takeSharedUris()
                    if (shared.isNotEmpty()) gpxUris = shared
                }
                TrackRenderScreen(
                    navController         = navController,
                    onNavigateToMediaPrep = { outputUri ->
                        navController.navigate(Route.MediaPrep()) { launchSingleTop = true }
                        navController.currentBackStackEntry
                            ?.savedStateHandle
                            ?.set(NavResultKeys.TRACK_URI, outputUri.toString())
                    },
                    incomingGpxUris = gpxUris,
                )
            }
            composable<Route.Recording> {
                RecordingScreen(navController = navController)
            }
            composable<Route.Audio> {
                AudioRecordingScreen(navController = navController)
            }
            composable<Route.Settings> {
                SettingsScreen(navController = navController)
            }
            composable<Route.Backup> {
                BackupScreen(navController = navController)
            }
            composable<Route.Restore> {
                RestoreScreen(navController = navController)
            }
            composable<Route.Consolidate> {
                ConsolidateScreen(navController = navController)
            }
            composable<Route.Queue> {
                QueueScreen(navController = navController)
            }
            composable<Route.Assets> {
                AssetListScreen(navController = navController)
            }
            composable<Route.AssetEdit> { backStackEntry ->
                val route = backStackEntry.toRoute<Route.AssetEdit>()
                AssetEditScreen(navController = navController, assetId = route.assetId)
            }
            composable<Route.AssetDiff> { backStackEntry ->
                val route = backStackEntry.toRoute<Route.AssetDiff>()
                AssetDiffScreen(navController = navController, assetId = route.assetId)
            }
            composable<Route.Posts> {
                PostListScreen(
                    navController    = navController,
                    onNavigateToPost = { id -> navController.navigate(Route.PostEdit(id)) },
                    onNewPost        = { navController.navigate(Route.PostNew) },
                )
            }
            composable<Route.PostNew> {
                PostScreen(
                    navController  = navController,
                    postId         = 0L,
                    onNavigateBack = { navController.popBackStack() },
                    onPublished    = {
                        navController.navigate(Route.Posts) {
                            popUpTo<Route.Posts> { inclusive = false }
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable<Route.PostEdit> { backStackEntry ->
                val route = backStackEntry.toRoute<Route.PostEdit>()
                PostScreen(
                    navController  = navController,
                    postId         = route.postId,
                    onNavigateBack = { navController.popBackStack() },
                    onPublished    = {
                        navController.navigate(Route.Posts) {
                            popUpTo<Route.Posts> { inclusive = false }
                            launchSingleTop = true
                        }
                    },
                )
            }
        }
    }
}

/**
 * Leave MediaPrep for the post editor. Came from that post's editor (the
 * normal "Add media" path) → pop back to it; navigating would stack a second
 * editor for the same post. Otherwise (menu, track render, share) → open the
 * editor in MediaPrep's place.
 */
private fun NavController.finishMediaPrep(postId: Long) {
    if (previousBackStackEntry.isEditorFor(postId)) {
        popBackStack()
    } else {
        navigate(Route.PostEdit(postId)) {
            popUpTo<Route.MediaPrep> { inclusive = true }
        }
    }
}

/** PostNew only opens MediaPrep with the id it just saved, so it always matches. */
private fun NavBackStackEntry?.isEditorFor(postId: Long): Boolean {
    val entry = this ?: return false
    val dest = entry.destination
    return dest.hasRoute<Route.PostNew>() ||
        (dest.hasRoute<Route.PostEdit>() && entry.toRoute<Route.PostEdit>().postId == postId)
}
