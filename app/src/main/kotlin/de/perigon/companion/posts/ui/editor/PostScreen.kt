package de.perigon.companion.posts.ui.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import de.perigon.companion.posts.data.PostMediaEntity
import de.perigon.companion.posts.data.MediaSourceStatus
import de.perigon.companion.posts.data.ResolvedMedia
import de.perigon.companion.core.ui.AppTopBar
import de.perigon.companion.core.ui.Route
import de.perigon.companion.posts.ui.common.MediaSourceBadge
import sh.calvin.reorderable.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostScreen(
    navController:  NavController,
    postId:         Long          = 0L,
    onNavigateBack: () -> Unit,
    onPublished:    () -> Unit,
    vm:             PostViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    LaunchedEffect(postId) { if (postId != 0L) vm.loadDraft(postId) }

    val state         by vm.state.collectAsStateWithLifecycle()
    val mediaStatuses by vm.mediaStatuses.collectAsStateWithLifecycle()
    val editPreviews  by vm.editPreviews.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }
    var showCalendar by remember { mutableStateOf(false) }
    var tagInput     by remember { mutableStateOf("") }

    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            vm.retryPublish { onPublished() }
        }
    }

    LaunchedEffect(Unit) {
        vm.snackbar.events.collect { snackbarHost.showSnackbar(it) }
    }

    LaunchedEffect(Unit) {
        vm.shareIntents.collect { intent -> context.startActivity(intent) }
    }

    LaunchedEffect(Unit) {
        vm.needsPermission.collect {
            snackbarHost.showSnackbar("PostMedia folder permission lost — please re-select the folder")
            folderPicker.launch(null)
        }
    }

    Scaffold(
        modifier     = Modifier.imePadding(),
        topBar       = { AppTopBar(navController) },
        snackbarHost = { SnackbarHost(snackbarHost) },
        floatingActionButton = {
            if (imeVisible && !state.isSaving) {
                SmallFloatingActionButton(
                    onClick        = { vm.saveDraft { onPublished() } },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor   = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Icon(Icons.Default.Save, "Save & go to posts")
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MediaCarousel(
                items         = state.mediaItems,
                pinnedHash    = state.pinnedHash,
                motto         = state.motto,
                editPreviews  = editPreviews,
                mediaStatuses = mediaStatuses,
                resolveMedia  = vm::resolveMedia,
                onPin         = vm::pinMedia,
                onRemove      = vm::removeMedia,
                onCommitOrder = vm::commitMediaOrder,
                onTapMedia    = {
                    val currentPostId = state.id
                    if (currentPostId != 0L) {
                        navController.navigate(Route.MediaPrep(postId = currentPostId))
                    }
                },
            )

            OutlinedButton(
                onClick  = {
                    val currentPostId = state.id
                    if (currentPostId == 0L) {
                        vm.saveDraft {
                            val savedId = vm.state.value.id
                            if (savedId != 0L) {
                                navController.navigate(Route.MediaPrep(postId = savedId))
                            }
                        }
                    } else {
                        navController.navigate(Route.MediaPrep(postId = currentPostId))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (state.mediaItems.isEmpty()) "Add media" else "Add more media")
            }

            DateRow(
                localDate      = state.localDate,
                dayNumber      = state.dayNumber,
                enabled        = !state.isPublished,
                onPrev         = { vm.setDate(stepDate(state.localDate, -1)) },
                onNext         = { vm.setDate(stepDate(state.localDate, +1)) },
                onToday        = { vm.setDate(LocalDate.now().toString()) },
                onYesterday    = { vm.setDate(LocalDate.now().minusDays(1).toString()) },
                onOpenCalendar = { showCalendar = true },
            )

            OutlinedTextField(
                value           = state.motto,
                onValueChange   = vm::setMotto,
                label           = { Text("Motto") },
                placeholder     = { Text("One-liner for the lead image overlay") },
                singleLine      = true,
                modifier        = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )

            OutlinedTextField(
                value           = state.title,
                onValueChange   = vm::setTitle,
                label           = { Text("Title") },
                singleLine      = true,
                modifier        = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                trailingIcon    = {
                    IconButton(onClick = vm::prefillTitleNow) {
                        Icon(Icons.Default.AutoFixHigh, "Regenerate title")
                    }
                },
            )

            OutlinedTextField(
                value           = state.slug,
                onValueChange   = vm::setSlug,
                label           = { Text("Slug") },
                singleLine      = true,
                modifier        = Modifier.fillMaxWidth(),
                enabled         = !state.isPublished,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                trailingIcon    = {
                    if (!state.isPublished) {
                        IconButton(onClick = vm::recomputeSlug) {
                            Icon(Icons.Default.AutoFixHigh, "Regenerate slug from title")
                        }
                    }
                },
                supportingText  = {
                    if (state.isPublished) Text("Locked — post is published")
                    else if (state.slugEdited) Text("Manually edited — won't auto-update")
                },
            )

            OutlinedTextField(
                value           = state.body,
                onValueChange   = vm::setBody,
                label           = { Text("Post body") },
                placeholder     = { Text("Full post text") },
                minLines        = 16,
                modifier        = Modifier.fillMaxWidth(),
                textStyle       = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize   = 14.sp,
                    lineHeight = 22.sp,
                ),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )

            OutlinedTextField(
                value           = state.teaser,
                onValueChange   = vm::setTeaser,
                label           = { Text("Teaser") },
                placeholder     = { Text("Brief excerpt for post cards and sharing") },
                minLines        = 4,
                maxLines        = 6,
                modifier        = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                supportingText  = {
                    if (state.teaserEdited) Text("Manually edited - won't auto-update")
                },
            )

            Text("Tags", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value         = tagInput,
                    onValueChange = { tagInput = it },
                    label         = { Text("Add tag") },
                    singleLine    = true,
                    modifier      = Modifier.weight(1f),
                    prefix        = { Text("#") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                )
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = {
                    vm.addTag(tagInput)
                    tagInput = ""
                }) { Icon(Icons.Default.Add, "Add tag") }
            }
            if (state.tags.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier              = Modifier.fillMaxWidth(),
                ) {
                    state.tags.forEach { tag ->
                        InputChip(
                            selected     = false,
                            onClick      = { vm.removeTag(tag) },
                            label        = { Text("#$tag") },
                            trailingIcon = { Icon(Icons.Default.Close, "Remove", Modifier.size(14.dp)) },
                        )
                    }
                }
            }

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                OutlinedButton(
                    onClick = { vm.saveDraft { onPublished() } },
                    enabled = !state.isSaving,
                ) { Text("Save draft") }
                Button(
                    onClick = { vm.publish { onPublished() } },
                    enabled = !state.isSaving && state.title.isNotBlank(),
                ) { Text("Publish") }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                OutlinedButton(onClick = vm::buildShareIntent) {
                    Icon(Icons.Default.Share, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Share")
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    if (showCalendar) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = runCatching {
                LocalDate.parse(state.localDate).toEpochDay() * 86_400_000L
            }.getOrDefault(System.currentTimeMillis())
        )
        DatePickerDialog(
            onDismissRequest = { showCalendar = false },
            confirmButton    = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { ms ->
                        val date = java.time.Instant.ofEpochMilli(ms)
                            .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                        vm.setDate(date.toString())
                    }
                    showCalendar = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showCalendar = false }) { Text("Cancel") } },
        ) { DatePicker(state = datePickerState) }
    }
}

// ---- Media Carousel ----

@Composable
private fun MediaCarousel(
    items:         List<PostMediaEntity>,
    pinnedHash:    String?,
    motto:         String,
    editPreviews:  Map<Long, android.graphics.Bitmap>,
    mediaStatuses: Map<Long, MediaSourceStatus>,
    resolveMedia:  (PostMediaEntity) -> ResolvedMedia,
    onPin:         (String) -> Unit,
    onRemove:      (String) -> Unit,
    onCommitOrder: (List<String>) -> Unit,
    onTapMedia:    () -> Unit,
) {
    if (items.isEmpty()) {
        Box(
            modifier         = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.PhotoLibrary, null, Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                Spacer(Modifier.height(8.dp))
                Text("Add photos to your post",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }

    val localItems = remember(items) { items.sortedBy { it.position }.toMutableStateList() }

    val lazyListState        = androidx.compose.foundation.lazy.rememberLazyListState()
    val reorderableLazyState = rememberReorderableLazyListState(lazyListState) { from, to ->
        localItems.apply { add(to.index, removeAt(from.index)) }
    }

    LaunchedEffect(reorderableLazyState.isAnyItemDragging) {
        if (!reorderableLazyState.isAnyItemDragging) {
            onCommitOrder(localItems.map { it.contentHash })
        }
    }

    LazyRow(
        state                 = lazyListState,
        contentPadding        = PaddingValues(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier              = Modifier.fillMaxWidth().height(220.dp),
    ) {
        itemsIndexed(localItems, key = { _, item -> item.id }) { index, item ->
            ReorderableItem(reorderableLazyState, key = item.id) { isDragging ->
                val elevation by androidx.compose.animation.core.animateDpAsState(
                    if (isDragging) 8.dp else 0.dp, label = "elevation")
                val resolved = resolveMedia(item)
                Surface(shadowElevation = elevation, shape = RoundedCornerShape(12.dp)) {
                    CarouselCard(
                        item         = item,
                        isPinned     = item.contentHash == pinnedHash,
                        isFirst      = index == 0,
                        motto        = motto,
                        editPreview  = editPreviews[item.id],
                        imageModel   = resolved.uri,
                        sourceStatus = mediaStatuses[item.id] ?: resolved.status,
                        onTap        = onTapMedia,
                        onPin        = { onPin(item.contentHash) },
                        onRemove     = { onRemove(item.contentHash) },
                        dragHandle   = { modifier ->
                            Box(modifier.draggableHandle()) {
                                Icon(Icons.Default.DragHandle, "Drag to reorder",
                                    modifier = Modifier.size(20.dp).align(Alignment.Center),
                                    tint = Color.White)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CarouselCard(
    item:         PostMediaEntity,
    isPinned:     Boolean,
    isFirst:      Boolean,
    motto:        String,
    editPreview:  android.graphics.Bitmap?,
    imageModel:   Any?,
    sourceStatus: MediaSourceStatus,
    onTap:        () -> Unit,
    onPin:        () -> Unit,
    onRemove:     () -> Unit,
    dragHandle:   @Composable (Modifier) -> Unit,
) {
    Box(
        modifier = Modifier
            .width(180.dp)
            .height(220.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onTap)
            .border(
                width = if (isPinned) 3.dp else 0.dp,
                color = if (isPinned) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = RoundedCornerShape(12.dp),
            )
    ) {
        if (editPreview != null) {
            Image(
                bitmap = editPreview.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            AsyncImage(
                model              = imageModel,
                contentDescription = null,
                contentScale       = ContentScale.Crop,
                modifier           = Modifier.fillMaxSize(),
            )
        }

        MediaSourceBadge(status = sourceStatus)

        if (item.mimeType.startsWith("video")) {
            Icon(
                Icons.Default.Videocam, null,
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 6.dp)
                    .size(22.dp),
            )
        }

        if (isFirst && motto.isNotBlank()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                    .padding(horizontal = 10.dp, vertical = 28.dp),
            ) {
                Text(motto, color = Color.White,
                    style    = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }

        IconButton(onClick = onPin, modifier = Modifier.align(Alignment.TopStart).size(36.dp)) {
            Icon(
                imageVector        = if (isPinned) Icons.Default.Star else Icons.Default.StarBorder,
                contentDescription = "Pin as lead",
                tint               = if (isPinned) MaterialTheme.colorScheme.primary else Color.White,
                modifier           = Modifier.size(20.dp),
            )
        }

        IconButton(onClick = onRemove, modifier = Modifier.align(Alignment.TopEnd).size(36.dp)) {
            Icon(Icons.Default.Close, "Remove", tint = Color.White, modifier = Modifier.size(20.dp))
        }

        dragHandle(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(28.dp)
                .background(Color.Black.copy(alpha = 0.35f))
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateRow(
    localDate:      String,
    dayNumber:      Int?,
    enabled:        Boolean,
    onPrev:         () -> Unit,
    onNext:         () -> Unit,
    onToday:        () -> Unit,
    onYesterday:    () -> Unit,
    onOpenCalendar: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!enabled) {
            Text("Date locked — unpublish to change",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = localDate == LocalDate.now().toString(),
                onClick = onToday,
                enabled = enabled,
                label = { Text("Today") },
            )
            FilterChip(
                selected = localDate == LocalDate.now().minusDays(1).toString(),
                onClick = onYesterday,
                enabled = enabled,
                label = { Text("Yesterday") },
            )
            if (dayNumber != null) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text("Day $dayNumber",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style    = MaterialTheme.typography.labelLarge,
                        color    = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = onPrev, enabled = enabled) {
                Icon(Icons.Default.ChevronLeft, "Previous day")
            }
            TextButton(onClick = onOpenCalendar, enabled = enabled) {
                Text(
                    runCatching {
                        LocalDate.parse(localDate).format(DateTimeFormatter.ofPattern("MMMM d, yyyy"))
                    }.getOrDefault(localDate),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            IconButton(onClick = onNext, enabled = enabled) {
                Icon(Icons.Default.ChevronRight, "Next day")
            }
        }
    }
}

private fun stepDate(current: String, days: Long): String =
    runCatching { LocalDate.parse(current).plusDays(days).toString() }.getOrDefault(current)
