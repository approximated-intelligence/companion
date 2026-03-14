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
    val contentHash: String,
    val mimeType: String = "image/jpeg",
    val position: Int = 0,
    val mediaStoreUri: String = "",
    val sourceUri: String = "",
    val addedAt: Long,
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
