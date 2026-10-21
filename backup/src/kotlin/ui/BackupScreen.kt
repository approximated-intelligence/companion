package de.perigon.companion.backup.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import de.perigon.companion.backup.R
import de.perigon.companion.backup.data.BackupFolderEntity
import de.perigon.companion.backup.data.BackupSchedulePrefs
import de.perigon.companion.backup.worker.BackupWorker
import de.perigon.companion.core.saf.folderLabel
import de.perigon.companion.core.ui.AppTopBar
import de.perigon.companion.core.ui.NotificationObserver
import de.perigon.companion.core.ui.formatDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    navController: NavController,
    vm:            BackupViewModel = hiltViewModel(),
) {
    val state   by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context  = LocalContext.current

    LaunchedEffect(Unit) {
        NotificationObserver.observe(this, vm.notifications, snackbar)
    }

    LaunchedEffect(Unit) {
        vm.snackbar.events.collect { snackbar.showSnackbar(it) }
    }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        context.contentResolver.takePersistableUriPermission(
            uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val display = folderLabel(uri)
        vm.addFolder(uri, display)
    }

    // No DcimGate here: AppAccessGate wraps the whole nav graph, so reaching
    // this screen already implies a writable DCIM grant.
    Scaffold(
        topBar       = { AppTopBar(navController) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            BackupContent(
                state            = state,
                snackbarHost     = snackbar,
                onCancelBackup   = vm::cancelBackup,
                onStartBackup    = vm::startBackup,
                onSetAutoEnabled = vm::setAutoEnabled,
                onSetInterval    = vm::setInterval,
                onSetNumParts    = vm::setNumPartsPerPack,
                onToggleFolder   = vm::toggleFolder,
                onRemoveFolder   = vm::removeFolder,
                folderPicker     = { folderPicker.launch(null) },
            )
        }
    }
}

@Composable
private fun BackupContent(
    state: BackupUiState,
    snackbarHost: SnackbarHostState,
    onCancelBackup: () -> Unit,
    onStartBackup: () -> Unit,
    onSetAutoEnabled: (Boolean) -> Unit,
    onSetInterval: (Int) -> Unit,
    onSetNumParts: (Int) -> Unit,
    onToggleFolder: (Long, Boolean) -> Unit,
    onRemoveFolder: (Long) -> Unit,
    folderPicker: () -> Unit,
) {
    LazyColumn(
        modifier            = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding      = PaddingValues(vertical = 16.dp),
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Storage", style = MaterialTheme.typography.titleSmall)
                    Text("${state.confirmedCount} files confirmed in B2")
                    state.lastConfirmedAt?.let { ts ->
                        Text(
                            "Last confirmed: ${formatDateTime(ts, "MMM d, HH:mm")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item {
            BackupDestinationSection(
                snackbarHost = snackbarHost,
                enabled      = !state.progress.isRunning,
            )
        }

        if (state.progress.isRunning || state.progress.packState != BackupWorker.STATE_DONE) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(16.dp).animateContentSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(backupStateLabel(state.progress.packState), style = MaterialTheme.typography.titleSmall)
                            if (state.progress.packPosition > 0)
                                Text("Pack ${state.progress.packPosition}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (state.progress.filesTotal > 0) {
                            LinearProgressIndicator(
                                progress = { state.progress.fileIndex.toFloat() / state.progress.filesTotal },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("File ${state.progress.fileIndex} / ${state.progress.filesTotal}",
                                    style = MaterialTheme.typography.bodySmall)
                                if (state.progress.partNumber > 0) {
                                    val partsLabel = pluralStringResource(
                                        R.plurals.parts_finished,
                                        state.progress.partNumber,
                                        state.progress.partNumber,
                                    )
                                    Text("$partsLabel - ${state.progress.packPercent}%",
                                        style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            if (state.progress.currentFile.isNotEmpty()) {
                                Text(state.progress.currentFile.substringAfterLast('/'),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }

        if (state.progress.hasError) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(backupErrorLabel(state.progress.errorType),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.titleSmall)
                        if (state.progress.errorDetail.isNotEmpty()) {
                            Text(state.progress.errorDetail,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        item {
            if (state.progress.isRunning) {
                OutlinedButton(onClick = onCancelBackup, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Stop, null); Spacer(Modifier.width(8.dp)); Text("Cancel")
                }
            } else {
                Button(onClick = onStartBackup, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.CloudUpload, null); Spacer(Modifier.width(8.dp)); Text("Back up now")
                }
            }
        }

        item { HorizontalDivider() }
        item { Text("Automatic backup", style = MaterialTheme.typography.titleSmall) }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Auto-backup on Wi-Fi", style = MaterialTheme.typography.bodyMedium)
                    Text(if (state.autoEnabled) "Enabled (B2 only)" else "Disabled",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.autoEnabled) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = state.autoEnabled, onCheckedChange = onSetAutoEnabled)
            }
        }

        if (state.autoEnabled) {
            item {
                Text("Interval", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BackupSchedulePrefs.INTERVAL_OPTIONS.forEach { h ->
                        FilterChip(
                            selected = state.intervalHours == h,
                            onClick = { onSetInterval(h) },
                            label = { Text(if (h < 24) "${h}h" else "${h}h") },
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("Runs every ${state.intervalHours}h when on Wi-Fi, B2 only.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        item { HorizontalDivider() }
        item { Text("Pack size", style = MaterialTheme.typography.titleSmall) }

        item {
            Text("Parts per pack", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BackupSchedulePrefs.NUM_PARTS_OPTIONS.forEach { n ->
                    FilterChip(
                        selected = state.numPartsPerPack == n,
                        onClick = { onSetNumParts(n) },
                        label = { Text("$n") },
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            val sizeMb = state.numPartsPerPack * 8
            Text("${state.numPartsPerPack} parts × 8 MB = ${sizeMb} MB per pack.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        item { HorizontalDivider() }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("Backup folders", style = MaterialTheme.typography.titleSmall)
                FilledTonalButton(onClick = folderPicker) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp)); Text("Add folder")
                }
            }
        }

        val defaultFolders = state.folders.filter { it.isDefault }
        val customFolders  = state.folders.filter { !it.isDefault }

        if (defaultFolders.isNotEmpty()) {
            items(defaultFolders, key = { it.id }) { folder ->
                DefaultFolderRow(
                    folder = folder,
                    onToggle = { onToggleFolder(folder.id, it) },
                )
            }
        }

        if (customFolders.isNotEmpty()) {
            item {
                Text("Additional folders", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp))
            }
            items(customFolders, key = { it.id }) { folder ->
                FolderRow(folder = folder,
                    onToggle = { onToggleFolder(folder.id, it) },
                    onRemove = { onRemoveFolder(folder.id) })
            }
        } else {
            item {
                Text("No additional folders added.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DefaultFolderRow(
    folder: BackupFolderEntity,
    onToggle: (Boolean) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Folder, null, modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(folder.displayName, style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (folder.includeInBackup) "Included in backup" else "Excluded from backup",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = folder.includeInBackup, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun FolderRow(
    folder: BackupFolderEntity,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Folder, null, modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(folder.displayName, style = MaterialTheme.typography.bodyMedium)
                Text(if (folder.includeInBackup) "Included in backup" else "Excluded from backup",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = folder.includeInBackup, onCheckedChange = onToggle)
            IconButton(onClick = onRemove, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Delete, "Remove folder",
                    tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp))
            }
        }
    }
}
