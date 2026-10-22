package de.perigon.companion.archive.backup.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.perigon.companion.archive.data.BackupFolderEntity
import de.perigon.companion.archive.data.ArchiveSourceScanner
import de.perigon.companion.archive.data.ArchiveStateRepository
import de.perigon.companion.core.feature.QrScan
import de.perigon.companion.core.feature.SettingsSection
import de.perigon.companion.core.saf.folderLabel
import de.perigon.companion.core.ui.SectionHeader
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The set of folders the backup reads from. Set-once configuration: the
 * default DCIM source is added on first open, custom folders can be added,
 * disabled or removed. Operational screens (Consolidate) show a summary
 * card that deep-links back here when the user wants to change the list.
 *
 * clear(secretsOnly) is a no-op — folder grants are permissions, not
 * settings; the "clear all settings" flow leaves them intact.
 */
class BackupSourcesSettingsSection @Inject constructor() : SettingsSection {

    override val id = "backup_sources"
    override val order = 95

    @Composable
    override fun Content(scan: QrScan) {
        BackupSourcesContent()
    }

    override suspend fun clear(secretsOnly: Boolean) { /* keep grants */ }
}

@Composable
private fun BackupSourcesContent(
    vm: BackupSourcesSettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val folders by vm.folders.collectAsStateWithLifecycle()

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        context.contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        vm.addFolder(uri, folderLabel(uri))
    }

    val defaultFolders = folders.filter { it.isDefault }
    val customFolders  = folders.filter { !it.isDefault }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader(
            "Backup sources",
            "Folders included in the backup. DCIM covers PostMedia and Consolidated automatically.",
        )

        defaultFolders.forEach { folder ->
            BackupSourceRow(
                folder = folder,
                onToggle = { vm.toggleFolder(folder.id, it) },
                onRemove = null,
            )
        }

        if (customFolders.isNotEmpty()) {
            Text(
                "Additional folders",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            customFolders.forEach { folder ->
                BackupSourceRow(
                    folder = folder,
                    onToggle = { vm.toggleFolder(folder.id, it) },
                    onRemove = { vm.removeFolder(folder.id) },
                )
            }
        } else {
            Text(
                "No additional folders added.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OutlinedButton(onClick = { picker.launch(null) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Add, null, Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Add folder")
        }
    }
}

@Composable
private fun BackupSourceRow(
    folder: BackupFolderEntity,
    onToggle: (Boolean) -> Unit,
    onRemove: (() -> Unit)?,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Default.Folder, null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(Modifier.weight(1f)) {
                Text(folder.displayName, style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (folder.includeInBackup) "Included in backup" else "Excluded from backup",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = folder.includeInBackup, onCheckedChange = onToggle)
            if (onRemove != null) {
                IconButton(onClick = onRemove, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Default.Delete, "Remove folder",
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@HiltViewModel
class BackupSourcesSettingsViewModel @Inject constructor(
    private val stateRepo: ArchiveStateRepository,
    private val scanner: ArchiveSourceScanner,
) : ViewModel() {

    val folders: StateFlow<List<BackupFolderEntity>> = stateRepo.observeFolders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // Preserve the "opening the settings seeds DCIM" behavior that the
        // deleted BackupViewModel provided. `seedDefaultsIfEmpty` is
        // idempotent and also runs before every scan, so this only matters
        // the very first time the user opens Settings before any backup.
        viewModelScope.launch { scanner.seedDefaultsIfEmpty() }
    }

    fun addFolder(uri: android.net.Uri, displayName: String) {
        viewModelScope.launch {
            stateRepo.upsertFolder(BackupFolderEntity(
                uri = uri.toString(),
                displayName = displayName,
                includeInBackup = true,
                isDefault = false,
                addedAt = System.currentTimeMillis(),
            ))
        }
    }

    fun removeFolder(id: Long) {
        viewModelScope.launch { stateRepo.deleteFolder(id) }
    }

    fun toggleFolder(id: Long, enabled: Boolean) {
        viewModelScope.launch { stateRepo.setFolderEnabled(id, enabled) }
    }
}
