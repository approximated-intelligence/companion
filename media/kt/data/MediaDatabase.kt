package de.perigon.companion.media.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [TransformJobEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class MediaDatabase : RoomDatabase() {
    abstract fun transformJobDao(): TransformJobDao

    companion object {
        /** v1 -> v2: job gains a single output-format column. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE transform_jobs ADD COLUMN outputFormat TEXT NOT NULL DEFAULT 'JPEG'")
            }
        }
    }
}
