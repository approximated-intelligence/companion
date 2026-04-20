package de.perigon.companion.audio.ui.recording

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import de.perigon.companion.audio.data.AudioConfigEntity
import de.perigon.companion.audio.data.AudioConfigPrefs
import de.perigon.companion.audio.data.AudioFormat
import de.perigon.companion.audio.data.AudioPreset
import de.perigon.companion.audio.data.AudioRecordingEntity
import de.perigon.companion.audio.data.AudioRepository
import de.perigon.companion.audio.service.AudioRecordingService
import de.perigon.companion.core.ui.SnackbarChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@Immutable
data class AudioRecordingUiState(
    val isRecording:        Boolean = false,
    val isPaused:           Boolean = false,
    val amplitudeDb:        Int     = -100,
    val elapsedMs:          Long    = 0L,
    val currentRecordingId: Long    = 0L,
    val config:             AudioConfigEntity = AudioConfigEntity.DEFAULT,
    val recordings:         List<AudioRecordingEntity> = emptyList(),
    val selectedIds:        Set<Long> = emptySet(),
    val folderUri:          String? = null,
    val folderLabel:        String? = null,
)

@HiltViewModel
class AudioRecordingViewModel @Inject constructor(
    @param:ApplicationContext private val ctx: Context,
    private val configPrefs: AudioConfigPrefs,
    private val repository:  AudioRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AudioRecordingUiState(
        folderUri   = configPrefs.folderUri(),
        folderLabel = configPrefs.folderLabel(),
    ))
    val state: StateFlow<AudioRecordingUiState> = _state.asStateFlow()

    val snackbar = SnackbarChannel()

    init {
        viewModelScope.launch {
            combine(
                AudioRecordingService.status,
                configPrefs.observe(),
                repository.observeAll(),
            ) { status, config, recordings ->
                AudioRecordingUiState(
                    isRecording        = status.isRecording,
                    isPaused           = status.isPaused,
                    amplitudeDb        = status.amplitudeDb,
                    elapsedMs          = status.elapsedMs,
                    currentRecordingId = status.currentRecordingId,
                    config             = config,
                    recordings         = recordings,
                    selectedIds        = _state.value.selectedIds,
                    folderUri          = configPrefs.folderUri(),
                    folderLabel        = configPrefs.folderLabel(),
                )
            }.collect { _state.value = it }
        }

        viewModelScope.launch(Dispatchers.IO) { finaliseOrphaned() }
    }

    // ---- Recording control ----

    fun startRecording() {
        if (_state.value.folderUri == null) {
            snackbar.send("Set a storage folder first")
            return
        }
        ctx.startForegroundService(Intent(ctx, AudioRecordingService::class.java).apply {
            action = AudioRecordingService.ACTION_START
        })
    }

    fun pause() {
        ctx.startService(Intent(ctx, AudioRecordingService::class.java).apply {
            action = AudioRecordingService.ACTION_PAUSE
        })
    }

    fun resume() {
        ctx.startService(Intent(ctx, AudioRecordingService::class.java).apply {
            action = AudioRecordingService.ACTION_RESUME
        })
    }

    fun stop() {
        ctx.startService(Intent(ctx, AudioRecordingService::class.java).apply {
            action = AudioRecordingService.ACTION_STOP
        })
    }

    // ---- Config: preset-bound fields switch preset to CUSTOM ----

    fun setPreset(preset: AudioPreset) {
        viewModelScope.launch {
            configPrefs.update { current ->
                if (preset == AudioPreset.CUSTOM) current.copy(preset = AudioPreset.CUSTOM)
                else current.applyingPreset(preset)
            }
        }
    }

    fun setFormat(format: AudioFormat) {
        viewModelScope.launch {
            configPrefs.update { it.copy(format = format, preset = AudioPreset.CUSTOM) }
        }
    }

    fun setSampleRate(hz: Int) { viewModelScope.launch {
        configPrefs.update { it.copy(sampleRateHz = hz, preset = AudioPreset.CUSTOM) } } }

    fun setBitrate(bps: Int) { viewModelScope.launch {
        configPrefs.update { it.copy(bitrateBps = bps, preset = AudioPreset.CUSTOM) } } }

    fun setNoiseSuppression(on: Boolean) { viewModelScope.launch {
        configPrefs.update { it.copy(noiseSuppression = on, preset = AudioPreset.CUSTOM) } } }

    fun setAutoGain(on: Boolean) { viewModelScope.launch {
        configPrefs.update { it.copy(autoGain = on, preset = AudioPreset.CUSTOM) } } }

    // ---- Config: recording-only fields don't affect preset ----

    fun setShowLevel(on: Boolean) { viewModelScope.launch {
        configPrefs.update { it.copy(showLevel = on) } } }

    // ---- Folder ----

    fun setFolder(uri: Uri, displayName: String) {
        viewModelScope.launch {
            ctx.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            configPrefs.setFolder(uri.toString(), displayName)
            _state.update { it.copy(folderUri = uri.toString(), folderLabel = displayName) }
        }
    }

    fun clearFolder() {
        viewModelScope.launch {
            configPrefs.clearFolder()
            _state.update { it.copy(folderUri = null, folderLabel = null) }
        }
    }

    // ---- Selection ----

    fun toggleSelection(id: Long) {
        _state.update { s ->
            val cur = s.selectedIds
            s.copy(selectedIds = if (id in cur) cur - id else cur + id)
        }
    }

    fun clearSelection() { _state.update { it.copy(selectedIds = emptySet()) } }

    fun toggleSelectAll() {
        val all = _state.value.recordings.map { it.id }.toSet()
        _state.update { s ->
            s.copy(selectedIds = if (s.selectedIds == all) emptySet() else all)
        }
    }

    // ---- Actions ----

    fun rename(id: Long, name: String) {
        viewModelScope.launch { repository.rename(id, name) }
    }

    fun delete(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val rec = repository.getById(id) ?: return@launch
            try {
                ctx.contentResolver.delete(Uri.parse(rec.uri), null, null)
            } catch (_: Exception) {}
            repository.delete(id)
            snackbar.send("Recording deleted")
        }
    }

    fun deleteSelected() {
        val ids = _state.value.selectedIds
        viewModelScope.launch(Dispatchers.IO) {
            for (id in ids) {
                val rec = repository.getById(id) ?: continue
                try { ctx.contentResolver.delete(Uri.parse(rec.uri), null, null) } catch (_: Exception) {}
                repository.delete(id)
            }
            _state.update { it.copy(selectedIds = emptySet()) }
            snackbar.send("${ids.size} recording(s) deleted")
        }
    }

    fun play(id: Long) {
        viewModelScope.launch {
            val rec = repository.getById(id) ?: return@launch
            val uri = Uri.parse(rec.uri)
            val mime = AudioFormat.ALL.firstOrNull { it.name == rec.format }?.mimeType ?: "audio/*"
            try {
                ctx.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, mime)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        },
                        "Play audio",
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (_: Exception) {
                snackbar.send("No app can play this file")
            }
        }
    }

    fun share(id: Long) {
        viewModelScope.launch {
            val rec = repository.getById(id) ?: return@launch
            val mime = AudioFormat.ALL.firstOrNull { it.name == rec.format }?.mimeType ?: "audio/*"
            ctx.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = mime
                        putExtra(Intent.EXTRA_STREAM, Uri.parse(rec.uri))
                        putExtra(Intent.EXTRA_SUBJECT, rec.name)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                    "Share recording",
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    // ---- Orphan recovery ----

    private suspend fun finaliseOrphaned() {
        val orphans = repository.getOrphaned()
        for (o in orphans) {
            val (duration, size) = try {
                val uri = Uri.parse(o.uri)
                val sizeBytes = ctx.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
                val durationMs = probeDurationMs(uri) ?: 0L
                durationMs to sizeBytes
            } catch (_: Exception) {
                0L to 0L
            }
            repository.finalize(o.id, duration, size)
        }
        if (orphans.isNotEmpty()) snackbar.send("${orphans.size} previous recording(s) recovered")
    }

    private fun probeDurationMs(uri: Uri): Long? = try {
        val mmr = android.media.MediaMetadataRetriever()
        mmr.setDataSource(ctx, uri)
        val d = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        mmr.release()
        d
    } catch (_: Exception) { null }
}
