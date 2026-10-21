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
fun GsmContent(vm: SensorDashboardViewModel) {
    val cells by vm.cellTowers.collectAsStateWithLifecycle()

    // Start on resume, stop on pause/dispose. TelephonyCallback is
    // unregistered in stopTelephony, which is what actually turns off the
    // radio-side notification stream.
    LifecycleResumeEffect(Unit) {
        vm.startTelephony()
        onPauseOrDispose { vm.stopTelephony() }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            RequireAccess(
                requirements = listOf(SensorsRequirements.telephony),
                inline = true,
            ) {
                LaunchedEffect(Unit) { vm.startTelephony() }
                CellTowerCard(cells)
            }
        }
    }
}

@Composable
private fun CellTowerCard(cells: List<CellTowerInfo>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Visible cells", style = MaterialTheme.typography.titleMedium)
            if (cells.isEmpty()) {
                Text(
                    "Waiting for telephony update…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            cells.forEach { cell ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${cell.type} · CID ${cell.cid}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "MCC ${cell.mcc} · MNC ${cell.mnc}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${cell.signalDbm} dBm", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
