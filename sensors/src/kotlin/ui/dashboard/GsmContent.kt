package de.perigon.companion.sensors.ui.dashboard

import android.os.SystemClock
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
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.perigon.companion.core.ui.access.RequireAccess
import de.perigon.companion.sensors.SensorsRequirements
import kotlinx.coroutines.delay

@Composable
fun GsmContent(vm: DashboardViewModel) {
    val cells by vm.cellTowers.collectAsStateWithLifecycle()

    // No lifecycle effect here. Registration is owned by SensorsScreen's
    // DisposableEffect so the callback runs across the whole Sensors screen
    // and survives screen-off/background. This tab only reads the
    // accumulation buffer. If the user grants telephony permission via the
    // gate below while this screen is already composed, the LaunchedEffect
    // inside RequireAccess re-registers — the idempotent guard in
    // startTelephony() makes that a no-op when the callback is already up.

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
    // UI-only ticker so the "seen Xs ago" labels don't go stale while the
    // tab is on screen. Does not drive data collection — the telephony
    // callback does that. 5 s is enough resolution for "recently seen" and
    // cheap enough to be invisible.
    val nowMs by produceState(initialValue = SystemClock.elapsedRealtime()) {
        while (true) {
            delay(5_000L)
            value = SystemClock.elapsedRealtime()
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Cells seen", style = MaterialTheme.typography.titleMedium)
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
                            "MCC ${cell.mcc} · MNC ${cell.mnc} · ${formatAge(nowMs - cell.lastSeenMs)}",
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

private fun formatAge(ageMs: Long): String = when {
    ageMs < 1_000L -> "just now"
    ageMs < 60_000L -> "${ageMs / 1_000L}s ago"
    ageMs < 3_600_000L -> "${ageMs / 60_000L}m ago"
    else -> "${ageMs / 3_600_000L}h ago"
}
