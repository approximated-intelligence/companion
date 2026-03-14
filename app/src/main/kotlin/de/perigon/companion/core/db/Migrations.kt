package de.perigon.companion.core.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE track_points ADD COLUMN undulation REAL")
        db.execSQL("ALTER TABLE track_points ADD COLUMN speedMs REAL")
        db.execSQL("ALTER TABLE track_points ADD COLUMN bearing REAL")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS track_stats (
                trackId INTEGER NOT NULL PRIMARY KEY,
                durationMs INTEGER NOT NULL,
                recordingLengthMs INTEGER NOT NULL,
                distanceM REAL NOT NULL,
                timeMovingMs INTEGER NOT NULL,
                FOREIGN KEY (trackId) REFERENCES tracks(id) ON DELETE CASCADE
            )
        """)
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_track_stats_trackId ON track_stats (trackId)")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS current_track (
                id INTEGER NOT NULL PRIMARY KEY,
                trackId INTEGER NOT NULL,
                state TEXT NOT NULL,
                FOREIGN KEY (trackId) REFERENCES tracks(id) ON DELETE CASCADE
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS index_current_track_trackId ON current_track (trackId)")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Fix provider column if missing
        val cursor = db.query("PRAGMA table_info(track_points)")
        val hasProvider = generateSequence { if (cursor.moveToNext()) cursor else null }
            .any { it.getString(it.getColumnIndexOrThrow("name")) == "provider" }
        cursor.close()
        if (!hasProvider) {
            db.execSQL("ALTER TABLE track_points ADD COLUMN provider TEXT NOT NULL DEFAULT 'gps'")
        }

        // Recreate current_track — table exists but is corrupt (empty schema)
        db.execSQL("DROP TABLE IF EXISTS current_track")
        db.execSQL("""
            CREATE TABLE current_track (
                id INTEGER NOT NULL PRIMARY KEY,
                trackId INTEGER NOT NULL,
                state TEXT NOT NULL,
                FOREIGN KEY (trackId) REFERENCES tracks(id) ON DELETE CASCADE
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS index_current_track_trackId ON current_track (trackId)")
    }
}