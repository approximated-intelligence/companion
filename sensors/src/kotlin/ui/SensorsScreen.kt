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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SensorsScreen(navController: NavController) {
    // Shared across GNSS / Raw / Wifi / GSM so the hardware listeners
    // (GNSS, sensors, telephony, wifi) survive tab switches.
    val vm: SensorDashboardViewModel = hiltViewModel()

    // Dedicated filter + listeners for the KF tab. Separate from vm so
    // the KF tab's diagnostics don't perturb the recording pipeline's
    // filter, and so the debug state (mode, history) survives tab
    // switches without restarting the recording pipeline's filter.
    val kfVm: KfViewModel = hiltViewModel()

    // Telephony is registered for the WHOLE Sensors screen, not just the
    // GSM tab. Two independent reasons:
    //
    //  1. DisposableEffect keeps the callback registered across screen-off
    //     and app backgrounding (composition survives lifecycle pause), so
    //     cells that appear while the screen is dark still land in the
    //     accumulation buffer and are visible on return. LifecycleResumeEffect
    //     would drop them on the first onPause.
    //  2. Registering at SensorsScreen level means the callback runs while
    //     the user is on any tab, not just GSM. Sparse cells don't disappear
    //     between tab switches because the buffer (on the ViewModel) keeps
    //     accumulating.
    //
    // The effect fires once on composition entry. Registration is idempotent
    // and no-ops without permission; GsmContent's RequireAccess has its own
    // LaunchedEffect(Unit) { vm.startTelephony() } which handles the case
    // where permission is granted while this screen is already composed.
    DisposableEffect(Unit) {
        vm.startTelephony()
        onDispose { vm.stopTelephony() }
    }

    val backgroundGpsEnabled by vm.backgroundGpsEnabled.collectAsStateWithLifecycle()

    // Default to GNSS when background GPS is off, Track otherwise.
    var selectedTab by rememberSaveable {
        mutableStateOf(if (backgroundGpsEnabled) SensorsTab.Track else SensorsTab.Gnss)
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
