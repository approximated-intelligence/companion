package de.perigon.companion.posts.ui.prep

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import de.perigon.companion.posts.data.PostsPrefs
import de.perigon.companion.posts.PostsRoute as Route
import de.perigon.companion.posts.data.MediaResolutionCache
import de.perigon.companion.posts.data.TransformPreviewCache
import de.perigon.companion.posts.domain.ItemStatus
import de.perigon.companion.posts.domain.MediaPrepItem
import de.perigon.companion.media.domain.MediaType
import de.perigon.companion.media.domain.TransformIntent
import de.perigon.companion.posts.data.PostMediaEntity
import de.perigon.companion.posts.data.PostMediaFileStore
import de.perigon.companion.posts.data.ResolvedMedia
import de.perigon.companion.posts.data.PostRepository
import de.perigon.companion.posts.domain.PostPublishState
import de.perigon.companion.core.ui.SnackbarChannel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject

/** An editable media item: its own original, which the invariant guarantees exists. */
data class EditRequest(
    val media: PostMediaEntity,
    val uri: Uri,
)

@Immutable
data class MediaPrepUiState(
    val items: List<MediaPrepItem> = emptyList(),
    val postId: Long = 0L,
    // URIs currently being imported. Guards concurrent double delivery only;
    // real dedup is by content hash against the post's rows.
    val inFlightUris: Set<Uri> = emptySet(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MediaPrepViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val postRepository: PostRepository,
    private val mediaFileStore: PostMediaFileStore,
    private val previewCache: TransformPreviewCache,
    private val resolutionCache: MediaResolutionCache,
    private val appPrefs: PostsPrefs,
) : ViewModel() {

    // Identity comes from the route itself. Previously the screen pushed it in
    // via init(postId) from a LaunchedEffect — any addItems() that ran before
    // that effect saw 0 and created a stray new post.
    private val _postId = MutableStateFlow(savedStateHandle.toRoute<Route.MediaPrep>().postId)

    private val _state = MutableStateFlow(MediaPrepUiState())
    val state: StateFlow<MediaPrepUiState> = _state.asStateFlow()

    val snackbar = SnackbarChannel()

    val mediaPickerMode: String get() = appPrefs.mediaPickerMode()

    val editPreviews: StateFlow<Map<Long, android.graphics.Bitmap>> = previewCache.previews

    /** Non-blocking, cache-backed display resolution — thumbnails no longer do
     *  file I/O per cell in composition. */
    val resolvedMedia: StateFlow<Map<Long, ResolvedMedia>> = resolutionCache.resolved

    private val _editRequests = MutableSharedFlow<EditRequest>(extraBufferCapacity = 1)
    val editRequests: SharedFlow<EditRequest> = _editRequests.asSharedFlow()

    init {
        viewModelScope.launch {
            _postId
                .flatMapLatest { id ->
                    if (id > 0L) postRepository.observeMediaForPost(id).map { id to it }
                    else flowOf(0L to emptyList())
                }
                .collect { (id, mediaList) ->
                    val items = mediaList.map { entity ->
                        val type = if (entity.mimeType.startsWith("video")) MediaType.VIDEO else MediaType.IMAGE
                        MediaPrepItem(entity = entity, type = type, status = ItemStatus.EXISTING)
                    }
                    _state.update { it.copy(items = items, postId = id) }
                    // Warms resolvedMedia off the main thread.
                    resolutionCache.prime(mediaList, emptyMap())
                    for (item in items) {
                        if (item.hasEdits) previewCache.get(item.entity)
                    }
                }
        }
    }

    private suspend fun ensurePostId(): Long {
        val current = _postId.value
        if (current > 0L) return current
        val newId = postRepository.saveDraft(
            id = 0L,
            localDate = LocalDate.now().toString(),
            motto = "",
            title = "",
            titleEdited = false,
            body = "",
            teaser = "",
            teaserEdited = false,
            tags = emptyList(),
            pinnedMediaId = null,
            slug = "",
            slugEdited = false,
            publishState = PostPublishState.DRAFT,
        )
        _postId.value = newId
        return newId
    }

    // ---- Add media ----

    /**
     * Every source is COPIED into PostMedia/Originals before it is attached;
     * the row only ever references our copy. Gallery files get deleted,
     * Consolidate removes camera originals, share grants lapse, frame grabs
     * and track renders live in an evictable cache — none of that can break
     * a post any more. A failed copy means the item is not attached.
     */
    fun addItems(uris: List<Uri>) {
        val newUris = uris.filter { it !in _state.value.inFlightUris }.distinct()
        if (newUris.isEmpty()) return

        _state.update { it.copy(inFlightUris = it.inFlightUris + newUris) }

        viewModelScope.launch {
            try {
                val resolvedPostId = ensurePostId()

                val existingMedia = postRepository.getMediaForPost(resolvedPostId)
                val attachedHashes = existingMedia.map { it.contentHash }.toMutableSet()
                val startPosition = if (existingMedia.isNotEmpty()) existingMedia.maxOf { it.position } + 1 else 0
                var hasLead = existingMedia.isNotEmpty()

                for ((offset, uri) in newUris.withIndex()) {
                    try {
                        // Temp sources (frame grabs) go as soon as they are imported or failed.
                        val original = try {
                            mediaFileStore.importOriginal(uri)
                        } finally {
                            mediaFileStore.discardIfTemp(uri)
                        }
                        // Already in this post (or earlier in this batch).
                        if (!attachedHashes.add(original.contentHash)) continue

                        val isVideo = original.mimeType.startsWith("video")
                        val entity = PostMediaEntity(
                            postId = resolvedPostId,
                            contentHash = original.contentHash,
                            mimeType = if (isVideo) "video/mp4" else "image/jpeg",
                            position = startPosition + offset,
                            mediaStoreUri = "",       // processed copy, set at publish
                            sourceUri = original.uri, // our copy in PostMedia/Originals
                            addedAt = System.currentTimeMillis(),
                        )

                        val persisted = postRepository.addMediaToPost(resolvedPostId, entity)

                        if (!hasLead) {
                            postRepository.updatePinnedMedia(resolvedPostId, persisted.id)
                            hasLead = true
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        snackbar.send("Couldn't add media: ${e.message}")
                    }
                }
            } finally {
                _state.update { it.copy(inFlightUris = it.inFlightUris - newUris.toSet()) }
            }
        }
    }

    // ---- Remove media ----

    fun removeItem(mediaId: Long) {
        viewModelScope.launch {
            postRepository.removeMediaFromPost(mediaId)
        }
    }

    // ---- Reorder ----

    fun commitOrder(mediaIds: List<Long>) {
        viewModelScope.launch {
            val items = _state.value.items
            val byId = items.associateBy { it.id }
            val reordered = mediaIds.mapIndexedNotNull { i, id ->
                byId[id]?.entity?.copy(position = i)
            }
            postRepository.updateMediaPositions(reordered)
        }
    }

    // ---- Edit ----

    /**
     * Editing always targets the item's own original. The former
     * "original is missing, edit the transformed copy instead" fallback is
     * gone: ingestion guarantees the original exists, so an unreadable one is
     * genuinely broken state and is reported as such rather than silently
     * degrading to a re-encode of a re-encode.
     */
    fun requestEdit(media: PostMediaEntity) {
        viewModelScope.launch {
            val readable = withContext(Dispatchers.IO) { mediaFileStore.isReadable(media.sourceUri) }
            if (!readable) {
                snackbar.send("This media file is missing — remove it and add it again")
                return@launch
            }
            _editRequests.tryEmit(EditRequest(media, media.sourceUri.toUri()))
        }
    }


    // ---- Edit: persist transform intent ----

    fun applyEditResult(mediaId: Long, intent: TransformIntent) {
        viewModelScope.launch {
            postRepository.updateTransformIntent(mediaId, intent)
            val entity = postRepository.getMediaById(mediaId) ?: return@launch
            previewCache.refresh(entity)
        }
    }
}
