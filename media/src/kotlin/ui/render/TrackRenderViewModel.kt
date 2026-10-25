package de.perigon.companion.media.ui.render

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.perigon.companion.core.feature.RecordedTrackSource
import de.perigon.companion.core.feature.RecordedTrackSummary
import de.perigon.companion.core.prefs.FolderGrants
import de.perigon.companion.core.prefs.FolderKeys
import de.perigon.companion.core.saf.openInputStreamRaw
import de.perigon.companion.core.saf.persistSafGrant
import de.perigon.companion.core.saf.writeFileFromStream
import de.perigon.companion.core.ui.SnackbarChannel
import de.perigon.companion.media.data.RenderPrefs
import de.perigon.companion.media.domain.GpxTrack
import de.perigon.companion.media.domain.parseGpx
import de.perigon.companion.media.domain.renderTracks
import de.perigon.companion.media.network.NetworkTileSource
import de.perigon.companion.media.network.SqliteTileSource
import de.perigon.companion.media.network.TileSource
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Optional
import javax.inject.Inject

/** Renders are plain files in DCIM/RenderedTracks: browsable, and indexed by the gallery. */
private const val RENDERS_FOLDER = "RenderedTracks"

@Immutable
sealed class TrackRenderUiState {
    data object Idle                                                 : TrackRenderUiState()
    data object Parsing                                              : TrackRenderUiState()
    data class  Loaded(val tracks: List<GpxTrack>)                  : TrackRenderUiState()
    data object Rendering                                            : TrackRenderUiState()
    data class  Done(
        val outputUri: Uri,
        val tracks: List<GpxTrack>,
        // Counts from the stitcher; the screen warns on failedTiles only.
        // missingTiles covers genuine coverage gaps (normal for an offline
        // map at 5 min hardware rate covering a tiny high-zoom area) and is
        // silent.
        val missingTiles: Int = 0,
        val failedTiles:  Int = 0,
    ) : TrackRenderUiState()
    data class  Error(val message: String)                          : TrackRenderUiState()
}

data class TileSourceState(
    val hasLocalTiles:        Boolean = false,
    val localLabel:           String  = "",
    val hasNetworkTiles:      Boolean = false,
    val networkUrl:           String  = "",
    val mode:                 String  = RenderPrefs.TILE_MODE_LOCAL,
)

@HiltViewModel
class TrackRenderViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val renderPrefs: RenderPrefs,
    private val recordedTrackSource: Optional<RecordedTrackSource>,
    private val folderGrants: FolderGrants,
) : ViewModel() {

    private val _state = MutableStateFlow<TrackRenderUiState>(TrackRenderUiState.Idle)
    val state: StateFlow<TrackRenderUiState> = _state.asStateFlow()

    private val _tileState = MutableStateFlow(buildTileState())
    val tileState: StateFlow<TileSourceState> = _tileState.asStateFlow()

    val snackbar = SnackbarChannel()

    val recordedTracksAvailable: Boolean get() = recordedTrackSource.isPresent

    val recordedTracks: StateFlow<List<RecordedTrackSummary>> =
        (recordedTrackSource.orElse(null)?.observeSummaries() ?: flowOf(emptyList()))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // --- tile source state ---

    private fun buildTileState(): TileSourceState {
        val localUri   = renderPrefs.tileSourceUri()
        val networkUrl = renderPrefs.tileSourceNetworkUrl()
        return TileSourceState(
            hasLocalTiles   = localUri != null,
            localLabel      = renderPrefs.tileSourceLabel() ?: "",
            hasNetworkTiles = networkUrl?.isNotBlank() == true,
            networkUrl      = networkUrl ?: "",
            mode            = renderPrefs.tileSourceMode(),
        )
    }

    // --- tile source mutations ---

    fun setTileSource(uri: Uri, displayName: String) {
        viewModelScope.launch {
            persistSafGrant(context, uri)
            val copied = withContext(Dispatchers.IO) {
                val cacheFile = SqliteTileSource.cacheFile(context.cacheDir)
                val input = openInputStreamRaw(context, uri) ?: return@withContext false
                input.use { cacheFile.outputStream().use { out -> it.copyTo(out) } }
                true
            }
            if (!copied) { snackbar.send("Cannot read tile file"); return@launch }
            renderPrefs.setTileSource(uri.toString(), displayName)
            _tileState.value = buildTileState()
            snackbar.send("Tile source set: $displayName")
        }
    }

    fun setTileSourceNetworkUrl(url: String) {
        _tileState.update { it.copy(networkUrl = url) }
        viewModelScope.launch {
            val trimmed = url.trim()
            if (trimmed.isNotBlank()) {
                renderPrefs.setTileSourceNetwork(trimmed)
            } else {
                renderPrefs.clearTileSourceNetwork()
            }
            _tileState.value = buildTileState()
        }
    }

    fun setTileSourceMode(mode: String) {
        viewModelScope.launch {
            renderPrefs.setTileSourceMode(mode)
            _tileState.value = buildTileState()
        }
    }

    fun clearTileSourceLocal() {
        viewModelScope.launch {
            SqliteTileSource.cacheFile(context.cacheDir).delete()
            renderPrefs.clearTileSourceLocal()
            _tileState.value = buildTileState()
            snackbar.send("Local tile source cleared")
        }
    }

    fun clearTileSourceNetwork() {
        viewModelScope.launch {
            renderPrefs.clearTileSourceNetwork()
            _tileState.value = buildTileState()
            snackbar.send("Network tile source cleared")
        }
    }

    // --- tile source resolution ---

    private fun loadTileSource(): TileSource? = when (renderPrefs.tileSourceMode()) {
        RenderPrefs.TILE_MODE_NETWORK -> {
            val url = renderPrefs.tileSourceNetworkUrl()
            if (url != null) NetworkTileSource(url) else loadLocalTileSource()
        }
        else -> loadLocalTileSource()
    }

    private fun loadLocalTileSource(): TileSource? {
        if (renderPrefs.tileSourceUri() == null) return null
        val cacheFile = SqliteTileSource.cacheFile(context.cacheDir)
        if (!cacheFile.exists()) return null
        return SqliteTileSource.open(cacheFile)
    }

    fun loadGpx(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _state.value = TrackRenderUiState.Parsing
            _state.value = withContext(Dispatchers.IO) {
                try {
                    val tracks = uris.mapNotNull { uri ->
                        openInputStreamRaw(context, uri)?.use { parseGpx(it) }
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
        val source = recordedTrackSource.orElse(null)
        if (source == null || trackIds.isEmpty()) return
        viewModelScope.launch {
            _state.value = TrackRenderUiState.Parsing
            _state.value = withContext(Dispatchers.IO) {
                try {
                    val tracks = trackIds.mapNotNull { id ->
                        source.renderable(id)?.let { GpxTrack(segments = it.segments, firstTimestamp = it.firstTimestamp, name = it.name) }
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
                    val result = try {
                        renderTracks(loaded.tracks, tileSource)
                    } finally {
                        tileSource?.close()
                    }
                    val name  = "TRK_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
                    val bytes = ByteArrayOutputStream()
                        .also { result.bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
                        .toByteArray()
                    result.bitmap.recycle()
                    val dcim = folderGrants.uri(FolderKeys.DCIM)?.let(Uri::parse)
                        ?: error("DCIM folder not granted")
                    val uri = writeFileFromStream(
                        context, dcim, listOf(RENDERS_FOLDER), name, "image/jpeg", ByteArrayInputStream(bytes),
                    )
                    TrackRenderUiState.Done(
                        outputUri = uri,
                        tracks = loaded.tracks,
                        missingTiles = result.missingTiles,
                        failedTiles = result.failedTiles,
                    )
                } catch (e: Exception) {
                    TrackRenderUiState.Error(e.message ?: "Render failed")
                }
            }
        }
    }

    fun reset() { _state.value = TrackRenderUiState.Idle }
}
