package de.perigon.companion.posts.ui.list

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import de.perigon.companion.core.data.UserNotificationDao
import de.perigon.companion.core.prefs.AppPrefs
import de.perigon.companion.core.prefs.CredentialStore
import de.perigon.companion.media.domain.TransformPreviewGenerator
import de.perigon.companion.posts.data.PostEntity
import de.perigon.companion.posts.data.PostMediaEntity
import de.perigon.companion.posts.data.PostMediaFileStore
import de.perigon.companion.posts.data.PostRepository
import de.perigon.companion.posts.domain.Post
import de.perigon.companion.posts.domain.PostPublishState
import de.perigon.companion.posts.domain.PostShareIntentBuilder
import de.perigon.companion.posts.domain.pullPostMediaFromGitHub
import de.perigon.companion.posts.domain.publishedUrl
import de.perigon.companion.util.network.GitHubClient
import de.perigon.companion.core.ui.SnackbarChannel
import de.perigon.companion.posts.worker.PublishWorker
import de.perigon.companion.posts.worker.UnpublishWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class PostListViewModel @Inject constructor(
    @param:ApplicationContext private val ctx: android.content.Context,
    private val postRepository: PostRepository,
    private val workManager: WorkManager,
    private val appPrefs: AppPrefs,
    private val credentialStore: CredentialStore,
    private val http: HttpClient,
    private val mediaFileStore: PostMediaFileStore,
    private val previewGenerator: TransformPreviewGenerator,
    private val notificationDao: UserNotificationDao,
) : ViewModel() {

    val posts: StateFlow<List<PostEntity>> =
        postRepository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _leadMedia = MutableStateFlow<Map<Long, PostMediaEntity>>(emptyMap())
    val leadMedia: StateFlow<Map<Long, PostMediaEntity>> = _leadMedia.asStateFlow()

    private val _editPreviews = MutableStateFlow<Map<Long, Bitmap>>(emptyMap())
    val editPreviews: StateFlow<Map<Long, Bitmap>> = _editPreviews.asStateFlow()

    val snackbar = SnackbarChannel()

    private val _verifying = MutableStateFlow<Set<Long>>(emptySet())
    val verifying: StateFlow<Set<Long>> = _verifying.asStateFlow()

    private val _shareIntents = Channel<Intent>(Channel.BUFFERED)
    val shareIntents = _shareIntents.receiveAsFlow()

    val notifications: UserNotificationDao get() = notificationDao

    init {
        viewModelScope.launch {
            posts.collect { postList ->
                val map = mutableMapOf<Long, PostMediaEntity>()
                postList.forEach { post ->
                    val media = postRepository.getMediaForPost(post.id)
                    val lead  = media
                        .sortedWith(compareBy({ if (it.id == post.pinnedMediaId) 0 else 1 }, { it.position }))
                        .firstOrNull()
                    if (lead != null) map[post.id] = lead
                }
                _leadMedia.value = map

                val currentPreviews = _editPreviews.value.toMutableMap()
                val activeMediaIds = mutableSetOf<Long>()

                for ((_, media) in map) {
                    activeMediaIds += media.id
                    if (media.hasTransformIntent && media.id !in currentPreviews) {
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
                _editPreviews.update { previews -> previews.filterKeys { it in activeMediaIds } }
            }
        }
    }

    private fun githubClient(): GitHubClient? {
        val token = credentialStore.githubToken() ?: return null
        val owner = appPrefs.githubOwner() ?: return null
        val repo  = appPrefs.githubRepo() ?: return null
        return GitHubClient(http, token, owner, repo)
    }

    fun resolveMediaUri(entity: PostMediaEntity): Uri {
        if (entity.mediaStoreUri.isNotBlank()) {
            val accessible = try {
                mediaFileStore.openInputStream(entity.mediaStoreUri)?.use { true } ?: false
            } catch (_: Exception) {
                false
            }
            if (accessible) return Uri.parse(entity.mediaStoreUri)
        }
        mediaFileStore.resolveUri(entity.contentHash, entity.mimeType)?.let { return it }

        val post = posts.value.find { postId ->
            _leadMedia.value[postId.id]?.contentHash == entity.contentHash
        }
        if (post != null && post.slug.isNotBlank()) {
            val github = githubClient()
            if (github != null) {
                viewModelScope.launch {
                    val newUri = pullPostMediaFromGitHub(
                        entity.contentHash, entity.mimeType, post.slug, github, mediaFileStore)
                    if (newUri != null) {
                        _leadMedia.update { it.toMutableMap() }
                    }
                }
            }
        }

        return Uri.EMPTY
    }

    fun publishPost(postId: Long) {
        viewModelScope.launch {
            postRepository.updatePublishState(postId, PostPublishState.QUEUED)
            workManager.enqueueUniqueWork(
                "publish_$postId",
                ExistingWorkPolicy.KEEP,
                PublishWorker.buildRequest(postId),
            )
        }
    }

    fun unpublishPost(post: PostEntity) {
        viewModelScope.launch {
            postRepository.updatePublishState(post.id, PostPublishState.QUEUED)
            workManager.enqueueUniqueWork(
                "unpublish_${post.id}",
                ExistingWorkPolicy.KEEP,
                UnpublishWorker.buildRequest(
                    postId      = post.id,
                    slug        = post.slug,
                    localDate   = post.localDate,
                    deleteLocal = false,
                ),
            )
        }
    }

    fun deletePost(post: PostEntity) {
        val wasPublished = post.publishState in listOf(
            PostPublishState.PUBLISHED, PostPublishState.NEEDS_FIXING)
        viewModelScope.launch {
            if (wasPublished && post.slug.isNotBlank()) {
                postRepository.deletePost(post.id)
                workManager.enqueueUniqueWork(
                    "unpublish_${post.id}",
                    ExistingWorkPolicy.KEEP,
                    UnpublishWorker.buildRequest(
                        postId      = post.id,
                        slug        = post.slug,
                        localDate   = post.localDate,
                        deleteLocal = true,
                    ),
                )
            } else {
                postRepository.deletePost(post.id)
            }
        }
    }

    fun verifyPost(post: PostEntity) {
        val github = githubClient() ?: run {
            snackbar.send("GitHub credentials not configured")
            return
        }
        viewModelScope.launch {
            _verifying.update { it + post.id }
            try {
                val postPath = "_posts/${post.localDate}-${post.slug}.md"
                val postSha  = github.getFileSha(postPath)

                val mediaList    = postRepository.getMediaForPost(post.id)
                val missingMedia = mediaList.count { media ->
                    val ext      = if (media.mimeType.startsWith("video")) "mp4" else "jpg"
                    val repoPath = "static/${post.slug}/${media.contentHash}.$ext"
                    github.getFileSha(repoPath) == null
                }

                val newState = when {
                    postSha == null && missingMedia == 0 -> PostPublishState.DRAFT
                    postSha != null && missingMedia == 0 -> PostPublishState.PUBLISHED
                    else                                 -> PostPublishState.NEEDS_FIXING
                }

                val publishedAt = if (newState == PostPublishState.PUBLISHED)
                    System.currentTimeMillis() else null

                postRepository.updatePublishState(post.id, newState,
                    publishedAt ?: post.publishedAt ?: System.currentTimeMillis())
                snackbar.send("Verified: ${newState.name.lowercase().replace('_', ' ')}")
            } catch (e: Exception) {
                snackbar.send("Verify failed: ${e.message}")
            } finally {
                _verifying.update { it - post.id }
            }
        }
    }

    fun buildShareIntent(post: PostEntity) {
        viewModelScope.launch {
            val tags = post.tags.split(",").filter { it.isNotBlank() }.map { it.trim() }

            val mediaList = postRepository.getMediaForPost(post.id)
            val allMedia = mediaList
                .sortedWith(compareBy(
                    { if (it.id == post.pinnedMediaId) 0 else 1 },
                    { it.position },
                ))
                .mapNotNull { entity ->
                    val uri = resolveMediaUri(entity)
                    if (uri == Uri.EMPTY) null
                    else PostShareIntentBuilder.ShareMedia(
                        uri = mediaFileStore.shareableUri(uri.toString()),
                        mimeType = entity.mimeType,
                    )
                }

            val intent = PostShareIntentBuilder.build(
                title = post.title,
                teaser = post.teaser,
                tags = tags,
                slug = post.slug,
                localDate = post.localDate,
                siteUrl = appPrefs.siteUrl(),
                media = allMedia,
            )

            _shareIntents.trySend(intent)
        }
    }
}
