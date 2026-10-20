package de.perigon.companion.posts.ui.prep

import de.perigon.companion.media.ui.edit.MediaEditScreen

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
import de.perigon.companion.posts.data.PostsPrefs
import de.perigon.companion.posts.PostsRoute as Route
import de.perigon.companion.posts.domain.ItemStatus
import de.perigon.companion.posts.domain.MediaPrepItem
import de.perigon.companion.core.transform.MediaType
import de.perigon.companion.posts.domain.toTransformIntent
import de.perigon.companion.posts.data.MediaSourceStatus
import de.perigon.companion.posts.data.ResolvedMedia
import de.perigon.companion.posts.ui.common.MediaSourceBadge
import de.perigon.companion.core.ui.AppTopBar
import sh.calvin.reorderable.*

/**
 * Incoming URIs (share intent, built-in picker, track render) are delivered by
 * NavGraph straight into the ViewModel; the post id comes from the route via
 * the ViewModel's SavedStateHandle. [onDone] leaves for the post editor —
 * where to go is NavGraph's decision, not this screen's.
 */
@Composable
fun MediaPrepScreen(
    navController: NavController,
    onDone:        (postId: Long) -> Unit,
    viewModel:     MediaPrepViewModel = hiltViewModel(),
) {
    val state         by viewModel.state.collectAsStateWithLifecycle()
    val editPreviews  by viewModel.editPreviews.collectAsStateWithLifecycle()
    val resolvedMedia by viewModel.resolvedMedia.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }
    var activeEditRequest by remember { mutableStateOf<EditRequest?>(null) }

    val useBuiltinPicker = viewModel.mediaPickerMode == PostsPrefs.MEDIA_PICKER_BUILTIN

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

    // Editor — replaces whole screen
    activeEditRequest?.let { request ->
        val item = state.items.find { it.entity.id == request.media.id }
        val mediaType = item?.type ?: if (request.media.mimeType.startsWith("video")) MediaType.VIDEO else MediaType.IMAGE
        MediaEditScreen(
            uri = request.uri,
            mediaType = mediaType,
            initialIntent = request.media.toTransformIntent(),
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

    // Commit order only after a real drag. Keying the effect on
    // isAnyItemDragging alone fired at initial composition (false) and wrote
    // the DB on every screen open.
    var everDragged by remember { mutableStateOf(false) }
    LaunchedEffect(reorderableState.isAnyItemDragging) {
        if (reorderableState.isAnyItemDragging) {
            everDragged = true
        } else if (everDragged && localItems.isNotEmpty()) {
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
                        onClick = { onDone(state.postId) },
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
                            val resolved = resolvedMedia[item.entity.id]
                                ?: ResolvedMedia(Uri.EMPTY, MediaSourceStatus.GONE)
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
    val isGone = sourceStatus == MediaSourceStatus.GONE

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clickable(onClick = onTap),
    ) {
        when {
            editPreview != null -> androidx.compose.foundation.Image(
                bitmap = editPreview.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // Broken state is shown, not hidden: the file this row points at
            // could not be resolved at all.
            isGone -> Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.BrokenImage, "Media file missing",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(28.dp),
                )
            }
            else -> AsyncImage(
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

        IconButton(onClick = onRemove, modifier = Modifier.align(Alignment.TopEnd).size(40.dp)) {
            Icon(Icons.Default.Close, "Remove", tint = Color.White, modifier = Modifier.size(18.dp))
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
