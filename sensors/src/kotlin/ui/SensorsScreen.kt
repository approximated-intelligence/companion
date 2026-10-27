package de.perigon.companion.sensors.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import de.perigon.companion.core.ui.AppTopBar
import de.perigon.companion.core.ui.KeepScreenOn
import de.perigon.companion.sensors.ui.dashboard.GnssContent
import de.perigon.companion.sensors.ui.dashboard.GsmContent
import de.perigon.companion.sensors.ui.dashboard.KfContent
import de.perigon.companion.sensors.ui.dashboard.KfViewModel
import de.perigon.companion.sensors.ui.dashboard.NmeaContent
import de.perigon.companion.sensors.ui.dashboard.NmeaViewModel
import de.perigon.companion.sensors.ui.dashboard.RawSensorsContent
import de.perigon.companion.sensors.ui.dashboard.DashboardViewModel
import de.perigon.companion.sensors.ui.dashboard.WifiContent
import de.perigon.companion.sensors.ui.dashboard.TrackContent

@Composable
private fun KeepScreenOnRow(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Keep screen on while viewing",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = enabled, onCheckedChange = onToggle)
    }
}

private enum class SensorsTab(val label: String) {
    Track("Track"),
    Gnss("GNSS"),
    Raw("Raw"),
    Wifi("Wifi"),
    Gsm("GSM"),
    Kf("KF"),
    Nmea("NMEA"),
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
    val vm: DashboardViewModel = hiltViewModel()

    // Dedicated filter + listeners for the KF tab. Separate from vm so
    // the KF tab's diagnostics don't perturb the recording pipeline's
    // filter, and so the debug state (mode, history) survives tab
    // switches without restarting the recording pipeline's filter.
    val kfVm: KfViewModel = hiltViewModel()

    // NMEA tab has its own ViewModel — it owns a private NmeaStream lease
    // and a private Location listener, both independent of the dashboard
    // VM and the recorder. Orthogonal per design §5.
    val nmeaVm: NmeaViewModel = hiltViewModel()

    val backgroundGpsEnabled by vm.backgroundGpsEnabled.collectAsStateWithLifecycle()
    val coordFormat by vm.coordFormat.collectAsStateWithLifecycle()

    // Screen-level "keep display on while viewing". Session-only by
    // design — not persisted to disk, off by default on every app launch.
    // rememberSaveable survives config change (rotation) via the saved-
    // state bundle, which is the correct scope: the toggle is a UI-session
    // choice, not an app preference. The flag is held by the Sensors screen
    // itself, so every tab inherits it without per-tab code.
    var keepScreenOnSensors by rememberSaveable { mutableStateOf(false) }

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
        add(SensorsTab.Nmea)
    }

    LaunchedEffect(visibleTabs) {
        if (selectedTab !in visibleTabs) selectedTab = visibleTabs.first()
    }

    // Screen-level lifecycle. Runs for the whole time the Sensors screen
    // is resumed, regardless of which tab is showing. Tab changes do not
    // touch this — keying on selectedTab was the bug: it conflated "tab
    // changed" with "lifecycle paused", so every switch tore the session
    // down and forced a cold reacquire. The user-visible symptom was
    // "position is missing after I switch tabs" on GNSS, and "cells
    // restart from empty" on GSM.
    //
    // Both listeners collect for the entire screen lifetime by design:
    //
    //   - GNSS: DashboardViewModel.gnssState is shared state. GNSS, Raw
    //     and GSM all read from it (Raw's declination needs a position).
    //     Stopping the session on tab change makes that state go stale
    //     while the user is on Wifi / Kf / Nmea, so on return the skyplot
    //     and position are empty until the chip warms up.
    //   - Telephony: DashboardViewModel._cellTowers is an accumulation
    //     buffer with a 10-minute TTL. Stopping the callback on tab change
    //     means the GSM tab starts from empty every time, and cells that
    //     were seen on another tab are lost. The original design intent
    //     was always "collect while the Sensors screen is on".
    //
    // "App is showing" is the correct gate: while the screen is on, the
    // display dominates the power budget and GNSS at 1 Hz plus a modem-
    // driven telephony callback are rounding errors against it. The rule
    // that matters is "stop when the app goes away".
    //
    // Recording is unaffected. It runs in its own foreground service
    // (BackgroundService) with a persistent notification and its own
    // GnssSession. GnssController ref-counts across sessions, so the
    // dashboard's session and the recorder's coexist — the min-interval
    // wins while the app is showing, drops to recorder rate (or off)
    // once backgrounded.
    //
    // Start functions are idempotent (guard on an already-held session /
    // callback), so pause → resume during a config change re-acquires
    // cleanly.
    //
    // Contrast with KfContent: that tab also keys its LifecycleResumeEffect
    // on Unit and stops on pause, but its composition only exists while
    // the KF tab is selected, so "stop on pause" there effectively means
    // "stop when the tab scrolls out of view." Here, the session spans
    // every tab, so "stop on pause" means "stop when the whole screen
    // pauses." Same primitive, different scope — do not "unify" them
    // without deciding which lifetime the session actually wants.
    LifecycleResumeEffect(Unit) {
        vm.startGnss()
        vm.startTelephony()
        onPauseOrDispose {
            vm.stopGnss()
            vm.stopTelephony()
        }
    }

    // Belt-and-suspenders for the navigate-away path. LifecycleResumeEffect
    // above already fires onPauseOrDispose on dispose, so this is
    // functionally redundant — kept because the intent is worth stating
    // explicitly and the calls are idempotent.
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

            // One switch governs every tab. Sits below the tab row so it
            // is visible from all six without duplicating UI per tab.
            KeepScreenOnRow(
                enabled = keepScreenOnSensors,
                onToggle = { keepScreenOnSensors = it },
            )
            HorizontalDivider()

            // Guarded by the toggle: adding/removing KeepScreenOn() from
            // composition flips the window flag via its internal
            // DisposableEffect, so flipping the switch at runtime works
            // without restarting the screen. Not persisted — off by
            // default on every app launch.
            if (keepScreenOnSensors) KeepScreenOn()

            when (selectedTab) {
                SensorsTab.Track -> TrackContent()
                SensorsTab.Gnss -> GnssContent(vm)
                SensorsTab.Raw -> RawSensorsContent(vm)
                SensorsTab.Wifi -> WifiContent(vm)
                SensorsTab.Gsm -> GsmContent(vm)
                SensorsTab.Kf -> KfContent(kfVm)
                SensorsTab.Nmea -> NmeaContent(nmeaVm, coordFormat, vm::setCoordFormat)
            }
        }
    }
}