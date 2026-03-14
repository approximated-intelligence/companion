package de.perigon.companion.posts.data

import androidx.room.withTransaction
import de.perigon.companion.core.db.AppDatabase
import de.perigon.companion.media.domain.TransformIntent
import de.perigon.companion.posts.domain.Post
import de.perigon.companion.posts.domain.PostPublishState
import javax.inject.Inject
import javax.inject.Singleton
import de.perigon.companion.posts.domain.computeSlug

@Singleton
class PostRepository @Inject constructor(
    private val db: AppDatabase,
    private val postDao: PostDao,
    private val postMediaDao: PostMediaDao,
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
        publishState: PostPublishState,
    ): Long {
        val slug = Post(localDate = localDate, title = title).computeSlug()
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

    suspend fun removeMediaFromPost(mediaId: Long) = postMediaDao.delete(mediaId)

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
        db.withTransaction {
            postMediaDao.deleteForPost(postId)
            postDao.delete(postId)
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
}
