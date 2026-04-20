package de.perigon.companion.audio.ui.recording

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import de.perigon.companion.audio.data.AudioConfigEntity
import de.perigon.companion.audio.data.AudioFormat
import de.perigon.companion.audio.data.AudioPreset
import de.perigon.companion.audio.data.AudioRecordingEntity
import de.perigon.companion.audio.data.BITRATE_OPTIONS_BPS
import de.perigon.companion.audio.data.SAMPLE_RATE_OPTIONS_HZ
import de.perigon.companion.audio.data.SILENCE_GRACE_OPTIONS_MS
import de.perigon.companion.audio.data.formatBitrate
import de.perigon.companion.audio.data.formatSampleRate
import de.perigon.companion.audio.domain.SilenceGate
import de.perigon.companion.core.ui.AppTopBar

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AudioRecordingScreen(
    navController: NavController,
    vm: AudioRecordingViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        vm.snackbar.events.collect { snackbar.showSnackbar(it) }
    }

    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPermission = granted
        if (granted) vm.startRecording()
    }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val display = uri.lastPathSegment?.substringAfterLast(':') ?: uri.toString()
        vm.setFolder(uri, display)
    }

    fun requestStart() {
        if (hasMicPermission) vm.startRecording()
        else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    val selectionMode = state.selectedIds.isNotEmpty()
    var confirmDeleteSelected by remember { mutableStateOf(false) }

    if (confirmDeleteSelected) {
        AlertDialog(
            onDismissRequest = { confirmDeleteSelected = false },
            title = { Text("Delete ${state.selectedIds.size} recording(s)?") },
            text  = { Text("Files will be permanently deleted.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteSelected(); confirmDeleteSelected = false }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteSelected = false }) { Text("Cancel") } },
        )
    }

    Scaffold(
        topBar       = { AppTopBar(navController) },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar    = {
            if (selectionMode) {
                SelectionBottomBar(
                    count       = state.selectedIds.size,
                    allSelected = state.selectedIds == state.recordings.map { it.id }.toSet(),
                    onClear     = vm::clearSelection,
                    onToggleAll = vm::toggleSelectAll,
                    onDelete    = { confirmDeleteSelected = true },
                )
            }
        },
    ) { padding ->
        LazyColumn(
            modifier            = Modifier.padding(padding).padding(horizontal = 16.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding      = PaddingValues(vertical = 16.dp),
        ) {
            item {
                ControlsCard(
                    state             = state,
                    hasMicPermission  = hasMicPermission,
                    onStart           = ::requestStart,
                    onPause           = vm::pause,
                    onResume          = vm::resume,
                    onStop            = vm::stop,
                )
            }

            item { HorizontalDivider() }
            item { Text("Preset", style = MaterialTheme.typography.titleSmall) }
            item { PresetChips(state.config.preset, vm::setPreset) }

            item { Text("Format", style = MaterialTheme.typography.titleSmall) }
            item { FormatChips(state.config.format, vm::setFormat) }

            if (!state.config.format.isFixedFormat) {
                item { Text("Sample rate", style = MaterialTheme.typography.titleSmall) }
                item { SampleRateChips(state.config.sampleRateHz, vm::setSampleRate) }
                item { Text("Bitrate", style = MaterialTheme.typography.titleSmall) }
                item { BitrateChips(state.config.bitrateBps, vm::setBitrate) }
            }

            item { HorizontalDivider() }
            item { Text("Silence gate", style = MaterialTheme.typography.titleSmall) }
            item {
                ToggleRow(
                    title    = "Skip silence",
                    subtitle = if (state.config.format.supportsPause)
                        "Auto-pause when input stays below threshold."
                    else
                        "Not supported for ${state.config.format.displayName}.",
                    checked  = state.config.skipSilence && state.config.format.supportsPause,
                    enabled  = state.config.format.supportsPause,
                    onCheckedChange = vm::setSkipSilence,
                )
            }
            if (state.config.skipSilence && state.config.format.supportsPause) {
                item {
                    Text("Threshold: ${state.config.silenceThresholdDb} dB",
                        style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value         = state.config.silenceThresholdDb.toFloat(),
                        onValueChange = { vm.setSilenceThresholdDb(it.toInt()) },
                        valueRange    = -60f..-10f,
                        steps         = 49,
                    )
                    Text("Lower = less sensitive. Quiet rooms: -50 dB. Noisy: -30 dB.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item { Text("Grace period", style = MaterialTheme.typography.bodyMedium) }
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SILENCE_GRACE_OPTIONS_MS.forEach { ms ->
                            FilterChip(
                                selected = state.config.silenceGraceMs == ms,
                                onClick  = { vm.setSilenceGraceMs(ms) },
                                label    = { Text(if (ms < 1000) "${ms}ms" else "${ms / 1000}s") },
                            )
                        }
                    }
                }
            }

            item { HorizontalDivider() }
            item { Text("Audio processing", style = MaterialTheme.typography.titleSmall) }
            item {
                ToggleRow(
                    title    = "Noise suppression",
                    subtitle = if (android.media.audiofx.NoiseSuppressor.isAvailable())
                        "Reduce steady background noise."
                    else
                        "Not available on this device.",
                    checked  = state.config.noiseSuppression && android.media.audiofx.NoiseSuppressor.isAvailable(),
                    enabled  = android.media.audiofx.NoiseSuppressor.isAvailable(),
                    onCheckedChange = vm::setNoiseSuppression,
                )
            }
            item {
                ToggleRow(
                    title    = "Automatic gain control",
                    subtitle = if (android.media.audiofx.AutomaticGainControl.isAvailable())
                        "Normalise recording volume."
                    else
                        "Not available on this device.",
                    checked  = state.config.autoGain && android.media.audiofx.AutomaticGainControl.isAvailable(),
                    enabled  = android.media.audiofx.AutomaticGainControl.isAvailable(),
                    onCheckedChange = vm::setAutoGain,
                )
            }
            item {
                ToggleRow(
                    title    = "Show level",
                    subtitle = "Display amplitude meter while recording.",
                    checked  = state.config.showLevel,
                    onCheckedChange = vm::setShowLevel,
                )
            }

            item { HorizontalDivider() }
            item { Text("Storage folder", style = MaterialTheme.typography.titleSmall) }
            item {
                FolderRow(
                    label    = state.folderLabel,
                    onSelect = { folderPicker.launch(null) },
                    onClear  = vm::clearFolder,
                )
            }

            if (state.recordings.isNotEmpty()) {
                item { HorizontalDivider() }
                item { Text("Recordings", style = MaterialTheme.typography.titleSmall) }
                items(state.recordings, key = { it.id }) { rec ->
                    val isActive = state.isRecording && rec.id == state.currentRecordingId
                    RecordingRow(
                        rec              = rec,
                        isActive         = isActive,
                        isPaused         = isActive && state.isPaused,
                        isAutoPaused     = isActive && state.isAutoPaused,
                        elapsedMs        = if (isActive) state.elapsedMs else rec.durationMs ?: 0L,
                        amplitudeDb      = if (isActive && state.config.showLevel) state.amplitudeDb else null,
                        isSelected       = rec.id in state.selectedIds,
                        selectionMode    = selectionMode,
                        onToggleSelect   = { vm.toggleSelection(rec.id) },
                        onPlay           = { vm.play(rec.id) },
                        onShare          = { vm.share(rec.id) },
                        onDelete         = { vm.delete(rec.id) },
                        onRename         = { name -> vm.rename(rec.id, name) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ControlsCard(
    state:            AudioRecordingUiState,
    hasMicPermission: Boolean,
    onStart:          () -> Unit,
    onPause:          () -> Unit,
    onResume:         () -> Unit,
    onStop:           () -> Unit,
) {
    when {
        state.isRecording -> Card(
            colors   = CardDefaults.cardColors(
                containerColor = if (state.isPaused || state.isAutoPaused)
                    MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.primaryContainer,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        formatElapsed(state.elapsedMs),
                        style = MaterialTheme.typography.headlineSmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f),
                    )
                    val stateLabel = when {
                        state.isPaused     -> "Paused"
                        state.isAutoPaused -> "Silence"
                        else               -> "Recording"
                    }
                    Text(stateLabel, style = MaterialTheme.typography.labelMedium)
                }

                if (state.config.showLevel) {
                    Text(
                        "${SilenceGate.asciiMeter(state.amplitudeDb)}  ${state.amplitudeDb} dB",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.config.format.supportsPause) {
                        if (state.isPaused) {
                            FilledTonalButton(onClick = onResume) {
                                Icon(Icons.Default.PlayArrow, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Resume")
                            }
                        } else {
                            FilledTonalButton(onClick = onPause) {
                                Icon(Icons.Default.Pause, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Pause")
                            }
                        }
                    }
                    FilledTonalButton(onClick = onStop) {
                        Icon(Icons.Default.Stop, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Stop")
                    }
                }
            }
        }

        else -> {
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Mic, null)
                Spacer(Modifier.width(8.dp))
                Text("Start recording")
            }
            if (!hasMicPermission) {
                Text("Microphone permission required.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp))
            }
            if (state.folderUri == null) {
                Text("Set a storage folder below before recording.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresetChips(current: AudioPreset, onSelect: (AudioPreset) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AudioPreset.ALL.forEach { p ->
            FilterChip(selected = current == p, onClick = { onSelect(p) }, label = { Text(p.displayName) })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FormatChips(current: AudioFormat, onSelect: (AudioFormat) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AudioFormat.ALL.forEach { f ->
            FilterChip(selected = current == f, onClick = { onSelect(f) }, label = { Text(f.displayName) })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SampleRateChips(current: Int, onSelect: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SAMPLE_RATE_OPTIONS_HZ.forEach { hz ->
            FilterChip(selected = current == hz, onClick = { onSelect(hz) }, label = { Text(formatSampleRate(hz)) })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BitrateChips(current: Int, onSelect: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BITRATE_OPTIONS_BPS.forEach { bps ->
            FilterChip(selected = current == bps, onClick = { onSelect(bps) }, label = { Text(formatBitrate(bps)) })
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun FolderRow(label: String?, onSelect: () -> Unit, onClear: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(label ?: "Not set",
                style = MaterialTheme.typography.bodyMedium,
                color = if (label != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Recordings are written here via SAF.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (label != null) FilledTonalButton(onClick = onClear) { Text("Clear") }
        FilledTonalButton(onClick = onSelect) {
            Icon(Icons.Default.FolderOpen, null, Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(if (label != null) "Change" else "Select")
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecordingRow(
    rec: AudioRecordingEntity,
    isActive: Boolean,
    isPaused: Boolean,
    isAutoPaused: Boolean,
    elapsedMs: Long,
    amplitudeDb: Int?,
    isSelected: Boolean,
    selectionMode: Boolean,
    onToggleSelect: () -> Unit,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onRename: (String) -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var nameInput by remember(rec.name) { mutableStateOf(rec.name) }

    val containerColor = when {
        isSelected -> MaterialTheme.colorScheme.secondaryContainer
        isActive   -> MaterialTheme.colorScheme.primaryContainer
        else       -> MaterialTheme.colorScheme.surfaceVariant
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor),
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .combinedClickable(
                onClick     = { when {
                    selectionMode -> onToggleSelect()
                    !isActive     -> onPlay()
                } },
                onLongClick = onToggleSelect,
            ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (selectionMode) {
                    Checkbox(checked = isSelected, onCheckedChange = { onToggleSelect() })
                }
                Icon(
                    when {
                        isActive && isPaused     -> Icons.Default.Pause
                        isActive && isAutoPaused -> Icons.Default.VolumeOff
                        isActive                 -> Icons.Default.Mic
                        else                     -> Icons.Default.AudioFile
                    },
                    null,
                    modifier = Modifier.size(20.dp),
                    tint = when {
                        isActive -> MaterialTheme.colorScheme.onPrimaryContainer
                        else     -> MaterialTheme.colorScheme.primary
                    },
                )
                Column(Modifier.weight(1f)) {
                    if (editing) {
                        OutlinedTextField(
                            value         = nameInput,
                            onValueChange = { nameInput = it },
                            singleLine    = true,
                            modifier      = Modifier.fillMaxWidth(),
                            trailingIcon  = {
                                Row {
                                    IconButton(onClick = { onRename(nameInput); editing = false }) {
                                        Icon(Icons.Default.Check, "Save")
                                    }
                                    IconButton(onClick = { nameInput = rec.name; editing = false }) {
                                        Icon(Icons.Default.Close, "Cancel")
                                    }
                                }
                            },
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(rec.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            if (!selectionMode) {
                                IconButton(onClick = { editing = true }, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Default.Edit, "Rename", modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    val fmt = AudioFormat.ALL.firstOrNull { it.name == rec.format }?.displayName ?: rec.format
                    val sub = buildList {
                        add(fmt)
                        add(formatElapsed(elapsedMs))
                        rec.sizeBytes?.let { if (it > 0) add(formatBytes(it)) }
                    }.joinToString(" · ")
                    Text(sub,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!selectionMode && !editing && !isActive) {
                    IconButton(onClick = onShare, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Share, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = { confirmDelete = true }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp))
                    }
                }
            }

            if (isActive && amplitudeDb != null) {
                Text(
                    "${SilenceGate.asciiMeter(amplitudeDb)}  $amplitudeDb dB",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete recording?") },
            text  = { Text("\"${rec.name}\" will be permanently deleted.") },
            confirmButton = { TextButton(onClick = { onDelete(); confirmDelete = false }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SelectionBottomBar(
    count: Int,
    allSelected: Boolean,
    onClear: () -> Unit,
    onToggleAll: () -> Unit,
    onDelete: () -> Unit,
) {
    BottomAppBar {
        IconButton(onClick = onClear) { Icon(Icons.Default.Close, "Cancel selection") }
        Text("$count selected", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = onToggleAll) {
            Icon(
                if (allSelected) Icons.Default.Deselect else Icons.Default.SelectAll,
                if (allSelected) "Deselect all" else "Select all",
            )
        }
        FilledTonalButton(
            onClick = onDelete,
            colors  = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor   = MaterialTheme.colorScheme.onErrorContainer,
            ),
        ) {
            Icon(Icons.Default.Delete, null, Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Delete")
        }
    }
}

private fun formatElapsed(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000     -> "%.0f kB".format(bytes / 1_000.0)
    else               -> "$bytes B"
}
