package de.perigon.companion.track

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import dagger.hilt.android.qualifiers.ApplicationContext
import de.perigon.companion.core.feature.AccessRequirement
import de.perigon.companion.core.feature.AppInitializer
import de.perigon.companion.core.feature.CommonRequirements
import de.perigon.companion.core.feature.FeatureEntry
import de.perigon.companion.core.feature.MediaInbox
import de.perigon.companion.core.feature.MenuItem
import de.perigon.companion.core.feature.QrScan
import de.perigon.companion.core.feature.SettingsSection
import de.perigon.companion.core.ui.SectionHeader
import de.perigon.companion.core.ui.ToggleRow
import de.perigon.companion.core.ui.takeSharedUris
import de.perigon.companion.track.data.TrackConfigPrefs
import de.perigon.companion.track.data.TrackPrefs
import de.perigon.companion.track.service.AutoScheduler
import de.perigon.companion.track.service.BackgroundService
import de.perigon.companion.track.ui.recording.RecordingScreen
import de.perigon.companion.track.ui.render.TrackRenderScreen
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.util.Optional
import javax.inject.Inject

object TrackRoute {
    @Serializable data object Recording
    @Serializable data object TrackRender
}

object TrackChannels {
    const val RECORDER = "track_recorder"
    const val EXPORT = "track_export"
}

/** "navigate_to" values; RECORDING matches BackgroundService.NAV_RECORDING. */
object TrackTargets {
    const val RECORDING = BackgroundService.NAV_RECORDING
}

object TrackRequirements {
    val location: AccessRequirement = AccessRequirement.Permissions(
        key = "location",
        title = "Location",
        rationale = "For recording tracks.",
        permissions = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
        optional = true,
    )
    val backgroundLocation: AccessRequirement =
        AccessRequirement.BackgroundLocation("For recording with the screen off.")
    val exactAlarms: AccessRequirement =
        AccessRequirement.ExactAlarms("For scheduled recording and alarm-mode fixes.")
}

class TrackFeature @Inject constructor(
    private val prefs: TrackPrefs,
    private val inbox: Optional<MediaInbox>,
) : FeatureEntry {
    override val id = "track"
    override val order = 300
    override val startRoute: Any = TrackRoute.Recording

    override val menuItems = listOf(
        MenuItem("Track render", TrackRoute.TrackRender, order = 60),
        MenuItem("Track", TrackRoute.Recording, order = 70, visible = prefs.observeBackgroundGps()),
    )

    override val requirements = listOf(
        TrackRequirements.location,
        TrackRequirements.backgroundLocation,
        TrackRequirements.exactAlarms,
        CommonRequirements.dcim, // renders go to DCIM/RenderedTracks
        CommonRequirements.notifications,
    )

    override fun register(graph: NavGraphBuilder, nav: NavHostController) {
        graph.composable<TrackRoute.Recording> { RecordingScreen(navController = nav) }
        graph.composable<TrackRoute.TrackRender> { entry ->
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
        route.endsWith("TrackRoute.TrackRender") -> "Track Render"
        route.endsWith("TrackRoute.Recording") -> "Run"
        else -> null
    }

    override fun routeFor(navigateTo: String): Any? =
        if (navigateTo == TrackTargets.RECORDING) TrackRoute.Recording else null

    override fun shareRoute(mimeType: String?): Any? {
        val mime = mimeType.orEmpty()
        return if (mime.contains("gpx") || mime.contains("octet")) TrackRoute.TrackRender else null
    }
}

class TrackSettingsSection @Inject constructor(
    private val prefs: TrackPrefs,
) : SettingsSection {
    override val order = 300

    @Composable
    override fun Content(scan: QrScan) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val enabled by prefs.observeBackgroundGps().collectAsStateWithLifecycle(prefs.isBackgroundGpsEnabled())

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeader("Background GPS", "Required for recording. Rendering and sharing work without it.")
            ToggleRow(
                title = "GPS recording",
                subtitle = if (enabled) "Enabled - background location available" else "Disabled",
                checked = enabled,
                onCheckedChange = { on ->
                    scope.launch {
                        prefs.setBackgroundGpsEnabled(on)
                        if (!on && BackgroundService.status.value.isRecording) {
                            context.startService(
                                Intent(context, BackgroundService::class.java).setAction(BackgroundService.ACTION_STOP)
                            )
                        }
                    }
                },
            )
        }
    }

    override suspend fun clear(secretsOnly: Boolean) {
        if (!secretsOnly) prefs.clear()
    }
}

/**
 * Resume recording when auto-schedule is on and the window is active; also
 * reschedules alarms lost to reinstall, force-stop or process death.
 */
class TrackResumeInitializer @Inject constructor(
    @param:ApplicationContext private val ctx: Context,
    private val configPrefs: TrackConfigPrefs,
    private val prefs: TrackPrefs,
) : AppInitializer {
    override suspend fun run() {
        val config = configPrefs.get()
        val gps = prefs.isBackgroundGpsEnabled()
        if (!config.autoScheduleEnabled || !gps) return
        val scheduler = AutoScheduler(ctx, config, gps)
        scheduler.apply()
        if (scheduler.isInActiveWindow() && !BackgroundService.status.value.isRecording) {
            ctx.startForegroundService(
                Intent(ctx, BackgroundService::class.java).setAction(BackgroundService.ACTION_START)
            )
        }
    }
}
