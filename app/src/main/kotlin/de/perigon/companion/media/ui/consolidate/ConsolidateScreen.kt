package de.perigon.companion.media.ui.consolidate

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import de.perigon.companion.backup.worker.BackupWorker
import de.perigon.companion.core.ui.AppTopBar
import de.perigon.companion.core.ui.NotificationObserver
import de.perigon.companion.media.data.SafeToDeleteView
import de.perigon.companion.util.saf.DcimGate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ConsolidateScreen(
    navController: NavController,
    vm: ConsolidateViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        NotificationObserver.observe(this, vm.notifications, snackbar)
    }

    LaunchedEffect(Unit) {
        vm.snackbar.events.collect { snackbar.showSnackbar(it) }
    }

    LaunchedEffect(Unit) {
        vm.previewIntents.collect { intent ->
            context.startActivity(intent)
        }
    }

    if (state.confirmDeleteCount != null) {
        AlertDialog(
            onDismissRequest = vm::dismissDelete,
            title = { Text("Delete files?") },
            text = { Text("Delete ${state.confirmDeleteCount} file(s) from Camera? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = vm::confirmDelete) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissDelete) { Text("Cancel") }
            },
        )
    }

    if (state.deleteProgress != null) {
        val progress = state.deleteProgress!!
        AlertDialog(
            onDismissRequest = { /* not dismissable while deleting */ },
            title = { Text("Deleting…") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${progress.current} / ${progress.total}")
                    LinearProgressIndicator(
                        progress = { progress.current.toFloat() / progress.total },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {},
        )
    }

    if (state.confirmUnprotectId != null) {
        AlertDialog(
            onDismissRequest = vm::dismissUnprotect,
            title = { Text("Remove protection?") },
            text = { Text("This file will become eligible for deletion again.") },
            confirmButton = {
                TextButton(onClick = vm::confirmUnprotect) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissUnprotect) { Text("Cancel") }
            },
        )
    }

    Scaffold(
        topBar = {
            if (state.selectionMode) {
                SelectionTopBar(
                    selectedCount = state.selectedCount,
                    onSelectAll = vm::selectAll,
                    onExit = vm::exitSelectionMode,
                    onDelete = vm::requestDelete,
                    deleteEnabled = !state.isDeleting,
                )
            } else {
                AppTopBar(navController)
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            DcimGate(
                dcimUri = state.dcimUri,
                onGranted = { uri -> vm.onDcimGranted(uri) },
                writeAccess = true,
            ) {
                when (state.viewMode) {
                    ViewMode.LIST -> ListContent(PaddingValues(), state, vm)
                    ViewMode.GRID -> GridContent(PaddingValues(), state, vm)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    selectedCount: Int,
    onSelectAll: () -> Unit,
    onExit: () -> Unit,
    onDelete: () -> Unit,
    deleteEnabled: Boolean,
) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onExit) {
                Icon(Icons.Default.Close, "Exit selection")
            }
        },
        title = { Text("$selectedCount selected") },
        actions = {
            TextButton(onClick = onSelectAll) { Text("All") }
            IconButton(onClick = onDelete, enabled = selectedCount > 0 && deleteEnabled) {
                Icon(Icons.Default.Delete, "Delete selected",
                    tint = if (selectedCount > 0 && deleteEnabled) MaterialTheme.colorScheme.error
                           else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}

@Composable
private fun ListContent(
    padding: PaddingValues,
    state: ConsolidateUiState,
    vm: ConsolidateViewModel,
) {
    LazyColumn(
        modifier = Modifier
            .padding(padding)
            .padding(horizontal = 16.dp)
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
    ) {
        item { ConsolidateProgressCard(state) }

        if (state.backupRunning || state.backupPackState != BackupWorker.STATE_DONE) {
            item { BackupProgressCard(state) }
        }

        if (state.backupHasError) {
            item { BackupErrorCard(state) }
        }

        item { StatusCard(state) }
        item { ActionButtons(state, vm) }

        if (state.sortedSafeToDelete.isNotEmpty()) {
            item { HorizontalDivider() }
            item { FileListHeader(state, vm) }

            items(state.sortedSafeToDelete, key = { it.id }) { file ->
                SafeToDeleteListRow(
                    file = file,
                    isProtected = file.isProtected,
                    isSelected = file.id in state.selectedIds,
                    selectionMode = state.selectionMode,
                    onLongPress = { if (!file.isProtected) vm.enterSelectionMode(file.id) },
                    onTap = {
                        if (state.selectionMode && !file.isProtected) vm.toggleSelected(file.id)
                        else vm.showPreview(file.uri)
                    },
                    onProtect = { vm.protect(file.id) },
                    onUnprotect = { vm.requestUnprotect(file.id) },
                )
            }
        }

        item { BackfillButton(state, vm) }
    }
}

@Composable
private fun GridContent(
    padding: PaddingValues,
    state: ConsolidateUiState,
    vm: ConsolidateViewModel,
) {
    LazyColumn(
        modifier = Modifier
            .padding(padding)
            .padding(horizontal = 16.dp)
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
    ) {
        item { ConsolidateProgressCard(state) }

        if (state.backupRunning || state.backupPackState != BackupWorker.STATE_DONE) {
            item { BackupProgressCard(state) }
        }

        if (state.backupHasError) {
            item { BackupErrorCard(state) }
        }

        item { StatusCard(state) }
        item { ActionButtons(state, vm) }

        if (state.sortedSafeToDelete.isNotEmpty()) {
            item { HorizontalDivider() }
            item { FileListHeader(state, vm) }

            item {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 10000.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(state.sortedSafeToDelete, key = { it.id }) { file ->
                        SafeToDeleteGridCell(
                            file = file,
                            isProtected = file.isProtected,
                            isSelected = file.id in state.selectedIds,
                            selectionMode = state.selectionMode,
                            onLongPress = { if (!file.isProtected) vm.enterSelectionMode(file.id) },
                            onTap = {
                                if (state.selectionMode && !file.isProtected) vm.toggleSelected(file.id)
                                else vm.showPreview(file.uri)
                            },
                            onProtect = { vm.protect(file.id) },
                            onUnprotect = { vm.requestUnprotect(file.id) },
                        )
                    }
                }
            }
        }

        item { BackfillButton(state, vm) }
    }
}

@Composable
private fun ConsolidateProgressCard(state: ConsolidateUiState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Consolidation", style = MaterialTheme.typography.titleSmall)
                if (state.consolidateRunning && state.consolidateTotal > 0) {
                    Text("${state.consolidateProcessed * 100 / state.consolidateTotal}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (state.consolidateRunning && state.consolidateTotal > 0) {
                LinearProgressIndicator(progress = { state.consolidateProcessed.toFloat() / state.consolidateTotal }, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("File ${state.consolidateProcessed} / ${state.consolidateTotal}", style = MaterialTheme.typography.bodySmall)
                    if (state.consolidateFailed > 0) { Text("${state.consolidateFailed} failed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                }
                if (state.consolidateCurrentFile.isNotBlank()) {
                    Text(state.consolidateCurrentFile.substringAfterLast('/'), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else if (state.consolidateRunning) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("Scanning…", style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Text("Ready", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun BackupProgressCard(state: ConsolidateUiState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(backupStateLabel(state.backupPackState), style = MaterialTheme.typography.titleSmall)
                if (state.backupPackPosition > 0) { Text("Pack ${state.backupPackPosition}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (state.backupFilesTotal > 0) {
                LinearProgressIndicator(progress = { state.backupFileIndex.toFloat() / state.backupFilesTotal }, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("File ${state.backupFileIndex} / ${state.backupFilesTotal}", style = MaterialTheme.typography.bodySmall)
                    if (state.backupPartNumber > 0) { Text("${state.backupPartNumber} parts - ${state.backupPackPercent}%", style = MaterialTheme.typography.bodySmall) }
                }
                if (state.backupCurrentFile.isNotEmpty()) {
                    Text(state.backupCurrentFile.substringAfterLast('/'), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun BackupErrorCard(state: ConsolidateUiState) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(state.backupErrorType, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.titleSmall)
            if (state.backupErrorDetail.isNotEmpty()) { Text(state.backupErrorDetail, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun StatusCard(state: ConsolidateUiState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Status", style = MaterialTheme.typography.titleSmall)
            Text("${state.consolidatedCount} files consolidated")
            Text("${state.confirmedCount} files confirmed in B2")
        }
    }
}

@Composable
private fun ActionButtons(state: ConsolidateUiState, vm: ConsolidateViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = vm::startConsolidate, enabled = !state.consolidateRunning, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Compress, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Consolidate")
            }
            FilledTonalButton(onClick = vm::startBackup, enabled = !state.backupRunning, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.CloudUpload, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Back up")
            }
        }
        Button(onClick = vm::startConsolidateAndBackup, enabled = !state.consolidateRunning && !state.backupRunning, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Consolidate + Back up")
        }
    }
}

@Composable
private fun FileListHeader(state: ConsolidateUiState, vm: ConsolidateViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("${state.safeToDelete.size} safe to delete", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = { vm.setViewMode(ViewMode.LIST) }) {
                    Icon(Icons.AutoMirrored.Filled.ViewList, "List view", tint = if (state.viewMode == ViewMode.LIST) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { vm.setViewMode(ViewMode.GRID) }) {
                    Icon(Icons.Default.GridView, "Grid view", tint = if (state.viewMode == ViewMode.GRID) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SortChip("Name", SortField.NAME, state, vm)
            SortChip("Date", SortField.MODIFIED, state, vm)
            SortChip("Size", SortField.SIZE, state, vm)
            SortChip("Type", SortField.TYPE, state, vm)
        }
    }
}

@Composable
private fun SortChip(label: String, field: SortField, state: ConsolidateUiState, vm: ConsolidateViewModel) {
    val isActive = state.sortField == field
    FilterChip(selected = isActive, onClick = { vm.setSortField(field) }, label = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label)
            if (isActive) { Icon(if (state.sortDirection == SortDirection.ASC) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward, null, modifier = Modifier.size(14.dp)) }
        }
    })
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SafeToDeleteListRow(file: SafeToDeleteView, isProtected: Boolean, isSelected: Boolean, selectionMode: Boolean, onLongPress: () -> Unit, onTap: () -> Unit, onProtect: () -> Unit, onUnprotect: () -> Unit) {
    Row(Modifier.fillMaxWidth().then(if (isSelected) Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)) else Modifier).combinedClickable(onClick = onTap, onLongClick = { if (!selectionMode) onLongPress() }).padding(vertical = 6.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp)) {
            AsyncImage(model = android.net.Uri.parse(file.uri), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (selectionMode && isSelected) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)), contentAlignment = Alignment.Center) { Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(20.dp)) }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(file.path.substringAfterLast('/'), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${formatSize(file.size)} · ${formatDate(file.mtime)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = { if (isProtected) onUnprotect() else onProtect() }, modifier = Modifier.size(36.dp)) {
            Icon(if (isProtected) Icons.Default.Shield else Icons.Default.ShieldMoon, contentDescription = if (isProtected) "Unprotect" else "Protect", tint = if (isProtected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SafeToDeleteGridCell(file: SafeToDeleteView, isProtected: Boolean, isSelected: Boolean, selectionMode: Boolean, onLongPress: () -> Unit, onTap: () -> Unit, onProtect: () -> Unit, onUnprotect: () -> Unit) {
    Box(Modifier.aspectRatio(1f).combinedClickable(onClick = onTap, onLongClick = { if (!selectionMode) onLongPress() })) {
        AsyncImage(model = android.net.Uri.parse(file.uri), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        if (selectionMode && isSelected) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)), contentAlignment = Alignment.Center) { Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(28.dp)) }
        }
        IconButton(onClick = { if (isProtected) onUnprotect() else onProtect() }, modifier = Modifier.align(Alignment.TopEnd).size(32.dp)) {
            Icon(if (isProtected) Icons.Default.Shield else Icons.Default.ShieldMoon, contentDescription = if (isProtected) "Unprotect" else "Protect", tint = if (isProtected) MaterialTheme.colorScheme.primary else Color.White, modifier = Modifier.size(16.dp))
        }
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.5f)).padding(4.dp)) {
            Text(file.path.substringAfterLast('/'), style = MaterialTheme.typography.labelSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun BackfillButton(state: ConsolidateUiState, vm: ConsolidateViewModel) {
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = vm::backfillExisting, enabled = !state.backfillRunning, modifier = Modifier.fillMaxWidth()) {
        if (state.backfillRunning) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("Scanning…")
        } else {
            Icon(Icons.Default.SearchOff, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Scan for previously consolidated files")
        }
    }
}

private fun backupStateLabel(state: String) = when (state) {
    BackupWorker.STATE_PLANNING -> "Planning…"
    BackupWorker.STATE_RECOVERING -> "Recovering…"
    BackupWorker.STATE_UPLOADING -> "Uploading…"
    BackupWorker.STATE_COMPLETING -> "Completing…"
    BackupWorker.STATE_CONFIRMING -> "Confirming…"
    BackupWorker.STATE_DONE -> "Backup complete"
    else -> state
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}

private fun formatDate(millis: Long): String = SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(millis))
