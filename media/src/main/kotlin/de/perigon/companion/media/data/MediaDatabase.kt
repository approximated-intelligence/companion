package de.perigon.companion.media.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [TransformJobEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class MediaDatabase : RoomDatabase() {
    abstract fun transformJobDao(): TransformJobDao
}
