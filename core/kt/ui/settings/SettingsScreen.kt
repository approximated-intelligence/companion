package de.perigon.companion.core.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import de.perigon.companion.core.feature.QrScan
import de.perigon.companion.core.ui.AccessibilityGate
import de.perigon.companion.core.ui.AppTopBar
import de.perigon.companion.core.ui.EmbeddedQrScanner
import de.perigon.companion.core.ui.SecureWindow
import de.perigon.companion.core.ui.SectionHeader
import de.perigon.companion.core.ui.access.AccessPanel
import de.perigon.companion.core.ui.access.AccessViewModel

private class ActiveScan(val prompt: String, val onResult: (String) -> Unit)

/**
 * Access (all permissions and folders), bootstrap scan, then every feature's
 * section in order, then the clear actions. Any scan that turns out to be a
 * bootstrap payload is staged, whichever button started it.
 */
@Composable
fun SettingsScreen(
    navController: NavController,
    vm: SettingsViewModel = hiltViewModel(),
    access: AccessViewModel = hiltViewModel(),
) {
    val pending by vm.pending.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }
    var activeScan by remember { mutableStateOf<ActiveScan?>(null) }
    var confirmClearSecrets by remember { mutableStateOf(false) }
    var confirmClearAll by remember { mutableStateOf(false) }

    val scan = remember { QrScan { prompt, onResult -> activeScan = ActiveScan(prompt, onResult) } }

    LaunchedEffect(Unit) {
        vm.snackbar.events.collect { snackbarHost.showSnackbar(it) }
    }

    SecureWindow()

    activeScan?.let { s ->
        BackHandler { activeScan = null }
        AccessibilityGate {
            EmbeddedQrScanner(
                prompt = s.prompt,
                onResult = { raw ->
                    activeScan = null
                    if (isBootstrapPayload(raw)) vm.onBootstrapScanned(raw) else s.onResult(raw)
                },
                onCancel = { activeScan = null },
            )
        }
        return
    }

    Scaffold(
        topBar = { AppTopBar(navController) },
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionHeader("Access", "Permissions and folders the app works with.")
            AccessPanel(requirements = access.all, vm = access)

            HorizontalDivider()

            SectionHeader(
                "Configuration",
                "Scan the bootstrap QR from setup_report.pdf to configure everything at once.",
            )
            FilledTonalButton(
                onClick = { scan.request("Scan bootstrap QR from setup_report.pdf", vm::onBootstrapScanned) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.QrCodeScanner, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Scan bootstrap QR (all-in-one)")
            }

            for (section in vm.sections) {
                HorizontalDivider()
                section.Content(scan)
            }

            HorizontalDivider()

            OutlinedButton(
                onClick = { confirmClearSecrets = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("Clear secrets only") }

            OutlinedButton(
                onClick = { confirmClearAll = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("Clear all settings") }
        }
    }

    if (confirmClearSecrets) {
        AlertDialog(
            onDismissRequest = { confirmClearSecrets = false },
            title = { Text("Clear secrets?") },
            text = { Text("Removes all device-bound keys, tokens and passwords. Other configuration is kept. Rescan QR codes to restore access.") },
            confirmButton = { TextButton(onClick = { vm.clear(secretsOnly = true); confirmClearSecrets = false }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClearSecrets = false }) { Text("Cancel") } },
        )
    }

    if (confirmClearAll) {
        AlertDialog(
            onDismissRequest = { confirmClearAll = false },
            title = { Text("Clear all settings?") },
            text = { Text("Removes all secrets, configuration and preferences. Folder and permission grants are kept.") },
            confirmButton = { TextButton(onClick = { vm.clear(secretsOnly = false); confirmClearAll = false }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClearAll = false }) { Text("Cancel") } },
        )
    }

    // Verifying the summaries against setup_report.pdf is the only thing
    // preventing a malicious QR from redirecting future backups.
    pending?.let { staged ->
        AlertDialog(
            onDismissRequest = vm::dismissBootstrap,
            title = { Text("Apply bootstrap configuration?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Verify against setup_report.pdf before accepting:")
                    staged.flatMap { it.summary }.forEach { Text(it) }
                }
            },
            confirmButton = { TextButton(onClick = vm::confirmBootstrap) { Text("Apply") } },
            dismissButton = { TextButton(onClick = vm::dismissBootstrap) { Text("Cancel") } },
        )
    }
}
