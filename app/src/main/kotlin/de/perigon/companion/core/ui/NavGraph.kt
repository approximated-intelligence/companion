package de.perigon.companion.core.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.navigation.*
import androidx.navigation.compose.*
import de.perigon.companion.audio.ui.recording.AudioRecordingScreen
import de.perigon.companion.posts.site.ui.editor.AssetEditScreen
import de.perigon.companion.posts.site.ui.diff.AssetDiffScreen
import de.perigon.companion.posts.site.ui.list.AssetListScreen
import de.perigon.companion.backup.ui.BackupScreen
import de.perigon.companion.media.ui.consolidate.ConsolidateScreen
import de.perigon.companion.media.ui.picker.MediaPickerScreen
import de.perigon.companion.media.ui.prep.MediaPrepScreen
import de.perigon.companion.posts.ui.list.PostListScreen
import de.perigon.companion.posts.ui.editor.PostScreen
import de.perigon.companion.media.ui.queue.QueueScreen
import de.perigon.companion.backup.ui.restore.RestoreScreen
import de.perigon.companion.core.ui.settings.SettingsScreen
import de.perigon.companion.core.ui.LocalBackgroundGps
import de.perigon.companion.track.ui.render.TrackRenderScreen
import de.perigon.companion.track.ui.recording.RecordingScreen

@Composable
fun NavGraph(
    navController: NavHostController,
    startRoute:    Any,
    incomingUris:  List<Uri> = emptyList(),
    backgroundGps: Boolean = false,
) {
    CompositionLocalProvider(LocalBackgroundGps provides backgroundGps) {
        NavHost(navController = navController, startDestination = startRoute) {
            composable<Route.MediaPrep> { backStackEntry ->
                val route = backStackEntry.toRoute<Route.MediaPrep>()
                val trackUri = navController.currentBackStackEntry
                    ?.savedStateHandle
                    ?.remove<String>("track_uri")
                    ?.let { Uri.parse(it) }
                val pickedUrisJson = navController.currentBackStackEntry
                    ?.savedStateHandle
                    ?.remove<String>("picked_uris")
                val pickedUris = pickedUrisJson
                    ?.split("\n")
                    ?.filter { it.isNotBlank() }
                    ?.map { Uri.parse(it) }
                    ?: emptyList()
                val initialUris = if (startRoute is Route.MediaPrep) incomingUris else emptyList()
                val combined = initialUris + pickedUris + listOfNotNull(trackUri)

                MediaPrepScreen(
                    navController = navController,
                    postId        = route.postId,
                    incomingUris  = combined,
                )
            }
            composable<Route.MediaPicker> {
                MediaPickerScreen(
                    navController = navController,
                    onConfirm = { uris ->
                        navController.previousBackStackEntry
                            ?.savedStateHandle
                            ?.set("picked_uris", uris.joinToString("\n") { it.toString() })
                        navController.popBackStack()
                    },
                    onCancel = { navController.popBackStack() },
                )
            }
            composable<Route.TrackRender> {
                val gpxUris = if (startRoute == Route.TrackRender) incomingUris else emptyList()
                TrackRenderScreen(
                    navController         = navController,
                    onNavigateToMediaPrep = { outputUri ->
                        navController.navigate(Route.MediaPrep()) { launchSingleTop = true }
                        navController.currentBackStackEntry
                            ?.savedStateHandle
                            ?.set("track_uri", outputUri.toString())
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
