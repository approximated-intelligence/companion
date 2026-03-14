package de.perigon.companion.media.ui.consolidate

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import de.perigon.companion.core.ui.AppTopBar
import de.perigon.companion.core.ui.NotificationObserver

@Composable
fun ConsolidateScreen(
    navController: NavController,
    vm:            ConsolidateViewModel = hiltViewModel(),
) {
    val state    by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        NotificationObserver.observe(this, vm.notifications, snackbar)
    }

    LaunchedEffect(Unit) {
        vm.snackbar.events.collect { snackbar.showSnackbar(it) }
    }

    Scaffold(
        topBar       = { AppTopBar(navController) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier            = Modifier.padding(padding).padding(horizontal = 16.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding      = PaddingValues(vertical = 16.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Compression progress", style = MaterialTheme.typography.titleSmall)
                        if (state.total > 0) {
                            LinearProgressIndicator(
                                progress = { state.processed.toFloat() / state.total },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text("${state.processed} / ${state.total} files" +
                                if (state.failed > 0) " (${state.failed} failed)" else "",
                                style = MaterialTheme.typography.bodySmall)
                            if (state.currentFile.isNotBlank()) {
                                Text(state.currentFile,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else {
                            Text(
                                if (state.isRunning) "Scanning…" else "No pending files",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item {
                if (state.isRunning) {
                    OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Consolidating…")
                    }
                } else {
                    Button(onClick = vm::consolidateAll, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Compress, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Consolidate all now")
                    }
                }
            }
        }
    }
}
