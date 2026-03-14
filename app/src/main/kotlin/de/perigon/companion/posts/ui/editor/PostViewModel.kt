package de.perigon.companion.posts.ui.editor

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.perigon.companion.core.prefs.AppPrefs
import de.perigon.companion.core.prefs.CredentialStore
import de.perigon.companion.media.domain.TransformPreviewGenerator
import de.perigon.companion.posts.data.PostMediaEntity
import de.perigon.companion.posts.data.PostMediaFileStore
import de.perigon.companion.posts.data.PostRepository
import de.perigon.companion.posts.domain.Post
import de.perigon.companion.posts.domain.PostPublishState
import de.perigon.companion.posts.domain.PostShareIntentBuilder
import de.perigon.companion.posts.domain.computeDayNumber
import de.perigon.companion.posts.domain.autoTeaser
import de.perigon.companion.posts.domain.prefillTitle
import de.perigon.companion.posts.domain.computeSlug
import de.perigon.companion.posts.domain.pullPostMediaFromGitHub
import de.perigon.companion.posts.domain.publishedUrl
import de.perigon.companion.core.ui.SnackbarChannel
import de.perigon.companion.posts.worker.PublishWorker
import de.perigon.companion.util.network.GitHubClient
import dagger.hilt.android.lifecycle.HiltViewModel
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager

@Immutable
data class PostUiState(
    val id:            Long              = 0,
    val localDate:     String            = LocalDate.now().toString(),
    val motto:         String            = "",
    val title:         String            = "",
    val titleEdited:   Boolean           = false,
    val body:          String            = "",
    val teaser:        String            = "",
    val teaserEdited:  Boolean           = false,
    val tags:          List<String>      = emptyList(),
    val mediaItems:    List<PostMediaEntity> = emptyList(),
    val pinnedHash:    String?           = null,
    val publishState:  PostPublishState  = PostPublishState.DRAFT,
    val isSaving:      Boolean           = false,
    val journeyStartDate: String         = "",
    val journeyTitle:     String         = "",
    val journeyTag:       String         = "",
    val editGeneration:   Long           = 0,
    val siteUrl:          String         = "",
) {
    val dayNumber: Int? get() = computeDayNumber(journeyStartDate, localDate)
    val hasContent: Boolean get() = title.isNotBlank() || body.isNotBlank() || mediaItems.isNotEmpty()
    val slug: String get() = Post(localDate = localDate, title = title).computeSlug()
}

@HiltViewModel
class PostViewModel @Inject constructor(
    private val workManager: WorkManager,
    private val postRepository: PostRepository,
    private val appPrefs: AppPrefs,
    private val credentialStore: CredentialStore,
    private val http: HttpClient,
    private val mediaFileStore: PostMediaFileStore,
    private val previewGenerator: TransformPreviewGenerator,
) : ViewModel() {

    private val _state = MutableStateFlow(PostUiState())
    val state: StateFlow<PostUiState> = _state.asStateFlow()

    private val _editPreviews = MutableStateFlow<Map<Long, Bitmap>>(emptyMap())
    val editPreviews: StateFlow<Map<Long, Bitmap>> = _editPreviews.asStateFlow()

    val snackbar = SnackbarChannel()

    private val _shareIntents = Channel<Intent>(Channel.BUFFERED)
    val shareIntents = _shareIntents.receiveAsFlow()

    init {
        loadJourneyContext()
        startAutosave()
        observeMedia()
    }

    private fun githubClient(): GitHubClient? {
        val token = credentialStore.githubToken() ?: return null
        val owner = appPrefs.githubOwner() ?: return null
        val repo  = appPrefs.githubRepo() ?: return null
        return GitHubClient(http, token, owner, repo)
    }

    private fun loadJourneyContext() {
        val startDate = appPrefs.journeyStartDate() ?: ""
        val title = appPrefs.journeyTitle() ?: ""
        val tag = appPrefs.journeyTag() ?: ""
        val siteUrl = appPrefs.siteUrl() ?: ""
        _state.update { it.copy(
            journeyStartDate = startDate,
            journeyTitle = title,
            journeyTag = tag,
            siteUrl = siteUrl,
            tags = if (tag.isNotBlank() && it.tags.isEmpty() && it.id == 0L) listOf(tag) else it.tags,
        )}
    }

    private fun observeMedia() {
        viewModelScope.launch {
            _state.map { it.id }.distinctUntilChanged().collect { postId ->
                if (postId != 0L) {
                    postRepository.observeMediaForPost(postId).collect { mediaList ->
                        val pinnedHash = _state.value.pinnedHash
                            ?: mediaList.firstOrNull()?.contentHash
                        _state.update { it.copy(
                            mediaItems = mediaList,
                            pinnedHash = pinnedHash,
                        )}

                        for (media in mediaList) {
                            if (media.hasTransformIntent && media.id !in _editPreviews.value) {
                                viewModelScope.launch {
                                    val preview = withContext(Dispatchers.IO) {
                                        previewGenerator.generate(media)
                                    }
                                    if (preview != null) {
                                        _editPreviews.update { it + (media.id to preview) }
                                    }
                                }
                            }
                        }
                        val currentIds = mediaList.map { it.id }.toSet()
                        _editPreviews.update { map -> map.filterKeys { it in currentIds } }
                    }
                }
            }
        }
    }

    @OptIn(FlowPreview::class)
    private fun startAutosave() {
        val debounceMs = appPrefs.autosaveDebounceMs()
        val maxIntervalMs = appPrefs.autosaveMaxIntervalMs()

        viewModelScope.launch {
            _state
                .map { it.editGeneration }
                .distinctUntilChanged()
                .drop(1)
                .debounce(debounceMs)
                .collect {
                    val s = _state.value
                    if (s.hasContent && !s.isSaving) {
                        saveDraftSilently()
                    }
                }
        }

        viewModelScope.launch {
            var lastSavedGeneration = 0L
            while (true) {
                delay(maxIntervalMs)
                val s = _state.value
                if (s.hasContent && !s.isSaving && s.editGeneration > lastSavedGeneration) {
                    saveDraftSilently()
                    lastSavedGeneration = s.editGeneration
                }
            }
        }
    }

    private fun bumpGeneration() {
        _state.update { it.copy(editGeneration = it.editGeneration + 1) }
    }

    fun loadDraft(postId: Long) {
        viewModelScope.launch {
            val post = postRepository.getById(postId) ?: return@launch
            val media = postRepository.getMediaForPost(postId)
            val tags = post.tags.split(",").map { it.trim() }.filter { it.isNotBlank() }
            val journeyTag = appPrefs.journeyTag() ?: ""
            val mergedTags = if (journeyTag.isNotBlank() && journeyTag !in tags) {
                listOf(journeyTag) + tags
            } else tags

            val pinnedHash = if (post.pinnedMediaId != null) {
                media.find { it.id == post.pinnedMediaId }?.contentHash
            } else {
                media.firstOrNull()?.contentHash
            }

            _state.update { s -> s.copy(
                id           = post.id,
                localDate    = post.localDate,
                motto        = post.motto,
                title        = post.title,
                titleEdited  = post.titleEdited,
                body         = post.body,
                teaser       = post.teaser,
                teaserEdited = post.teaserEdited,
                tags         = mergedTags,
                mediaItems   = media,
                pinnedHash   = pinnedHash,
                publishState = post.publishState,
            )}
        }
    }

    fun setDate(localDate: String) {
        _state.update { it.copy(localDate = localDate) }
        maybeAutoTitle()
        bumpGeneration()
    }

    fun setMotto(motto: String) {
        _state.update { it.copy(motto = motto) }
        maybeAutoTitle()
        bumpGeneration()
    }

    fun setTitle(title: String) {
        _state.update { it.copy(title = title, titleEdited = true) }
        bumpGeneration()
    }

    fun setBody(body: String) {
        _state.update { s ->
            val newTeaser = if (s.teaserEdited) s.teaser else autoTeaser(body)
            s.copy(body = body, teaser = newTeaser)
        }
        bumpGeneration()
    }

    fun setTeaser(teaser: String) {
        _state.update { it.copy(teaser = teaser, teaserEdited = true) }
        bumpGeneration()
    }

    fun addTag(tag: String) {
        val cleaned = tag.removePrefix("#").trim().lowercase()
        if (cleaned.isBlank()) return
        _state.update { s ->
            if (cleaned in s.tags) s else s.copy(tags = s.tags + cleaned)
        }
        bumpGeneration()
    }

    fun removeTag(tag: String) {
        _state.update { s -> s.copy(tags = s.tags - tag) }
        bumpGeneration()
    }

    fun prefillTitleNow() {
        val s = _state.value
        val title = prefillTitle(s.dayNumber, s.motto, s.journeyTitle, s.journeyTag)
        _state.update { it.copy(title = title, titleEdited = false) }
        bumpGeneration()
    }

    private fun maybeAutoTitle() {
        val s = _state.value
        if (!s.titleEdited) {
            val title = prefillTitle(s.dayNumber, s.motto, s.journeyTitle, s.journeyTag)
            _state.update { it.copy(title = title) }
        }
    }

    // ---- Media management ----

    fun pinMedia(contentHash: String) {
        _state.update { it.copy(
            pinnedHash = if (it.pinnedHash == contentHash) null else contentHash
        )}
        bumpGeneration()
    }

    fun commitMediaOrder(hashes: List<String>) {
        _state.update { s ->
            val byHash = s.mediaItems.associateBy { it.contentHash }
            val reordered = hashes.mapIndexedNotNull { i, hash ->
                byHash[hash]?.copy(position = i)
            }
            s.copy(mediaItems = reordered)
        }
        viewModelScope.launch {
            postRepository.updateMediaPositions(_state.value.mediaItems)
        }
        bumpGeneration()
    }

    fun removeMedia(contentHash: String) {
        val entity = _state.value.mediaItems.find { it.contentHash == contentHash }
        _state.update { s ->
            val updated = s.mediaItems.filter { it.contentHash != contentHash }
                .mapIndexed { i, m -> m.copy(position = i) }
            s.copy(
                mediaItems = updated,
                pinnedHash = if (s.pinnedHash == contentHash) updated.firstOrNull()?.contentHash else s.pinnedHash,
            )
        }
        if (entity != null && entity.id != 0L) {
            viewModelScope.launch {
                postRepository.removeMediaFromPost(entity.id)
                _editPreviews.update { it - entity.id }
            }
        }
        bumpGeneration()
    }

    fun mediaUri(entity: PostMediaEntity): Uri {
        val localUri = mediaFileStore.resolveDisplayUri(entity)
        if (localUri != Uri.EMPTY) return localUri

        val s = _state.value
        if (s.slug.isNotBlank()) {
            val github = githubClient()
            if (github != null) {
                viewModelScope.launch {
                    val newUri = pullPostMediaFromGitHub(
                        entity.contentHash, entity.mimeType, s.slug, github, mediaFileStore)
                    if (newUri != null) {
                        _state.update { state ->
                            state.copy(mediaItems = state.mediaItems.map { m ->
                                if (m.contentHash == entity.contentHash) m.copy(mediaStoreUri = newUri)
                                else m
                            })
                        }
                    }
                }
            }
        }

        return Uri.EMPTY
    }

    // ---- Save / Publish ----

    private suspend fun saveDraftSilently() {
        try { persistDraft() } catch (_: Exception) { }
    }

    fun saveDraft(onComplete: (() -> Unit)? = null) {
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true) }
            try {
                persistDraft()
                _state.update { it.copy(isSaving = false) }
                snackbar.send("Draft saved")
                onComplete?.invoke()
            } catch (e: Exception) {
                _state.update { it.copy(isSaving = false) }
                snackbar.send("Save failed: ${e.message}")
            }
        }
    }

    private suspend fun persistDraft() {
        val s = _state.value
        val pinnedId = s.mediaItems.find { it.contentHash == s.pinnedHash }?.id

        val resolvedId = postRepository.saveDraft(
            id = s.id,
            localDate = s.localDate,
            motto = s.motto,
            title = s.title,
            titleEdited = s.titleEdited,
            body = s.body,
            teaser = s.teaser,
            teaserEdited = s.teaserEdited,
            tags = s.tags,
            pinnedMediaId = pinnedId,
            publishState = s.publishState,
        )

        _state.update { it.copy(id = resolvedId) }
    }

    fun publish(onComplete: (() -> Unit)? = null) {
        viewModelScope.launch {
            saveDraft()
            val id = _state.value.id
            if (id == 0L) return@launch
            postRepository.updatePublishState(id, PostPublishState.QUEUED)
            workManager.enqueueUniqueWork(
                "publish_$id",
                ExistingWorkPolicy.KEEP,
                PublishWorker.buildRequest(id),
            )
            _state.update { it.copy(publishState = PostPublishState.QUEUED) }
            snackbar.send("Publishing…")
            onComplete?.invoke()
        }
    }

    fun deletePost(onComplete: () -> Unit) {
        viewModelScope.launch {
            val id = _state.value.id
            if (id != 0L) postRepository.deletePost(id)
            onComplete()
        }
    }

    fun buildShareIntent() {
        val s = _state.value
        if (s.title.isBlank()) return

        viewModelScope.launch {
            val allMedia = s.mediaItems
                .sortedWith(compareBy(
                    { if (it.contentHash == s.pinnedHash) 0 else 1 },
                    { it.position },
                ))
                .mapNotNull { entity ->
                    val uri = mediaUri(entity)
                    if (uri == Uri.EMPTY) null
                    else PostShareIntentBuilder.ShareMedia(
                        uri = mediaFileStore.shareableUri(uri.toString()),
                        mimeType = entity.mimeType,
                    )
                }

            val intent = PostShareIntentBuilder.build(
                title = s.title,
                teaser = s.teaser,
                tags = s.tags,
                slug = s.slug,
                localDate = s.localDate,
                siteUrl = s.siteUrl,
                media = allMedia,
            )

            _shareIntents.trySend(intent)
        }
    }
}
