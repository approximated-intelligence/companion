package de.perigon.companion.core.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import de.perigon.companion.core.prefs.AppPrefs
import de.perigon.companion.core.ui.AccessibilityGate
import de.perigon.companion.core.ui.AppTopBar
import de.perigon.companion.core.ui.EmbeddedQrScanner
import de.perigon.companion.core.ui.SecureWindow
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private enum class ScanType { NACL, B2, GITHUB, BOOTSTRAP }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    vm:            SettingsViewModel = hiltViewModel(),
) {
    val state        by vm.state.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        vm.snackbar.events.collect { snackbarHost.showSnackbar(it) }
    }

    var activeScan by remember { mutableStateOf<ScanType?>(null) }

    val tilePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val display = uri.lastPathSegment?.substringAfterLast('/') ?: uri.toString()
        vm.setTileSource(uri, display)
    }

    val postMediaPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val display = uri.lastPathSegment?.substringAfterLast(':') ?: uri.toString()
        vm.setPostMediaFolder(uri, display)
    }

    fun handleScanResult(type: ScanType, raw: String) {
        if (vm.onAnyQrScanned(raw)) return
        when (type) {
            ScanType.NACL      -> vm.onNaclQrScanned(raw)
            ScanType.B2        -> vm.onB2QrScanned(raw)
            ScanType.GITHUB    -> vm.onGithubQrScanned(raw)
            ScanType.BOOTSTRAP -> vm.onBootstrapQrScanned(raw)
        }
    }

    SecureWindow()

    activeScan?.let { scanType ->
        AccessibilityGate {
            val prompt = when (scanType) {
                ScanType.NACL      -> "Scan NaCl public key from setup_report.pdf"
                ScanType.B2        -> "Scan B2 credentials from setup_report.pdf"
                ScanType.GITHUB    -> "Scan GitHub config from setup_report.pdf"
                ScanType.BOOTSTRAP -> "Scan bootstrap QR from setup_report.pdf"
            }
            EmbeddedQrScanner(
                prompt = prompt,
                onResult = { raw ->
                    handleScanResult(scanType, raw)
                    activeScan = null
                },
                onCancel = { activeScan = null },
            )
        }
        return
    }

    var showDatePicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar       = { AppTopBar(navController) },
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
            Text("Journey", style = MaterialTheme.typography.titleMedium)
            Text("Configure your current journey. Day numbers and post titles are computed from these.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            JourneyDateRow(label = "Start date", value = state.journeyStartDate, onPick = { showDatePicker = true })

            OutlinedTextField(
                value = state.journeyTitle,
                onValueChange = vm::setJourneyTitle,
                label = { Text("Journey title") },
                placeholder = { Text("e.g. Silk Road") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = state.journeyTag,
                onValueChange = vm::setJourneyTag,
                label = { Text("Journey tag") },
                placeholder = { Text("e.g. silkroad") },
                singleLine = true,
                prefix = { Text("#") },
                modifier = Modifier.fillMaxWidth(),
            )

            HorizontalDivider()

            Text("Editor", style = MaterialTheme.typography.titleMedium)

            Text("Autosave delay", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppPrefs.AUTOSAVE_OPTIONS_MS.forEach { ms ->
                    FilterChip(
                        selected = state.autosaveDebounceMs == ms,
                        onClick = { vm.setAutosaveDebounceMs(ms) },
                        label = { Text(formatAutosaveLabel(ms)) },
                    )
                }
            }
            Text("Post drafts are saved automatically after ${formatAutosaveLabel(state.autosaveDebounceMs)} of inactivity. Takes effect on next post edit.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            Text("Max save interval", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppPrefs.AUTOSAVE_MAX_INTERVAL_OPTIONS_MS.forEach { ms ->
                    FilterChip(
                        selected = state.autosaveMaxIntervalMs == ms,
                        onClick = { vm.setAutosaveMaxIntervalMs(ms) },
                        label = { Text(formatAutosaveMaxLabel(ms)) },
                    )
                }
            }
            Text("Forces a save even during continuous typing. Prevents data loss.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            HorizontalDivider()

            Text("PostMedia storage", style = MaterialTheme.typography.titleMedium)
            Text("Compressed media for posts. Default is app-private storage (hidden from gallery). Select a custom folder to make files accessible to other apps.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            PostMediaFolderRow(
                configured = state.postMediaConfigured,
                label = state.postMediaLabel,
                onSelect = { postMediaPicker.launch(null) },
                onClear = vm::clearPostMediaFolder,
            )

            HorizontalDivider()

            Text("Configuration", style = MaterialTheme.typography.titleMedium)
            Text("These values are included in backup and restored automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            FilledTonalButton(
                onClick = { activeScan = ScanType.BOOTSTRAP },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.QrCodeScanner, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Scan bootstrap QR (all-in-one)")
            }

            HorizontalDivider()
            ConfigRow(label = "Backup encryption key",
                configured = state.naclConfigured,
                detail = state.naclFingerprint,
                onScan = { activeScan = ScanType.NACL })
            ConfigRow(label = "B2 endpoint & bucket",
                configured = state.b2Configured,
                onScan = { activeScan = ScanType.B2 })
            ConfigRow(label = "GitHub repository",
                configured = state.githubConfigured,
                detail = state.githubLabel,
                onScan = { activeScan = ScanType.GITHUB })

            OutlinedTextField(
                value = state.siteUrl,
                onValueChange = vm::setSiteUrl,
                label = { Text("Site URL") },
                placeholder = { Text("https://yourblog.example.com") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                supportingText = {
                    Text("Auto-detected from CNAME on GitHub scan. Used for share links.")
                },
            )

            HorizontalDivider()

            Text("Map tiles", style = MaterialTheme.typography.titleMedium)
            Text("Select an OsmAnd SQLiteDB tile source for track rendering backgrounds.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TileSourceRow(
                configured = state.tileSourceUri != null,
                label = state.tileSourceLabel,
                onSelect = { tilePicker.launch(arrayOf("application/octet-stream", "application/x-sqlite3", "*/*")) },
                onClear = vm::clearTileSource,
            )

            HorizontalDivider()

            Text("Background GPS", style = MaterialTheme.typography.titleMedium)
            Text(
                "Enable to allow background GPS. When disabled, track rendering and sharing still work.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Icon(
                    imageVector = if (state.backgroundGps) Icons.Default.LocationOn else Icons.Default.LocationOff,
                    contentDescription = null,
                    tint = if (state.backgroundGps) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("GPS", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (state.backgroundGps) "Enabled - background location available"
                        else "Disabled - no GPS access, no background location",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.backgroundGps) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.backgroundGps,
                    onCheckedChange = vm::setBackgroundGpsEnabled,
                )
            }

            HorizontalDivider()

            Text("Secrets", style = MaterialTheme.typography.titleMedium)
            Text("Device-bound - cannot be restored from backup. Rescan QR codes after restoring to a new device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
            HorizontalDivider()
            CredentialRow(label = "B2 app key",
                configured = state.b2Configured,
                onScan = { activeScan = ScanType.B2 })
            CredentialRow(label = "GitHub token",
                configured = state.githubConfigured,
                detail = state.githubLabel,
                onScan = { activeScan = ScanType.GITHUB })

            HorizontalDivider()

            var confirmClearSecrets by remember { mutableStateOf(false) }
            var confirmClearAll by remember { mutableStateOf(false) }

            OutlinedButton(
                onClick = { confirmClearSecrets = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("Clear secrets only") }

            OutlinedButton(
                onClick = { confirmClearAll = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("Clear all settings") }

            if (confirmClearSecrets) {
                AlertDialog(
                    onDismissRequest = { confirmClearSecrets = false },
                    title = { Text("Clear secrets?") },
                    text = { Text("Removes the B2 app key and GitHub token. All other config is kept. Rescan QR codes to restore access.") },
                    confirmButton = {
                        TextButton(onClick = { vm.clearSecrets(); confirmClearSecrets = false }) { Text("Clear") }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmClearSecrets = false }) { Text("Cancel") }
                    },
                )
            }

            if (confirmClearAll) {
                AlertDialog(
                    onDismissRequest = { confirmClearAll = false },
                    title = { Text("Clear all settings?") },
                    text = { Text("Removes all secrets, config, and preferences. Rescan all QR codes and reconfigure journey settings to restore.") },
                    confirmButton = {
                        TextButton(onClick = { vm.clearAll(); confirmClearAll = false }) { Text("Clear") }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmClearAll = false }) { Text("Cancel") }
                    },
                )
            }
        }
    }

    if (showDatePicker) {
        val initial = runCatching {
            LocalDate.parse(state.journeyStartDate).toEpochDay() * 86_400_000L
        }.getOrDefault(System.currentTimeMillis())

        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initial)

        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { ms ->
                        val date = java.time.Instant.ofEpochMilli(ms)
                            .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                        vm.setJourneyStartDate(date.toString())
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } },
        ) { DatePicker(state = datePickerState) }
    }
}

private fun formatAutosaveLabel(ms: Long): String = when {
    ms < 1000L -> "${ms}ms"
    ms % 1000L == 0L -> "${ms / 1000}s"
    else -> "%.1fs".format(ms / 1000.0)
}

private fun formatAutosaveMaxLabel(ms: Long): String = when {
    ms < 60_000L -> "${ms / 1000}s"
    else -> "${ms / 60_000}min"
}

@Composable
private fun JourneyDateRow(label: String, value: String, onPick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                if (value.isNotBlank())
                    runCatching {
                        LocalDate.parse(value).format(DateTimeFormatter.ofPattern("MMMM d, yyyy"))
                    }.getOrDefault(value)
                else "Not set",
                style = MaterialTheme.typography.bodySmall,
                color = if (value.isNotBlank()) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FilledTonalButton(onClick = onPick) {
            Icon(Icons.Default.CalendarMonth, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(if (value.isNotBlank()) "Change" else "Set date")
        }
    }
}

@Composable
private fun CredentialRow(
    label: String, configured: Boolean, detail: String = "", onScan: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (configured) Icons.Default.Check else Icons.Default.Warning,
            contentDescription = null,
            tint = if (configured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            val sub = when {
                configured && detail.isNotBlank() -> "$detail - not in backup"
                configured -> "Configured - not in backup"
                else -> "Not configured - rescan QR after restore"
            }
            Text(sub, style = MaterialTheme.typography.bodySmall,
                color = if (configured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.width(8.dp))
        FilledTonalButton(onClick = onScan) {
            Icon(Icons.Default.QrCodeScanner, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(if (configured) "Rescan" else "Scan QR")
        }
    }
}

@Composable
private fun ConfigRow(
    label: String, configured: Boolean, detail: String = "", onScan: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (configured) Icons.Default.Check else Icons.Default.Warning,
            contentDescription = null,
            tint = if (configured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            val sub = when {
                configured && detail.isNotBlank() -> "$detail - included in backup"
                configured -> "Configured - included in backup"
                else -> "Not configured"
            }
            Text(sub, style = MaterialTheme.typography.bodySmall,
                color = if (configured) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        FilledTonalButton(onClick = onScan) {
            Icon(Icons.Default.QrCodeScanner, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(if (configured) "Rescan" else "Scan QR")
        }
    }
}

@Composable
private fun PostMediaFolderRow(
    configured: Boolean, label: String, onSelect: () -> Unit, onClear: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Default.FolderOpen,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("PostMedia folder", style = MaterialTheme.typography.bodyMedium)
            Text(label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(8.dp))
        if (configured) {
            FilledTonalButton(onClick = onClear) { Text("Reset") }
            Spacer(Modifier.width(4.dp))
        }
        FilledTonalButton(onClick = onSelect) {
            Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(if (configured) "Change" else "Select")
        }
    }
}

@Composable
private fun TileSourceRow(
    configured: Boolean, label: String, onSelect: () -> Unit, onClear: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (configured) Icons.Default.Check else Icons.Default.Warning,
            contentDescription = null,
            tint = if (configured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Tile source", style = MaterialTheme.typography.bodyMedium)
            Text(if (configured) label else "Not configured",
                style = MaterialTheme.typography.bodySmall,
                color = if (configured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.width(8.dp))
        if (configured) {
            FilledTonalButton(onClick = onClear) { Text("Clear") }
            Spacer(Modifier.width(4.dp))
        }
        FilledTonalButton(onClick = onSelect) {
            Icon(Icons.Default.Map, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(if (configured) "Change" else "Select")
        }
    }
}
