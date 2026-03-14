package de.perigon.companion.media.ui.prep

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.perigon.companion.media.domain.ItemStatus
import de.perigon.companion.media.domain.MediaPrepItem
import de.perigon.companion.media.domain.MediaType
import de.perigon.companion.media.domain.TransformIntent
import de.perigon.companion.media.domain.TransformPreviewGenerator
import de.perigon.companion.media.domain.toTransformIntent
import de.perigon.companion.posts.data.PostMediaEntity
import de.perigon.companion.posts.data.PostMediaFileStore
import de.perigon.companion.posts.data.PostRepository
import de.perigon.companion.posts.domain.PostPublishState
import de.perigon.companion.core.ui.SnackbarChannel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject

@Immutable
data class MediaPrepUiState(
    val items: List<MediaPrepItem> = emptyList(),
    val postId: Long = 0L,
    val consumedUris: Set<Uri> = emptySet(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MediaPrepViewModel @Inject constructor(
    private val postRepository: PostRepository,
    private val mediaFileStore: PostMediaFileStore,
    private val previewGenerator: TransformPreviewGenerator,
) : ViewModel() {

    private val _postId = MutableStateFlow(0L)

    private val _state = MutableStateFlow(MediaPrepUiState())
    val state: StateFlow<MediaPrepUiState> = _state.asStateFlow()

    val snackbar = SnackbarChannel()

    private val _editPreviews = MutableStateFlow<Map<Long, android.graphics.Bitmap>>(emptyMap())
    val editPreviews: StateFlow<Map<Long, android.graphics.Bitmap>> = _editPreviews.asStateFlow()

    init {
        viewModelScope.launch {
            _postId
                .flatMapLatest { id ->
                    if (id > 0L) {
                        postRepository.observeMediaForPost(id).map { id to it }
                    } else {
                        flowOf(0L to emptyList())
                    }
                }
                .collect { (id, mediaList) ->
                    val items = mediaList.map { entity ->
                        val type = if (entity.mimeType.startsWith("video")) MediaType.VIDEO else MediaType.IMAGE
                        MediaPrepItem(entity = entity, type = type, status = ItemStatus.EXISTING)
                    }
                    _state.update { it.copy(items = items, postId = id) }
                    for (item in items) {
                        if (item.hasEdits) {
                            generatePreview(item.entity)
                        }
                    }
                }
        }
    }

    fun init(postId: Long) {
        _postId.value = postId
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
            publishState = PostPublishState.DRAFT,
        )
        _postId.value = newId
        return newId
    }

    // ---- Add media ----

    fun addItems(uris: List<Uri>) {
        val currentConsumed = _state.value.consumedUris
        val newUris = uris.filter { it !in currentConsumed }
        if (newUris.isEmpty()) return

        _state.update { it.copy(consumedUris = it.consumedUris + newUris) }

        viewModelScope.launch {
            val resolvedPostId = ensurePostId()

            val existingMedia = postRepository.getMediaForPost(resolvedPostId)
            val existingHashes = existingMedia.map { it.contentHash }.toSet()
            val startPosition = if (existingMedia.isNotEmpty()) existingMedia.maxOf { it.position } + 1 else 0

            for ((offset, uri) in newUris.withIndex()) {
                try {
                    val mimeType = mediaFileStore.queryMimeType(uri) ?: "image/jpeg"
                    val isVideo = mimeType.startsWith("video")

                    val contentHash = withContext(Dispatchers.IO) {
                        mediaFileStore.computeContentHash(uri)
                    }
                    if (contentHash.isEmpty()) continue
                    if (contentHash in existingHashes) continue

                    val entity = PostMediaEntity(
                        postId = resolvedPostId,
                        contentHash = contentHash,
                        mimeType = if (isVideo) "video/mp4" else "image/jpeg",
                        position = startPosition + offset,
                        mediaStoreUri = uri.toString(),
                        sourceUri = uri.toString(),
                        addedAt = System.currentTimeMillis(),
                    )

                    val persisted = postRepository.addMediaToPost(resolvedPostId, entity)

                    if (existingMedia.isEmpty() && offset == 0) {
                        postRepository.updatePinnedMedia(resolvedPostId, persisted.id)
                    }
                } catch (e: Exception) {
                    snackbar.send("Failed to add media: ${e.message}")
                }
            }
        }
    }

    // ---- Remove media ----

    fun removeItem(mediaId: Long) {
        viewModelScope.launch {
            postRepository.removeMediaFromPost(mediaId)
            _editPreviews.update { it - mediaId }
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

    // ---- Edit: persist transform intent to DB immediately ----

    fun applyEditResult(mediaId: Long, intent: TransformIntent) {
        viewModelScope.launch {
            postRepository.updateTransformIntent(mediaId, intent)
            val entity = postRepository.getMediaById(mediaId) ?: return@launch
            generatePreview(entity)
        }
    }

    private fun generatePreview(entity: PostMediaEntity) {
        val intent = entity.toTransformIntent()
        if (intent.isIdentity) {
            _editPreviews.update { it - entity.id }
            return
        }
        viewModelScope.launch {
            val preview = withContext(Dispatchers.IO) {
                previewGenerator.generate(entity)
            }
            if (preview != null) {
                _editPreviews.update { it + (entity.id to preview) }
            } else {
                _editPreviews.update { it - entity.id }
            }
        }
    }

    /**
     * Resolve a display URI for a media entity.
     * Delegates entirely to [PostMediaFileStore] — no ContentResolver access here.
     */
    fun resolveDisplayUri(entity: PostMediaEntity): Uri =
        mediaFileStore.resolveDisplayUri(entity)
}
