package de.perigon.companion.media.ui.prep

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.perigon.companion.core.prefs.AppPrefs
import de.perigon.companion.media.data.TransformPreviewCache
import de.perigon.companion.media.domain.ItemStatus
import de.perigon.companion.media.domain.MediaPrepItem
import de.perigon.companion.media.domain.MediaType
import de.perigon.companion.media.domain.TransformIntent
import de.perigon.companion.posts.data.PostMediaEntity
import de.perigon.companion.posts.data.PostMediaFileStore
import de.perigon.companion.posts.data.ResolvedMedia
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

data class EditRequest(
    val media: PostMediaEntity,
    val uri: Uri,
    val isFallback: Boolean,
)

@Immutable
data class MediaPrepUiState(
    val items: List<MediaPrepItem> = emptyList(),
    val postId: Long = 0L,
    val consumedUris: Set<Uri> = emptySet(),
    val pendingFallbackEdit: EditRequest? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MediaPrepViewModel @Inject constructor(
    private val postRepository: PostRepository,
    private val mediaFileStore: PostMediaFileStore,
    private val previewCache: TransformPreviewCache,
    private val appPrefs: AppPrefs,
) : ViewModel() {

    private val _postId = MutableStateFlow(0L)

    private val _state = MutableStateFlow(MediaPrepUiState())
    val state: StateFlow<MediaPrepUiState> = _state.asStateFlow()

    val snackbar = SnackbarChannel()

    val mediaPickerMode: String get() = appPrefs.mediaPickerMode()

    val editPreviews: StateFlow<Map<Long, android.graphics.Bitmap>> = previewCache.previews

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
                    for (item in items) {
                        if (item.hasEdits) previewCache.get(item.entity)
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
            slug = "",
            slugEdited = false,
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
                    mediaFileStore.tryPersistReadPermission(uri)

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

    // ---- Edit: resolve URI and handle fallback ----

    fun requestEdit(media: PostMediaEntity) {
        val request = resolveEditUri(media)
        if (request == null) {
            snackbar.send("Media unavailable — both source and transformed files are missing")
            return
        }
        if (request.isFallback) {
            _state.update { it.copy(pendingFallbackEdit = request) }
        } else {
            _editRequests.tryEmit(request)
        }
    }

    fun confirmFallbackEdit() {
        val request = _state.value.pendingFallbackEdit ?: return
        _state.update { it.copy(pendingFallbackEdit = null) }
        _editRequests.tryEmit(request)
    }

    fun dismissFallbackEdit() {
        _state.update { it.copy(pendingFallbackEdit = null) }
    }

    private fun resolveEditUri(media: PostMediaEntity): EditRequest? {
        val sourceOk = isUriReadable(media.sourceUri)
        val mediaStoreOk = isUriReadable(media.mediaStoreUri)

        return when {
            sourceOk -> EditRequest(media, Uri.parse(media.sourceUri), isFallback = false)
            mediaStoreOk -> EditRequest(media, Uri.parse(media.mediaStoreUri), isFallback = true)
            else -> null
        }
    }

    private fun isUriReadable(uri: String): Boolean {
        if (uri.isBlank()) return false
        return try {
            mediaFileStore.openInputStream(uri)?.use { true } ?: false
        } catch (_: Exception) { false }
    }

    // ---- Edit: persist transform intent ----

    fun applyEditResult(mediaId: Long, intent: TransformIntent) {
        viewModelScope.launch {
            postRepository.updateTransformIntent(mediaId, intent)
            val entity = postRepository.getMediaById(mediaId) ?: return@launch
            previewCache.refresh(entity)
        }
    }

    fun resolveDisplayMedia(entity: PostMediaEntity): ResolvedMedia =
        mediaFileStore.resolveDisplayMedia(entity)
}
