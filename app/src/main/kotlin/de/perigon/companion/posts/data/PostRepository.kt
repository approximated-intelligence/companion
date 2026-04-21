package de.perigon.companion.posts.data

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.room.withTransaction
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import de.perigon.companion.core.db.AppDatabase
import de.perigon.companion.media.data.ConsolidateRepository
import de.perigon.companion.media.domain.TransformIntent
import de.perigon.companion.posts.domain.Post
import de.perigon.companion.posts.domain.PostPublishState
import de.perigon.companion.posts.worker.PublishWorker
import de.perigon.companion.posts.worker.UnpublishWorker
import de.perigon.companion.util.FileHasher
import javax.inject.Inject
import javax.inject.Singleton
import de.perigon.companion.posts.domain.computeSlug
import dagger.hilt.android.qualifiers.ApplicationContext

sealed class PublishResult {
    object Queued : PublishResult()
    object NeedsDcimPermission : PublishResult()
}

@Singleton
class PostRepository @Inject constructor(
    @param:ApplicationContext private val ctx: Context,
    private val db: AppDatabase,
    private val postDao: PostDao,
    private val postMediaDao: PostMediaDao,
    private val consolidateRepo: ConsolidateRepository,
    private val mediaFileStore: PostMediaFileStore,
    private val workManager: WorkManager,
    private val hasher: FileHasher,
) {
    suspend fun getById(id: Long): PostEntity? = postDao.getById(id)

    suspend fun getMediaForPost(postId: Long): List<PostMediaEntity> =
        postMediaDao.getForPost(postId)

    suspend fun getMediaById(mediaId: Long): PostMediaEntity? =
        postMediaDao.getById(mediaId)

    suspend fun saveDraft(
        id: Long,
        localDate: String,
        motto: String,
        title: String,
        titleEdited: Boolean,
        body: String,
        teaser: String,
        teaserEdited: Boolean,
        tags: List<String>,
        pinnedMediaId: Long?,
        slug: String,
        slugEdited: Boolean,
        publishState: PostPublishState,
    ): Long {
        val tagsStr = tags.joinToString(",")
        val now = System.currentTimeMillis()
        return if (id == 0L) {
            val entity = PostEntity(
                localDate = localDate,
                motto = motto,
                title = title,
                titleEdited = titleEdited,
                body = body,
                teaser = teaser,
                teaserEdited = teaserEdited,
                tags = tagsStr,
                pinnedMediaId = pinnedMediaId,
                slug = slug,
                slugEdited = slugEdited,
                publishState = publishState,
                createdAt = now,
                updatedAt = now,
            )
            postDao.upsert(entity)
        } else {
            postDao.updateDraft(
                id = id,
                localDate = localDate,
                motto = motto,
                title = title,
                titleEdited = titleEdited,
                body = body,
                teaser = teaser,
                teaserEdited = teaserEdited,
                tags = tagsStr,
                pinnedMediaId = pinnedMediaId,
                slug = slug,
                slugEdited = slugEdited,
                publishState = publishState,
                updatedAt = now,
            )
            id
        }
    }

    suspend fun addMediaToPost(postId: Long, media: PostMediaEntity): PostMediaEntity {
        val withPost = media.copy(id = 0, postId = postId)
        val newId = postMediaDao.upsert(withPost)
        protectSourceIfNeeded(withPost)
        return withPost.copy(id = newId)
    }

    suspend fun removeMediaFromPost(mediaId: Long) {
        val media = postMediaDao.getById(mediaId)
        postMediaDao.delete(mediaId)
        if (media != null) unprotectSourceIfNeeded(media)
    }

    suspend fun updateMediaPositions(items: List<PostMediaEntity>) {
        for (item in items) {
            if (item.id != 0L) {
                postMediaDao.updatePosition(item.id, item.position)
            }
        }
    }

    suspend fun updateTransformIntent(mediaId: Long, intent: TransformIntent) {
        postMediaDao.updateTransformIntent(
            id = mediaId,
            cropLeft = intent.cropRect.left,
            cropTop = intent.cropRect.top,
            cropRight = intent.cropRect.right,
            cropBottom = intent.cropRect.bottom,
            orientationDegrees = intent.orientation.rotationDegrees,
            flipHorizontal = intent.orientation.flipHorizontal,
            fineRotationDegrees = intent.fineRotationDegrees,
            trimStartMs = intent.trimStartMs,
            trimEndMs = intent.trimEndMs,
        )
    }

    suspend fun deletePost(postId: Long) {
        val media = postMediaDao.getForPost(postId)
        db.withTransaction {
            postMediaDao.deleteForPost(postId)
            postDao.delete(postId)
        }
        for (m in media) unprotectSourceIfNeeded(m)
    }

    suspend fun deletePostAndUnpublish(post: PostEntity) {
        val wasPublished = post.publishState in listOf(
            PostPublishState.PUBLISHED, PostPublishState.NEEDS_FIXING)
        deletePost(post.id)
        if (wasPublished && post.slug.isNotBlank()) {
            workManager.enqueueUniqueWork(
                "unpublish_${post.id}",
                ExistingWorkPolicy.KEEP,
                UnpublishWorker.buildRequest(
                    postId = post.id,
                    slug = post.slug,
                    localDate = post.localDate,
                    deleteLocal = true,
                ),
            )
        }
    }

    suspend fun deleteMedia(mediaId: Long) = postMediaDao.delete(mediaId)

    suspend fun updatePublishState(postId: Long, state: PostPublishState, at: Long = System.currentTimeMillis()) =
        postDao.updatePublishState(postId, state, at)

    suspend fun updatePinnedMedia(postId: Long, mediaId: Long?) =
        postDao.updatePinnedMedia(postId, mediaId)

    suspend fun getQueued(): List<PostEntity> = postDao.getQueued()

    fun observeAll() = postDao.observeAll()

    fun observeMediaForPost(postId: Long) = postMediaDao.observeForPost(postId)

    // ── Publish / Unpublish ──

    suspend fun publish(postId: Long): PublishResult {
        if (!mediaFileStore.hasWritePermission()) {
            return PublishResult.NeedsDcimPermission
        }
        postDao.updatePublishState(postId, PostPublishState.QUEUED)
        workManager.enqueueUniqueWork(
            "publish_$postId",
            ExistingWorkPolicy.KEEP,
            PublishWorker.buildRequest(postId),
        )
        return PublishResult.Queued
    }

    suspend fun unpublish(post: PostEntity) {
        postDao.updatePublishState(post.id, PostPublishState.QUEUED)
        workManager.enqueueUniqueWork(
            "unpublish_${post.id}",
            ExistingWorkPolicy.KEEP,
            UnpublishWorker.buildRequest(
                postId = post.id,
                slug = post.slug,
                localDate = post.localDate,
                deleteLocal = false,
            ),
        )
    }

    // ── Source protection for consolidation ──

    suspend fun unprotectAllMedia(postId: Long) {
        val media = postMediaDao.getForPost(postId)
        for (m in media) unprotectSourceIfNeeded(m)
    }

    private suspend fun protectSourceIfNeeded(media: PostMediaEntity) {
        val cf = resolveConsolidateFile(media.sourceUri) ?: return
        consolidateRepo.protect(cf.id)
    }

    private suspend fun unprotectSourceIfNeeded(media: PostMediaEntity) {
        val cf = resolveConsolidateFile(media.sourceUri) ?: return
        consolidateRepo.unprotect(cf.id)
    }

    /**
     * Resolve a MediaStore source URI to its ConsolidateFileEntity, if any.
     *
     * The consolidate identity is (path, sha256). MediaStore gives us path,
     * mtime and size — we use those to hit the shared FileHasher cache (or
     * hash on miss) and then look up by (path, sha256).
     */
    private suspend fun resolveConsolidateFile(sourceUri: String) =
        run {
            if (sourceUri.isBlank()) return@run null
            val uri = Uri.parse(sourceUri)
            if (uri.scheme != "content") return@run null
            val meta = queryMeta(uri) ?: return@run null
            val sha = hasher.hashOrCached(meta.path, meta.mtime, meta.size) {
                ctx.contentResolver.openInputStream(uri)
                    ?: error("openInputStream returned null")
            } ?: return@run null
            consolidateRepo.findByPathSha256(meta.path, sha)
        }

    private data class ConsolidateMeta(val path: String, val mtime: Long, val size: Long)

    private fun queryMeta(uri: Uri): ConsolidateMeta? {
        val projection = arrayOf(
            MediaStore.Files.FileColumns.RELATIVE_PATH,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.SIZE,
        )
        return try {
            ctx.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                val relPath = cursor.getString(0) ?: return null
                val name = cursor.getString(1) ?: return null
                val mtime = cursor.getLong(2) * 1000L
                val size = cursor.getLong(3)
                ConsolidateMeta(path = relPath + name, mtime = mtime, size = size)
            }
        } catch (_: Exception) {
            null
        }
    }
}
