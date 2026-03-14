package de.perigon.companion.backup.ui.restore

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BuildCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import de.perigon.companion.core.ui.AccessibilityGate
import de.perigon.companion.core.ui.EmbeddedQrScanner
import de.perigon.companion.core.ui.SecureWindow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items

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
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }
    var scanning by remember { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                scanning = false
                vm.onPause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        vm.snackbar.events.collect { snackbarHost.showSnackbar(it) }
    }

    if (scanning) {
        EmbeddedQrScanner(
            prompt = "Scan restore QR from restore_key tool",
            onResult = { raw ->
                scanning = false
                vm.onSecretQrScanned(raw)
            },
            onCancel = { scanning = false },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Restore") },
                navigationIcon = {
                    IconButton(onClick = {
                        vm.clearCredentials()
                        navController.popBackStack()
                    }) {
                        Icon(Icons.Default.Close, "Close & clear credentials")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Credentials are ephemeral",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer)
                    Text("Secret key and B2 restore credentials are held in memory only. " +
                         "They are cleared when you leave this screen or the app is backgrounded.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }

            CredentialStatus(
                label = "Secret key & B2 restore credentials",
                loaded = state.hasSecretQr,
                onScan = { scanning = true },
            )

            if (state.phase == RestorePhase.NEED_CREDENTIALS) {
                Text("Run restore_key.py on your laptop and scan the QR code.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (state.needsIndexRebuild && state.phase == RestorePhase.READY) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Index rebuild needed",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer)
                        Text("Some files are missing part-level index data. " +
                             "Rebuild downloads and scans pack headers from B2.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer)
                        Button(onClick = vm::rebuildIndex) {
                            Icon(Icons.Default.BuildCircle, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Rebuild index")
                        }
                    }
                }
            }

            if (state.phase == RestorePhase.REBUILDING_INDEX) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Rebuilding index…", style = MaterialTheme.typography.titleSmall)
                        if (state.rebuildTotal > 0) {
                            LinearProgressIndicator(
                                progress = { state.rebuildProgress.toFloat() / state.rebuildTotal },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text("Pack ${state.rebuildProgress} / ${state.rebuildTotal}",
                                style = MaterialTheme.typography.bodySmall)
                        } else {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            }

            if (state.phase == RestorePhase.READY && state.files.isEmpty()) {
                Button(
                    onClick = vm::loadFileList,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Load file list from backup index")
                }
            }

            if (state.phase == RestorePhase.LISTING) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("Loading file list…")
                }
            }

            if (state.files.isNotEmpty()) {
                val selectedCount = state.files.count { it.selected }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${state.files.size} files - $selectedCount selected",
                        style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = vm::selectAll) { Text("All") }
                        TextButton(onClick = vm::deselectAll) { Text("None") }
                    }
                }

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(
                        state.files,
                        key = { it.path },
                    ) { file ->
                        RestoreFileRow(
                            file = file,
                            onToggle = { vm.toggleFile(file.path) },
                        )
                    }
                }

                if (state.phase == RestorePhase.RESTORING) {
                    LinearProgressIndicator(
                        progress = { state.restoreProgress.toFloat() / state.restoreTotal.coerceAtLeast(1) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("Restoring ${state.currentFile.substringAfterLast('/')}…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }

                Button(
                    onClick = vm::restoreSelected,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = selectedCount > 0 && state.phase == RestorePhase.READY,
                ) {
                    Icon(Icons.Default.Restore, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Restore $selectedCount file(s)")
                }
            }

            if (state.phase == RestorePhase.DONE) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Restore complete",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text("Files restored to DCIM/Restored/",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }

            if (state.errors.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${state.errors.size} error(s)",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer)
                        state.errors.take(5).forEach { err ->
                            Text(err,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CredentialStatus(
    label: String,
    loaded: Boolean,
    onScan: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (loaded) Icons.Default.Check else Icons.Default.Warning,
            null,
            tint = if (loaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                if (loaded) "Loaded - in memory only" else "Not loaded",
                style = MaterialTheme.typography.bodySmall,
                color = if (loaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.width(8.dp))
        FilledTonalButton(onClick = onScan) {
            Icon(Icons.Default.QrCodeScanner, null, Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(if (loaded) "Rescan" else "Scan")
        }
    }
}

@Composable
private fun RestoreFileRow(
    file: RestoreFileEntry,
    onToggle: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = file.selected, onCheckedChange = { onToggle() })
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                file.path.substringAfterLast('/'),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val sizeStr = formatSize(file.size)
            val dateStr = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
                .format(Date(file.mtime))
            Text("$sizeStr - $dateStr",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("Pack ${file.startPack}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline)
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}
