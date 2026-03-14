package de.perigon.companion.track.ui.render

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.perigon.companion.core.prefs.AppPrefs
import de.perigon.companion.media.data.MediaStoreWriter
import de.perigon.companion.track.data.TrackRepository
import de.perigon.companion.track.data.TrackSummary
import de.perigon.companion.track.domain.GpxTrack
import de.perigon.companion.track.domain.toGpxTrack
import de.perigon.companion.track.network.TileSource
import de.perigon.companion.track.domain.parseGpx
import de.perigon.companion.track.domain.renderTracks
import de.perigon.companion.core.ui.SnackbarChannel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@Immutable
sealed class TrackRenderUiState {
    data object Idle                                              : TrackRenderUiState()
    data object Parsing                                          : TrackRenderUiState()
    data class Loaded(val tracks: List<GpxTrack>)                : TrackRenderUiState()
    data object Rendering                                        : TrackRenderUiState()
    data class Done(val outputUri: Uri, val tracks: List<GpxTrack>) : TrackRenderUiState()
    data class Error(val message: String)                        : TrackRenderUiState()
}

@HiltViewModel
class TrackRenderViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val appPrefs: AppPrefs,
    private val trackRepository: TrackRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<TrackRenderUiState>(TrackRenderUiState.Idle)
    val state: StateFlow<TrackRenderUiState> = _state.asStateFlow()

    val snackbar = SnackbarChannel()

    val backgroundGpsEnabled: Boolean get() = appPrefs.isBackgroundGpsEnabled()

    val recordedTracks: StateFlow<List<TrackSummary>> =
        trackRepository.observeSummaries()
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private fun loadTileSource(): TileSource? {
        val uriStr = appPrefs.tileSourceUri() ?: return null
        val uri = Uri.parse(uriStr)
        return try {
            context.contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            TileSource.open(context.contentResolver, uri)
        } catch (e: Exception) {
            android.util.Log.w("TrackRender", "Failed to open tile source: ${e.message}")
            snackbar.send("Tile source unavailable — re-select it in Settings")
            null
        }
    }

    fun loadGpx(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _state.value = TrackRenderUiState.Parsing
            _state.value = withContext(Dispatchers.IO) {
                try {
                    val tracks = uris.mapNotNull { uri ->
                        context.contentResolver.openInputStream(uri)?.use { parseGpx(it) }
                            ?.takeIf { !it.isEmpty }
                    }
                    if (tracks.isEmpty()) TrackRenderUiState.Error("No valid track points found")
                    else TrackRenderUiState.Loaded(tracks)
                } catch (e: Exception) {
                    TrackRenderUiState.Error(e.message ?: "Failed to parse GPX")
                }
            }
        }
    }

    fun loadRecordedTracks(trackIds: List<Long>) {
        if (trackIds.isEmpty()) return
        viewModelScope.launch {
            _state.value = TrackRenderUiState.Parsing
            _state.value = withContext(Dispatchers.IO) {
                try {
                    val tracks = trackIds.mapNotNull { id ->
                        trackRepository.getTrackWithSegments(id)?.toGpxTrack()?.takeIf { !it.isEmpty }
                    }
                    if (tracks.isEmpty()) TrackRenderUiState.Error("No track points found")
                    else TrackRenderUiState.Loaded(tracks)
                } catch (e: Exception) {
                    TrackRenderUiState.Error(e.message ?: "Failed to load tracks")
                }
            }
        }
    }

    fun render() {
        val loaded = _state.value as? TrackRenderUiState.Loaded ?: return
        viewModelScope.launch {
            _state.value = TrackRenderUiState.Rendering
            _state.value = withContext(Dispatchers.IO) {
                try {
                    val tileSource = loadTileSource()
                    val bitmap = try {
                        renderTracks(loaded.tracks, tileSource)
                    } finally {
                        tileSource?.close()
                    }

                    val name = "TRK_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}_s.jpg"
                    val bytes = ByteArrayOutputStream()
                        .also { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
                        .toByteArray()
                    bitmap.recycle()

                    val uri = MediaStoreWriter.insertImage(
                        context      = context,
                        displayName  = name,
                        mimeType     = "image/jpeg",
                        relativePath = "DCIM/PostMedia",
                        source       = ByteArrayInputStream(bytes),
                    )

                    TrackRenderUiState.Done(uri, loaded.tracks)
                } catch (e: Exception) {
                    TrackRenderUiState.Error(e.message ?: "Render failed")
                }
            }
        }
    }

    fun reset() { _state.value = TrackRenderUiState.Idle }
}
