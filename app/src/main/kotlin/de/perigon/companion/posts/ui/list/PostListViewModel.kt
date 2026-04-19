// app/src/main/kotlin/de/perigon/companion/posts/ui/list/PostListViewModel.kt

package de.perigon.companion.posts.ui.list

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.perigon.companion.core.data.UserNotificationDao
import de.perigon.companion.core.prefs.AppPrefs
import de.perigon.companion.core.prefs.CredentialStore
import de.perigon.companion.media.data.MediaResolutionCache
import de.perigon.companion.media.data.TransformPreviewCache
import de.perigon.companion.posts.data.PostEntity
import de.perigon.companion.posts.data.PostMediaDao
import de.perigon.companion.posts.data.PostMediaEntity
import de.perigon.companion.posts.data.PostMediaFileStore
import de.perigon.companion.posts.data.MediaSourceStatus
import de.perigon.companion.posts.data.ResolvedMedia
import de.perigon.companion.posts.data.PostRepository
import de.perigon.companion.posts.data.PublishResult
import de.perigon.companion.posts.domain.PostPublishState
import de.perigon.companion.posts.domain.PostShareIntentBuilder
import de.perigon.companion.util.network.GitHubClient
import de.perigon.companion.core.ui.SnackbarChannel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PostListViewModel @Inject constructor(
    @param:ApplicationContext private val ctx: android.content.Context,
    private val postRepository: PostRepository,
    private val postMediaDao: PostMediaDao,
    private val appPrefs: AppPrefs,
    private val credentialStore: CredentialStore,
    private val http: HttpClient,
    private val mediaFileStore: PostMediaFileStore,
    private val resolutionCache: MediaResolutionCache,
    private val previewCache: TransformPreviewCache,
    private val notificationDao: UserNotificationDao,
) : ViewModel() {

    val posts: StateFlow<List<PostEntity>> =
        postRepository.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** One DAO query for lead media across all posts — scales to any N. */
    val leadMedia: StateFlow<Map<Long, PostMediaEntity>> =
        combine(posts, postMediaDao.observeLeadMediaForAllPosts()) { postList, leads ->
            val byPostId = leads.associateBy { it.postId }
            val postIds = postList.map { it.id }.toSet()
            byPostId.filterKeys { it in postIds }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val leadMediaStatus: StateFlow<Map<Long, MediaSourceStatus>> =
        combine(leadMedia, resolutionCache.resolved) { leads, resolved ->
            leads.mapNotNull { (postId, entity) ->
                val r = resolved[entity.id] ?: return@mapNotNull null
                postId to r.status
            }.toMap()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val editPreviews: StateFlow<Map<Long, android.graphics.Bitmap>> = previewCache.previews

    val snackbar = SnackbarChannel()

    private val _verifying = MutableStateFlow<Set<Long>>(emptySet())
    val verifying: StateFlow<Set<Long>> = _verifying.asStateFlow()

    private val _shareIntents = Channel<Intent>(Channel.BUFFERED)
    val shareIntents = _shareIntents.receiveAsFlow()

    private val _needsPermission = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val needsPermission: SharedFlow<Long> = _needsPermission.asSharedFlow()

    val notifications: UserNotificationDao get() = notificationDao

    init {
        // Prime caches when lead media set changes.
        viewModelScope.launch {
            leadMedia.collect { map ->
                val slugByPostId = posts.value.associate { it.id to it.slug }
                resolutionCache.prime(map.values.toList(), slugByPostId)
                map.values.forEach { previewCache.get(it) }
            }
        }
    }

    private fun githubClient(): GitHubClient? {
        val token = credentialStore.githubToken() ?: return null
        val owner = appPrefs.githubOwner() ?: return null
        val repo = appPrefs.githubRepo() ?: return null
        return GitHubClient(http, token, owner, repo)
    }

    /** Non-blocking read from cache. */
    fun resolveMediaUri(entity: PostMediaEntity): ResolvedMedia {
        val slug = posts.value.find { it.id == entity.postId }?.slug
        return resolutionCache.get(entity, slug)
    }

    fun publishPost(postId: Long) {
        viewModelScope.launch {
            when (postRepository.publish(postId)) {
                PublishResult.Queued -> { /* flows update UI */ }
                PublishResult.NeedsDcimPermission -> _needsPermission.tryEmit(postId)
            }
        }
    }

    fun retryPublish(postId: Long) = publishPost(postId)

    fun unpublishPost(post: PostEntity) {
        viewModelScope.launch { postRepository.unpublish(post) }
    }

    fun deletePost(post: PostEntity) {
        viewModelScope.launch { postRepository.deletePostAndUnpublish(post) }
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
                val postSha = github.getFileSha(postPath)

                val mediaList = postRepository.getMediaForPost(post.id)
                val missingMedia = mediaList.count { media ->
                    val ext = if (media.mimeType.startsWith("video")) "mp4" else "jpg"
                    val repoPath = "static/${post.slug}/${media.contentHash}.$ext"
                    github.getFileSha(repoPath) == null
                }

                val newState = when {
                    postSha == null && missingMedia == 0 -> PostPublishState.DRAFT
                    postSha != null && missingMedia == 0 -> PostPublishState.PUBLISHED
                    else -> PostPublishState.NEEDS_FIXING
                }

                val publishedAt = if (newState == PostPublishState.PUBLISHED)
                    System.currentTimeMillis() else null

                postRepository.updatePublishState(
                    post.id, newState,
                    publishedAt ?: post.publishedAt ?: System.currentTimeMillis(),
                )
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
                    val resolved = resolveMediaUri(entity)
                    if (resolved.uri == Uri.EMPTY) null
                    else PostShareIntentBuilder.ShareMedia(
                        uri = mediaFileStore.shareableUri(resolved.uri.toString()),
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
