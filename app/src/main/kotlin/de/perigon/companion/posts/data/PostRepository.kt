package de.perigon.companion.posts.data

import android.net.Uri
import android.util.Log
import androidx.room.withTransaction
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import androidx.work.await
import de.perigon.companion.core.db.AppDatabase
import de.perigon.companion.media.domain.TransformIntent
import de.perigon.companion.posts.domain.PostPublishState
import de.perigon.companion.posts.worker.PublishWorker
import de.perigon.companion.posts.worker.UnpublishWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PostRepository"

sealed class PublishResult {
    object Queued : PublishResult()
    object NeedsDcimPermission : PublishResult()
    data class Failed(val reason: String) : PublishResult()
}

/**
 * Posts and their media rows, plus the lifecycle of imported originals
 * (PostMedia/Originals): created by MediaPrep at add time, released here when
 * the last row referencing them goes away.
 *
 * The former consolidate auto-protection (protect the camera file behind
 * sourceUri while a post needed it) is gone: posts now own a copy of every
 * original, so the camera file is genuinely safe to delete once consolidated
 * and backed up. Manual protection in Consolidate is unaffected.
 */
@Singleton
class PostRepository @Inject constructor(
    private val db: AppDatabase,
    private val postDao: PostDao,
    private val postMediaDao: PostMediaDao,
    private val mediaFileStore: PostMediaFileStore,
    private val workManager: WorkManager,
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
        return withPost.copy(id = newId)
    }

    suspend fun removeMediaFromPost(mediaId: Long) {
        val media = postMediaDao.getById(mediaId) ?: return
        postMediaDao.delete(mediaId)
        releaseOriginals(listOf(media))
    }

    /**
     * Reorder atomically: a crash mid-loop previously left a half-applied
     * ordering (duplicate/skipped positions) that the UI then re-read.
     */
    suspend fun updateMediaPositions(items: List<PostMediaEntity>) {
        db.withTransaction {
            for (item in items) {
                if (item.id != 0L) {
                    postMediaDao.updatePosition(item.id, item.position)
                }
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
        releaseOriginals(media)
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
            ).await()
        }
    }

    /** Same as [removeMediaFromPost] — every removal path releases originals. */
    suspend fun deleteMedia(mediaId: Long) = removeMediaFromPost(mediaId)

    suspend fun updatePublishState(postId: Long, state: PostPublishState, at: Long = System.currentTimeMillis()) =
        postDao.updatePublishState(postId, state, at)

    suspend fun updatePinnedMedia(postId: Long, mediaId: Long?) =
        postDao.updatePinnedMedia(postId, mediaId)

    suspend fun getQueued(): List<PostEntity> = postDao.getQueued()

    fun observeAll() = postDao.observeAll()

    fun observeMediaForPost(postId: Long) = postMediaDao.observeForPost(postId)

    // ── Publish / Unpublish ──

    /**
     * Enqueue a publish and mark the post QUEUED — in that order, and only
     * if the enqueue actually lands.
     *
     * Previously the state was written first and the returned Operation was
     * discarded, so a failed enqueue left the post showing "Queued" with no
     * work behind it and no way back. REPLACE (not KEEP) because a user
     * pressing Publish again means "try again now": KEEP silently dropped
     * the retry whenever a stale unique-work record still existed.
     */
    suspend fun publish(postId: Long): PublishResult {
        if (!mediaFileStore.hasWritePermission()) {
            return PublishResult.NeedsDcimPermission
        }
        return try {
            workManager.enqueueUniqueWork(
                PublishWorker.workName(postId),
                ExistingWorkPolicy.REPLACE,
                PublishWorker.buildRequest(postId),
            ).await()
            postDao.updatePublishState(postId, PostPublishState.QUEUED)
            PublishResult.Queued
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "failed to enqueue publish for $postId", e)
            PublishResult.Failed(e.message ?: "could not schedule publishing")
        }
    }

    suspend fun unpublish(post: PostEntity) {
        workManager.enqueueUniqueWork(
            "unpublish_${post.id}",
            ExistingWorkPolicy.REPLACE,
            UnpublishWorker.buildRequest(
                postId = post.id,
                slug = post.slug,
                localDate = post.localDate,
                deleteLocal = false,
            ),
        ).await()
        postDao.updatePublishState(post.id, PostPublishState.QUEUED)
    }

    /**
     * Clear posts stuck at QUEUED with no work behind them.
     *
     * A worker killed by the system (process death, the background execution
     * cap, a force-stop) leaves the row at QUEUED forever: nothing else ever
     * writes that state back. The UI then shows a spinner for work that will
     * never run — which is exactly how a publish from a previous session
     * ends up spinning days later.
     *
     * Runs at app start. A post whose work is still ENQUEUED/RUNNING/BLOCKED
     * is left alone; anything else becomes NEEDS_FIXING, which is the state
     * that offers a Re-publish button.
     */
    suspend fun reconcileQueuedPosts() {
        val queued = postDao.getQueued()
        if (queued.isEmpty()) return

        for (post in queued) {
            val live = try {
                // first() on the unique-work flow: one snapshot of current
                // state, no ListenableFuture bridging.
                workManager.getWorkInfosForUniqueWorkFlow(PublishWorker.workName(post.id))
                    .first()
                    .any { !it.state.isFinished }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "cannot query work state for post ${post.id}", e)
                true // uncertain — leave the row alone rather than mislabel it
            }
            if (!live) {
                Log.i(TAG, "post ${post.id} was QUEUED with no live work; marking NEEDS_FIXING")
                postDao.updatePublishState(post.id, PostPublishState.NEEDS_FIXING)
            }
        }
    }

    // ── Originals lifecycle ──

    /**
     * Delete imported originals that no row references any more. Content-
     * addressed: one file can back media in several posts, so the check runs
     * by hash AFTER the rows are gone. deleteOriginalIfOwned refuses anything
     * outside PostMedia/Originals, so legacy rows still pointing at gallery
     * files can never cause a gallery deletion.
     */
    private suspend fun releaseOriginals(removed: List<PostMediaEntity>) {
        for (media in removed.distinctBy { it.sourceUri }) {
            if (postMediaDao.countByContentHash(media.contentHash) > 0) continue
            mediaFileStore.deleteOriginalIfOwned(media.sourceUri)
        }
    }

    /**
     * Idempotent, run at app start: rows added before originals were imported
     * still point at gallery/shared URIs. Copy those originals in while they
     * are still readable and repoint the row. Owned rows are skipped without
     * IO; unreadable sources are retried next start; a source whose bytes no
     * longer match contentHash is not the media that was added — its copy is
     * discarded (unless other rows use it) and the row left untouched.
     */
    suspend fun adoptLegacyOriginals() {
        for (media in postMediaDao.getAll()) {
            if (media.sourceUri.isBlank() || mediaFileStore.isOriginalUri(media.sourceUri)) continue

            val imported = try {
                mediaFileStore.importOriginal(Uri.parse(media.sourceUri))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                continue
            }

            if (imported.contentHash != media.contentHash) {
                if (postMediaDao.countByContentHash(imported.contentHash) == 0) {
                    mediaFileStore.deleteOriginalIfOwned(imported.uri)
                }
                continue
            }
            postMediaDao.updateSourceUri(media.id, imported.uri)
        }
    }
}
