package de.perigon.companion.media.ui.prep

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import de.perigon.companion.core.prefs.AppPrefs
import de.perigon.companion.core.ui.Route
import de.perigon.companion.media.domain.ItemStatus
import de.perigon.companion.media.domain.MediaPrepItem
import de.perigon.companion.media.domain.MediaType
import de.perigon.companion.media.domain.toTransformIntent
import de.perigon.companion.posts.data.MediaSourceStatus
import de.perigon.companion.posts.ui.common.MediaSourceBadge
import de.perigon.companion.core.ui.AppTopBar
import sh.calvin.reorderable.*

@Composable
fun MediaPrepScreen(
    navController: NavController,
    postId:        Long = 0L,
    incomingUris:  List<Uri> = emptyList(),
    viewModel:     MediaPrepViewModel = hiltViewModel(),
) {
    val state        by viewModel.state.collectAsStateWithLifecycle()
    val editPreviews by viewModel.editPreviews.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }
    var activeEditRequest by remember { mutableStateOf<EditRequest?>(null) }

    val useBuiltinPicker = viewModel.mediaPickerMode == AppPrefs.MEDIA_PICKER_BUILTIN

    LaunchedEffect(postId) { viewModel.init(postId) }

    LaunchedEffect(incomingUris) {
        if (incomingUris.isNotEmpty()) viewModel.addItems(incomingUris)
    }

    LaunchedEffect(Unit) {
        viewModel.snackbar.events.collect { snackbarHost.showSnackbar(it) }
    }

    LaunchedEffect(Unit) {
        viewModel.editRequests.collect { request ->
            activeEditRequest = request
        }
    }

    val pickMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris -> if (uris.isNotEmpty()) viewModel.addItems(uris) }

    // Fallback warning dialog
    state.pendingFallbackEdit?.let { request ->
        AlertDialog(
            onDismissRequest = viewModel::dismissFallbackEdit,
            title = { Text("Original file missing") },
            text = { Text("You'll be editing the previously transformed version. Quality may be reduced.") },
            confirmButton = {
                TextButton(onClick = viewModel::confirmFallbackEdit) { Text("Edit anyway") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissFallbackEdit) { Text("Cancel") }
            },
        )
    }

    // Editor — replaces whole screen
    activeEditRequest?.let { request ->
        val item = state.items.find { it.entity.id == request.media.id }
        val mediaType = item?.type ?: if (request.media.mimeType.startsWith("video")) MediaType.VIDEO else MediaType.IMAGE
        val intent = request.media.toTransformIntent()
        MediaEditScreen(
            uri = request.uri,
            mediaType = mediaType,
            initialIntent = if (request.isFallback) de.perigon.companion.media.domain.TransformIntent() else intent,
            onConfirm = { result ->
                viewModel.applyEditResult(request.media.id, result)
                activeEditRequest = null
            },
            onCancel = { activeEditRequest = null },
            onFrameExtracted = { frameUri -> viewModel.addItems(listOf(frameUri)) },
            onError = { msg -> viewModel.snackbar.send(msg) },
        )
        return
    }

    // Local mutable list for drag-to-reorder
    val localItems = remember(state.items) {
        state.items.sortedBy { it.entity.position }
            .distinctBy { it.id }
            .toMutableStateList()
    }

    val gridState = rememberLazyGridState()
    val reorderableState = rememberReorderableLazyGridState(gridState) { from, to ->
        localItems.apply { add(to.index, removeAt(from.index)) }
    }

    LaunchedEffect(reorderableState.isAnyItemDragging) {
        if (!reorderableState.isAnyItemDragging && localItems.isNotEmpty()) {
            viewModel.commitOrder(localItems.map { it.id })
        }
    }

    Scaffold(
        topBar = { AppTopBar(navController) },
        snackbarHost = { SnackbarHost(snackbarHost) },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                if (useBuiltinPicker) {
                    navController.navigate(Route.MediaPicker())
                } else {
                    pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                }
            }) { Icon(Icons.Default.Add, "Add media") }
        },
        bottomBar = {
            if (state.items.isNotEmpty()) {
                BottomAppBar {
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = {
                            navController.navigate(Route.PostEdit(state.postId)) {
                                popUpTo<Route.MediaPrep> { inclusive = true }
                            }
                        },
                        modifier = Modifier.padding(end = 16.dp),
                    ) { Text("Done") }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (state.items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("No media yet", style = MaterialTheme.typography.titleMedium)
                        Text("Tap + to add photos and videos",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    state = gridState,
                    contentPadding = PaddingValues(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(localItems, key = { it.id }) { item ->
                        ReorderableItem(reorderableState, key = item.id) { isDragging ->
                            val elevation by androidx.compose.animation.core.animateDpAsState(
                                if (isDragging) 8.dp else 0.dp, label = "elevation")
                            val resolved = viewModel.resolveDisplayMedia(item.entity)
                            Surface(shadowElevation = elevation) {
                                MediaCell(
                                    item = item,
                                    editPreview = editPreviews[item.id],
                                    displayUri = resolved.uri,
                                    sourceStatus = resolved.status,
                                    onTap = { viewModel.requestEdit(item.entity) },
                                    onRemove = { viewModel.removeItem(item.id) },
                                    dragHandle = { modifier ->
                                        Box(modifier.draggableHandle()) {
                                            Icon(
                                                Icons.Default.DragHandle, "Drag to reorder",
                                                modifier = Modifier.size(16.dp).align(Alignment.Center),
                                                tint = Color.White,
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaCell(
    item:         MediaPrepItem,
    editPreview:  android.graphics.Bitmap?,
    displayUri:   Uri,
    sourceStatus: MediaSourceStatus,
    onTap:        () -> Unit,
    onRemove:     () -> Unit,
    dragHandle:   @Composable (Modifier) -> Unit,
    modifier:     Modifier = Modifier,
) {
    val isVideo = item.type == MediaType.VIDEO
    val hasEdits = item.hasEdits

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clickable(onClick = onTap),
    ) {
        if (editPreview != null) {
            androidx.compose.foundation.Image(
                bitmap = editPreview.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            AsyncImage(
                model = displayUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }

        MediaSourceBadge(status = sourceStatus)

        if (isVideo) {
            Icon(Icons.Default.Videocam, null, tint = Color.White,
                modifier = Modifier.align(Alignment.TopStart).padding(4.dp).size(18.dp))
        }

        if (hasEdits) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
            ) {
                Row(
                    Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (item.entity.hasCrop) {
                        Icon(Icons.Default.Crop, null, tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(12.dp))
                    }
                    if (item.entity.hasOrientation || item.entity.hasFineRotation) {
                        Icon(Icons.AutoMirrored.Filled.RotateRight, null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(12.dp))
                    }
                    if (isVideo && item.entity.hasTrim) {
                        Icon(Icons.Default.ContentCut, null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(12.dp))
                    }
                }
            }
        }

        IconButton(onClick = onRemove, modifier = Modifier.align(Alignment.TopEnd).size(32.dp)) {
            Icon(Icons.Default.Close, null, tint = Color.White, modifier = Modifier.size(18.dp))
        }

        dragHandle(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(24.dp)
                .background(Color.Black.copy(alpha = 0.35f))
        )
    }
}
