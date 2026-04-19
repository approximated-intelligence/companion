package de.perigon.companion.track.ui.recording

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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import de.perigon.companion.core.ui.AppTopBar
import de.perigon.companion.core.ui.SecureWindow
import de.perigon.companion.track.data.RecordingMode
import de.perigon.companion.track.data.TrackConfigEntity
import de.perigon.companion.track.data.TrackPointEntity
import de.perigon.companion.track.data.TrackStatsEntity
import de.perigon.companion.track.data.TrackSummary
import de.perigon.companion.track.domain.INTERVAL_OPTIONS_MS
import de.perigon.companion.track.domain.formatIntervalLabel
import de.perigon.companion.track.domain.formatModeLabel
import de.perigon.companion.track.service.GnssInfo
import java.time.Instant
import java.time.format.DateTimeFormatter

private val LOCAL_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssXXX")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun RecordingScreen(
    navController: NavController,
    vm: RecordingViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    SecureWindow()

    LaunchedEffect(Unit) {
        vm.snackbar.events.collect { snackbar.showSnackbar(it) }
    }

    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasLocationPermission = results[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (hasLocationPermission) vm.startRecording()
    }

    val exactAlarmLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            val am = context.getSystemService(android.app.AlarmManager::class.java)
            if (am.canScheduleExactAlarms()) vm.applyScheduleIfEnabled()
        }
    }

    LaunchedEffect(state.config.autoScheduleEnabled) {
        if (state.config.autoScheduleEnabled && android.os.Build.VERSION.SDK_INT >= 31) {
            val am = context.getSystemService(android.app.AlarmManager::class.java)
            if (!am.canScheduleExactAlarms()) {
                exactAlarmLauncher.launch(
                    android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                )
            }
        }
    }

    val exportFolderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val display = uri.lastPathSegment?.substringAfterLast(':') ?: uri.toString()
        vm.setGpxExportFolder(uri, display)
    }

    val selectionExportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        vm.exportSelected(uri)
    }

    val selectionMode = state.selectedTrackIds.isNotEmpty()

    fun requestStartRecording() {
        if (hasLocationPermission) vm.startRecording()
        else permissionLauncher.launch(arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ))
    }

    if (state.showStartNowPrompt) {
        AlertDialog(
            onDismissRequest = { vm.confirmStartNow(false) },
            title = { Text("Start recording now?") },
            text  = { Text("The scheduled recording window is currently active. Start recording immediately?") },
            confirmButton = { TextButton(onClick = { vm.confirmStartNow(true) }) { Text("Start now") } },
            dismissButton = { TextButton(onClick = { vm.confirmStartNow(false) }) { Text("Wait for schedule") } },
        )
    }

    if (state.showStopPrompt) {
        AlertDialog(
            onDismissRequest = { vm.confirmStop(StopPromptChoice.CANCEL) },
            title = { Text("Stop recording") },
            text  = { Text("Auto-schedule is active. It would restart recording at ${formatTime(state.config.autoStartTime)} tomorrow.") },
            confirmButton = {
                Column {
                    TextButton(onClick = { vm.confirmStop(StopPromptChoice.STOP_AND_DISABLE) }) { Text("Stop and disable schedule") }
                    TextButton(onClick = { vm.confirmStop(StopPromptChoice.STOP_FOR_TODAY) }) { Text("Stop for today only") }
                    TextButton(onClick = { vm.confirmStop(StopPromptChoice.CANCEL) }) { Text("Cancel") }
                }
            },
            dismissButton = null,
        )
    }

    var confirmDeleteSelected by remember { mutableStateOf(false) }
    if (confirmDeleteSelected) {
        AlertDialog(
            onDismissRequest = { confirmDeleteSelected = false },
            title = { Text("Delete ${state.selectedTrackIds.size} track(s)?") },
            text  = { Text("Selected tracks will be permanently deleted.") },
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
                    selectedCount = state.selectedTrackIds.size,
                    allSelected   = state.selectedTrackIds == state.recentTracks.map { it.id }.toSet(),
                    onClear       = vm::clearSelection,
                    onToggleAll   = vm::toggleSelectAll,
                    onExport      = vm::exportSelectedOrSnack,
                    onRender      = vm::renderSelected,
                    onDelete      = { confirmDeleteSelected = true },
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
                RecordingControls(
                    isRecording           = state.isRecording,
                    isPaused              = state.isPaused,
                    hasLocationPermission = hasLocationPermission,
                    onStart               = ::requestStartRecording,
                    onStop                = vm::requestStop,
                    onPause               = vm::pause,
                    onResume              = vm::resume,
                    onNewSegment          = vm::newSegment,
                )
            }

            item { HorizontalDivider() }
            item { Text("Recording mode", style = MaterialTheme.typography.titleSmall) }
            item { RecordingModeSelector(currentMode = state.config.mode, onModeSelected = vm::setMode) }

            if (state.config.mode != RecordingMode.PASSIVE) {
                item { Text("Fix interval", style = MaterialTheme.typography.titleSmall) }
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        INTERVAL_OPTIONS_MS.forEach { ms ->
                            FilterChip(selected = state.config.intervalMs == ms, onClick = { vm.setInterval(ms) }, label = { Text(formatIntervalLabel(ms)) })
                        }
                    }
                }
                item { Text("Fix timeout", style = MaterialTheme.typography.titleSmall) }
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        TrackConfigEntity.FIX_TIMEOUT_OPTIONS_MS.forEach { ms ->
                            FilterChip(selected = state.config.fixTimeoutMs == ms, onClick = { vm.setFixTimeout(ms) }, label = { Text("${ms / 1000}s") })
                        }
                    }
                }
                item { Text("Max inaccuracy", style = MaterialTheme.typography.titleSmall) }
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        TrackConfigEntity.MAX_INACCURACY_OPTIONS_M.forEach { m ->
                            FilterChip(selected = state.config.maxInaccuracyM == m, onClick = { vm.setMaxInaccuracy(m) }, label = { Text("${m.toInt()}m") })
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("Fixes less accurate than this trigger a retry.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            item { HorizontalDivider() }
            item { Text("Power management", style = MaterialTheme.typography.titleSmall) }
            item { ToggleRow("Hold wake lock", "Prevents CPU from sleeping. Required for reliable continuous mode.", state.config.holdWakeLock, vm::setHoldWakeLock) }
            item { ToggleRow("Keep ephemeris warm", "Passive listener keeps GPS chip data fresh between fixes. Low power.", state.config.keepEphemerisWarm, vm::setKeepEphemerisWarm) }

            item { HorizontalDivider() }
            item { Text("Segmentation", style = MaterialTheme.typography.titleSmall) }
            item {
                Text("Auto-segment gap", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TrackConfigEntity.AUTO_SEGMENT_GAP_OPTIONS_MS.forEach { ms ->
                        FilterChip(
                            selected = state.config.autoSegmentGapMs == ms,
                            onClick  = { vm.setAutoSegmentGapMs(ms) },
                            label    = { Text(formatIntervalLabel(ms)) },
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("A new segment is started if no fix is received for this long.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            item { HorizontalDivider() }
            item { Text("Day rollover", style = MaterialTheme.typography.titleSmall) }
            item {
                ToggleRow(
                    title           = "Auto-split at midnight",
                    subtitle        = "Start a new recording when the day changes. Disable to continue the same recording across midnight.",
                    checked         = state.config.autoSplitOnDayRollover,
                    onCheckedChange = vm::setAutoSplitOnDayRollover,
                )
            }

            item { HorizontalDivider() }
            item { Text("Auto schedule", style = MaterialTheme.typography.titleSmall) }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Scheduled recording", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (state.config.autoScheduleEnabled) "${formatTime(state.config.autoStartTime)} – ${formatTime(state.config.autoStopTime)}" else "Disabled",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (state.config.autoScheduleEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.config.autoScheduleEnabled,
                        onCheckedChange = { enabled ->
                            if (enabled && android.os.Build.VERSION.SDK_INT >= 31) {
                                val am = context.getSystemService(android.app.AlarmManager::class.java)
                                if (!am.canScheduleExactAlarms()) {
                                    exactAlarmLauncher.launch(
                                        android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                    )
                                    return@Switch
                                }
                            }
                            vm.setAutoScheduleEnabled(enabled)
                        }
                    )
                }
            }
            if (state.config.autoScheduleEnabled) {
                item { TimePickerRow(label = "Start time", time = state.config.autoStartTime, onTimeSelected = vm::setAutoStartTime) }
                item { TimePickerRow(label = "Stop time",  time = state.config.autoStopTime,  onTimeSelected = vm::setAutoStopTime) }
                item {
                    if (state.config.autoStopTime < state.config.autoStartTime) {
                        Text("Overnight: records until ${formatTime(state.config.autoStopTime)} next day.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            item { HorizontalDivider() }
            item { Text("GPX export", style = MaterialTheme.typography.titleSmall) }
            item {
                GpxExportFolder(
                    label    = state.gpxExportFolderLabel,
                    onSelect = { exportFolderPicker.launch(null) },
                    onClear  = vm::clearGpxExportFolder,
                )
            }
            item {
                Button(
                    onClick  = vm::exportAll,
                    enabled  = state.gpxExportFolderLabel != null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.SaveAlt, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Export all tracks")
                }
            }

            if (state.recentTracks.isNotEmpty()) {
                item { HorizontalDivider() }
                item { Text("Recent tracks", style = MaterialTheme.typography.titleSmall) }
                items(state.recentTracks, key = { it.id }) { track ->
                    val isActive      = state.isRecording && track.id == state.currentTrackId
                    val isPausedTrack = state.isPaused   && track.id == state.currentTrackId
                    TrackRow(
                        track             = track,
                        isActive          = isActive,
                        isPaused          = isPausedTrack,
                        isSelected        = track.id in state.selectedTrackIds,
                        selectionMode     = selectionMode,
                        livePointCount    = if (isActive) track.pointCount + state.pendingPoints else track.pointCount,
                        pendingPoints     = state.pendingPoints,
                        lastProvidedFix   = state.lastProvidedFix,
                        lastAcceptedFix   = state.lastAcceptedFix,
                        gnssInfo          = state.gnssInfo,
                        onToggleSelection = { vm.toggleSelection(track.id) },
                        onShare           = { vm.shareTrack(track.id) },
                        onDelete          = { vm.deleteTrack(track.id) },
                        onDeleteActive    = vm::deleteActiveTrack,
                        onShareGeo        = vm::shareLastProvidedFixAsGeo,
                        onRename          = { name -> vm.renameTrack(track.id, name) },
                        onEnsureStats     = { vm.ensureStats(track.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordingControls(
    isRecording: Boolean,
    isPaused: Boolean,
    hasLocationPermission: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onNewSegment: () -> Unit,
) {
    when {
        isRecording -> Card(
            colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier              = Modifier.padding(16.dp).fillMaxWidth(),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onPause) {
                        Icon(Icons.Default.Pause, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Pause")
                    }
                    FilledTonalButton(onClick = onNewSegment) {
                        Icon(Icons.Default.Splitscreen, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Split")
                    }
                    FilledTonalButton(onClick = onStop) {
                        Icon(Icons.Default.Stop, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Stop")
                    }
                }
            }
        }

        isPaused -> Card(
            colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier              = Modifier.padding(16.dp).fillMaxWidth(),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onResume) {
                        Icon(Icons.Default.PlayArrow, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Cont.")
                    }
                    FilledTonalButton(onClick = onNewSegment) {
                        Icon(Icons.Default.Splitscreen, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Split")
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
                Icon(Icons.Default.PlayArrow, null)
                Spacer(Modifier.width(8.dp))
                Text("Start recording")
            }
            if (!hasLocationPermission) {
                Text("Location permission required.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackRow(
    track: TrackSummary,
    isActive: Boolean,
    isPaused: Boolean,
    isSelected: Boolean,
    selectionMode: Boolean,
    livePointCount: Int,
    pendingPoints: Int,
    lastProvidedFix: TrackPointEntity?,
    lastAcceptedFix: TrackPointEntity?,
    gnssInfo: GnssInfo,
    onToggleSelection: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onDeleteActive: (restart: Boolean) -> Unit,
    onShareGeo: () -> Unit,
    onRename: (String) -> Unit,
    onEnsureStats: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmDeleteActive by remember { mutableStateOf(false) }
    var editingName by remember { mutableStateOf(false) }
    var nameInput by remember(track.name) { mutableStateOf(track.name) }

    LaunchedEffect(track.id, track.stats) {
        if (track.stats == null && !isActive) onEnsureStats()
    }

    val containerColor = when {
        isSelected -> MaterialTheme.colorScheme.secondaryContainer
        isActive   -> MaterialTheme.colorScheme.primaryContainer
        isPaused   -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
        else       -> MaterialTheme.colorScheme.surfaceVariant
    }

    Card(
        colors   = CardDefaults.cardColors(containerColor = containerColor),
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .combinedClickable(
                onClick     = { if (selectionMode) onToggleSelection() },
                onLongClick = onToggleSelection,
            ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (selectionMode) {
                    Checkbox(checked = isSelected, onCheckedChange = { onToggleSelection() })
                }
                Icon(
                    when {
                        isActive -> Icons.Default.LocationOn
                        isPaused -> Icons.Default.Pause
                        else     -> Icons.Default.Route
                    },
                    null,
                    modifier = Modifier.size(20.dp),
                    tint = when {
                        isActive -> MaterialTheme.colorScheme.onPrimaryContainer
                        isPaused -> MaterialTheme.colorScheme.secondary
                        else     -> MaterialTheme.colorScheme.primary
                    },
                )
                Column(Modifier.weight(1f)) {
                    if (editingName) {
                        OutlinedTextField(
                            value         = nameInput,
                            onValueChange = { nameInput = it },
                            singleLine    = true,
                            modifier      = Modifier.fillMaxWidth(),
                            trailingIcon  = {
                                Row {
                                    IconButton(onClick = { onRename(nameInput); editingName = false }) {
                                        Icon(Icons.Default.Check, "Save")
                                    }
                                    IconButton(onClick = { nameInput = track.name; editingName = false }) {
                                        Icon(Icons.Default.Close, "Cancel")
                                    }
                                }
                            },
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(track.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            if (!selectionMode) {
                                IconButton(onClick = { editingName = true }, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Default.Edit, "Rename", modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    val pointsLabel = if (isActive && pendingPoints > 0)
                        "$livePointCount pts ($pendingPoints buffered)" else "$livePointCount pts"
                    Text("${track.date} · $pointsLabel",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!selectionMode && !editingName) {
                    IconButton(onClick = onShare, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Share, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    }
                    IconButton(
                        onClick  = { if (isActive || isPaused) confirmDeleteActive = true else confirmDelete = true },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp))
                    }
                }
            }

            TrackStatsRow(stats = track.stats, isActive = isActive)

            if ((isActive || isPaused) && !selectionMode) {
                ActiveTrackInfo(
                    providedFix = lastProvidedFix,
                    acceptedFix = lastAcceptedFix,
                    gnssInfo    = gnssInfo,
                    onShareGeo  = onShareGeo,
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete track?") },
            text  = { Text("\"${track.name}\" will be permanently deleted.") },
            confirmButton = { TextButton(onClick = { onDelete(); confirmDelete = false }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }

    if (confirmDeleteActive) {
        AlertDialog(
            onDismissRequest = { confirmDeleteActive = false },
            title = { Text(if (isActive) "Stop recording and delete?" else "Delete paused track?") },
            text  = { Text("\"${track.name}\" will be permanently deleted.") },
            confirmButton = {
                Column {
                    if (isActive) {
                        TextButton(onClick = { onDeleteActive(true);  confirmDeleteActive = false }) { Text("Delete & restart") }
                        TextButton(onClick = { onDeleteActive(false); confirmDeleteActive = false }) { Text("Delete & stop") }
                    } else {
                        TextButton(onClick = { onDelete(); confirmDeleteActive = false }) { Text("Delete") }
                    }
                }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteActive = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TrackStatsRow(stats: TrackStatsEntity?, isActive: Boolean) {
    if (isActive) return
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val style = MaterialTheme.typography.labelSmall
    if (stats == null) {
        Text("computing…", style = style, color = color)
        return
    }
    val parts = buildList {
        add(if (stats.distanceM >= 1000f) "%.1f km".format(stats.distanceM / 1000f)
            else "%.0f m".format(stats.distanceM))
        add(formatDuration(stats.durationMs))
        if (stats.recordingLengthMs < stats.durationMs * 9 / 10)
            add("rec ${formatDuration(stats.recordingLengthMs)}")
        if (stats.timeMovingMs > 0)
            add("moving ${formatDuration(stats.timeMovingMs)}")
    }
    Text(parts.joinToString(" · "), style = style, color = color)
}

private fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0s"
    val s   = ms / 1000
    val h   = s / 3600
    val m   = (s % 3600) / 60
    val sec = s % 60
    return when {
        h > 0 -> "%dh%02dm".format(h, m)
        m > 0 -> "%dm%02ds".format(m, sec)
        else  -> "${sec}s"
    }
}

@Composable
private fun ActiveTrackInfo(
    providedFix: TrackPointEntity?,
    acceptedFix: TrackPointEntity?,
    gnssInfo: GnssInfo,
    onShareGeo: () -> Unit,
) {
    val contentColor = MaterialTheme.colorScheme.onPrimaryContainer

    if (acceptedFix != null) {
        val parts = mutableListOf("accepted ${formatLocal(acceptedFix.time)}")
        if (acceptedFix.accuracyM != null) parts += "±%.0fm".format(acceptedFix.accuracyM)
        Text(parts.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = contentColor)
    } else {
        Text("no accepted fix", style = MaterialTheme.typography.labelSmall, color = contentColor)
    }

    if (providedFix != null) {
        val firstLine = mutableListOf("provided ${formatLocal(providedFix.time)}")
        if (providedFix.accuracyM != null) firstLine += "±%.0fm".format(providedFix.accuracyM)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(firstLine.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = contentColor, modifier = Modifier.weight(1f))
            IconButton(onClick = onShareGeo, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.MyLocation, "Share location", tint = contentColor, modifier = Modifier.size(14.dp))
            }
        }
        val secondLine = buildList {
            if (providedFix.ele        != null) add("%.0fm".format(providedFix.ele))
            if (providedFix.undulation != null) add("geoid: %.1fm".format(providedFix.undulation))
            if (providedFix.bearing    != null) add("%.0f°".format(providedFix.bearing))
            if (providedFix.speedMs    != null) add("%.1f km/h".format(providedFix.speedMs * 3.6f))
        }
        if (secondLine.isNotEmpty()) {
            Text(secondLine.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = contentColor)
        }
    } else {
        Text("no provided fix", style = MaterialTheme.typography.labelSmall, color = contentColor)
    }

    val gnssText = gnssInfo.format()
    if (gnssText.isNotEmpty()) {
        Text(gnssText, style = MaterialTheme.typography.labelSmall, color = contentColor)
    }
}

@Composable
private fun RecordingModeSelector(currentMode: String, onModeSelected: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RecordingMode.ALL.forEach { mode ->
            FilterChip(selected = currentMode == mode, onClick = { onModeSelected(mode) }, label = { Text(formatModeLabel(mode)) })
        }
    }
    Spacer(Modifier.height(4.dp))
    Text(
        when (currentMode) {
            RecordingMode.CONTINUOUS -> "GPS stays active. Best accuracy, highest power usage."
            RecordingMode.SINGLE_FIX -> "Requests a single GPS fix each cycle. Moderate power."
            RecordingMode.ALARM      -> "Uses alarms for Doze-safe scheduling. Low power."
            RecordingMode.PASSIVE    -> "Logs fixes from other apps. Zero active power."
            else -> ""
        },
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title,    style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun GpxExportFolder(label: String?, onSelect: () -> Unit, onClear: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text("Export folder", style = MaterialTheme.typography.bodyMedium)
            Text(label ?: "Not set",
                style = MaterialTheme.typography.bodySmall,
                color = if (label != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (label != null) FilledTonalButton(onClick = onClear) { Text("Clear") }
        FilledTonalButton(onClick = onSelect) { Text(if (label != null) "Change" else "Select") }
    }
}

@Composable
private fun SelectionBottomBar(
    selectedCount: Int,
    allSelected: Boolean,
    onClear: () -> Unit,
    onToggleAll: () -> Unit,
    onExport: () -> Unit,
    onRender: () -> Unit,
    onDelete: () -> Unit,
) {
    BottomAppBar {
        IconButton(onClick = onClear) { Icon(Icons.Default.Close, "Cancel selection") }
        Text("$selectedCount selected", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = onToggleAll) {
            Icon(
                if (allSelected) Icons.Default.Deselect else Icons.Default.SelectAll,
                if (allSelected) "Deselect all" else "Select all",
            )
        }
        FilledTonalButton(onClick = onRender) {
            Icon(Icons.Default.Map, null, Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Render")
        }
        Spacer(Modifier.width(4.dp))
        FilledTonalButton(onClick = onExport) {
            Icon(Icons.Default.SaveAlt, null, Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Export")
        }
        Spacer(Modifier.width(4.dp))
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerRow(label: String, time: java.time.LocalTime, onTimeSelected: (java.time.LocalTime) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Column {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(formatTime(time), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        FilledTonalButton(onClick = { showPicker = true }) {
            Icon(Icons.Default.Schedule, null, Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Change")
        }
    }
    if (showPicker) {
        val pickerState = rememberTimePickerState(initialHour = time.hour, initialMinute = time.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title   = { Text(label) },
            text    = { TimePicker(state = pickerState) },
            confirmButton = {
                TextButton(onClick = {
                    onTimeSelected(java.time.LocalTime.of(pickerState.hour, pickerState.minute))
                    showPicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text("Cancel") } },
        )
    }
}

private fun formatTime(time: java.time.LocalTime): String = "%02d:%02d".format(time.hour, time.minute)

private fun formatLocal(timeMs: Long): String =
    Instant.ofEpochMilli(timeMs)
        .atZone(java.time.ZoneId.systemDefault())
        .format(LOCAL_FORMAT)
