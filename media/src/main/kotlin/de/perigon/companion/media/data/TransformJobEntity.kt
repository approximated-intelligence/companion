package de.perigon.companion.media.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

enum class TransformJobStatus { PENDING, RUNNING, DONE, FAILED }

@Entity(tableName = "transform_jobs")
data class TransformJobEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceUri: String,
    val displayName: String,
    val mediaType: String,
    val status: TransformJobStatus = TransformJobStatus.PENDING,
    val progress: Int = 0,
    val outputPath: String? = null,
    val error: String? = null,
    val boxPx: Int = 960,
    val quality: Int = 85,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = Long.MAX_VALUE,
    val cropLeft: Float = 0f,
    val cropTop: Float = 0f,
    val cropRight: Float = 1f,
    val cropBottom: Float = 1f,
    val orientationDegrees: Int = 0,
    val flipHorizontal: Boolean = false,
    val fineRotationDegrees: Float = 0f,
    val callerTag: String = "",
    // "JPEG" | "PNG" | "AVIF" | "H264" | "AV1". One value space: the still
    // entries are used on IMAGE rows, the video entries on VIDEO rows; each
    // is ignored on the other. Defaults match the prior JPEG/H.264 behavior.
    val outputFormat: String = "JPEG",
    val createdAt: Long = System.currentTimeMillis(),
) {
    val hasCrop: Boolean
        get() = cropLeft != 0f || cropTop != 0f || cropRight != 1f || cropBottom != 1f

    val hasOrientation: Boolean
        get() = orientationDegrees != 0 || flipHorizontal

    val hasFineRotation: Boolean
        get() = fineRotationDegrees != 0f

    val hasAnyTransform: Boolean
        get() = hasCrop || hasOrientation || hasFineRotation ||
                trimStartMs > 0L || trimEndMs != Long.MAX_VALUE

    /** Extension the output file will carry. AV1-in-MP4 is still .mp4. */
    val outputExtension: String
        get() = when (outputFormat) {
            "PNG"          -> "png"
            "AVIF"         -> "avif"
            "H264", "AV1"  -> "mp4"
            else           -> "jpg"
        }

    /** MIME type the output file will carry. */
    val outputMimeType: String
        get() = when (outputFormat) {
            "PNG"  -> "image/png"
            "AVIF" -> "image/avif"
            "H264", "AV1" -> "video/mp4"
            else   -> "image/jpeg"
        }
}

@Dao
interface TransformJobDao {
    @Query("SELECT * FROM transform_jobs ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<TransformJobEntity>>

    @Query("SELECT * FROM transform_jobs WHERE id = :id")
    fun observeById(id: Long): Flow<TransformJobEntity?>

    @Query("SELECT * FROM transform_jobs WHERE id = :id")
    suspend fun getById(id: Long): TransformJobEntity?

    @Query("SELECT * FROM transform_jobs WHERE status = :status ORDER BY createdAt ASC LIMIT 1")
    suspend fun nextPending(status: TransformJobStatus = TransformJobStatus.PENDING): TransformJobEntity?

    @Query("SELECT COUNT(*) FROM transform_jobs WHERE status = :status")
    fun countByStatus(status: TransformJobStatus): Flow<Int>

    @Insert
    suspend fun insert(entity: TransformJobEntity): Long

    @Query("UPDATE transform_jobs SET status = :status, error = :error WHERE id = :id")
    suspend fun updateStatus(id: Long, status: TransformJobStatus, error: String? = null)

    @Query("UPDATE transform_jobs SET status = 'DONE', outputPath = :outputPath, progress = 100 WHERE id = :id")
    suspend fun updateCompleted(id: Long, outputPath: String)

    @Query("UPDATE transform_jobs SET status = 'PENDING' WHERE status = 'RUNNING'")
    suspend fun resetRunningToPending()

    @Query("DELETE FROM transform_jobs WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM transform_jobs WHERE status = :status")
    suspend fun deleteByStatus(status: TransformJobStatus)

    @Query("DELETE FROM transform_jobs WHERE status IN ('DONE', 'FAILED')")
    suspend fun deleteFinished()

    @Query("SELECT * FROM transform_jobs WHERE callerTag = :tag AND status IN ('PENDING', 'RUNNING')")
    suspend fun findActiveByTag(tag: String): List<TransformJobEntity>

    @Query("SELECT * FROM transform_jobs WHERE callerTag = :tag ORDER BY createdAt DESC")
    fun observeByTag(tag: String): Flow<List<TransformJobEntity>>
}
