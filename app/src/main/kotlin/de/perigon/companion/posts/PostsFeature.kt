package de.perigon.companion.posts

import android.Manifest
import android.net.Uri
import androidx.compose.runtime.LaunchedEffect
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import de.perigon.companion.core.feature.AccessRequirement
import de.perigon.companion.core.feature.AppInitializer
import de.perigon.companion.core.feature.CommonRequirements
import de.perigon.companion.core.feature.FeatureEntry
import de.perigon.companion.core.feature.MediaInbox
import de.perigon.companion.core.feature.MenuItem
import de.perigon.companion.core.ui.NavResultKeys
import de.perigon.companion.core.ui.access.RequireAccess
import de.perigon.companion.core.ui.encodeUris
import de.perigon.companion.core.ui.takeHandedOverUris
import de.perigon.companion.posts.data.PostRepository
import de.perigon.companion.posts.site.ui.diff.AssetDiffScreen
import de.perigon.companion.posts.site.ui.editor.AssetEditScreen
import de.perigon.companion.posts.site.ui.list.AssetListScreen
import de.perigon.companion.posts.ui.editor.PostScreen
import de.perigon.companion.posts.ui.list.PostListScreen
import de.perigon.companion.posts.ui.picker.MediaPickerScreen
import de.perigon.companion.posts.ui.prep.MediaPrepScreen
import de.perigon.companion.posts.ui.prep.MediaPrepViewModel
import kotlinx.serialization.Serializable
import javax.inject.Inject

/** Member names match the former core Route, so screens keep `Route.X` via an import alias. */
object PostsRoute {
    @Serializable data object Posts
    @Serializable data object PostNew
    @Serializable data class PostEdit(val postId: Long)
    @Serializable data class MediaPrep(val postId: Long = 0L)
    @Serializable data class MediaPicker(val returnRoute: String = "MediaPrep")
    @Serializable data object Assets
    @Serializable data class AssetEdit(val assetId: Long)
    @Serializable data class AssetDiff(val assetId: Long)
}

object PostsChannels {
    const val PUBLISH = "publish"
}

object PostsTargets {
    const val POSTS = "Posts"
}

object PostsRequirements {
    /** Only the built-in picker queries MediaStore; the system picker needs nothing. */
    val photos: AccessRequirement = AccessRequirement.Permissions(
        key = "photos",
        title = "Photos and videos",
        rationale = "For the built-in media picker. The system picker works without it.",
        permissions = listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO),
        alternatives = listOf(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED),
        optional = true,
    )
}

class PostsFeature @Inject constructor() : FeatureEntry {
    override val id = "posts"
    override val order = 200

    override val menuItems = listOf(
        MenuItem("Posts", PostsRoute.Posts, order = 10),
        MenuItem("Media prep", PostsRoute.MediaPrep(), order = 50),
        MenuItem("Assets", PostsRoute.Assets, order = 100),
    )

    override val requirements = listOf(
        CommonRequirements.dcim,
        PostsRequirements.photos,
        CommonRequirements.notifications,
    )

    override fun register(graph: NavGraphBuilder, nav: NavHostController) {
        graph.composable<PostsRoute.MediaPrep> { entry ->
            // Same instance MediaPrepScreen gets: both scope to this entry.
            val vm: MediaPrepViewModel = hiltViewModel(entry)
            // Inbox for everything handed over (share, picker, other features).
            // Runs on each committed entry into composition; take() makes it one-shot.
            LaunchedEffect(entry) { vm.addItems(entry.savedStateHandle.takeHandedOverUris()) }
            MediaPrepScreen(
                navController = nav,
                onDone = { postId -> nav.finishMediaPrep(postId) },
                viewModel = vm,
            )
        }
        graph.composable<PostsRoute.MediaPicker> {
            RequireAccess(listOf(PostsRequirements.photos)) {
                MediaPickerScreen(
                    navController = nav,
                    onConfirm = { uris ->
                        nav.previousBackStackEntry?.savedStateHandle
                            ?.set(NavResultKeys.PICKED_URIS, encodeUris(uris))
                        nav.popBackStack()
                    },
                    onCancel = { nav.popBackStack() },
                )
            }
        }
        graph.composable<PostsRoute.Posts> {
            PostListScreen(
                navController = nav,
                onNavigateToPost = { id -> nav.navigate(PostsRoute.PostEdit(id)) },
                onNewPost = { nav.navigate(PostsRoute.PostNew) },
            )
        }
        graph.composable<PostsRoute.PostNew> {
            PostScreen(
                navController = nav,
                postId = 0L,
                onNavigateBack = { nav.popBackStack() },
                onPublished = { nav.backToPostList() },
            )
        }
        graph.composable<PostsRoute.PostEdit> { entry ->
            PostScreen(
                navController = nav,
                postId = entry.toRoute<PostsRoute.PostEdit>().postId,
                onNavigateBack = { nav.popBackStack() },
                onPublished = { nav.backToPostList() },
            )
        }
        graph.composable<PostsRoute.Assets> { AssetListScreen(navController = nav) }
        graph.composable<PostsRoute.AssetEdit> { entry ->
            AssetEditScreen(navController = nav, assetId = entry.toRoute<PostsRoute.AssetEdit>().assetId)
        }
        graph.composable<PostsRoute.AssetDiff> { entry ->
            AssetDiffScreen(navController = nav, assetId = entry.toRoute<PostsRoute.AssetDiff>().assetId)
        }
    }

    override fun titleFor(route: String): String? = when {
        route.contains("PostsRoute.PostNew") -> "New Post"
        route.contains("PostsRoute.PostEdit") -> "Post"
        route.contains("PostsRoute.Posts") -> "Posts"
        route.contains("PostsRoute.MediaPicker") -> "Select Media"
        route.contains("PostsRoute.MediaPrep") -> "Media Prep"
        route.contains("PostsRoute.AssetDiff") -> "Asset Diff"
        route.contains("PostsRoute.AssetEdit") -> "Edit Asset"
        route.contains("PostsRoute.Assets") -> "Assets"
        else -> null
    }

    override fun routeFor(navigateTo: String): Any? =
        if (navigateTo == PostsTargets.POSTS) PostsRoute.Posts else null

    /** Images and videos start a new post; GPX and friends belong to other features. */
    override fun shareRoute(mimeType: String?): Any? {
        val mime = mimeType.orEmpty()
        val media = mime.isEmpty() || mime == "*/*" || mime.startsWith("image/") || mime.startsWith("video/")
        return if (media) PostsRoute.MediaPrep() else null
    }
}

private fun NavController.backToPostList() {
    navigate(PostsRoute.Posts) {
        popUpTo<PostsRoute.Posts> { inclusive = false }
        launchSingleTop = true
    }
}

/**
 * Leave MediaPrep for the post editor. Came from that post's editor → pop back
 * to it; otherwise (menu, share, another feature) → open the editor in its place.
 */
private fun NavController.finishMediaPrep(postId: Long) {
    if (previousBackStackEntry.isEditorFor(postId)) {
        popBackStack()
    } else {
        navigate(PostsRoute.PostEdit(postId)) {
            popUpTo<PostsRoute.MediaPrep> { inclusive = true }
        }
    }
}

/** PostNew only opens MediaPrep with the id it just saved, so it always matches. */
private fun NavBackStackEntry?.isEditorFor(postId: Long): Boolean {
    val entry = this ?: return false
    val dest = entry.destination
    return dest.hasRoute<PostsRoute.PostNew>() ||
        (dest.hasRoute<PostsRoute.PostEdit>() && entry.toRoute<PostsRoute.PostEdit>().postId == postId)
}

/** Media from other features (track renders) opens a fresh MediaPrep with it. */
class PostsMediaInbox @Inject constructor() : MediaInbox {
    override fun offer(nav: NavController, uris: List<Uri>) {
        nav.navigate(PostsRoute.MediaPrep())
        nav.currentBackStackEntry?.savedStateHandle?.set(NavResultKeys.SHARED_URIS, encodeUris(uris))
    }
}

/** Clear posts stuck at QUEUED whose worker is gone (see PostRepository.reconcileQueuedPosts). */
class PostRepairInitializer @Inject constructor(
    private val repository: PostRepository,
) : AppInitializer {
    override suspend fun run() = repository.reconcileQueuedPosts()
}
