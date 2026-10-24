package de.perigon.companion.sensors.ui.recording

import de.perigon.companion.core.ui.ToggleRow
import de.perigon.companion.core.ui.cardinal
import de.perigon.companion.core.ui.formatDateTime
import de.perigon.companion.core.ui.formatSpan
import de.perigon.companion.core.saf.folderLabel

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.perigon.companion.core.ui.SecureWindow
import de.perigon.companion.sensors.data.RecordingMode
import de.perigon.companion.sensors.data.TrackConfigEntity
import de.perigon.companion.sensors.data.TrackPointEntity
import de.perigon.companion.sensors.data.TrackStatsEntity
import de.perigon.companion.sensors.data.TrackSummary
import de.perigon.companion.sensors.data.isValidLoggingRate
import de.perigon.companion.sensors.data.toKalmanTuning
import de.perigon.companion.sensors.domain.KalmanMode
import de.perigon.companion.sensors.domain.KalmanModel
import de.perigon.companion.sensors.domain.formatIntervalLabel
import de.perigon.companion.sensors.domain.formatModeLabel
import de.perigon.companion.sensors.service.GnssInfo
import de.perigon.companion.sensors.ui.BaroCalibrationDialog
import de.perigon.companion.sensors.ui.KalmanAdvancedPanel
import de.perigon.companion.sensors.ui.KalmanModeSelector
import de.perigon.companion.sensors.ui.KalmanModelSelector

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun RecordingScreenContent(
    vm: RecordingViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val showDeprecatedModes by vm.showDeprecatedModes.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    SecureWindow()

    DisposableEffect(Unit) {
        vm.onScreenEnter()
        onDispose { vm.onScreenExit() }
    }

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
        val am = context.getSystemService(android.app.AlarmManager::class.java)
        if (am.canScheduleExactAlarms()) vm.applyScheduleIfEnabled()
    }

    LaunchedEffect(state.config.autoScheduleEnabled) {
        if (state.config.autoScheduleEnabled) {
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
        val display = folderLabel(uri)
        vm.setGpxExportFolder(uri, display)
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

    val currentTrack = state.recentTracks.find { it.id == state.currentTrackId }
    val showLiveCard = state.previewActive || state.isRecording
    val otherTracks = state.recentTracks.filter { it.id != state.currentTrackId }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier            = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding      = PaddingValues(top = 16.dp, bottom = 96.dp),
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
                    onShareCurrent        = vm::shareCurrentTrack,
                )
            }

            if (showLiveCard) {
                item {
                    LiveCard(
                        isRecording          = state.isRecording,
                        isPaused             = state.isPaused,
                        livePointCount       = (currentTrack?.pointCount ?: 0) + state.pendingPoints,
                        pendingPoints        = state.pendingPoints,
                        lastProvidedFix      = state.lastProvidedFix,
                        lastAcceptedFix      = state.lastAcceptedFix,
                        lastEnqueuedFix      = state.lastEnqueuedFix,
                        lastHeadingDegrees   = state.lastHeadingDegrees,
                        liveDistanceM        = state.liveDistanceM,
                        currentBaroAltitudeM = state.currentBaroAltitudeM,
                        currentPressureHpa   = state.currentPressureHpa,
                        kalmanAltitudeM      = state.kalmanAltitudeM,
                        currentQnhHpa        = state.currentQnhHpa,
                        verticalSpeedMps     = state.verticalSpeedMps,
                        slopePercent         = state.slopePercent,
                        kalmanBiasM          = state.kalmanBiasM,
                        kalmanBiasRateMps    = state.kalmanBiasRateMps,
                        kalmanAltitudeSigmaM = state.kalmanAltitudeSigmaM,
                        kalmanBiasSigmaM     = state.kalmanBiasSigmaM,
                        lastGpsInnovationM   = state.lastGpsInnovationM,
                        lastBaroInnovationM  = state.lastBaroInnovationM,
                        airconDetected       = state.airconDetected,
                        baroEnabled          = state.config.recordBaroAltitude,
                        kalmanEnabled        = state.config.kalmanEnabled,
                        modelUsesBaro        = state.config.kalmanModel.hasBaroMeasurement,
                        hasBarometer         = state.hasBarometer,
                        airplaneMode         = state.config.airplaneMode,
                        gnssInfo             = state.gnssInfo,
                        onShareFix           = vm::shareFixAsGeo,
                        onCalibrateBaro      = vm::calibrateBaro,
                        onSetAirplaneMode    = vm::setAirplaneMode,
                    )
                }
            }

            item { HorizontalDivider() }
            item { Text("Recording mode", style = MaterialTheme.typography.titleSmall) }
            item {
                RecordingModeSelector(
                    currentMode    = state.config.mode,
                    showDeprecated = showDeprecatedModes,
                    onModeSelected = vm::setMode,
                )
            }

            if (state.config.mode != RecordingMode.PASSIVE) {
                val isContinuous = state.config.mode == RecordingMode.CONTINUOUS

                if (isContinuous) {
                    item { Text("Hardware rate", style = MaterialTheme.typography.titleSmall) }
                    item {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            TrackConfigEntity.HARDWARE_INTERVAL_OPTIONS_MS.forEach { ms ->
                                FilterChip(selected = state.config.hardwareIntervalMs == ms, onClick = { vm.setHardwareInterval(ms) }, label = { Text(formatIntervalLabel(ms)) })
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text("How often the GPS chip produces a fix. Lower = better accuracy and faster fixes, more battery.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (state.config.kalmanEnabled &&
                            state.config.hardwareIntervalMs > TrackConfigEntity.KF_MAX_HARDWARE_INTERVAL_MS) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "Effective: ${formatIntervalLabel(state.config.effectiveHardwareIntervalMs)} " +
                                    "while the filter runs (bias needs frequent GPS to converge). " +
                                    "Returns to ${formatIntervalLabel(state.config.hardwareIntervalMs)} " +
                                    "when filtering is off.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                item {
                    Text(
                        if (isContinuous) "Logging rate" else "Fix interval",
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                item {
                    val loggingOptions = remember(state.config.hardwareIntervalMs, isContinuous) {
                        if (isContinuous) TrackConfigEntity.INTERVAL_OPTIONS_MS.filter { isValidLoggingRate(it, state.config.hardwareIntervalMs) }
                        else TrackConfigEntity.INTERVAL_OPTIONS_MS
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        loggingOptions.forEach { ms ->
                            FilterChip(selected = state.config.intervalMs == ms, onClick = { vm.setInterval(ms) }, label = { Text(formatIntervalLabel(ms)) })
                        }
                    }
                    if (isContinuous && loggingOptions.size < TrackConfigEntity.INTERVAL_OPTIONS_MS.size) {
                        Spacer(Modifier.height(4.dp))
                        Text("Options restricted to multiples of the hardware rate.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                if (state.config.mode == RecordingMode.SINGLE_FIX || state.config.mode == RecordingMode.ALARM) {
                    item { Text("Fix timeout", style = MaterialTheme.typography.titleSmall) }
                    item {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            TrackConfigEntity.FIX_TIMEOUT_OPTIONS_MS.forEach { ms ->
                                FilterChip(selected = state.config.fixTimeoutMs == ms, onClick = { vm.setFixTimeout(ms) }, label = { Text("${ms / 1000}s") })
                            }
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
            item { Text("Barometric altitude", style = MaterialTheme.typography.titleSmall) }
            item { ToggleRow("Record barometric altitude", "MEMS pressure sensor via standard atmosphere. ~100 µA at 5 Hz. Controls per-point storage; the sensor also runs when the Kalman filter needs it, even with this off.", state.config.recordBaroAltitude, vm::setRecordBaroAltitude) }

            item { HorizontalDivider() }
            item { Text("Altitude filter", style = MaterialTheme.typography.titleSmall) }

            // Master switch. Off by default: the recorder stores raw GPS
            // altitude and the per-point baro column as-is, and the live
            // card's KF-derived readouts (h, v_z, slope, b, σ, innovations,
            // CUSUM) stay "--". The model/mode/tuning controls below are
            // only meaningful when this is on, so they're hidden while off.
            item {
                ToggleRow(
                    title           = "Kalman filtering",
                    subtitle        = if (state.config.kalmanEnabled)
                        "Smooth or fuse GPS altitude through a Kalman filter."
                    else
                        "Off: raw GPS altitude is recorded. No filter, no slope / v_z / bias readouts.",
                    checked         = state.config.kalmanEnabled,
                    onCheckedChange = vm::setKalmanEnabled,
                )
            }

            if (state.config.kalmanEnabled) {
                item {
                    Text("Model", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    KalmanModelSelector(
                        currentModel = state.config.kalmanModel,
                        onModelSelected = vm::setKalmanModel,
                    )
                }

                item {
                    Text("Update method", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    KalmanModeSelector(
                        currentMode = state.config.kalmanMode,
                        onModeSelected = vm::setKalmanMode,
                    )
                }

                if (state.config.kalmanMode == KalmanMode.ISKF) {
                    item {
                        ToggleRow(
                            title = "Inverse-Hessian covariance (advanced)",
                            subtitle = "Report the exact robust posterior covariance instead of the nominal Riccati one. Affects only the σ values shown; a slightly larger cost per measurement.",
                            checked = state.config.iskfInverseHessian,
                            onCheckedChange = vm::setIskfInverseHessian,
                        )
                    }
                }

                // Advanced tuning expander. Same composable as the KF tab,
                // but wired to the persisted config: onChange writes through
                // RecordingViewModel → TrackConfigPrefs, applyConfigChange
                // picks it up, and the filter rebuilds if the change affects
                // process noise. No "Apply to Track" button here — the tab
                // *is* the Track settings.
                item {
                    KalmanAdvancedPanel(
                        model = state.config.kalmanModel,
                        tuning = state.config.toKalmanTuning(),
                        onChange = vm::setKalmanTuning,
                    )
                }
            }

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
                            if (enabled) {
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
                    onClear  = { vm.clearGpxExportFolder() },
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

            if (otherTracks.isNotEmpty()) {
                item { HorizontalDivider() }
                item { Text("Recent tracks", style = MaterialTheme.typography.titleSmall) }
                items(otherTracks, key = { it.id }) { track ->
                    TrackRow(
                        track             = track,
                        isSelected        = track.id in state.selectedTrackIds,
                        selectionMode     = selectionMode,
                        onToggleSelection = { vm.toggleSelection(track.id) },
                        onShare           = { vm.shareTrack(track.id) },
                        onDelete          = { vm.deleteTrack(track.id) },
                        onRename          = { name -> vm.renameTrack(track.id, name) },
                        onEnsureStats     = { vm.ensureStats(track.id) },
                    )
                }
            }
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        if (selectionMode) {
            SelectionBottomBar(
                selectedCount = state.selectedTrackIds.size,
                allSelected   = state.selectedTrackIds == otherTracks.map { it.id }.toSet(),
                onClear       = vm::clearSelection,
                onToggleAll   = vm::toggleSelectAll,
                onExport      = vm::exportSelectedOrSnack,
                onRender      = vm::renderSelected,
                onDelete      = { confirmDeleteSelected = true },
                modifier      = Modifier.align(Alignment.BottomCenter),
            )
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
    onShareCurrent: () -> Unit,
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
                IconButton(onClick = onShareCurrent) {
                    Icon(Icons.Default.Share, "Share current track")
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
                IconButton(onClick = onShareCurrent) {
                    Icon(Icons.Default.Share, "Share current track")
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

@Composable
private fun StatValueWithUnit(
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    color: Color,
    horizontalAlignment: Arrangement.Horizontal,
    valueStyle: TextStyle = MaterialTheme.typography.headlineMedium,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = horizontalAlignment,
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            value,
            style = valueStyle,
            fontWeight = FontWeight.SemiBold,
            color = color,
            maxLines = 1,
            softWrap = false,
        )
        if (unit.isNotEmpty()) {
            Spacer(Modifier.width(2.dp))
            Text(
                unit,
                style = MaterialTheme.typography.bodySmall,
                color = color.copy(alpha = 0.75f),
                modifier = Modifier.padding(bottom = 3.dp),
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/**
 * Bicycle-computer card.
 *
 *   [status · points]
 *   [distance]           [speed]           [heading]
 *   [altitude]           [slope]           [v_z]
 *     heights (one line): GPS · geoid · WGS84 · baro
 *     KF diagnostics (one line): b · σ(h) · σ(b) · Δgps · Δbaro
 *     bias-rate line: db/dt · "aircon" when the CUSUM fires
 *     Q-values (one line, right-aligned "recalibrate" link when live)
 *   fix chain (each line clickable → share via map)
 *   GNSS · airplane toggle
 *
 * The three top-row values are rendered as value + smaller unit inline so
 * they never wrap; the previous `"%.1f km/h".format(...)` fit on one line
 * only up to ~99.9 km/h at `headlineMedium`.
 *
 * The three fix-chain lines are tight `labelSmall` text (lineHeight 14 sp)
 * and are clickable directly — no icon button. The 24 dp icon button that
 * used to sit on the provided-fix line enforced a Row height that pushed
 * the three lines apart. Every fix is shareable, not just the most recent.
 *
 * Airplane-mode hides the baro-derived values (b, σ(b), Δbaro, bias-rate,
 * Q-line, recalibrate) — cabin pressure is not valid baro input.
 *
 * No-barometer hides baro from the heights line and the Q-line/recalibrate
 * entirely.
 *
 * slope and v_z are populated in EVERY model. 3/4/GPS-2-state: v_z is the
 * KF's own velocity state. 2-state: v_z comes from the filter's cascade
 * position-difference estimator (a leaf — no feedback into h or b), so the
 * live card shows climb rate and slope in the 2-state too. The "--" case
 * for slope remains valid: it also covers v_h < 0.5 m/s, and the first
 * fraction of a second after seed when the cascade has not produced its
 * first sample yet.
 *
 * When Kalman filtering is off entirely, h/v_z/slope/b/σ/innovations/CUSUM
 * are all null and every KF-derived readout renders "--"; raw baro pressure
 * and the per-point baro column still flow through independently.
 *
 * When the model has no bias state (GPS-only), the b / σ(b) / Δbaro /
 * bias-rate / Q-line / recalibrate rows are hidden even with baro enabled —
 * there is no bias state for them to read from.
 *
 * Airplane mode's effect on the live card is determined by the filter
 * state, not by the model identity. When the filter is off, the airplane
 * toggle's ONLY effect is on whether baro alt is stored per point
 * (BackgroundService.convert), so the row is still rendered when baro
 * recording is enabled — hiding it would strand the user with no way to
 * change the setting that controls the stored column. When the filter is
 * on, the row is rendered regardless (the filter uses airplaneMode).
 *
 * The bias-rate line is new (4-state feature surfaced in every model). It
 * is a diagnostic, shown only when baro is in the pipeline. It prefixes
 * "aircon" when the CUSUM fires — a subtle marker, not a chip, so it adds
 * no visual noise under normal operation.
 */
@Composable
private fun LiveCard(
    isRecording: Boolean,
    isPaused: Boolean,
    livePointCount: Int,
    pendingPoints: Int,
    lastProvidedFix: TrackPointEntity?,
    lastAcceptedFix: TrackPointEntity?,
    lastEnqueuedFix: TrackPointEntity?,
    lastHeadingDegrees: Float?,
    liveDistanceM: Float?,
    currentBaroAltitudeM: Float?,
    currentPressureHpa: Float?,
    kalmanAltitudeM: Float?,
    currentQnhHpa: Float?,
    verticalSpeedMps: Float?,
    slopePercent: Float?,
    kalmanBiasM: Float?,
    kalmanBiasRateMps: Float?,
    kalmanAltitudeSigmaM: Float?,
    kalmanBiasSigmaM: Float?,
    lastGpsInnovationM: Float?,
    lastBaroInnovationM: Float?,
    airconDetected: Boolean,
    baroEnabled: Boolean,
    kalmanEnabled: Boolean,
    modelUsesBaro: Boolean,
    hasBarometer: Boolean,
    airplaneMode: Boolean,
    gnssInfo: GnssInfo,
    onShareFix: (TrackPointEntity) -> Unit,
    onCalibrateBaro: (Float) -> Unit,
    onSetAirplaneMode: (Boolean) -> Unit,
) {
    var showCalibrateDialog by remember { mutableStateOf(false) }

    val containerColor = when {
        isRecording -> MaterialTheme.colorScheme.primaryContainer
        isPaused    -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
        else        -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when {
        isRecording -> MaterialTheme.colorScheme.onPrimaryContainer
        isPaused    -> MaterialTheme.colorScheme.onSecondaryContainer
        else        -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val speedKmh: Float? = lastProvidedFix?.speedMs?.let { it * 3.6f }
    // Last non-null bearing from the service. Unfiltered — the service
    // stores whatever the chipset reports, holding the previous value when
    // a fix arrives without a course. No smoothing, no threshold.
    val heading: Float? = lastHeadingDegrees

    // Row 1 — values with units inline, no labels.
    val (distanceValue, distanceUnit) = when {
        liveDistanceM == null -> "--" to ""
        liveDistanceM >= 1000f -> "%.2f".format(liveDistanceM / 1000f) to "km"
        else -> "%.0f".format(liveDistanceM) to "m"
    }
    val (speedValue, speedUnit) = if (speedKmh != null) {
        "%.1f".format(speedKmh) to "km/h"
    } else "--" to ""

    val kfAltitudeText: String =
        if (!kalmanEnabled) "--"
        else kalmanAltitudeM?.let { "%.1f m".format(it) } ?: "--"
    // "--" covers four cases: Kalman disabled, v_h < 0.5 m/s (below
    // MIN_SPEED_FOR_SLOPE_MPS), v_z not yet available (first sample after
    // seed), or the filter not seeded at all. All four are genuinely
    // unknown, not "flat".
    val slopeText: String =
        if (!kalmanEnabled) "--"
        else slopePercent?.let { "%+.1f%%".format(it) } ?: "--"
    val vzText: String =
        if (!kalmanEnabled) "--"
        else verticalSpeedMps?.let { "%+.2f m/s".format(it) } ?: "--"

    // Debug line. Every height representation the pipeline carries, plus baro.
    // Each part is present with a "--" placeholder when the value is not yet
    // available, so the line never changes shape as state arrives.
    val eleM: Float? = lastProvidedFix?.ele
    val geoidM: Float? = lastProvidedFix?.undulation
    val wgs84M: Float? = if (eleM != null && geoidM != null) eleM + geoidM else null
    // Sensor runs when per-point storage is on OR the KF model fuses baro.
    // Showing "off" / "(no sensor)" when those are the actual states makes
    // the line honest: previously the whole segment disappeared when
    // recordBaroAltitude was off, leaving KF-on + storage-off users with no
    // way to see whether the sensor was running.
    val baroSensorRunning = baroEnabled || (kalmanEnabled && modelUsesBaro)
    val heightLine = buildString {
        append("GPS ").append(eleM?.let { "%.1f".format(it) } ?: "--")
        append(" · geoid ").append(geoidM?.let { "%.1f".format(it) } ?: "--")
        append(" · WGS84 ").append(wgs84M?.let { "%.1f".format(it) } ?: "--")
        append(" · ")
        when {
            !hasBarometer -> append("baro (no sensor)")
            !baroSensorRunning -> append("baro off")
            airplaneMode -> append("cabin ").append(currentBaroAltitudeM?.let { "%.1f".format(it) } ?: "--")
            else -> append("baro ").append(currentBaroAltitudeM?.let { "%.1f".format(it) } ?: "--")
        }
    }

    // Baro is *fused* only when Kalman is on, the model uses baro, the
    // sensor is present, and airplane mode isn't excluding it. When false,
    // the KF-derived baro rows (b, σ(b), Δbaro, CUSUM, QNH) are hidden.
    val baroFused = kalmanEnabled && modelUsesBaro && !airplaneMode && hasBarometer

    val kfLine: String? = if (kalmanEnabled && kalmanAltitudeM != null) {
        buildString {
            if (baroFused) {
                append("b ").append(kalmanBiasM?.let { "%+.1f m".format(it) } ?: "--")
                append(" · ")
            }
            append("σ(h) ").append(kalmanAltitudeSigmaM?.let { "%.1f".format(it) } ?: "--")
            if (baroFused) {
                append(" · σ(b) ").append(kalmanBiasSigmaM?.let { "%.1f".format(it) } ?: "--")
            }
            append(" · Δgps ").append(lastGpsInnovationM?.let { "%+.1f".format(it) } ?: "--")
            if (baroFused) {
                append(" · Δbaro ").append(lastBaroInnovationM?.let { "%+.1f".format(it) } ?: "--")
            }
        }
    } else null

    // Bias-rate line: shown whenever baro is in the pipeline (v_b is
    // baro-derived). Prefixes "aircon" when the CUSUM has fired.
    val biasRateLine: String? = if (baroFused && kalmanBiasRateMps != null) {
        buildString {
            append("db/dt ")
            append("%+.2f mm/s".format(kalmanBiasRateMps * 1000f))
            if (airconDetected) append(" · aircon")
        }
    } else null

    // QNH requires a 3D fix: the filter cannot seed from baro alone, because
    // the weather bias is unknown until GPS pins the absolute reference.
    // "(needs 3D fix)" is shown in that window rather than a bare "--".
    val qLine: String? = if (baroFused) {
        buildString {
            append("QFE ")
            append(currentPressureHpa?.let { "%.1f hPa".format(it) } ?: "-- hPa")
            append(" · QNH ")
            when {
                currentQnhHpa != null   -> append("%.1f hPa".format(currentQnhHpa))
                kalmanAltitudeM == null -> append("(needs 3D fix)")
                else                    -> append("-- hPa")
            }
        }
    } else null

    val canRecalibrate = baroFused && currentBaroAltitudeM != null
    val gnssText: String = gnssInfo.format()

    // Airplane-mode row visibility is determined by the toggle's *effect*,
    // not by filter state alone. The toggle affects:
    //   - The filter's q_v (GPS-only) or baro fusion (fusion models) when
    //     the filter is on.
    //   - Whether baro alt is stored per point (BackgroundService.convert)
    //     when baro recording is on, regardless of filter state.
    // So the row is shown whenever EITHER effect is active. Hiding it when
    // the filter is off would strand the user with no way to change the
    // setting that controls the stored baro column.
    val showAirplaneRow = kalmanEnabled || baroEnabled

    Card(
        colors   = CardDefaults.cardColors(containerColor = containerColor),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    when {
                        isRecording -> Icons.Default.LocationOn
                        isPaused    -> Icons.Default.Pause
                        else        -> Icons.Default.MyLocation
                    },
                    null,
                    modifier = Modifier.size(18.dp),
                    tint = contentColor,
                )
                Text(
                    when {
                        isRecording -> "Recording"
                        isPaused    -> "Paused"
                        else        -> "Locating…"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = contentColor,
                )
                Spacer(Modifier.weight(1f))
                if (isRecording) {
                    val ptsLabel = if (pendingPoints > 0)
                        "$livePointCount pts ($pendingPoints buffered)"
                        else "$livePointCount pts"
                    Text(ptsLabel, style = MaterialTheme.typography.labelSmall, color = contentColor.copy(alpha = 0.7f))
                }
            }

            // Distance only when there is one (recording or paused); during
            // locating/preview it is null and the cell is omitted entirely
            // rather than rendering a placeholder. Speed and heading stay
            // because they are meaningful even before recording starts —
            // the preview is the user's chance to see them before
            // committing.
            // Three fixed slots — heading (left), speed (center), distance
            // (right). When distance is absent (preview, or after stop), the
            // right slot is a blank Spacer, not a "--": the layout stays
            // stable across recording state so the other two cells don't
            // slide around when recording starts or stops. The distance slot
            // is never rendered as "--" in any state.
            val showDistance = liveDistanceM != null
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                HeadingCell(
                    heading,
                    Modifier.weight(1f), contentColor,
                    Arrangement.Start,
                )
                StatValueWithUnit(
                    speedValue, speedUnit,
                    Modifier.weight(1f), contentColor,
                    Arrangement.Center,
                )
                if (showDistance) {
                    StatValueWithUnit(distanceValue, distanceUnit, Modifier.weight(1f), contentColor, Arrangement.End)
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }

            // Row 2 — altitude · slope · v_z, one step down in prominence.
            // Hidden entirely when Kalman is off — the readouts are always
            // "--" in that case, and three permanent dashes are just noise.
            if (kalmanEnabled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    StatColumn(
                        value = kfAltitudeText,
                        modifier = Modifier.weight(1f),
                        color = contentColor,
                        valueStyle = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Start,
                    )
                    StatColumn(
                        value = slopeText,
                        modifier = Modifier.weight(1f),
                        color = contentColor,
                        valueStyle = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                    )
                    StatColumn(
                        value = vzText,
                        modifier = Modifier.weight(1f),
                        color = contentColor,
                        valueStyle = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.End,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                val debugStyle = MaterialTheme.typography.labelSmall.copy(lineHeight = 14.sp)
                Text(
                    heightLine,
                    style = debugStyle,
                    color = contentColor.copy(alpha = 0.6f),
                )
                if (kfLine != null) {
                    Text(
                        kfLine,
                        style = debugStyle,
                        color = contentColor.copy(alpha = 0.6f),
                    )
                }
                if (biasRateLine != null) {
                    Text(
                        biasRateLine,
                        style = debugStyle,
                        color = contentColor.copy(alpha = 0.7f),
                    )
                }
                if (qLine != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            qLine,
                            modifier = Modifier.weight(1f),
                            style = debugStyle,
                            color = contentColor.copy(alpha = 0.6f),
                        )
                        if (canRecalibrate) {
                            Text(
                                "recalibrate",
                                style = debugStyle,
                                color = contentColor.copy(alpha = 0.85f),
                                modifier = Modifier
                                    .clickable { showCalibrateDialog = true }
                                    .padding(horizontal = 4.dp),
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = contentColor.copy(alpha = 0.2f))

            FixChain(
                providedFix = lastProvidedFix,
                acceptedFix = lastAcceptedFix,
                enqueuedFix = lastEnqueuedFix,
                contentColor = contentColor,
                onShareFix = onShareFix,
            )

            // Only shown when satellite status has arrived. "--" is
            // dropped entirely; an empty line communicates the same absence
            // without pretending to know the sensor state.
            if (gnssText.isNotEmpty()) {
                Text(
                    gnssText,
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = 0.7f),
                )
            }

            // Airplane-mode row: shown whenever the toggle has an effect —
            // see showAirplaneRow's comment. Subtitle differentiates the
            // three states so the user knows which behaviour is being
            // controlled.
            if (showAirplaneRow) {
                HorizontalDivider(color = contentColor.copy(alpha = 0.2f))

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Airplane mode",
                            style = MaterialTheme.typography.bodyMedium,
                            color = contentColor,
                        )
                        Text(
                            when {
                                !kalmanEnabled ->
                                    "Excludes barometric altitude from recorded points"
                                modelUsesBaro ->
                                    "Cabin pressure excluded from fusion"
                                else ->
                                    "Switches GPS-only filter to a looser q_v for takeoff ramps"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor.copy(alpha = 0.7f),
                        )
                    }
                    Switch(checked = airplaneMode, onCheckedChange = onSetAirplaneMode)
                }
            }
        }
    }

    if (showCalibrateDialog) {
        BaroCalibrationDialog(
            currentBaro = currentBaroAltitudeM ?: 0f,
            gpsOrthoAlt = lastProvidedFix?.ele,
            kfAlt       = kalmanAltitudeM,
            onCalibrate = { ref ->
                onCalibrateBaro(ref)
                showCalibrateDialog = false
            },
            onDismiss   = { showCalibrateDialog = false },
        )
    }
}

/**
 * Heading cell: "45°" at headline size, "NE" as a smaller annotation.
 *
 * The ° is emitted at the same size as the digits, in one Text: the °
 * glyph is drawn in the upper half of the character cell by the font
 * itself, so it is already a superscript without any shift. Splitting it
 * into a smaller Text (or adding BaselineShift) made it read as a
 * separate, floating character — neither is needed.
 *
 * "--" when heading is null (no fix yet).
 */
@Composable
private fun HeadingCell(
    heading: Float?,
    modifier: Modifier = Modifier,
    color: Color,
    horizontalAlignment: Arrangement.Horizontal,
) {
    val headlineStyle = MaterialTheme.typography.headlineMedium
    val smallStyle = MaterialTheme.typography.bodySmall
    Row(
        modifier = modifier,
        horizontalArrangement = horizontalAlignment,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (heading != null) {
            Text(
                "${heading.toInt()}°",
                style = headlineStyle,
                fontWeight = FontWeight.SemiBold,
                color = color,
                softWrap = false,
                maxLines = 1,
            )
            Spacer(Modifier.width(3.dp))
            Text(
                cardinal(heading),
                style = smallStyle,
                color = color.copy(alpha = 0.75f),
                modifier = Modifier.padding(bottom = 3.dp),
            )
        } else {
            Text(
                "--",
                style = headlineStyle,
                fontWeight = FontWeight.SemiBold,
                color = color,
            )
        }
    }
}

@Composable
private fun StatColumn(
    value: String,
    modifier: Modifier = Modifier,
    color: Color,
    valueStyle: TextStyle = MaterialTheme.typography.titleMedium,
    textAlign: TextAlign = TextAlign.Start,
) {
    Column(modifier = modifier) {
        Text(
            value,
            style = valueStyle,
            fontWeight = FontWeight.SemiBold,
            color = color,
            textAlign = textAlign,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun FixChain(
    providedFix: TrackPointEntity?,
    acceptedFix: TrackPointEntity?,
    enqueuedFix: TrackPointEntity?,
    contentColor: Color,
    onShareFix: (TrackPointEntity) -> Unit,
) {
    // Each line appears once and stays for the session — monotone progression
    // through the pipeline. "waiting for fixes…" is the only state where all
    // three are absent, and it is replaced the moment the first fix arrives.
    // Not flicker: state advances, it does not oscillate.
    val lineStyle = MaterialTheme.typography.labelSmall.copy(lineHeight = 14.sp)

    if (providedFix != null) FixLine("provided", providedFix, lineStyle, contentColor, onShareFix)
    if (acceptedFix != null) FixLine("accepted", acceptedFix, lineStyle, contentColor, onShareFix)
    if (enqueuedFix != null) FixLine("enqueued", enqueuedFix, lineStyle, contentColor, onShareFix)

    if (providedFix == null && acceptedFix == null && enqueuedFix == null) {
        Text("waiting for fixes…", style = lineStyle, color = contentColor.copy(alpha = 0.7f))
    }
}

@Composable
private fun FixLine(
    label: String,
    fix: TrackPointEntity,
    style: TextStyle,
    contentColor: Color,
    onShareFix: (TrackPointEntity) -> Unit,
) {
    val parts = mutableListOf("$label ${formatDateTime(fix.time, "yyyy-MM-dd HH:mm:ssXXX")}")
    if (fix.accuracyM != null) parts += "±%.0fm".format(fix.accuracyM)
    Text(
        parts.joinToString(" · "),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onShareFix(fix) },
        style = style,
        color = contentColor.copy(alpha = 0.7f),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackRow(
    track: TrackSummary,
    isSelected: Boolean,
    selectionMode: Boolean,
    onToggleSelection: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onRename: (String) -> Unit,
    onEnsureStats: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    var editingName by remember { mutableStateOf(false) }
    var nameInput by remember(track.name) { mutableStateOf(track.name) }

    LaunchedEffect(track.id, track.pointCount, track.stats?.pointCount) {
        if (track.stats?.pointCount != track.pointCount) {
            onEnsureStats()
        }
    }

    val containerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer
                         else MaterialTheme.colorScheme.surfaceVariant

    Card(
        colors   = CardDefaults.cardColors(containerColor = containerColor),
        modifier = Modifier
            .fillMaxWidth()
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
                Icon(Icons.Default.Route, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
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
                                IconButton(onClick = { editingName = true }, modifier = Modifier.size(40.dp)) {
                                    Icon(Icons.Default.Edit, "Rename", modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    Text("${track.date} · ${track.pointCount} pts",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!selectionMode && !editingName) {
                    IconButton(onClick = onShare, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.Share, "Share track", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    }
                    IconButton(
                        onClick  = { confirmDelete = true },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(Icons.Default.Delete, "Delete track", tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp))
                    }
                }
            }

            TrackStatsRow(stats = track.stats)
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
}

@Composable
private fun TrackStatsRow(stats: TrackStatsEntity?) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val style = MaterialTheme.typography.labelSmall
    if (stats == null) {
        Text("computing…", style = style, color = color)
        return
    }
    val parts = buildList {
        add(if (stats.distanceM >= 1000f) "%.1f km".format(stats.distanceM / 1000f)
            else "%.0f m".format(stats.distanceM))
        if (stats.recordingLengthMs > 0) add(formatSpan(stats.recordingLengthMs))
        if (stats.durationMs - stats.recordingLengthMs > stats.durationMs / 20) {
            add("elapsed ${formatSpan(stats.durationMs)}")
        }
        if (stats.timeMovingMs > 0 && stats.timeMovingMs < stats.recordingLengthMs * 95 / 100) {
            add("moving ${formatSpan(stats.timeMovingMs)}")
        }
    }
    Text(parts.joinToString(" · "), style = style, color = color)
}

@Composable
private fun RecordingModeSelector(
    currentMode: String,
    showDeprecated: Boolean,
    onModeSelected: (String) -> Unit,
) {
    val visibleModes = RecordingMode.ALL.filter { mode ->
        mode == currentMode || mode !in RecordingMode.DEPRECATED || showDeprecated
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        visibleModes.forEach { mode ->
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
    modifier: Modifier = Modifier,
) {
    BottomAppBar(modifier = modifier) {
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
