package de.perigon.companion.track.ui.recording

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import androidx.work.ExistingWorkPolicy
import de.perigon.companion.core.prefs.AppPrefs
import de.perigon.companion.core.ui.SnackbarChannel
import de.perigon.companion.track.data.CurrentTrackState
import de.perigon.companion.track.data.TrackConfigEntity
import de.perigon.companion.track.data.TrackConfigPrefs
import de.perigon.companion.track.data.TrackPointEntity
import de.perigon.companion.track.data.TrackRepository
import de.perigon.companion.track.data.TrackSummary
import de.perigon.companion.track.domain.GpxExporter
import de.perigon.companion.track.service.AutoScheduler
import de.perigon.companion.track.service.GnssInfo
import de.perigon.companion.track.service.BackgroundService
import de.perigon.companion.track.worker.GpxExportWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import androidx.core.content.FileProvider
import java.io.File

enum class StopPromptChoice { STOP_FOR_TODAY, STOP_AND_DISABLE, CANCEL }

@Immutable
data class RecordingUiState(
    val isRecording:          Boolean            = false,
    val isPaused:             Boolean            = false,
    val currentTrackId:       Long?              = null,
    val config:               TrackConfigEntity  = TrackConfigEntity.DEFAULT,
    val recentTracks:         List<TrackSummary> = emptyList(),
    val todayDate:            String             = LocalDate.now().toString(),
    val lastProvidedFix:      TrackPointEntity?  = null,
    val lastAcceptedFix:      TrackPointEntity?  = null,
    val pendingPoints:        Int                = 0,
    val gnssInfo:             GnssInfo           = GnssInfo.EMPTY,
    val gpxExportFolderUri:   String?            = null,
    val gpxExportFolderLabel: String?            = null,
    val showStartNowPrompt:   Boolean            = false,
    val showStopPrompt:       Boolean            = false,
    val selectedTrackIds:     Set<Long>          = emptySet(),
)

@HiltViewModel
class RecordingViewModel @Inject constructor(
    @param:ApplicationContext private val ctx: Context,
    private val configPrefs: TrackConfigPrefs,
    private val appPrefs: AppPrefs,
    private val trackRepository: TrackRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(RecordingUiState(
        gpxExportFolderUri   = appPrefs.gpxExportFolderUri(),
        gpxExportFolderLabel = appPrefs.gpxExportFolderLabel(),
    ))
    val state: StateFlow<RecordingUiState> = _state.asStateFlow()

    val snackbar = SnackbarChannel()

    init {
        viewModelScope.launch {
            combine(
                BackgroundService.status,
                configPrefs.observe(),
                trackRepository.observeSummaries(),
                trackRepository.observeCurrentTrack(),
            ) { status, config, tracks, currentTrack ->
                RecordingUiState(
                    isRecording          = status.isRecording,
                    isPaused             = !status.isRecording && currentTrack?.state == CurrentTrackState.PAUSED,
                    currentTrackId       = currentTrack?.trackId,
                    lastProvidedFix      = status.lastProvidedFix,
                    lastAcceptedFix      = status.lastAcceptedFix,
                    pendingPoints        = status.pendingPoints,
                    gnssInfo             = status.gnssInfo,
                    config               = config,
                    recentTracks         = tracks,
                    todayDate            = LocalDate.now().toString(),
                    gpxExportFolderUri   = appPrefs.gpxExportFolderUri(),
                    gpxExportFolderLabel = appPrefs.gpxExportFolderLabel(),
                    showStartNowPrompt   = _state.value.showStartNowPrompt,
                    showStopPrompt       = _state.value.showStopPrompt,
                    selectedTrackIds     = _state.value.selectedTrackIds,
                )
            }.collect { _state.value = it }
        }
    }

    // ---- Recording control ----

    fun startRecording() {
        if (!appPrefs.isBackgroundGpsEnabled()) {
            snackbar.send("Background GPS is disabled — enable it in Settings")
            return
        }
        ctx.startForegroundService(Intent(ctx, BackgroundService::class.java).apply {
            action = BackgroundService.ACTION_START
        })
    }

    fun pause() {
        ctx.startService(Intent(ctx, BackgroundService::class.java).apply {
            action = BackgroundService.ACTION_PAUSE
        })
    }

    fun resume() {
        if (!appPrefs.isBackgroundGpsEnabled()) {
            snackbar.send("Background GPS is disabled — enable it in Settings")
            return
        }
        ctx.startForegroundService(Intent(ctx, BackgroundService::class.java).apply {
            action = BackgroundService.ACTION_START
        })
    }

    fun newSegment() {
        ctx.startService(Intent(ctx, BackgroundService::class.java).apply {
            action = BackgroundService.ACTION_NEW_SEGMENT
        })
    }

    fun requestStop() {
        if (_state.value.config.autoScheduleEnabled) {
            _state.update { it.copy(showStopPrompt = true) }
        } else {
            doStop()
        }
    }

    private fun doStop() {
        ctx.startService(Intent(ctx, BackgroundService::class.java).apply {
            action = BackgroundService.ACTION_STOP
        })
    }

    fun confirmStop(choice: StopPromptChoice) {
        _state.update { it.copy(showStopPrompt = false) }
        when (choice) {
            StopPromptChoice.STOP_FOR_TODAY   -> doStop()
            StopPromptChoice.STOP_AND_DISABLE -> viewModelScope.launch {
                configPrefs.update { it.copy(autoScheduleEnabled = false) }
                applySchedule()
                doStop()
            }
            StopPromptChoice.CANCEL -> {}
        }
    }

    fun confirmStartNow(start: Boolean) {
        _state.update { it.copy(showStartNowPrompt = false) }
        if (start) startRecording()
    }

    // ---- Stats ----

    fun ensureStats(trackId: Long) {
        viewModelScope.launch(Dispatchers.IO) { trackRepository.ensureStats(trackId) }
    }

    // ---- Rename ----

    fun renameTrack(trackId: Long, name: String) {
        viewModelScope.launch { trackRepository.renameTrack(trackId, name.trim()) }
    }

    // ---- Config ----

    fun setMode(mode: String)                    { viewModelScope.launch { configPrefs.update { it.copy(mode = mode) } } }
    fun setInterval(ms: Long)                    { viewModelScope.launch { configPrefs.update { it.copy(intervalMs = ms) } } }
    fun setFixTimeout(ms: Long)                  { viewModelScope.launch { configPrefs.update { it.copy(fixTimeoutMs = ms) } } }
    fun setMaxInaccuracy(m: Float)               { viewModelScope.launch { configPrefs.update { it.copy(maxInaccuracyM = m) } } }
    fun setKeepEphemerisWarm(v: Boolean)         { viewModelScope.launch { configPrefs.update { it.copy(keepEphemerisWarm = v) } } }
    fun setHoldWakeLock(v: Boolean)              { viewModelScope.launch { configPrefs.update { it.copy(holdWakeLock = v) } } }
    fun setAutoSplitOnDayRollover(v: Boolean)    { viewModelScope.launch { configPrefs.update { it.copy(autoSplitOnDayRollover = v) } } }
    fun setAutoSegmentGapMs(ms: Long)            { viewModelScope.launch { configPrefs.update { it.copy(autoSegmentGapMs = ms) } } }

    fun setAutoScheduleEnabled(enabled: Boolean) {
        viewModelScope.launch {
            configPrefs.update { it.copy(autoScheduleEnabled = enabled) }
            applySchedule()
            if (enabled) {
                val config = configPrefs.get()
                val scheduler = AutoScheduler(ctx, config, appPrefs.isBackgroundGpsEnabled())
                if (scheduler.isInActiveWindow() && !_state.value.isRecording) {
                    _state.update { it.copy(showStartNowPrompt = true) }
                }
            }
        }
    }

    fun applyScheduleIfEnabled() {
        if (_state.value.config.autoScheduleEnabled) viewModelScope.launch { applySchedule() }
    }

    fun setAutoStartTime(time: LocalTime) {
        viewModelScope.launch {
            configPrefs.update { it.copy(autoStartSeconds = time.toSecondOfDay().toLong()) }
            if (_state.value.config.autoScheduleEnabled) applySchedule()
        }
    }

    fun setAutoStopTime(time: LocalTime) {
        viewModelScope.launch {
            configPrefs.update { it.copy(autoStopSeconds = time.toSecondOfDay().toLong()) }
            if (_state.value.config.autoScheduleEnabled) applySchedule()
        }
    }

    // ---- Selection ----

    fun toggleSelection(trackId: Long) {
        _state.update { s ->
            val cur = s.selectedTrackIds
            s.copy(selectedTrackIds = if (trackId in cur) cur - trackId else cur + trackId)
        }
    }

    fun selectAll()       { _state.update { s -> s.copy(selectedTrackIds = s.recentTracks.map { it.id }.toSet()) } }
    fun clearSelection()  { _state.update { it.copy(selectedTrackIds = emptySet()) } }
    fun toggleSelectAll() {
        val allIds = _state.value.recentTracks.map { it.id }.toSet()
        if (_state.value.selectedTrackIds == allIds) clearSelection() else selectAll()
    }

    // ---- Delete ----

    fun deleteTrack(trackId: Long) {
        viewModelScope.launch { trackRepository.deleteTrack(trackId); snackbar.send("Track deleted") }
    }

    fun deleteActiveTrack(restart: Boolean) {
        viewModelScope.launch {
            doStop()
            val todayTrack = _state.value.recentTracks.find { it.date == _state.value.todayDate }
            if (todayTrack != null) trackRepository.deleteTrack(todayTrack.id)
            if (restart) {
                kotlinx.coroutines.delay(500)
                startRecording()
                snackbar.send("Track deleted, recording restarted")
            } else {
                snackbar.send("Track deleted, recording stopped")
            }
        }
    }

    fun deleteSelected() {
        val trackIds = _state.value.selectedTrackIds
        viewModelScope.launch {
            val hasActive = trackIds.any { id ->
                _state.value.isRecording &&
                _state.value.recentTracks.find { it.id == id }?.date == _state.value.todayDate
            }
            if (hasActive) doStop()
            for (id in trackIds) trackRepository.deleteTrack(id)
            _state.update { it.copy(selectedTrackIds = emptySet()) }
            snackbar.send("${trackIds.size} track(s) deleted")
        }
    }

    // ---- Export via Worker ----

    private fun flushActiveBuffer() {
        if (!_state.value.isRecording) return
        ctx.startService(Intent(ctx, BackgroundService::class.java).apply {
            action = BackgroundService.ACTION_FLUSH
        })
    }

    private fun enqueueExport(trackIds: Set<Long>, folderUri: Uri) {
        flushActiveBuffer()
        val request = GpxExportWorker.buildRequest(
            trackIds = trackIds.toLongArray(),
            folderUri = folderUri,
        )
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            GpxExportWorker.WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
        snackbar.send("Export started for ${trackIds.size} track(s)")
    }

    fun exportSelected(folderUri: Uri) {
        val trackIds = _state.value.selectedTrackIds
        if (trackIds.isEmpty()) return
        enqueueExport(trackIds, folderUri)
        _state.update { it.copy(selectedTrackIds = emptySet()) }
    }

    fun exportSelectedOrSnack() {
        val uri = _state.value.gpxExportFolderUri?.let { Uri.parse(it) }
            ?: run { snackbar.send("Set export folder first"); return }
        exportSelected(uri)
    }

    fun exportAll() {
        val uri = _state.value.gpxExportFolderUri?.let { Uri.parse(it) }
            ?: run { snackbar.send("Set export folder first"); return }
        val allIds = _state.value.recentTracks.map { it.id }.toSet()
        if (allIds.isEmpty()) return
        enqueueExport(allIds, uri)
    }

    fun shareTrack(trackId: Long) {
        viewModelScope.launch {
            flushActiveBuffer()
            val track    = trackRepository.getTrackById(trackId) ?: return@launch
            val segments = withContext(Dispatchers.IO) { trackRepository.getSegmentsWithPoints(trackId) }
            if (segments.isEmpty()) return@launch
            val file = withContext(Dispatchers.IO) {
                val dir = File(ctx.cacheDir, "gpx_share"); dir.mkdirs()
                val f = File(dir, "${track.name.sanitizeFilename()}.gpx")
                f.outputStream().use { GpxExporter.export(track.name, segments, it) }
                f
            }
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            ctx.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "application/gpx+xml"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, track.name)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                    "Share track",
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun renderSelected() {
        val trackIds = _state.value.selectedTrackIds
        viewModelScope.launch {
            flushActiveBuffer()
            val uris = ArrayList<Uri>()
            withContext(Dispatchers.IO) {
                val dir = File(ctx.cacheDir, "gpx_share"); dir.mkdirs()
                for (id in trackIds) {
                    val track    = trackRepository.getTrackById(id) ?: continue
                    val segments = trackRepository.getSegmentsWithPoints(id)
                    if (segments.isEmpty()) continue
                    val f = File(dir, "${track.name.sanitizeFilename()}.gpx")
                    f.outputStream().use { GpxExporter.export(track.name, segments, it) }
                    uris += FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
                }
            }
            if (uris.isEmpty()) return@launch
            ctx.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                        type = "application/gpx+xml"
                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                    "Render tracks",
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun shareLastProvidedFixAsGeo() {
        val fix = _state.value.lastProvidedFix ?: return
        ctx.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_VIEW, Uri.parse("geo:${fix.lat},${fix.lon}?z=16")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
                "Open location",
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    // ---- GPX export folder ----

    fun setGpxExportFolder(uri: Uri, displayName: String) {
        viewModelScope.launch {
            ctx.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            appPrefs.setGpxExportFolder(uri.toString(), displayName)
            _state.update { it.copy(gpxExportFolderUri = uri.toString(), gpxExportFolderLabel = displayName) }
        }
    }

    fun clearGpxExportFolder() {
        viewModelScope.launch {
            appPrefs.clearGpxExportFolder()
            _state.update { it.copy(gpxExportFolderUri = null, gpxExportFolderLabel = null) }
        }
    }

    internal suspend fun applySchedule() {
        val config = configPrefs.get()
        AutoScheduler(ctx, config, appPrefs.isBackgroundGpsEnabled()).apply()
    }
}

private fun String.sanitizeFilename(): String =
    replace(Regex("[^a-zA-Z0-9._-]"), "_").take(64)
