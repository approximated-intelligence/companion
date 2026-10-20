package de.perigon.companion.sensors

import android.Manifest
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import dagger.hilt.android.qualifiers.ApplicationContext
import de.perigon.companion.core.feature.AccessRequirement
import de.perigon.companion.core.feature.AppInitializer
import de.perigon.companion.core.feature.CommonRequirements
import de.perigon.companion.core.feature.FeatureEntry
import de.perigon.companion.core.feature.MenuItem
import de.perigon.companion.core.feature.QrScan
import de.perigon.companion.core.feature.SettingsSection
import de.perigon.companion.core.ui.SectionHeader
import de.perigon.companion.core.ui.ToggleRow
import de.perigon.companion.sensors.data.TrackConfigPrefs
import de.perigon.companion.sensors.data.TrackPrefs
import de.perigon.companion.sensors.service.AutoScheduler
import de.perigon.companion.sensors.service.BackgroundService
import de.perigon.companion.sensors.ui.recording.RecordingScreen
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import javax.inject.Inject

object SensorsRoute {
    @Serializable data object Recording
}

object SensorsChannels {
    const val RECORDER = "track_recorder"
    const val EXPORT = "track_export"
}

/** "navigate_to" values; RECORDING matches BackgroundService.NAV_RECORDING. */
object SensorsTargets {
    const val RECORDING = BackgroundService.NAV_RECORDING
}

object SensorsRequirements {
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

/**
 * The recording feature. Produces tracks; does not consume GPX from other
 * apps, and does not render — both live in :media.
 */
class SensorsFeature @Inject constructor(
    private val prefs: TrackPrefs,
) : FeatureEntry {
    override val id = "sensors"
    override val order = 300
    override val startRoute: Any = SensorsRoute.Recording

    override val menuItems = listOf(
        MenuItem("Track", SensorsRoute.Recording, order = 70, visible = prefs.observeBackgroundGps()),
    )

    override val requirements = listOf(
        SensorsRequirements.location,
        SensorsRequirements.backgroundLocation,
        SensorsRequirements.exactAlarms,
        CommonRequirements.notifications,
    )

    override fun register(graph: NavGraphBuilder, nav: NavHostController) {
        graph.composable<SensorsRoute.Recording> { RecordingScreen(navController = nav) }
    }

    override fun titleFor(route: String): String? = when {
        route.endsWith("SensorsRoute.Recording") -> "Run"
        else -> null
    }

    override fun routeFor(navigateTo: String): Any? =
        if (navigateTo == SensorsTargets.RECORDING) SensorsRoute.Recording else null
}

class SensorsSettingsSection @Inject constructor(
    private val prefs: TrackPrefs,
) : SettingsSection {
    override val order = 300

    @Composable
    override fun Content(scan: QrScan) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val enabled by prefs.observeBackgroundGps().collectAsStateWithLifecycle(prefs.isBackgroundGpsEnabled())
        val showDeprecated by prefs.observeShowDeprecatedModes()
            .collectAsStateWithLifecycle(prefs.isShowDeprecatedModesEnabled())

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeader("Background GPS", "Required for recording. Rendering and sharing work without it.")
            ToggleRow(
                title = "Enable Background GPS",
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
            if (enabled) {
                ToggleRow(
                    title = "Show deprecated recording modes",
                    subtitle = "Single fix and Alarm. Older scheduling strategies; hidden by default.",
                    checked = showDeprecated,
                    onCheckedChange = { on ->
                        scope.launch { prefs.setShowDeprecatedModes(on) }
                    },
                )
            }
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
class SensorsResumeInitializer @Inject constructor(
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
