package de.perigon.companion.posts.ui.list

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.perigon.companion.core.data.UserNotificationDao
import de.perigon.companion.core.prefs.AppPrefs
import de.perigon.companion.core.prefs.CredentialStore
import de.perigon.companion.posts.data.MediaResolutionCache
import de.perigon.companion.posts.data.TransformPreviewCache
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
import de.perigon.companion.posts.network.GitHubClient
import de.perigon.companion.core.network.NetworkFailure
import de.perigon.companion.core.network.isTransportFailure
import de.perigon.companion.core.ui.SnackbarChannel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.request.head
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
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

/** A media presence probe that distinguishes "absent" from "couldn't ask". */
private sealed interface Presence {
    data object Present : Presence
    data object Absent : Presence
    data object Unknown : Presence
}

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
            when (val result = postRepository.publish(postId)) {
                PublishResult.Queued -> { /* flows update UI */ }
                PublishResult.NeedsDcimPermission -> _needsPermission.tryEmit(postId)
                is PublishResult.Failed -> snackbar.send("Couldn't start publishing: ${result.reason}")
            }
        }
    }

    fun retryPublish(postId: Long) = publishPost(postId)

    fun unpublishPost(post: PostEntity) {
        viewModelScope.launch {
            try {
                postRepository.unpublish(post)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                snackbar.send("Couldn't start unpublishing: ${e.message}")
            }
        }
    }

    fun deletePost(post: PostEntity) {
        viewModelScope.launch { postRepository.deletePostAndUnpublish(post) }
    }

    /**
     * Verify against what readers actually see: the post is markdown at its
     * recorded (or derived) GitHub path; a media file EXISTS when it is
     * fetchable at any URL the published markdown could reference —
     * recorded/current static base or GitHub — regardless of upload
     * mechanism. Names probed: processedHash.
     *
     * A verdict is only written when every probe returned a definite answer.
     * Previously any exception collapsed to "absent", so verifying on a bad
     * connection demoted perfectly good published posts to NEEDS_FIXING.
     */
    fun verifyPost(post: PostEntity) {
        val github = githubClient() ?: run {
            snackbar.send("GitHub is not configured — scan the GitHub QR in Settings")
            return
        }
        viewModelScope.launch {
            _verifying.update { it + post.id }
            try {
                val postPath = post.publishedPath ?: "_posts/${post.localDate}-${post.slug}.md"
                val postSha = github.getFileSha(postPath)

                val bases = listOfNotNull(
                    post.publishedMediaBase?.trimEnd('/'),
                    appPrefs.s3MediaConfig()?.staticUrl?.trimEnd('/'),
                    appPrefs.httpMediaConfig()?.staticUrl?.trimEnd('/'),
                ).distinct()

                val mediaList = postRepository.getMediaForPost(post.id)
                var missingMedia = 0
                for (media in mediaList) {
                    when (mediaPresence(media, post.slug, bases, github)) {
                        Presence.Present -> {}
                        Presence.Absent -> missingMedia++
                        Presence.Unknown -> {
                            snackbar.send("Verify incomplete — couldn't reach the server. Post left as-is.")
                            return@launch
                        }
                    }
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: NetworkFailure) {
                snackbar.send(e.userMessage)
            } catch (e: Exception) {
                snackbar.send("Verify failed: ${e.message}")
            } finally {
                _verifying.update { it - post.id }
            }
        }
    }

    /**
     * Probe one media across every URL the published markdown could use.
     * Unknown wins over Absent: if any probe was inconclusive we cannot
     * claim the file is missing.
     */
    private suspend fun mediaPresence(
        media: PostMediaEntity,
        slug: String,
        bases: List<String>,
        github: GitHubClient,
    ): Presence {
        val hash = media.processedHash ?: return Presence.Absent // never published
        val ext = if (media.mimeType.startsWith("video")) "mp4" else "jpg"
        val name = "$hash.$ext"

        var sawUnknown = false
        for (base in bases) {
            when (headPresence("$base/static/$slug/$name")) {
                Presence.Present -> return Presence.Present
                Presence.Unknown -> sawUnknown = true
                Presence.Absent -> {}
            }
        }

        try {
            if (github.getFileSha("static/$slug/$name") != null) return Presence.Present
        } catch (e: NetworkFailure) {
            if (e.kind != NetworkFailure.Kind.NOT_FOUND) sawUnknown = true
        }

        return if (sawUnknown) Presence.Unknown else Presence.Absent
    }

    private suspend fun headPresence(url: String): Presence = try {
        val status = http.head(url).status
        when {
            status.isSuccess() -> Presence.Present
            status == HttpStatusCode.NotFound -> Presence.Absent
            // 5xx, 401, 403: the file may well be there; we simply cannot tell.
            else -> Presence.Unknown
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (e.isTransportFailure()) Presence.Unknown else Presence.Absent
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
