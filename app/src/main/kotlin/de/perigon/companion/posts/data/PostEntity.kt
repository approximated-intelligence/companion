package de.perigon.companion.posts.data

import androidx.room.*
import de.perigon.companion.posts.domain.PostPublishState
import kotlinx.coroutines.flow.Flow
import de.perigon.companion.posts.domain.Post

@Entity(tableName = "posts")
data class PostEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val localDate: String,
    val motto: String = "",
    val title: String,
    val titleEdited: Boolean = false,
    val body: String = "",
    val teaser: String = "",
    val teaserEdited: Boolean = false,
    val tags: String = "",
    val pinnedMediaId: Long? = null,
    val slug: String = "",
    val publishState: PostPublishState = PostPublishState.DRAFT,
    val publishedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

@Dao
interface PostDao {
    @Query("SELECT * FROM posts ORDER BY localDate DESC")
    fun observeAll(): Flow<List<PostEntity>>

    @Query("SELECT * FROM posts WHERE id = :id")
    suspend fun getById(id: Long): PostEntity?

    @Query("SELECT * FROM posts WHERE publishState = 'QUEUED'")
    suspend fun getQueued(): List<PostEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(post: PostEntity): Long

    @Query(
        "UPDATE posts SET localDate = :localDate, title = :title, motto = :motto, " +
        "body = :body, teaser = :teaser, teaserEdited = :teaserEdited, " +
        "titleEdited = :titleEdited, tags = :tags, " +
        "pinnedMediaId = :pinnedMediaId, slug = :slug, publishState = :publishState, " +
        "updatedAt = :updatedAt WHERE id = :id"
    )
    suspend fun updateDraft(
        id: Long,
        localDate: String,
        motto: String,
        title: String,
        titleEdited: Boolean,
        body: String,
        teaser: String,
        teaserEdited: Boolean,
        tags: String,
        pinnedMediaId: Long?,
        slug: String,
        publishState: PostPublishState,
        updatedAt: Long,
    )

    @Query("DELETE FROM posts WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE posts SET publishState = :state, publishedAt = :at, updatedAt = :at WHERE id = :id")
    suspend fun updatePublishState(id: Long, state: PostPublishState, at: Long = System.currentTimeMillis())

    @Query("UPDATE posts SET pinnedMediaId = :mediaId, updatedAt = :at WHERE id = :id")
    suspend fun updatePinnedMedia(id: Long, mediaId: Long?, at: Long = System.currentTimeMillis())
}

// --- Merged from PostEntityMapper.kt ---

fun PostEntity.toDomain(): Post = Post(
    id            = id,
    localDate     = localDate,
    motto         = motto,
    title         = title,
    titleEdited   = titleEdited,
    body          = body,
    teaser        = teaser,
    teaserEdited  = teaserEdited,
    tags          = tags.split(",").map { it.trim() }.filter { it.isNotBlank() },
    pinnedMediaId = pinnedMediaId,
    slug          = slug,
    publishState  = publishState,
    publishedAt   = publishedAt,
    createdAt     = createdAt,
    updatedAt     = updatedAt,
)

