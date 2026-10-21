package de.perigon.companion.sensors.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class CurrentTrackStateConverter {
    @TypeConverter fun fromState(state: CurrentTrackState): String = state.name
    @TypeConverter fun toState(value: String): CurrentTrackState = CurrentTrackState.valueOf(value)
}

/** v1 → v2:
 * - add barometric altitude to track_points (nullable, no default needed).
 * - recreate the view track_point_rows to include baroAlt.
 * - add track_stats.pointCount to track_stats, the point count the stats were computed from.
 *   Existing rows get 0, which never matches a live count, so stale stats are recomputed on first read (see TrackRepository.ensureStats).
 **/
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE track_points ADD COLUMN baroAlt REAL")
        db.execSQL("DROP VIEW IF EXISTS track_point_rows")
        db.execSQL("CREATE VIEW `track_point_rows` AS SELECT t.id AS trackId, t.date AS trackDate, t.name AS trackName, s.id AS segmentId, s.startedAt AS segmentStartedAt, p.id AS pointId, p.lat, p.lon, p.ele, p.undulation, p.baroAlt, p.accuracyM, p.speedMs, p.bearing, p.time, p.provider FROM track_points p JOIN track_segments s ON s.id = p.segmentId JOIN tracks t ON t.id = s.trackId ORDER BY t.id ASC, s.id ASC, p.id ASC")
        db.execSQL("ALTER TABLE track_stats ADD COLUMN pointCount INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * v2 → v3: Kotlin-only change. `lat`/`lon` in TrackPointEntity and
 * TrackPointRow go from Float to Double to carry the full precision of the
 * underlying Location values into GPX export and distance math. SQLite REAL
 * is already double-precision, so no DDL is needed — the migration body is
 * deliberately empty.
 *
 * Existing rows keep whatever precision they were written with (old Float
 * storage at ~0.4 m granularity at lat=48). New writes carry the full
 * Location value. This is a one-way ratchet in favour of new data; old data
 * cannot be un-quantized.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // REAL == REAL. Nothing to do.
    }
}

/** Recordings are database objects until explicitly exported as GPX. */
@Database(
    entities = [
        TrackEntity::class,
        TrackSegmentEntity::class,
        TrackPointEntity::class,
        TrackStatsEntity::class,
        CurrentTrackEntity::class,
    ],
    views = [TrackPointRow::class],
    version = 3,
    exportSchema = true,
)
@TypeConverters(CurrentTrackStateConverter::class)
abstract class TrackDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
}
