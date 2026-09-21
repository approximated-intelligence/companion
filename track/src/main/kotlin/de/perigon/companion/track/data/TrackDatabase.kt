package de.perigon.companion.track.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class CurrentTrackStateConverter {
    @TypeConverter fun fromState(state: CurrentTrackState): String = state.name
    @TypeConverter fun toState(value: String): CurrentTrackState = CurrentTrackState.valueOf(value)
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
    version = 1,
    exportSchema = true,
)
@TypeConverters(CurrentTrackStateConverter::class)
abstract class TrackDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
}
