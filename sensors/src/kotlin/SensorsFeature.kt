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
import androidx.navigation.toRoute
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
import de.perigon.companion.sensors.ui.SensorsScreen
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import javax.inject.Inject

object SensorsRoute {
    /**
     * The Sensors destination. [initialTab] names a SensorsTab enum entry
     * (e.g. "Kf") to select on first composition of this back-stack entry.
     * When null (the natural navigation case), SensorsScreen falls back to
     * its standard "Track if GPS enabled, GNSS otherwise" default.
     *
     * A data class, not a data object, so the FGS notification (and any
     * future deep link) can request a specific tab. The screen reads
     * [initialTab] once as a rememberSaveable key; subsequent user tab taps
     * override it without changing the route, so re-entering the same entry
     * (e.g. back then forward through another tab) preserves the user's
     * last choice.
     */
    @Serializable data class Recording(val initialTab: String? = null)
}

object SensorsChannels {
    const val RECORDER   = "track_recorder"
    const val EXPORT     = "track_export"
    const val KF_SANDBOX = "kf_sandbox"
}

/**
 * "navigate_to" values; RECORDING matches BackgroundService.NAV_RECORDING.
 *
 * KF_SANDBOX is used by the KF sandbox's FGS notification (see
 * KfSandboxService) to land the user directly on the KF tab instead of the
 * Sensors screen's default. Resolves through SensorsFeature.routeFor() to
 * SensorsRoute.Recording(initialTab = "Kf").
 */
object SensorsTargets {
    const val RECORDING  = BackgroundService.NAV_RECORDING
    const val KF_SANDBOX = "KfSandbox"
}

object SensorsRequirements {
    val location: AccessRequirement = AccessRequirement.Permissions(
        key = "location",
        title = "Location",
        rationale = "For recording tracks and showing the GNSS skyplot.",
        permissions = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
        optional = true,
    )
    val backgroundLocation: AccessRequirement =
        AccessRequirement.BackgroundLocation("For recording with the screen off.")
    val exactAlarms: AccessRequirement =
        AccessRequirement.ExactAlarms("For scheduled recording and alarm-mode fixes.")

    /**
     * Cell info on API 30+ requires BOTH permissions: READ_PHONE_STATE for
     * the TelephonyCallback to fire, and ACCESS_FINE_LOCATION for the cells
     * not to be redacted (MCC/MNC blank, CID/NCI = UNAVAILABLE). Asking for
     * only READ_PHONE_STATE produced a callback that delivered cells with
     * every identity field blank — indistinguishable from "no cells".
     * Both are already required elsewhere ([location] asks for the same
     * FINE_LOCATION), so in practice this doesn't add a new prompt; it just
     * makes the WiFi/GSM tab's own requirement honest.
     */
    val telephony: AccessRequirement = AccessRequirement.Permissions(
        key = "telephony",
        title = "Phone State",
        rationale = "Required to display GSM/LTE/NR cell tower information.",
        permissions = listOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ),
        optional = true
    )

    /**
     * NEARBY_WIFI_DEVICES is the only runtime permission WiFi scan needs on
     * API 33+. ACCESS_FINE_LOCATION was required on API 30-32, but minSdk=34
     * excludes those — and requiring it here would force any user who wants
     * only the WiFi tab to grant a location permission for a feature that
     * doesn't use location.
     */
    val wifi: AccessRequirement = AccessRequirement.Permissions(
        key = "wifi_scan",
        title = "WiFi Scan",
        rationale = "Required to display nearby WiFi networks.",
        permissions = listOf(Manifest.permission.NEARBY_WIFI_DEVICES),
        optional = true
    )
}

/**
 * The sensors feature: recording, GNSS skyplot, raw sensors, WiFi and GSM
 * inspection. Produces tracks; does not consume GPX from other apps, and
 * does not render — both live in :media.
 */
class SensorsFeature @Inject constructor() : FeatureEntry {
    override val id = "sensors"
    override val order = 300
    override val startRoute: Any = SensorsRoute.Recording()

    /**
     * Always visible: only the Track tab needs background GPS, and the other
     * tabs (GNSS / Raw / Wifi / GSM) work regardless of it. [SensorsScreen]
     * hides the Track tab itself when background GPS is off.
     */
    override val menuItems = listOf(
        MenuItem("Sensors", SensorsRoute.Recording(), order = 70),
    )

    override val requirements = listOf(
        SensorsRequirements.location,
        SensorsRequirements.backgroundLocation,
        SensorsRequirements.exactAlarms,
        CommonRequirements.notifications,
        SensorsRequirements.telephony,
        SensorsRequirements.wifi
    )

    override fun register(graph: NavGraphBuilder, nav: NavHostController) {
        graph.composable<SensorsRoute.Recording> { entry ->
            val route = entry.toRoute<SensorsRoute.Recording>()
            SensorsScreen(navController = nav, initialTabId = route.initialTab)
        }
    }

    override fun titleFor(route: String): String? = when {
        // Data class with optional arg: the serialized route pattern is
        // "SensorsRoute.Recording?initialTab=..." or "SensorsRoute.Recording"
        // depending on whether the arg is present. contains() matches both.
        route.contains("SensorsRoute.Recording") -> "Sensors"
        else -> null
    }

    override fun routeFor(navigateTo: String): Any? = when (navigateTo) {
        SensorsTargets.RECORDING  -> SensorsRoute.Recording()
        SensorsTargets.KF_SANDBOX -> SensorsRoute.Recording(initialTab = "Kf")
        else -> null
    }
}

class SensorsSettingsSection @Inject constructor(
    private val prefs: TrackPrefs,
) : SettingsSection {
    override val id = "sensors"
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
