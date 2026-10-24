package de.perigon.companion.sensors.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import de.perigon.companion.core.ui.AppTopBar
import de.perigon.companion.sensors.ui.dashboard.GnssContent
import de.perigon.companion.sensors.ui.dashboard.GsmContent
import de.perigon.companion.sensors.ui.dashboard.KfContent
import de.perigon.companion.sensors.ui.dashboard.KfViewModel
import de.perigon.companion.sensors.ui.dashboard.RawSensorsContent
import de.perigon.companion.sensors.ui.dashboard.SensorDashboardViewModel
import de.perigon.companion.sensors.ui.dashboard.WifiContent
import de.perigon.companion.sensors.ui.recording.RecordingScreenContent

private enum class SensorsTab(val label: String) {
    Track("Track"),
    Gnss("GNSS"),
    Raw("Raw"),
    Wifi("Wifi"),
    Gsm("GSM"),
    Kf("KF"),
}

/**
 * [initialTabId] names a [SensorsTab] entry ("Kf" for the KF sandbox). Used
 * by the KfSandboxService notification to deep-link past the default tab.
 * Null for ordinary navigation. See SensorsRoute.Recording.
 *
 * Read as a rememberSaveable key: when the route's initialTab changes (a
 * KF notification tap while already on Sensors with launchSingleTop), the
 * state re-initializes to the requested tab. User tab taps within the same
 * composition do not change the key, so they survive save/restore.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SensorsScreen(navController: NavController, initialTabId: String? = null) {
    // Shared across GNSS / Raw / Wifi / GSM so the hardware listeners
    // (GNSS, sensors, telephony, wifi) survive tab switches.
    val vm: SensorDashboardViewModel = hiltViewModel()

    // Dedicated filter + listeners for the KF tab. Separate from vm so
    // the KF tab's diagnostics don't perturb the recording pipeline's
    // filter, and so the debug state (mode, history) survives tab
    // switches without restarting the recording pipeline's filter.
    val kfVm: KfViewModel = hiltViewModel()

    val backgroundGpsEnabled by vm.backgroundGpsEnabled.collectAsStateWithLifecycle()

    // Route-provided tab wins on first composition of this back-stack entry
    // (and re-initializes when initialTabId changes, e.g. tapping the KF
    // notification while already on Sensors with launchSingleTop). Otherwise
    // falls back to the natural default. If the route-selected tab is not in
    // visibleTabs (e.g. "Track" when background GPS is off), the
    // LaunchedEffect(visibleTabs) below corrects it to the first visible one.
    var selectedTab by rememberSaveable(initialTabId) {
        val routed = initialTabId?.let { id ->
            SensorsTab.entries.firstOrNull { it.name == id }
        }
        mutableStateOf(routed ?: if (backgroundGpsEnabled) SensorsTab.Track else SensorsTab.Gnss)
    }

    val visibleTabs = buildList {
        if (backgroundGpsEnabled) add(SensorsTab.Track)
        add(SensorsTab.Gnss)
        add(SensorsTab.Raw)
        add(SensorsTab.Wifi)
        add(SensorsTab.Gsm)
        add(SensorsTab.Kf)
    }

    LaunchedEffect(visibleTabs) {
        if (selectedTab !in visibleTabs) selectedTab = visibleTabs.first()
    }

    // Listener registration is keyed on the visible tab, not on the screen.
    // Before this, GNSS ran at 1 Hz for the WHOLE Sensors screen lifetime
    // (including while the user was on the Track tab with the recorder
    // holding an unrelated 5 min hardware rate), waking the CPU and
    // feeding the recorder's passive-listener pipeline at a rate far above
    // what the user asked for.
    //
    //  - GNSS: needed by GNSS (skyplot), Raw (declination), Gsm (cells).
    //  - Telephony: needed by Gsm only.
    //
    // LifecycleResumeEffect keyed on selectedTab: re-fires on every tab
    // change AND every resume. The start/stop calls are idempotent, so
    // the resume-retry case (permission granted in Settings while
    // backgrounded) is a no-op when already registered.
    //
    // DO NOT add stops to onPauseOrDispose. The empty body is intentional:
    // not unregistering on pause keeps the listeners feeding across
    // screen-off and backgrounding, so a GNSS tab return shows a live
    // skyplot and Gsm cells don't drop between callbacks. The KF tab
    // stops on pause instead (see KfContent) — different design, different
    // rationale, both intentional.
    //
    // DisposableEffect: full unregister when the Sensors destination leaves
    // composition (navigate away, pop off back stack).
    LifecycleResumeEffect(selectedTab) {
        val needsGnss = selectedTab == SensorsTab.Gnss ||
            selectedTab == SensorsTab.Raw ||
            selectedTab == SensorsTab.Gsm
        if (needsGnss) vm.startGnss() else vm.stopGnss()

        val needsTelephony = selectedTab == SensorsTab.Gsm
        if (needsTelephony) vm.startTelephony() else vm.stopTelephony()

        onPauseOrDispose { /* intentionally empty — see comment above */ }
    }

    DisposableEffect(Unit) {
        onDispose {
            vm.stopGnss()
            vm.stopTelephony()
        }
    }

    Scaffold(
        topBar = { AppTopBar(navController) }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            PrimaryScrollableTabRow(
                selectedTabIndex = visibleTabs.indexOf(selectedTab).coerceAtLeast(0),
                edgePadding = 0.dp,
            ) {
                visibleTabs.forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        text = { Text(tab.label) },
                    )
                }
            }

            when (selectedTab) {
                SensorsTab.Track -> RecordingScreenContent()
                SensorsTab.Gnss -> GnssContent(vm)
                SensorsTab.Raw -> RawSensorsContent(vm)
                SensorsTab.Wifi -> WifiContent(vm)
                SensorsTab.Gsm -> GsmContent(vm)
                SensorsTab.Kf -> KfContent(kfVm)
            }
        }
    }
}
