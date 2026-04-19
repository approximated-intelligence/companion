package de.perigon.companion.media.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import dagger.hilt.android.qualifiers.ApplicationContext
import de.perigon.companion.core.prefs.AppPrefs
import de.perigon.companion.media.domain.TransformPreviewGenerator
import de.perigon.companion.media.domain.toTransformIntent
import de.perigon.companion.posts.data.MediaSourceStatus
import de.perigon.companion.posts.data.PostMediaEntity
import de.perigon.companion.posts.data.PostMediaFileStore
import de.perigon.companion.posts.data.ResolvedMedia
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

// =============================================================================
// MediaResolutionCache
// =============================================================================
//
// Resolves PostMediaEntity → ResolvedMedia (either a local Uri or a remote
// public URL). Never downloads. Coil handles fetching and caching of remote
// URLs; this cache only decides *where* an image lives.
//
// Resolution order:
//   1. Local sourceUri readable      → local POST_MEDIA
//   2. Local mediaStoreUri readable  → local POST_MEDIA
//   3. S3 configured + slug known    → https://{s3StaticUrl}/static/{slug}/{hash}.{ext}
//   4. HTTP configured + slug known  → https://{httpStaticUrl}/static/{slug}/{hash}.{ext}
//   5. siteUrl + slug known          → https://{siteUrl}/static/{slug}/{hash}.{ext}
//   6. Otherwise                     → GONE
//
// All IO (the readability checks) runs on Dispatchers.IO; composables call
// get() which is non-blocking and returns cached state immediately.
// =============================================================================

private data class ResolutionKey(
    val id: Long,
    val sourceUri: String,
    val mediaStoreUri: String,
    val contentHash: String,
    val slug: String,
)

private fun PostMediaEntity.key(slug: String?) =
    ResolutionKey(id, sourceUri, mediaStoreUri, contentHash, slug ?: "")

@Singleton
class MediaResolutionCache @Inject constructor(
    private val mediaFileStore: PostMediaFileStore,
    private val appPrefs: AppPrefs,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _resolved = MutableStateFlow<Map<Long, ResolvedMedia>>(emptyMap())
    val resolved: StateFlow<Map<Long, ResolvedMedia>> = _resolved.asStateFlow()

    private val keys = mutableMapOf<Long, ResolutionKey>()
    private val keysLock = Mutex()

    private val inFlight = mutableMapOf<Long, Deferred<ResolvedMedia>>()
    private val inFlightLock = Mutex()

    /** Non-blocking. Returns cached value or triggers background resolution. */
    fun get(entity: PostMediaEntity, slug: String? = null): ResolvedMedia {
        val currentKey = entity.key(slug)
        val cached = _resolved.value[entity.id]
        val cachedKey = runCatching { keys[entity.id] }.getOrNull()

        if (cached != null && cachedKey == currentKey) return cached

        scope.launch { resolveAsync(entity, slug) }
        return cached ?: ResolvedMedia(Uri.EMPTY, MediaSourceStatus.GONE)
    }

    fun prime(entities: List<PostMediaEntity>, slugByPostId: Map<Long, String> = emptyMap()) {
        scope.launch {
            for (entity in entities) resolveAsync(entity, slugByPostId[entity.postId])
        }
    }

    fun invalidate(mediaId: Long) {
        _resolved.update { it - mediaId }
        scope.launch { keysLock.withLock { keys.remove(mediaId) } }
    }

    private suspend fun resolveAsync(entity: PostMediaEntity, slug: String?): ResolvedMedia {
        val currentKey = entity.key(slug)

        val deferred = inFlightLock.withLock {
            inFlight[entity.id]?.let { return@withLock it }
            val d = scope.async {
                val result = withContext(Dispatchers.IO) { resolve(entity, slug) }
                _resolved.update { it + (entity.id to result) }
                keysLock.withLock { keys[entity.id] = currentKey }
                result
            }
            inFlight[entity.id] = d
            d
        }

        return try { deferred.await() } finally {
            inFlightLock.withLock {
                if (inFlight[entity.id] === deferred) inFlight.remove(entity.id)
            }
        }
    }

    /** Pure resolution — runs on IO. Tries local first, then falls back to remote URL. */
    private fun resolve(entity: PostMediaEntity, slug: String?): ResolvedMedia {
        // Local first. mediaFileStore.resolveDisplayMedia encapsulates the
        // local-file checks and content-resolver readability probes.
        val local = mediaFileStore.resolveDisplayMedia(entity)
        if (local.status != MediaSourceStatus.GONE) return local

        // No local file — construct a remote URL from the active media backend.
        if (slug.isNullOrBlank()) return ResolvedMedia(Uri.EMPTY, MediaSourceStatus.GONE)

        val ext = if (entity.mimeType.startsWith("video")) "mp4" else "jpg"
        val path = "static/$slug/${entity.contentHash}.$ext"

        val base = remoteBaseUrl() ?: return ResolvedMedia(Uri.EMPTY, MediaSourceStatus.GONE)
        val url = "${base.trimEnd('/')}/$path"
        return ResolvedMedia(Uri.parse(url), MediaSourceStatus.POST_MEDIA)
    }

    /** Pick the active remote base URL. S3 → HTTP → Jekyll site. */
    private fun remoteBaseUrl(): String? {
        appPrefs.s3MediaConfig()?.staticUrl?.takeIf { it.isNotBlank() }?.let { return it }
        appPrefs.httpMediaConfig()?.staticUrl?.takeIf { it.isNotBlank() }?.let { return it }
        appPrefs.siteUrl()?.takeIf { it.isNotBlank() }?.let { return it }
        return null
    }
}

// =============================================================================
// TransformPreviewCache
// =============================================================================
//
// Process-wide bitmap cache for transform previews. Survives VM lifecycle so
// navigating PostList → PostEdit → PostList doesn't regenerate.
//
// Bounded by bytes (~20MB). Keyed by (mediaId, transformIntentHash) so edits
// auto-invalidate. In-flight generations dedupe via Mutex-guarded Deferreds.
// =============================================================================

private const val PREVIEW_CACHE_BYTES = 20 * 1024 * 1024 // 20MB

private data class PreviewKey(val mediaId: Long, val intentHash: Int)

private fun PostMediaEntity.previewKey() = PreviewKey(id, toTransformIntent().hashCode())

@Singleton
class TransformPreviewCache @Inject constructor(
    @param:ApplicationContext private val ctx: Context,
    private val generator: TransformPreviewGenerator,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val lru = object : LruCache<PreviewKey, Bitmap>(PREVIEW_CACHE_BYTES) {
        override fun sizeOf(key: PreviewKey, value: Bitmap): Int = value.byteCount
    }
    private val lruLock = Mutex()

    private val _previews = MutableStateFlow<Map<Long, Bitmap>>(emptyMap())
    val previews: StateFlow<Map<Long, Bitmap>> = _previews.asStateFlow()

    private val inFlight = mutableMapOf<PreviewKey, Deferred<Bitmap?>>()
    private val inFlightLock = Mutex()

    /** Get cached preview or kick off generation. Null for identity intents. */
    fun get(entity: PostMediaEntity): Bitmap? {
        val intent = entity.toTransformIntent()
        if (intent.isIdentity) {
            if (_previews.value.containsKey(entity.id)) _previews.update { it - entity.id }
            return null
        }

        val key = entity.previewKey()
        val cached = lru.get(key)
        val published = _previews.value[entity.id]

        if (cached != null) {
            if (published !== cached) _previews.update { it + (entity.id to cached) }
            return cached
        }

        // Stale publish — bitmap under this id was from an older intent.
        if (published != null) _previews.update { it - entity.id }

        scope.launch { generateAsync(entity, key) }
        return null
    }

    /** Eagerly refresh after a transform intent changes. */
    fun refresh(entity: PostMediaEntity) {
        val intent = entity.toTransformIntent()
        if (intent.isIdentity) {
            _previews.update { it - entity.id }
            return
        }
        scope.launch { generateAsync(entity, entity.previewKey()) }
    }

    private suspend fun generateAsync(entity: PostMediaEntity, key: PreviewKey): Bitmap? {
        val deferred = inFlightLock.withLock {
            inFlight[key]?.let { return@withLock it }
            val d = scope.async {
                val existing = lruLock.withLock { lru.get(key) }
                if (existing != null) return@async existing

                val bmp = generator.generate(entity)
                if (bmp != null) {
                    lruLock.withLock { lru.put(key, bmp) }
                    _previews.update { it + (entity.id to bmp) }
                }
                bmp
            }
            inFlight[key] = d
            d
        }

        return try { deferred.await() } finally {
            inFlightLock.withLock {
                if (inFlight[key] === deferred) inFlight.remove(key)
            }
        }
    }
}
