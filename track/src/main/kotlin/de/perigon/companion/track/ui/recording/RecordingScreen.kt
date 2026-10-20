package de.perigon.companion.track.ui.recording

import de.perigon.companion.core.ui.ToggleRow
import de.perigon.companion.core.ui.formatSpan
import de.perigon.companion.core.saf.folderLabel

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
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
import de.perigon.companion.track.data.isValidLoggingRate
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

    Scaffold(
        topBar       = { AppTopBar(navController) },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar    = {
            if (selectionMode) {
                SelectionBottomBar(
                    selectedCount = state.selectedTrackIds.size,
                    allSelected   = state.selectedTrackIds == otherTracks.map { it.id }.toSet(),
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
                        liveDistanceM        = state.liveDistanceM,
                        currentBaroAltitudeM = state.currentBaroAltitudeM,
                        kalmanAltitudeM      = state.kalmanAltitudeM,
                        currentQnhHpa        = state.currentQnhHpa,
                        verticalSpeedMps     = state.verticalSpeedMps,
                        slopePercent         = state.slopePercent,
                        kalmanBiasM          = state.kalmanBiasM,
                        baroEnabled          = state.config.recordBaroAltitude,
                        hasBarometer         = state.hasBarometer,
                        airplaneMode         = state.config.airplaneMode,
                        gnssInfo             = state.gnssInfo,
                        onShareGeo           = vm::shareLastProvidedFixAsGeo,
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
            item { ToggleRow("Record barometric altitude", "MEMS pressure sensor via standard atmosphere. Adds relative climb precision; ~100 µA at 5 Hz.", state.config.recordBaroAltitude, vm::setRecordBaroAltitude) }

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

/**
 * Bicycle-computer card. No live-state-driven conditional hiding: every
 * stat stays on screen and shows "--" until the data arrives.
 *
 *   [status · points]
 *   [distance]           [speed]           [heading]
 *   [altitude]           [slope]           [v_z]
 *     altitude            slope             v_z
 *   heights: GPS · geoid · WGS84 · baro · QNH (one line, placeholders)
 *   calibration row (b shown here, and only here)
 *   fix chain · GNSS · airplane toggle
 *
 * The first three values use large type and inline units (km, km/h, °) so
 * they read as a unit and need no labels. The second row uses slightly
 * smaller type and one-word labels, because "+2.1%" and "+1.23 m/s" are
 * ambiguous without them.
 *
 * QNH shows "(needs 3D fix)" when the barometer is enabled and working but
 * the altitude filter has not yet seeded — that happens when the device is
 * stationary indoors or otherwise has only a 2D fix. The filter requires a
 * GPS altitude measurement to initialize; a barometer-only seed is not
 * possible because the weather bias is unknown until GPS pins the
 * absolute reference.
 *
 * "heading" is Location.getBearing(): course over ground, the direction
 * the device is actually moving. Goes null at rest (hasBearing is false
 * when stationary), which is why the column used to disappear — the fix
 * is to keep it visible and show "--" while still. Not bearing-to-a-
 * target; this app has no routing.
 *
 * Airplane-mode interaction:
 *   - KF altitude still works (GPS-only smoothing).
 *   - slope label flips to "gradient".
 *   - bias b and QNH disappear: they describe a barometric measurement
 *     that is no longer in the pipeline. Calibration row is hidden.
 *   - the baro reading is labelled "cabin" and never stored.
 *
 * No-barometer interaction (hardware without a pressure sensor):
 *   - calibration row is hidden
 *   - debug line shows "baro (no sensor)" instead of a numeric placeholder
 *   - QNH is omitted entirely (there is no pressure from which to compute it)
 *   - KF still works from GPS alone
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
    liveDistanceM: Float?,
    currentBaroAltitudeM: Float?,
    kalmanAltitudeM: Float?,
    currentQnhHpa: Float?,
    verticalSpeedMps: Float?,
    slopePercent: Float?,
    kalmanBiasM: Float?,
    baroEnabled: Boolean,
    hasBarometer: Boolean,
    airplaneMode: Boolean,
    gnssInfo: GnssInfo,
    onShareGeo: () -> Unit,
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
    val slopeLabel = if (airplaneMode) "gradient" else "slope"

    val speedKmh: Float? = lastProvidedFix?.speedMs?.let { it * 3.6f }
    // Location.getBearing() is course over ground (direction of travel),
    // i.e. heading, not bearing-to-a-target. There is no target here.
    // Goes null at rest — the UI must show "--" rather than hiding.
    val heading: Float? = lastProvidedFix?.bearing

    // Row 1 — values with units inline, no labels.
    val distanceText: String = liveDistanceM?.let {
        if (it >= 1000f) "%.2f km".format(it / 1000f) else "%.0f m".format(it)
    } ?: "--"
    val speedText: String = speedKmh?.let { "%.1f km/h".format(it) } ?: "--"
    val headingText: String = heading?.let { "${cardinal(it)} ${it.toInt()}°" } ?: "--"

    // Row 2 — smaller; values with units inline, tiny label below.
    val kfAltitudeText: String = kalmanAltitudeM?.let { "%.1f m".format(it) } ?: "--"
    val slopeText: String = "%+.1f%%".format(slopePercent ?: 0f)
    val vzText: String = verticalSpeedMps?.let { "%+.2f m/s".format(it) } ?: "--"

    // Debug line. Every height representation the pipeline carries, plus QNH.
    // Each part is present with a "--" placeholder when the value is not yet
    // available, so the line never changes shape as state arrives. QNH is
    // gated only on config (baro on, not airplane mode), never on live state.
    val eleM: Float? = lastProvidedFix?.ele
    val geoidM: Float? = lastProvidedFix?.undulation
    val wgs84M: Float? = if (eleM != null && geoidM != null) eleM + geoidM else null
    val baroRawM: Float? = currentBaroAltitudeM
    val debugParts = buildList {
        add("GPS " + (eleM?.let { "%.1f".format(it) } ?: "--"))
        add("geoid " + (geoidM?.let { "%.1f".format(it) } ?: "--"))
        add("WGS84 " + (wgs84M?.let { "%.1f".format(it) } ?: "--"))
        if (baroEnabled) {
            when {
                !hasBarometer -> add("baro (no sensor)")
                else -> {
                    val baroLabel = if (airplaneMode) "cabin " else "baro "
                    add(baroLabel + (baroRawM?.let { "%.1f".format(it) } ?: "--"))
                    if (!airplaneMode) {
                        // QNH requires a 3D fix: the filter cannot seed from
                        // baro alone, because the weather bias is unknown
                        // until GPS pins the absolute reference.
                        val qnhText = when {
                            currentQnhHpa != null   -> "%.0f".format(currentQnhHpa)
                            kalmanAltitudeM == null -> "(needs 3D fix)"
                            else                    -> "--"
                        }
                        add("QNH " + qnhText)
                    }
                }
            }
        }
    }

    val gnssText: String = gnssInfo.format().ifEmpty { "--" }

    Card(
        colors   = CardDefaults.cardColors(containerColor = containerColor),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Status row.
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

            // Row 1: distance · speed · heading, inline units, no labels.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    distanceText,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor,
                    textAlign = TextAlign.Start,
                )
                Text(
                    speedText,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor,
                    textAlign = TextAlign.Center,
                )
                Text(
                    headingText,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor,
                    textAlign = TextAlign.End,
                )
            }

            // Row 2: altitude · slope · v_z — one step down in prominence.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StatColumn(
                    value = kfAltitudeText,
                    // label = "altitude",
                    modifier = Modifier.weight(1f),
                    color = contentColor,
                    valueStyle = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Start,
                )
                StatColumn(
                    value = slopeText,
                    // label = slopeLabel,
                    modifier = Modifier.weight(1f),
                    color = contentColor,
                    valueStyle = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )
                StatColumn(
                    value = vzText,
                    // label = "v_z",
                    modifier = Modifier.weight(1f),
                    color = contentColor,
                    valueStyle = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.End,
                )
            }

            // Debug line: single row, all heights + QNH, "--" placeholders.
            Text(
                debugParts.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = contentColor.copy(alpha = 0.6f),
            )

            // Calibration row. b lives here, and only here. Config-gated
            // (baro on, not airplane mode) AND hardware-gated (sensor present)
            // — no flicker with live state, no dead calibration button on
            // phones without a barometer.
            if (baroEnabled && !airplaneMode && hasBarometer) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (kalmanBiasM == null) "uncalibrated (converging)"
                        else "b = %+.1f m".format(kalmanBiasM),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (kalmanBiasM == null) MaterialTheme.colorScheme.error else contentColor.copy(alpha = 0.7f),
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = { showCalibrateDialog = true },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(24.dp),
                    ) {
                        Text(
                            if (kalmanBiasM == null) "initial calibration" else "recalibrate",
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor,
                        )
                    }
                }
            }

            HorizontalDivider(color = contentColor.copy(alpha = 0.2f))

            FixChain(
                providedFix = lastProvidedFix,
                acceptedFix = lastAcceptedFix,
                enqueuedFix = lastEnqueuedFix,
                contentColor = contentColor,
                onShareGeo = onShareGeo,
            )

            // GNSS: always shown. "--" before first satellite status arrives.
            Text(
                gnssText,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor.copy(alpha = 0.7f),
            )

            HorizontalDivider(color = contentColor.copy(alpha = 0.2f))

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Airplane mode",
                        style = MaterialTheme.typography.bodyMedium,
                        color = contentColor,
                    )
                    Text(
                        "Cabin pressure is regulated and excluded from altitude fusion",
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.7f),
                    )
                }
                Switch(checked = airplaneMode, onCheckedChange = onSetAirplaneMode)
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

@Composable
private fun StatColumn(
    value: String,
    // label: String,
    modifier: Modifier = Modifier,
    color: Color,
    valueStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.titleMedium,
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
        // Text(
        //     label,
        //     style = MaterialTheme.typography.labelSmall,
        //     color = color.copy(alpha = 0.7f),
        //     textAlign = textAlign,
        //     modifier = Modifier.fillMaxWidth(),
        // )
    }
}

@Composable
private fun FixChain(
    providedFix: TrackPointEntity?,
    acceptedFix: TrackPointEntity?,
    enqueuedFix: TrackPointEntity?,
    contentColor: Color,
    onShareGeo: () -> Unit,
) {
    // Each line appears once and stays for the session — monotone progression
    // through the pipeline. "waiting for fixes…" is the only state where all
    // three are absent, and it is replaced the moment the first fix arrives.
    // Not flicker: state advances, it does not oscillate.
    if (providedFix != null) {
        val parts = mutableListOf("provided ${formatLocal(providedFix.time)}")
        if (providedFix.accuracyM != null) parts += "±%.0fm".format(providedFix.accuracyM)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(parts.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = contentColor.copy(alpha = 0.7f), modifier = Modifier.weight(1f))
            IconButton(onClick = onShareGeo, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.MyLocation, "Share location", tint = contentColor, modifier = Modifier.size(14.dp))
            }
        }
    }

    if (acceptedFix != null) {
        val parts = mutableListOf("accepted ${formatLocal(acceptedFix.time)}")
        if (acceptedFix.accuracyM != null) parts += "±%.0fm".format(acceptedFix.accuracyM)
        Text(parts.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = contentColor.copy(alpha = 0.7f))
    }

    if (enqueuedFix != null) {
        val parts = mutableListOf("enqueued ${formatLocal(enqueuedFix.time)}")
        if (enqueuedFix.accuracyM != null) parts += "±%.0fm".format(enqueuedFix.accuracyM)
        Text(parts.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = contentColor.copy(alpha = 0.7f))
    }

    if (providedFix == null && acceptedFix == null && enqueuedFix == null) {
        Text("waiting for fixes…", style = MaterialTheme.typography.labelSmall, color = contentColor.copy(alpha = 0.7f))
    }
}

@Composable
private fun BaroCalibrationDialog(
    currentBaro: Float,
    gpsOrthoAlt: Float?,
    kfAlt: Float?,
    onCalibrate: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    var manualInput by remember { mutableStateOf("") }
    val manualValue = manualInput.toFloatOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Initial barometric calibration") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Raw ISA baro: %.1f m".format(currentBaro))
                if (kfAlt != null) {
                    Text("KF altitude: %.1f m".format(kfAlt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    "This seeds the Kalman filter once so it converges immediately " +
                        "instead of over several minutes. It does not store a persistent " +
                        "offset — the filter's bias state absorbs any remaining weather drift.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (gpsOrthoAlt != null) {
                    OutlinedButton(
                        onClick = { onCalibrate(gpsOrthoAlt) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Use GPS height (%.1f m)".format(gpsOrthoAlt))
                    }
                }
                OutlinedTextField(
                    value = manualInput,
                    onValueChange = { manualInput = it.filter { c -> c.isDigit() || c == '.' || c == '-' } },
                    label = { Text("Manual altitude (m)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { manualValue?.let(onCalibrate) },
                enabled = manualValue != null,
            ) { Text("Seed") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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

private fun cardinal(deg: Float): String {
    val d = ((deg % 360f) + 360f) % 360f
    return when {
        d < 22.5f || d >= 337.5f -> "N"
        d < 67.5f  -> "NE"
        d < 112.5f -> "E"
        d < 157.5f -> "SE"
        d < 202.5f -> "S"
        d < 247.5f -> "SW"
        d < 292.5f -> "W"
        d < 337.5f -> "NW"
        else       -> "N"
    }
}
