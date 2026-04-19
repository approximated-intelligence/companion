package de.perigon.companion.track.ui.render

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import de.perigon.companion.core.prefs.AppPrefs
import de.perigon.companion.track.domain.GpxTrack
import de.perigon.companion.core.ui.AppTopBar

@Composable
fun TrackRenderScreen(
    navController:         NavController,
    onNavigateToMediaPrep: (Uri) -> Unit,
    incomingGpxUris:       List<Uri> = emptyList(),
    viewModel:             TrackRenderViewModel = hiltViewModel(),
) {
    val state     by viewModel.state.collectAsStateWithLifecycle()
    val tileState by viewModel.tileState.collectAsStateWithLifecycle()

    LaunchedEffect(incomingGpxUris) {
        if (incomingGpxUris.isNotEmpty() && state is TrackRenderUiState.Idle) {
            viewModel.loadGpx(incomingGpxUris)
        }
    }

    val gpxPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.loadGpx(uris)
    }

    val tilePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val display = uri.lastPathSegment?.substringAfterLast('/') ?: uri.toString()
        viewModel.setTileSource(uri, display)
    }

    Scaffold(
        topBar = { AppTopBar(navController) },
    ) { padding ->
        Box(
            modifier = Modifier.padding(padding).padding(16.dp).fillMaxSize()
        ) {
            when (val s = state) {
                is TrackRenderUiState.Idle      -> IdleContent(
                    onPickFiles    = { gpxPicker.launch(arrayOf("application/gpx+xml", "application/octet-stream", "*/*")) },
                    onPickRecorded = { viewModel.loadRecordedTracks(it) },
                    viewModel      = viewModel,
                    tileState      = tileState,
                    onPickTileFile = { tilePicker.launch(arrayOf("application/octet-stream", "application/x-sqlite3", "*/*")) },
                )
                is TrackRenderUiState.Parsing   -> LoadingContent("Parsing GPX…")
                is TrackRenderUiState.Loaded    -> LoadedContent(
                    tracks         = s.tracks,
                    onRender       = { viewModel.render() },
                    onReset        = { viewModel.reset() },
                    tileState      = tileState,
                    viewModel      = viewModel,
                    onPickTileFile = { tilePicker.launch(arrayOf("application/octet-stream", "application/x-sqlite3", "*/*")) },
                )
                is TrackRenderUiState.Rendering -> LoadingContent("Rendering track…")
                is TrackRenderUiState.Done      -> DoneContent(
                    outputUri   = s.outputUri,
                    tracks      = s.tracks,
                    onAddToPost = { onNavigateToMediaPrep(s.outputUri) },
                    onReset     = { viewModel.reset() },
                )
                is TrackRenderUiState.Error     -> ErrorContent(s.message, onRetry = { viewModel.reset() })
            }
        }
    }
}

@Composable
private fun TileSourceSection(
    tileState:      TileSourceState,
    viewModel:      TrackRenderViewModel,
    onPickTileFile: () -> Unit,
) {
    Text("Map tiles", style = MaterialTheme.typography.titleMedium)
    Text(
        "Configure tile sources for track rendering backgrounds. Both can coexist — toggle to choose the active source.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (tileState.hasLocalTiles && tileState.hasNetworkTiles) {
        Text("Active source", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = tileState.mode == AppPrefs.TILE_MODE_LOCAL,
                onClick  = { viewModel.setTileSourceMode(AppPrefs.TILE_MODE_LOCAL) },
                label    = { Text("Local file") },
            )
            FilterChip(
                selected = tileState.mode == AppPrefs.TILE_MODE_NETWORK,
                onClick  = { viewModel.setTileSourceMode(AppPrefs.TILE_MODE_NETWORK) },
                label    = { Text("Network") },
            )
        }
    }

    OutlinedTextField(
        value         = tileState.networkUrl,
        onValueChange = viewModel::setTileSourceNetworkUrl,
        label         = { Text("Network tile URL") },
        placeholder   = { Text("https://tile.example.com/{z}/{x}/{y}.png") },
        singleLine    = true,
        modifier      = Modifier.fillMaxWidth(),
        supportingText = {
            Text("Use {z}/{x}/{y} for XYZ or {q} for quadkey.")
        },
    )

    TileSourceRow(
        configured = tileState.hasLocalTiles,
        label      = tileState.localLabel,
        onSelect   = onPickTileFile,
        onClear    = viewModel::clearTileSourceLocal,
    )
}

@Composable
private fun IdleContent(
    onPickFiles:    () -> Unit,
    onPickRecorded: (List<Long>) -> Unit,
    viewModel:      TrackRenderViewModel,
    tileState:      TileSourceState,
    onPickTileFile: () -> Unit,
) {
    var showTrackPicker by remember { mutableStateOf(false) }

    Column(
        modifier            = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.weight(1f))

        Icon(Icons.Default.FolderOpen, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Text("Select tracks to render", style = MaterialTheme.typography.titleMedium)
        Button(onClick = onPickFiles) { Text("Open GPX file(s)") }

        if (viewModel.backgroundGpsEnabled) {
            OutlinedButton(onClick = { showTrackPicker = true }) {
                Icon(Icons.Default.Route, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Select recorded track(s)")
            }
        }

        Spacer(Modifier.weight(1f))

        HorizontalDivider()

        TileSourceSection(
            tileState      = tileState,
            viewModel      = viewModel,
            onPickTileFile = onPickTileFile,
        )
    }

    if (showTrackPicker) {
        RecordedTrackPickerDialog(
            viewModel = viewModel,
            onSelect  = { trackIds -> showTrackPicker = false; onPickRecorded(trackIds) },
            onDismiss = { showTrackPicker = false },
        )
    }
}

@Composable
private fun RecordedTrackPickerDialog(
    viewModel: TrackRenderViewModel,
    onSelect: (List<Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    val tracks by viewModel.recordedTracks.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf(linkedSetOf<Long>()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select recorded tracks") },
        text = {
            if (tracks.isEmpty()) {
                Text("No recorded tracks yet.")
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier            = Modifier.heightIn(max = 400.dp),
                ) {
                    items(tracks, key = { it.id }) { track ->
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable {
                                selected = LinkedHashSet(if (track.id in selected) selected - track.id else selected + track.id)
                            },
                            color = if (track.id in selected) MaterialTheme.colorScheme.secondaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Row(
                                modifier              = Modifier.padding(12.dp),
                                verticalAlignment     = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Checkbox(
                                    checked         = track.id in selected,
                                    onCheckedChange = {
                                        selected = LinkedHashSet(if (it) selected + track.id else selected - track.id)
                                    },
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(track.name, style = MaterialTheme.typography.bodyMedium)
                                    Text("${track.date} · ${track.pointCount} pts",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick  = { if (selected.isNotEmpty()) onSelect(selected.toList()) },
                enabled  = selected.isNotEmpty(),
            ) { Text("Render ${if (selected.size > 1) "${selected.size} tracks" else "track"}") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun LoadingContent(label: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CircularProgressIndicator()
            Text(label)
        }
    }
}

@Composable
private fun LoadedContent(
    tracks:         List<GpxTrack>,
    onRender:       () -> Unit,
    onReset:        () -> Unit,
    tileState:      TileSourceState,
    viewModel:      TrackRenderViewModel,
    onPickTileFile: () -> Unit,
) {
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        tracks.forEach { track -> TrackStats(track) }

        HorizontalDivider()

        TileSourceSection(
            tileState      = tileState,
            viewModel      = viewModel,
            onPickTileFile = onPickTileFile,
        )

        HorizontalDivider()

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onReset) { Text("Reset") }
            Button(onClick = onRender, modifier = Modifier.weight(1f)) {
                Text(if (tracks.size > 1) "Render ${tracks.size} tracks" else "Render track map")
            }
        }
    }
}

@Composable
private fun DoneContent(
    outputUri:   Uri,
    tracks:      List<GpxTrack>,
    onAddToPost: () -> Unit,
    onReset:     () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AsyncImage(
            model              = outputUri,
            contentDescription = "Rendered track map",
            contentScale       = ContentScale.Fit,
            modifier           = Modifier.fillMaxWidth().aspectRatio(1f),
        )
        tracks.forEach { track -> TrackStats(track) }
        Text("Saved to DCIM/PostMedia", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onReset) {
                Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("New track")
            }
            Button(onClick = onAddToPost, modifier = Modifier.weight(1f)) { Text("Add to post") }
        }
    }
}

@Composable
private fun ErrorContent(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Error", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
            Text(message)
            Button(onClick = onRetry) { Text("Try again") }
        }
    }
}

@Composable
private fun TrackStats(track: GpxTrack) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            StatItem("Points",   track.points.size.toString())
            StatItem("Distance", "%.1f km".format(track.distanceMetres / 1000.0))
            if (track.name.isNotBlank()) StatItem("Name", track.name)
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            tint = if (configured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Local tile file", style = MaterialTheme.typography.bodyMedium)
            Text(if (configured) label else "Not configured",
                style = MaterialTheme.typography.bodySmall,
                color = if (configured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        if (configured) {
            FilledTonalButton(onClick = onClear) { Text("Clear") }
            Spacer(Modifier.width(4.dp))
        }
        FilledTonalButton(onClick = onSelect) {
            Icon(Icons.Default.Map, null, Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(if (configured) "Change" else "Select")
        }
    }
}
