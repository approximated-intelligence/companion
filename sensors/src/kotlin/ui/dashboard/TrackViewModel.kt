package de.perigon.companion.sensors.ui.dashboard

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import androidx.work.ExistingWorkPolicy
import de.perigon.companion.sensors.data.TrackPrefs
import de.perigon.companion.core.geo.CoordFormat
import de.perigon.companion.core.saf.persistSafGrant
import de.perigon.companion.core.ui.SnackbarChannel
import de.perigon.companion.sensors.data.CurrentTrackState
import de.perigon.companion.sensors.data.RecordingMode
import de.perigon.companion.sensors.data.TrackConfigEntity
import de.perigon.companion.sensors.data.TrackConfigPrefs
import de.perigon.companion.sensors.data.TrackPointEntity
import de.perigon.companion.sensors.data.TrackRepository
import de.perigon.companion.sensors.data.TrackSummary
import de.perigon.companion.sensors.data.isValidLoggingRate
import de.perigon.companion.sensors.data.smallestValidLoggingRate
import de.perigon.companion.sensors.domain.GpxExporter
import de.perigon.companion.sensors.domain.KalmanMode
import de.perigon.companion.sensors.domain.KalmanModel
import de.perigon.companion.sensors.domain.KalmanTuning
import de.perigon.companion.sensors.service.AutoScheduler
import de.perigon.companion.sensors.middleware.GnssSourceType
import de.perigon.companion.sensors.service.GnssInfo
import de.perigon.companion.sensors.service.BackgroundService
import de.perigon.companion.sensors.worker.GpxExportWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import java.io.File

enum class StopPromptChoice { STOP_FOR_TODAY, STOP_AND_DISABLE, CANCEL }

@Immutable
data class RecordingUiState(
    val isRecording:           Boolean            = false,
    val isPaused:              Boolean            = false,
    val previewActive:         Boolean            = false,
    val currentTrackId:        Long?              = null,
    val config:                TrackConfigEntity  = TrackConfigEntity.DEFAULT,
    val recentTracks:          List<TrackSummary> = emptyList(),
    val todayDate:             String             = LocalDate.now().toString(),
    val lastProvidedFix:       TrackPointEntity?  = null,
    val lastAcceptedFix:       TrackPointEntity?  = null,
    val lastEnqueuedFix:       TrackPointEntity?  = null,
    val lastHeadingDegrees:    Float?             = null,
    val liveDistanceM:         Float?             = null,
    val hasBarometer:          Boolean            = false,
    val currentBaroAltitudeM:  Float?             = null,
    val currentPressureHpa:    Float?             = null,
    val kalmanAltitudeM:       Float?             = null,
    val currentQnhHpa:         Float?             = null,
    val verticalSpeedMps:      Float?             = null,
    val slopePercent:          Float?             = null,
    val kalmanBiasM:           Float?             = null,
    /** Bias rate v_b (m/s). State in 4-state, cascade in 2/3-state. */
    val kalmanBiasRateMps:     Float?             = null,
    val kalmanAltitudeSigmaM:  Float?             = null,
    val kalmanBiasSigmaM:      Float?             = null,
    val lastGpsInnovationM:    Float?             = null,
    val lastBaroInnovationM:   Float?             = null,
    /** True when either CUSUM accumulator crosses its threshold. */
    val airconDetected:        Boolean            = false,
    val pendingPoints:         Int                = 0,
    val gnssInfo:              GnssInfo           = GnssInfo.EMPTY,
    // Setting-vs-reality diagnostic. The middleware's configured source,
    // paired with each FixLine's per-fix `provider` string so a divergence
    // (passive mode bypassing the middleware, a race during source swap,
    // a stale prefs emission) is visible in the LiveCard.
    val sourceType:            GnssSourceType     = GnssSourceType.LOCATION,
    val gpxExportFolderUri:    String?            = null,
    val gpxExportFolderLabel:  String?            = null,
    val showStartNowPrompt:    Boolean            = false,
    val showStopPrompt:        Boolean            = false,
    val selectedTrackIds:      Set<Long>          = emptySet(),
)

@HiltViewModel
class TrackViewModel @Inject constructor(
    @param:ApplicationContext private val ctx: Context,
    private val configPrefs: TrackConfigPrefs,
    private val appPrefs: TrackPrefs,
    private val trackRepository: TrackRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(RecordingUiState(
        gpxExportFolderUri   = appPrefs.gpxExportFolderUri(),
        gpxExportFolderLabel = appPrefs.gpxExportFolderLabel(),
    ))
    val state: StateFlow<RecordingUiState> = _state.asStateFlow()

    val showDeprecatedModes: StateFlow<Boolean> = appPrefs.observeShowDeprecatedModes()
        .stateIn(viewModelScope, SharingStarted.Eagerly, appPrefs.isShowDeprecatedModesEnabled())

    /**
     * Coordinate display format, shared with the GNSS tab via [TrackPrefs].
     * Both ViewModels observe the same DataStore key, so a chip tap in
     * either surface updates the other without an explicit sync path.
     */
    val coordFormat: StateFlow<CoordFormat> = appPrefs.observeCoordFormat()
        .stateIn(viewModelScope, SharingStarted.Eagerly, appPrefs.coordFormat())

    fun setCoordFormat(format: CoordFormat) {
        viewModelScope.launch { appPrefs.setCoordFormat(format) }
    }

    val snackbar = SnackbarChannel()

    /**
     * Transient UI state (prompts + selection) as its own flow + combine
     * source. Previously the combine spliced these back in from _state.value,
     * racing every mutator (a toggle/prompt set landing between combine's read
     * and its write was lost).
     */
    private data class LocalUi(
        val showStartNowPrompt: Boolean = false,
        val showStopPrompt:     Boolean = false,
        val selectedTrackIds:   Set<Long> = emptySet(),
    )
    private val localUi = MutableStateFlow(LocalUi())

    init {
        viewModelScope.launch {
            combine(
                combine(
                    BackgroundService.status,
                    appPrefs.observeGnssSourceType(),
                ) { status, sourceType -> status to sourceType },
                configPrefs.observe(),
                trackRepository.observeSummaries(),
                trackRepository.observeCurrentTrack(),
                localUi,
            ) { (status, sourceType), config, tracks, currentTrack, local ->
                RecordingUiState(
                    isRecording          = status.isRecording,
                    isPaused             = !status.isRecording && currentTrack?.state == CurrentTrackState.PAUSED,
                    previewActive        = status.previewActive,
                    currentTrackId       = currentTrack?.trackId,
                    lastProvidedFix      = status.lastProvidedFix,
                    lastAcceptedFix      = status.lastAcceptedFix,
                    lastEnqueuedFix      = status.lastEnqueuedFix,
                    lastHeadingDegrees   = status.lastHeadingDegrees,
                    liveDistanceM        = status.liveDistanceM,
                    hasBarometer         = status.hasBarometer,
                    currentBaroAltitudeM = status.currentBaroAltitudeM,
                    currentPressureHpa   = status.currentPressureHpa,
                    kalmanAltitudeM      = status.kalmanAltitudeM,
                    currentQnhHpa        = status.currentQnhHpa,
                    verticalSpeedMps     = status.verticalSpeedMps,
                    slopePercent         = status.slopePercent,
                    kalmanBiasM          = status.kalmanBiasM,
                    kalmanBiasRateMps    = status.kalmanBiasRateMps,
                    kalmanAltitudeSigmaM = status.kalmanAltitudeSigmaM,
                    kalmanBiasSigmaM     = status.kalmanBiasSigmaM,
                    lastGpsInnovationM   = status.lastGpsInnovationM,
                    lastBaroInnovationM  = status.lastBaroInnovationM,
                    airconDetected       = status.airconDetected,
                    pendingPoints        = status.pendingPoints,
                    gnssInfo             = status.gnssInfo,
                    sourceType           = sourceType,
                    config               = config,
                    recentTracks         = tracks,
                    todayDate            = LocalDate.now().toString(),
                    gpxExportFolderUri   = appPrefs.gpxExportFolderUri(),
                    gpxExportFolderLabel = appPrefs.gpxExportFolderLabel(),
                    showStartNowPrompt   = local.showStartNowPrompt,
                    showStopPrompt       = local.showStopPrompt,
                    // Prune ids of deleted tracks.
                    selectedTrackIds     = local.selectedTrackIds.intersect(tracks.map { it.id }.toSet()),
                )
            }.collect { _state.value = it }
        }
    }

    // ---- Screen lifecycle ----

    fun onScreenEnter() {
        ctx.startForegroundService(Intent(ctx, BackgroundService::class.java).apply {
            action = BackgroundService.ACTION_PREVIEW_START
        })
    }

    fun onScreenExit() {
        ctx.startService(Intent(ctx, BackgroundService::class.java).apply {
            action = BackgroundService.ACTION_PREVIEW_STOP
        })
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
            localUi.update { it.copy(showStopPrompt = true) }
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
        localUi.update { it.copy(showStopPrompt = false) }
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
        localUi.update { it.copy(showStartNowPrompt = false) }
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

    // ---- Baro calibration ----

    /**
     * One-shot KF seeding. Sends the reference altitude to the service,
     * which snaps h and b. No offset is stored — the KF absorbs residual
     * bias on its own. Ignored when the filter is disabled (nothing to
     * seed) or when the model has no bias state (nothing for b to absorb).
     */
    fun calibrateBaro(referenceAltM: Float) {
        if (!_state.value.config.kalmanEnabled) {
            snackbar.send("Kalman filtering is disabled")
            return
        }
        if (_state.value.config.airplaneMode) {
            snackbar.send("Calibration is disabled in airplane mode")
            return
        }
        if (!_state.value.hasBarometer) {
            snackbar.send("No barometer on this device")
            return
        }
        ctx.startService(Intent(ctx, BackgroundService::class.java).apply {
            action = BackgroundService.ACTION_CALIBRATE
            putExtra(BackgroundService.EXTRA_CALIB_REF_ALT, referenceAltM)
        })
    }

    // ---- Config ----

    /**
     * Interval must stay valid against the current hardware rate when the
     * user switches to CONTINUOUS: the interval chips in SINGLE_FIX/ALARM
     * are unfiltered, so an interval that was fine there can be invalid
     * against hardwareIntervalMs on the way back. Snap rather than refuse.
     */
    fun setMode(mode: String) {
        viewModelScope.launch {
            configPrefs.update { cfg ->
                if (mode == RecordingMode.CONTINUOUS && !isValidLoggingRate(cfg.intervalMs, cfg.hardwareIntervalMs)) {
                    cfg.copy(mode = mode, intervalMs = smallestValidLoggingRate(cfg.hardwareIntervalMs, TrackConfigEntity.INTERVAL_OPTIONS_MS))
                } else {
                    cfg.copy(mode = mode)
                }
            }
        }
    }

    fun setFixTimeout(ms: Long)                  { viewModelScope.launch { configPrefs.update { it.copy(fixTimeoutMs = ms) } } }
    fun setMaxInaccuracy(m: Float)               { viewModelScope.launch { configPrefs.update { it.copy(maxInaccuracyM = m) } } }
    fun setKeepEphemerisWarm(v: Boolean)         { viewModelScope.launch { configPrefs.update { it.copy(keepEphemerisWarm = v) } } }
    fun setHoldWakeLock(v: Boolean)              { viewModelScope.launch { configPrefs.update { it.copy(holdWakeLock = v) } } }
    fun setRecordBaroAltitude(v: Boolean)        { viewModelScope.launch { configPrefs.update { it.copy(recordBaroAltitude = v) } } }
    fun setKalmanEnabled(v: Boolean)             { viewModelScope.launch { configPrefs.update { it.copy(kalmanEnabled = v) } } }
    fun setAirplaneMode(v: Boolean)              { viewModelScope.launch { configPrefs.update { it.copy(airplaneMode = v) } } }
    fun setAutoSplitOnDayRollover(v: Boolean)    { viewModelScope.launch { configPrefs.update { it.copy(autoSplitOnDayRollover = v) } } }
    fun setAutoSegmentGapMs(ms: Long)            { viewModelScope.launch { configPrefs.update { it.copy(autoSegmentGapMs = ms) } } }

    fun setKalmanModel(model: KalmanModel) {
        viewModelScope.launch { configPrefs.update { it.copy(kalmanModel = model) } }
    }

    fun setKalmanMode(mode: KalmanMode) {
        viewModelScope.launch { configPrefs.update { it.copy(kalmanMode = mode) } }
    }

    fun setIskfInverseHessian(enabled: Boolean) {
        viewModelScope.launch { configPrefs.update { it.copy(iskfInverseHessian = enabled) } }
    }

    /**
     * Write the Advanced-tuning overrides to config. Null fields become
     * "remove the override, fall back to the built-in default" — matching
     * the "Reset" semantics in the panel. Called from the Track tab's
     * Advanced expander; the KF tab uses [KfViewModel.applyToTrackSettings]
     * to the same effect.
     *
     * Process-noise fields are stored in q (variance rate) units, matching
     * the filter's internal representation and the panel's slider labels.
     */
    fun setKalmanTuning(tuning: KalmanTuning) {
        viewModelScope.launch {
            configPrefs.update { cfg ->
                cfg.copy(
                    kalmanAccelNoiseQ     = tuning.accelNoiseQ,
                    kalmanBiasNoiseQ      = tuning.biasNoiseQ,
                    kalmanQH              = tuning.qH,
                    kalmanDerivedVelNoise = tuning.derivedVelNoise,
                    kalmanBiasRateQ       = tuning.biasRateQ,
                    kalmanDerivedBiasRateNoise = tuning.derivedBiasRateNoise,
                    baroSigmaM            = tuning.baroSigmaM,
                    kalmanGpsVerticalMinM = tuning.gpsVerticalMinM,
                    gpsOnlyAccelQ         = tuning.gpsOnlyAccelQ,
                    gpsOnlyAccelQAirplane = tuning.gpsOnlyAccelQAirplane,
                    gpsOnlySigmaM         = tuning.gpsOnlySigmaM,
                )
            }
        }
    }

    fun setHardwareInterval(ms: Long) {
        viewModelScope.launch {
            configPrefs.update { cfg ->
                val newLogging = if (isValidLoggingRate(cfg.intervalMs, ms)) cfg.intervalMs
                    else smallestValidLoggingRate(ms, TrackConfigEntity.INTERVAL_OPTIONS_MS)
                cfg.copy(hardwareIntervalMs = ms, intervalMs = newLogging)
            }
        }
    }

    fun setInterval(ms: Long) {
        viewModelScope.launch {
            configPrefs.update { cfg ->
                if (!isValidLoggingRate(ms, cfg.hardwareIntervalMs)) cfg
                else cfg.copy(intervalMs = ms)
            }
        }
    }

    fun setAutoScheduleEnabled(enabled: Boolean) {
        viewModelScope.launch {
            configPrefs.update { it.copy(autoScheduleEnabled = enabled) }
            applySchedule()
            if (enabled) {
                val config = configPrefs.get()
                val scheduler = AutoScheduler(ctx, config, appPrefs.isBackgroundGpsEnabled())
                if (scheduler.isInActiveWindow() && !_state.value.isRecording) {
                    localUi.update { it.copy(showStartNowPrompt = true) }
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
        localUi.update { s ->
            val cur = s.selectedTrackIds
            s.copy(selectedTrackIds = if (trackId in cur) cur - trackId else cur + trackId)
        }
    }

    fun selectAll()       { localUi.update { s -> s.copy(selectedTrackIds = _state.value.recentTracks.map { it.id }.toSet()) } }
    fun clearSelection()  { localUi.update { it.copy(selectedTrackIds = emptySet()) } }
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
            localUi.update { it.copy(selectedTrackIds = emptySet()) }
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
        localUi.update { it.copy(selectedTrackIds = emptySet()) }
    }

    fun exportSelectedOrSnack() {
        val uri = _state.value.gpxExportFolderUri?.toUri()
            ?: run { snackbar.send("Set export folder first"); return }
        exportSelected(uri)
    }

    fun exportAll() {
        val uri = _state.value.gpxExportFolderUri?.toUri()
            ?: run { snackbar.send("Set export folder first"); return }
        val allIds = _state.value.recentTracks.map { it.id }.toSet()
        if (allIds.isEmpty()) return
        enqueueExport(allIds, uri)
    }

    fun shareCurrentTrack() {
        val trackId = _state.value.currentTrackId ?: run {
            snackbar.send("Nothing to share yet")
            return
        }
        viewModelScope.launch { doShare(trackId) }
    }

    fun shareTrack(trackId: Long) {
        viewModelScope.launch { doShare(trackId) }
    }

    private suspend fun doShare(trackId: Long) {
        if (_state.value.pendingPoints > 0) {
            ctx.startService(Intent(ctx, BackgroundService::class.java).apply {
                action = BackgroundService.ACTION_FLUSH
            })
            withTimeoutOrNull(3_000) {
                _state.first { it.pendingPoints == 0 }
            }
        }

        val track    = trackRepository.getTrackById(trackId) ?: return
        val segments = withContext(Dispatchers.IO) { trackRepository.getSegmentsWithPoints(trackId) }
        if (segments.isEmpty()) return

        val file = withContext(Dispatchers.IO) {
            val dir = File(ctx.cacheDir, "gpx_share"); dir.mkdirs()
            val f = File(dir, "${track.name.sanitizeFilename()}.gpx")
            f.outputStream().use { GpxExporter.export(track.name, segments, it) }
            f
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.track.fileprovider", file)
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
                    uris += FileProvider.getUriForFile(ctx, "${ctx.packageName}.track.fileprovider", f)
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

    /**
     * Opens the system map at [fix]'s coordinates. Called from any of the
     * three position lines in the live card — provided, accepted, enqueued.
     */
    fun shareFixAsGeo(fix: TrackPointEntity) {
        ctx.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_VIEW, "geo:${fix.lat},${fix.lon}?z=16".toUri()).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
                "Open location",
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    // ---- GPX export folder ----

    fun setGpxExportFolder(uri: Uri, displayName: String) {
        viewModelScope.launch {
            persistSafGrant(ctx, uri, write = true)
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
