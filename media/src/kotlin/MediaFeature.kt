package de.perigon.companion.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import de.perigon.companion.core.feature.CommonRequirements
import de.perigon.companion.core.feature.FeatureEntry
import de.perigon.companion.core.feature.MediaInbox
import de.perigon.companion.core.feature.MenuItem
import de.perigon.companion.core.ui.takeSharedUris
import de.perigon.companion.media.ui.queue.QueueScreen
import de.perigon.companion.media.ui.render.TrackRenderScreen
import kotlinx.serialization.Serializable
import java.util.Optional
import javax.inject.Inject

object MediaRoute {
    @Serializable data object Queue
    @Serializable data object TrackRender
}

/**
 * `navigate_to` values a notification may carry. Route class names are not
 * user-facing strings — these are what producers put into PendingIntent
 * extras, so they must survive renames of [MediaRoute] entries.
 */
object MediaTargets {
    const val QUEUE        = "Queue"
    const val TRACK_RENDER = "TrackRender"
}

/**
 * Media owns transforms and rendering. Consumes GPX (from shares, or from
 * :sensors via the RecordedTrackSource contract in :core); produces JPEG.
 *
 * Also owns the transform Queue: [MediaRoute.Queue] is the single diagnostic
 * surface for every consumer of TransformQueue — posts publish and archive
 * consolidation alike. It is a Media menu item and a Media `navigate_to`
 * target, not a Posts one, because the pipeline it reports on belongs to no
 * single feature.
 */
class MediaFeature @Inject constructor(
    private val inbox: Optional<MediaInbox>,
) : FeatureEntry {
    override val id = "media"
    override val order = 500
    override val menuItems = listOf(
        MenuItem("Track render", MediaRoute.TrackRender, order = 60),
        MenuItem("Queue", MediaRoute.Queue, order = 90),
    )

    override val requirements = listOf(
        CommonRequirements.dcim, // renders go to DCIM/RenderedTracks
        CommonRequirements.notifications,
    )

    override fun register(graph: NavGraphBuilder, nav: NavHostController) {
        graph.composable<MediaRoute.Queue> { QueueScreen(navController = nav) }
        graph.composable<MediaRoute.TrackRender> { entry ->
            // Shared GPX files, drained once.
            var gpxUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
            LaunchedEffect(entry) {
                val shared = entry.savedStateHandle.takeSharedUris()
                if (shared.isNotEmpty()) gpxUris = shared
            }
            val context = LocalContext.current
            TrackRenderScreen(
                navController = nav,
                onNavigateToMediaPrep = { uri -> handOver(context, nav, uri) },
                incomingGpxUris = gpxUris,
            )
        }
    }

    /** To posts when present, otherwise out through the share sheet. */
    private fun handOver(context: Context, nav: NavController, uri: Uri) {
        val target = inbox.orElse(null)
        if (target != null) {
            target.offer(nav, listOf(uri))
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share render").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun titleFor(route: String): String? = when {
        route.endsWith("MediaRoute.TrackRender") -> "Track Render"
        route.endsWith("MediaRoute.Queue") -> "Queue"
        else -> null
    }

    /**
     * Queue and TrackRender as deep-link targets. `navigate_to = "Queue"`
     * lands here — the queue is a Media-owned diagnostic, so a notification
     * from a publish or consolidate worker that wants to point the user at
     * pending transform work resolves to the same surface regardless of
     * which feature submitted the job.
     */
    override fun routeFor(navigateTo: String): Any? = when (navigateTo) {
        MediaTargets.QUEUE        -> MediaRoute.Queue
        MediaTargets.TRACK_RENDER -> MediaRoute.TrackRender
        else                      -> null
    }

    override fun shareRoute(mimeType: String?): Any? {
        val mime = mimeType.orEmpty()
        return if (mime.contains("gpx") || mime.contains("octet")) MediaRoute.TrackRender else null
    }
}
