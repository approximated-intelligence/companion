package de.perigon.companion.posts.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "post_media",
    foreignKeys = [ForeignKey(
        entity = PostEntity::class,
        parentColumns = ["id"],
        childColumns = ["postId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("postId"), Index("contentHash")],
)
data class PostMediaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val postId: Long,
    // sha256 of the ORIGINAL bytes. Also the name of our copy of the
    // original: PostMedia/Originals/<contentHash>.<realExt>.
    val contentHash: String,
    // OUTPUT type (image/jpeg or video/mp4) — what publishing produces. The
    // original's real type lives in its file extension.
    val mimeType: String = "image/jpeg",
    val position: Int = 0,
    // The PROCESSED copy in PostMedia; empty until the first publish.
    val mediaStoreUri: String = "",
    // Our imported original in PostMedia/Originals. Rows created before
    // import existed may still point at gallery URIs until adopted
    // (PostRepository.adoptLegacyOriginals).
    val sourceUri: String = "",
    val addedAt: Long,
    // sha256 of the PROCESSED bytes as uploaded/served. This is the media's
    // published identity: server key and local PostMedia filename are both
    // "<processedHash>.<ext>". Set at publish; stale after an intent edit
    // until the next publish — deliberately, since the server genuinely still
    // holds the previous rendering.
    val processedHash: String? = null,
    // Transform intent - persisted so nothing is lost on crash
    val cropLeft: Float = 0f,
    val cropTop: Float = 0f,
    val cropRight: Float = 1f,
    val cropBottom: Float = 1f,
    val orientationDegrees: Int = 0,
    val flipHorizontal: Boolean = false,
    val fineRotationDegrees: Float = 0f,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = Long.MAX_VALUE,
) {
    val hasCrop: Boolean
        get() = cropLeft != 0f || cropTop != 0f || cropRight != 1f || cropBottom != 1f

    val hasOrientation: Boolean
        get() = orientationDegrees != 0 || flipHorizontal

    val hasFineRotation: Boolean
        get() = fineRotationDegrees != 0f

    val hasTrim: Boolean
        get() = trimStartMs > 0L || trimEndMs != Long.MAX_VALUE

    val hasTransformIntent: Boolean
        get() = hasCrop || hasOrientation || hasFineRotation || hasTrim
}

@Dao
interface PostMediaDao {
    @Query("SELECT * FROM post_media WHERE postId = :postId ORDER BY position ASC")
    fun observeForPost(postId: Long): Flow<List<PostMediaEntity>>

    @Query("SELECT * FROM post_media WHERE postId = :postId ORDER BY position ASC")
    suspend fun getForPost(postId: Long): List<PostMediaEntity>

    @Query("SELECT * FROM post_media WHERE id = :id")
    suspend fun getById(id: Long): PostMediaEntity?

    @Query("SELECT * FROM post_media")
    suspend fun getAll(): List<PostMediaEntity>

    /** How many rows (any post) still reference this original. */
    @Query("SELECT COUNT(*) FROM post_media WHERE contentHash = :contentHash")
    suspend fun countByContentHash(contentHash: String): Int

    /**
     * Reactive query returning one lead media entity per post — the pinned one
     * if set, otherwise the lowest-position media. Replaces the old N+1 pattern
     * of per-post queries in PostListViewModel.
     */
    @Query("""
        SELECT pm.* FROM post_media pm
        INNER JOIN posts p ON pm.postId = p.id
        WHERE pm.id = COALESCE(
            p.pinnedMediaId,
            (SELECT id FROM post_media WHERE postId = p.id ORDER BY position ASC LIMIT 1)
        )
    """)
    fun observeLeadMediaForAllPosts(): Flow<List<PostMediaEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(media: PostMediaEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(media: List<PostMediaEntity>)

    @Query("DELETE FROM post_media WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM post_media WHERE postId = :postId")
    suspend fun deleteForPost(postId: Long)

    @Query("UPDATE post_media SET position = :position WHERE id = :id")
    suspend fun updatePosition(id: Long, position: Int)

    @Query("UPDATE post_media SET sourceUri = :sourceUri WHERE id = :id")
    suspend fun updateSourceUri(id: Long, sourceUri: String)

    @Query("""
        UPDATE post_media SET
            cropLeft = :cropLeft, cropTop = :cropTop,
            cropRight = :cropRight, cropBottom = :cropBottom,
            orientationDegrees = :orientationDegrees,
            flipHorizontal = :flipHorizontal,
            fineRotationDegrees = :fineRotationDegrees,
            trimStartMs = :trimStartMs, trimEndMs = :trimEndMs
        WHERE id = :id
    """)
    suspend fun updateTransformIntent(
        id: Long,
        cropLeft: Float,
        cropTop: Float,
        cropRight: Float,
        cropBottom: Float,
        orientationDegrees: Int,
        flipHorizontal: Boolean,
        fineRotationDegrees: Float,
        trimStartMs: Long,
        trimEndMs: Long,
    )
}
