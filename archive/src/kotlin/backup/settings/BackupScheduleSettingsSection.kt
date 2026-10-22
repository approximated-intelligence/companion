package de.perigon.companion.archive.backup.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import de.perigon.companion.archive.backup.data.BackupSchedulePrefs
import de.perigon.companion.archive.backup.worker.syncBackupSchedule
import de.perigon.companion.core.feature.QrScan
import de.perigon.companion.core.feature.SettingsSection
import de.perigon.companion.core.ui.SectionHeader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Set-once backup configuration that used to live on the now-deleted Backup
 * screen: the automatic-backup schedule and the pack size. Both affect every
 * future run, not the current one, so they belong in Settings alongside the
 * other set-once configuration rather than on an operational screen.
 *
 * No bootstrap QR payload — the schedule is device-local. Sources and B2
 * credentials each have their own section.
 */
class BackupScheduleSettingsSection @Inject constructor() : SettingsSection {

    override val id = "backup_schedule"
    override val order = 90

    @Composable
    override fun Content(scan: QrScan) {
        BackupScheduleContent()
    }

    override suspend fun clear(secretsOnly: Boolean) {
        // The schedule is not a secret and does not carry bootstrap data;
        // cleared by BackupSettingsSection's "clear all" when applicable.
    }
}

@Immutable
data class BackupScheduleState(
    val autoEnabled: Boolean,
    val intervalHours: Int,
    val numPartsPerPack: Int,
)

@HiltViewModel
class BackupScheduleSettingsViewModel @Inject constructor(
    private val prefs: BackupSchedulePrefs,
    private val workManager: WorkManager,
) : ViewModel() {

    private val _state = MutableStateFlow(
        BackupScheduleState(
            autoEnabled = prefs.autoEnabled(),
            intervalHours = prefs.intervalHours(),
            numPartsPerPack = prefs.numPartsPerPack(),
        )
    )
    val state: StateFlow<BackupScheduleState> = _state.asStateFlow()

    fun setAutoEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefs.setAutoEnabled(enabled)
            _state.update { it.copy(autoEnabled = enabled) }
            workManager.syncBackupSchedule(
                enabled = enabled,
                intervalHours = prefs.intervalHours(),
                policy = ExistingPeriodicWorkPolicy.UPDATE,
            )
        }
    }

    fun setInterval(hours: Int) {
        if (hours !in BackupSchedulePrefs.INTERVAL_OPTIONS) return
        viewModelScope.launch {
            prefs.setIntervalHours(hours)
            _state.update { it.copy(intervalHours = hours) }
            if (prefs.autoEnabled()) {
                workManager.syncBackupSchedule(
                    enabled = true,
                    intervalHours = hours,
                    policy = ExistingPeriodicWorkPolicy.UPDATE,
                )
            }
        }
    }

    fun setNumPartsPerPack(n: Int) {
        if (n !in BackupSchedulePrefs.NUM_PARTS_OPTIONS) return
        viewModelScope.launch {
            prefs.setNumPartsPerPack(n)
            _state.update { it.copy(numPartsPerPack = n) }
        }
    }
}

@Composable
private fun BackupScheduleContent(
    vm: BackupScheduleSettingsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader(
            "Backup schedule",
            "Automatic backups run on Wi-Fi only, to B2. Manual backups run on any network.",
        )

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Auto-backup on Wi-Fi", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (state.autoEnabled) "Enabled (B2 only)" else "Disabled",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.autoEnabled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = state.autoEnabled, onCheckedChange = vm::setAutoEnabled)
        }

        if (state.autoEnabled) {
            Text("Interval", style = MaterialTheme.typography.bodyMedium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                BackupSchedulePrefs.INTERVAL_OPTIONS.forEach { h ->
                    FilterChip(
                        selected = state.intervalHours == h,
                        onClick = { vm.setInterval(h) },
                        label = { Text("${h}h") },
                    )
                }
            }
            Text(
                "Runs every ${state.intervalHours}h when on Wi-Fi, B2 only.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider()
        Text("Pack size", style = MaterialTheme.typography.titleSmall)
        Text("Parts per pack", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            BackupSchedulePrefs.NUM_PARTS_OPTIONS.forEach { n ->
                FilterChip(
                    selected = state.numPartsPerPack == n,
                    onClick = { vm.setNumPartsPerPack(n) },
                    label = { Text("$n") },
                )
            }
        }
        Text(
            "${state.numPartsPerPack} parts × 8 MB = ${state.numPartsPerPack * 8} MB per pack.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
