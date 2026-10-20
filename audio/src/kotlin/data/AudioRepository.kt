package de.perigon.companion.audio.data

import kotlinx.coroutines.flow.Flow

/**
 * Thin wrapper over [AudioRecordingDao]. Injected into the service and
 * the view model so both can observe and mutate recordings.
 */
class AudioRepository(private val dao: AudioRecordingDao) {

    fun observeAll(): Flow<List<AudioRecordingEntity>> = dao.observeAll()

    suspend fun getById(id: Long): AudioRecordingEntity? = dao.getById(id)

    suspend fun getOrphaned(): List<AudioRecordingEntity> = dao.getOrphaned()

    suspend fun insert(entity: AudioRecordingEntity): Long = dao.insert(entity)

    suspend fun finalize(id: Long, durationMs: Long, sizeBytes: Long) =
        dao.finalize(id, durationMs, sizeBytes)

    suspend fun rename(id: Long, name: String) = dao.rename(id, name.trim())

    suspend fun delete(id: Long) = dao.delete(id)
}
