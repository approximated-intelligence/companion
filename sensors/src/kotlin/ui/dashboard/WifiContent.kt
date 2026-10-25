package de.perigon.companion.sensors.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.perigon.companion.core.ui.access.RequireAccess
import de.perigon.companion.sensors.SensorsRequirements

@Composable
fun WifiContent(vm: DashboardViewModel) {
    val networks by vm.wifiNetworks.collectAsStateWithLifecycle()

    // Start on resume, stop on pause/dispose. Tab switch disposes the old
    // content → stopWifi; back to the tab → LifecycleResumeEffect → startWifi.
    LifecycleResumeEffect(Unit) {
        vm.startWifi()
        onPauseOrDispose { vm.stopWifi() }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            RequireAccess(
                requirements = listOf(SensorsRequirements.wifi),
                inline = true,
            ) {
                LaunchedEffect(Unit) { vm.startWifi() }
                WifiCard(networks)
            }
        }
    }
}

@Composable
private fun WifiCard(networks: List<WifiInfo>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Nearby networks", style = MaterialTheme.typography.titleMedium)
            if (networks.isEmpty()) {
                Text(
                    "Scanning…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            networks.forEach { net ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(net.ssid, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        Text(
                            net.bssid,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${net.signalDbm} dBm", style = MaterialTheme.typography.bodyMedium)
                        Text("Ch: ${net.frequencyMhz / 5}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
