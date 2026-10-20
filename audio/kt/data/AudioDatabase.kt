package de.perigon.companion.audio.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [AudioRecordingEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AudioDatabase : RoomDatabase() {
    abstract fun audioRecordingDao(): AudioRecordingDao
}
