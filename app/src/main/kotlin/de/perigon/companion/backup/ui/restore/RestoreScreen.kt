package de.perigon.companion.backup.ui.restore

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import de.perigon.companion.backup.worker.RestoreNotifier
import de.perigon.companion.core.ui.AccessibilityGate
import de.perigon.companion.core.ui.SecureWindow
import de.perigon.companion.util.saf.DcimGate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestoreScreen(
    navController: NavController,
    vm: RestoreViewModel = hiltViewModel(),
) {
    SecureWindow()
    AccessibilityGate {
        RestoreScreenContent(navController = navController, vm = vm)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RestoreScreenContent(
    navController: NavController,
    vm: RestoreViewModel,
) {
    val state        by vm.state.collectAsStateWithLifecycle()
    val snackbarHost  = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        vm.snackbar.events.collect { snackbarHost.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Restore") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.Close, "Close")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            DcimGate(
                dcimUri = state.dcimUri,
                onGranted = { uri -> vm.onDcimGranted(uri) },
                writeAccess = true,
            ) {
                RestoreMainContent(state = state, vm = vm)
            }
        }
    }
}

@Composable
private fun RestoreMainContent(
    state: RestoreUiState,
    vm: RestoreViewModel,
) {
    Column(
        Modifier.padding(16.dp).fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {

        if (state.isRunning) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(restoreStateLabel(state.restoreState), style = MaterialTheme.typography.titleSmall)
                        if (state.packsTotal > 0)
                            Text("Pack ${state.packPosition + 1} / ${state.packsTotal}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (state.packsTotal > 0) {
                        LinearProgressIndicator(
                            progress = { (state.packPosition + 1).toFloat() / state.packsTotal },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    if (state.filesTotal > 0) {
                        Text("File ${state.fileIndex} / ${state.filesTotal}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (state.currentFile.isNotEmpty()) {
                        Text(state.currentFile.substringAfterLast('/'),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(onClick = vm::cancelRestore, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Stop, null); Spacer(Modifier.width(8.dp)); Text("Cancel")
                    }
                }
            }
        }

        if (!state.isRunning) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = vm::rebuildIndex, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Storage, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Rebuild index")
                }
                FilledTonalButton(onClick = vm::rebuildAndRestoreAll, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Restore, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Restore all")
                }
            }
            if (!state.hasFiles) {
                Button(onClick = vm::loadFileList, modifier = Modifier.fillMaxWidth()) {
                    Text("Load file list from index")
                }
            }
        }

        if (state.hasFiles) {
            val selectedCount = state.tree.selectedCount()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("$selectedCount selected", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = vm::selectAll)   { Text("All") }
                    TextButton(onClick = vm::deselectAll) { Text("None") }
                }
            }

            LazyColumn(modifier = Modifier.weight(1f)) {
                state.tree.children.forEach { node ->
                    renderTreeNode(
                        node = node, pathPrefix = "", depth = 0,
                        onToggleFolder = vm::toggleFolder,
                        onToggleFolderExpanded = vm::toggleFolderExpanded,
                        onToggleVersion = vm::toggleVersion,
                        onToggleVersionsExpanded = vm::toggleVersionsExpanded,
                    )
                }
            }

            Button(
                onClick = vm::restoreSelected,
                modifier = Modifier.fillMaxWidth(),
                enabled = selectedCount > 0 && !state.isRunning,
            ) {
                Icon(Icons.Default.Restore, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Restore $selectedCount file(s)")
            }
        }

        if (state.phase == RestorePhase.DONE && state.errors.isEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Complete", style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("Files restored to their original locations.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }

        if (state.errors.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${state.errors.size} error(s)", style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer)
                    state.errors.take(5).forEach { err ->
                        Text(err, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Tree rendering (unchanged)
// ---------------------------------------------------------------------------

private fun LazyListScope.renderTreeNode(
    node: TreeNode, pathPrefix: String, depth: Int,
    onToggleFolder: (String) -> Unit,
    onToggleFolderExpanded: (String) -> Unit,
    onToggleVersion: (path: String, sha256: String) -> Unit,
    onToggleVersionsExpanded: (String) -> Unit,
) {
    val fullPath = if (pathPrefix.isEmpty()) node.name else "$pathPrefix/${node.name}"
    when (node) {
        is TreeNode.Folder -> {
            item(key = "folder:$fullPath") {
                FolderRow(folder = node, depth = depth,
                    onToggleSelection = { onToggleFolder(fullPath) },
                    onToggleExpanded = { onToggleFolderExpanded(fullPath) })
            }
            if (node.expanded) {
                node.children.forEach { child ->
                    renderTreeNode(child, fullPath, depth + 1,
                        onToggleFolder, onToggleFolderExpanded, onToggleVersion, onToggleVersionsExpanded)
                }
            }
        }
        is TreeNode.File -> {
            val filePath = node.versions.firstOrNull()?.path ?: fullPath
            if (node.versions.size == 1) {
                item(key = "file:$filePath") {
                    FileRow(version = node.versions[0], depth = depth,
                        onToggle = { onToggleVersion(node.versions[0].path, node.versions[0].sha256) })
                }
            } else {
                item(key = "file-header:$filePath") {
                    MultiVersionFileRow(node = node, depth = depth,
                        onToggleExpanded = { onToggleVersionsExpanded(filePath) },
                        onToggleSelection = {
                            val selectAll = node.selectionState != SelectionState.ALL
                            node.versions.forEach { v -> if (v.selected != selectAll) onToggleVersion(v.path, v.sha256) }
                        })
                }
                if (node.versionsExpanded) {
                    items(node.versions, key = { "version:${it.path}:${it.sha256}" }) { version ->
                        VersionRow(version = version, depth = depth + 1,
                            onToggle = { onToggleVersion(version.path, version.sha256) })
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderRow(folder: TreeNode.Folder, depth: Int, onToggleSelection: () -> Unit, onToggleExpanded: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggleExpanded)
            .padding(start = (depth * 16).dp, top = 4.dp, bottom = 4.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TriStateCheckbox(state = folder.selectionState.toToggleableState(), onClick = onToggleSelection)
        Spacer(Modifier.width(4.dp))
        Icon(if (folder.expanded) Icons.Default.FolderOpen else Icons.Default.Folder, null,
            Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(6.dp))
        Text(folder.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Icon(if (folder.expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight, null,
            Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FileRow(version: RestoreFileVersion, depth: Int, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle)
            .padding(start = (depth * 16).dp, top = 2.dp, bottom = 2.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = version.selected, onCheckedChange = { onToggle() })
        Spacer(Modifier.width(4.dp))
        Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null, Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(version.path.substringAfterLast('/'), style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${formatSize(version.size)} — ${formatDate(version.mtime)} — Pack ${version.startPack}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MultiVersionFileRow(node: TreeNode.File, depth: Int, onToggleExpanded: () -> Unit, onToggleSelection: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggleExpanded)
            .padding(start = (depth * 16).dp, top = 2.dp, bottom = 2.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TriStateCheckbox(state = node.selectionState.toToggleableState(), onClick = onToggleSelection)
        Spacer(Modifier.width(4.dp))
        Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null, Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(node.name, style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${node.versions.size} versions", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(if (node.versionsExpanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
            null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun VersionRow(version: RestoreFileVersion, depth: Int, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle)
            .padding(start = (depth * 16).dp, top = 2.dp, bottom = 2.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = version.selected, onCheckedChange = { onToggle() })
        Spacer(Modifier.width(4.dp))
        Icon(Icons.Default.History, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(formatDate(version.mtime), style = MaterialTheme.typography.bodyMedium)
            Text("${formatSize(version.size)} — Pack ${version.startPack}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun SelectionState.toToggleableState() = when (this) {
    SelectionState.ALL  -> ToggleableState.On
    SelectionState.NONE -> ToggleableState.Off
    SelectionState.SOME -> ToggleableState.Indeterminate
}

private fun restoreStateLabel(state: String) = when (state) {
    RestoreNotifier.STATE_COUNTING   -> "Counting packs…"
    RestoreNotifier.STATE_REBUILDING -> "Rebuilding index…"
    RestoreNotifier.STATE_RESTORING  -> "Restoring files…"
    RestoreNotifier.STATE_DONE       -> "Complete"
    else                             -> state
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1_024         -> "$bytes B"
    bytes < 1_024 * 1_024 -> "${bytes / 1_024} KB"
    else                   -> "%.1f MB".format(bytes / (1_024.0 * 1_024.0))
}

private fun formatDate(ms: Long): String =
    SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()).format(Date(ms))
