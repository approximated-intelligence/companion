package de.perigon.companion.audio.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * One row per recording. Written when recording starts (uri + format + name),
 * updated on stop with durationMs and sizeBytes.
 *
 * A row with durationMs == null represents an orphaned recording (service
 * died mid-capture). The UI finalises these with best-effort file metadata
 * on next app start.
 */
@Entity(
    tableName = "audio_recordings",
    indices = [Index("createdAt")],
)
data class AudioRecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val uri: String,
    val format: String,
    val sampleRateHz: Int,
    val bitrateBps: Int,
    val createdAt: Long = System.currentTimeMillis(),
    val durationMs: Long? = null,
    val sizeBytes: Long? = null,
)

@Dao
interface AudioRecordingDao {
    @Query("SELECT * FROM audio_recordings ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<AudioRecordingEntity>>

    @Query("SELECT * FROM audio_recordings WHERE id = :id")
    suspend fun getById(id: Long): AudioRecordingEntity?

    @Query("SELECT * FROM audio_recordings WHERE durationMs IS NULL")
    suspend fun getOrphaned(): List<AudioRecordingEntity>

    @Insert
    suspend fun insert(entity: AudioRecordingEntity): Long

    @Query("UPDATE audio_recordings SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("UPDATE audio_recordings SET durationMs = :durationMs, sizeBytes = :sizeBytes WHERE id = :id")
    suspend fun finalize(id: Long, durationMs: Long, sizeBytes: Long)

    @Query("DELETE FROM audio_recordings WHERE id = :id")
    suspend fun delete(id: Long)
}
