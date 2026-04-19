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
import de.perigon.companion.R
import de.perigon.companion.backup.data.BackupFolderEntity
import de.perigon.companion.core.ui.AppTopBar
import de.perigon.companion.core.ui.NotificationObserver
import de.perigon.companion.backup.worker.BackupWorker
import de.perigon.companion.util.saf.DcimGate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import de.perigon.companion.backup.data.BackupSchedulePrefs

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
        val display = uri.lastPathSegment?.substringAfterLast(':') ?: uri.toString()
        vm.addFolder(uri, display)
    }

    Scaffold(
        topBar       = { AppTopBar(navController) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            DcimGate(
                dcimUri = state.dcimUri,
                onGranted = { uri -> vm.onDcimGranted(uri) },
            ) {
                BackupContent(state = state, vm = vm, folderPicker = { folderPicker.launch(null) })
            }
        }
    }
}

@Composable
private fun BackupContent(
    state: BackupUiState,
    vm: BackupViewModel,
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
                            "Last confirmed: ${formatTs(ts)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (state.isRunning || state.packState != BackupWorker.STATE_DONE) {
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
                            Text(packStateLabel(state.packState), style = MaterialTheme.typography.titleSmall)
                            if (state.packPosition > 0)
                                Text("Pack ${state.packPosition}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (state.filesTotal > 0) {
                            LinearProgressIndicator(
                                progress = { state.fileIndex.toFloat() / state.filesTotal },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("File ${state.fileIndex} / ${state.filesTotal}",
                                    style = MaterialTheme.typography.bodySmall)
                                if (state.partNumber > 0) {
                                    val partsLabel = pluralStringResource(
                                        R.plurals.parts_finished,
                                        state.partNumber,
                                        state.partNumber,
                                    )
                                    Text("$partsLabel - ${state.packPercent}%",
                                        style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            if (state.currentFile.isNotEmpty()) {
                                Text(state.currentFile.substringAfterLast('/'),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }

        if (state.hasError) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(errorTypeLabel(state.errorType),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.titleSmall)
                        if (state.errorDetail.isNotEmpty()) {
                            Text(state.errorDetail,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        item {
            if (state.isRunning) {
                OutlinedButton(onClick = vm::cancelBackup, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Stop, null); Spacer(Modifier.width(8.dp)); Text("Cancel")
                }
            } else {
                Button(onClick = vm::startBackup, modifier = Modifier.fillMaxWidth()) {
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
                    Text(if (state.autoEnabled) "Enabled" else "Disabled",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.autoEnabled) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = state.autoEnabled, onCheckedChange = vm::setAutoEnabled)
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
                            onClick = { vm.setInterval(h) },
                            label = { Text(if (h < 24) "${h}h" else "${h}h") },
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("Runs every ${state.intervalHours}h when on Wi-Fi.",
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
                        onClick = { vm.setNumPartsPerPack(n) },
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
        val customFolders = state.folders.filter { !it.isDefault }

        if (defaultFolders.isNotEmpty()) {
            items(defaultFolders, key = { it.id }) { folder ->
                DefaultFolderRow(
                    folder = folder,
                    onToggle = { vm.toggleFolder(folder.id, it) },
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
                    onToggle = { vm.toggleFolder(folder.id, it) },
                    onRemove = { vm.removeFolder(folder.id) })
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
            IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Delete, null,
                    tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp))
            }
        }
    }
}

private fun packStateLabel(state: String) = when (state) {
    BackupWorker.STATE_PLANNING   -> "Planning…"
    BackupWorker.STATE_HASHING    -> "Hashing…"
    BackupWorker.STATE_RECOVERING -> "Recovering interrupted upload…"
    BackupWorker.STATE_UPLOADING  -> "Uploading…"
    BackupWorker.STATE_COMPLETING -> "Completing upload…"
    BackupWorker.STATE_CONFIRMING -> "Confirming…"
    BackupWorker.STATE_DONE       -> "Last run complete"
    else                          -> state
}

private fun errorTypeLabel(type: String) = when (type) {
    BackupWorker.ERR_MISSING_CREDENTIALS -> "Credentials not configured"
    BackupWorker.ERR_FILE_MISSING        -> "File missing during backup"
    BackupWorker.ERR_FILE_MODIFIED       -> "File modified during backup"
    BackupWorker.ERR_B2_ERROR            -> "Storage error"
    BackupWorker.ERR_INCONSISTENT        -> "Inconsistent state - pack was reset"
    else                                 -> type
}

private fun formatTs(ts: Long): String =
    SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(ts))
