package de.perigon.companion.track.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "track_segments",
    foreignKeys = [ForeignKey(
        entity = TrackEntity::class,
        parentColumns = ["id"],
        childColumns = ["trackId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("trackId")],
)
data class TrackSegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: Long,
    val startedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "track_points",
    foreignKeys = [ForeignKey(
        entity = TrackSegmentEntity::class,
        parentColumns = ["id"],
        childColumns = ["segmentId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("segmentId")],
)
data class TrackPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val segmentId: Long,
    val lat: Float,
    val lon: Float,
    val ele: Float? = null,
    val undulation: Float? = null,
    val accuracyM: Float? = null,
    val speedMs: Float? = null,
    val bearing: Float? = null,
    val time: Long,
    val provider: String = "gps",
)

@Entity(
    tableName = "track_stats",
    foreignKeys = [ForeignKey(
        entity = TrackEntity::class,
        parentColumns = ["id"],
        childColumns = ["trackId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("trackId", unique = true)],
)
data class TrackStatsEntity(
    @PrimaryKey val trackId: Long,
    val durationMs: Long,
    val recordingLengthMs: Long,
    val distanceM: Float,
    val timeMovingMs: Long,
)

enum class CurrentTrackState { RECORDING, PAUSED }

@Entity(
    tableName = "current_track",
    foreignKeys = [ForeignKey(
        entity = TrackEntity::class,
        parentColumns = ["id"],
        childColumns = ["trackId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("trackId")],
)
data class CurrentTrackEntity(
    @PrimaryKey val id: Int = 0, // always 0, enforces singleton
    val trackId: Long,
    val state: CurrentTrackState,
)

data class TrackSummary(
    val id: Long,
    val date: String,
    val name: String,
    val createdAt: Long,
    val pointCount: Int,
    @Embedded(prefix = "stats_") val stats: TrackStatsEntity?,
)

data class TrackWithSegments(
    @Embedded val track: TrackEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "trackId",
        entity = TrackSegmentEntity::class,
    )
    val segments: List<TrackSegmentWithPoints>,
)

data class TrackSegmentWithPoints(
    @Embedded val segment: TrackSegmentEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "segmentId",
    )
    val points: List<TrackPointEntity>,
)

@Dao
interface TrackDao {

    @Transaction
    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun getTrackWithSegments(id: Long): TrackWithSegments?

    @Transaction
    @Query("SELECT * FROM tracks ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<TrackWithSegments>>

    @Query("""
        SELECT
            t.id, t.date, t.name, t.createdAt,
            COALESCE(SUM(pc.cnt), 0) AS pointCount,
            ts.trackId           AS stats_trackId,
            ts.durationMs        AS stats_durationMs,
            ts.recordingLengthMs AS stats_recordingLengthMs,
            ts.distanceM         AS stats_distanceM,
            ts.timeMovingMs      AS stats_timeMovingMs
        FROM tracks t
        LEFT JOIN track_segments s ON s.trackId = t.id
        LEFT JOIN (
            SELECT segmentId, COUNT(*) AS cnt FROM track_points GROUP BY segmentId
        ) pc ON pc.segmentId = s.id
        LEFT JOIN track_stats ts ON ts.trackId = t.id
        GROUP BY t.id
        ORDER BY t.createdAt DESC
    """)
    fun observeSummaries(): Flow<List<TrackSummary>>

    @Query("SELECT * FROM tracks WHERE date = :date LIMIT 1")
    suspend fun getByDate(date: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun getTrackById(id: Long): TrackEntity?

    @Insert
    suspend fun insertTrack(track: TrackEntity): Long

    @Query("UPDATE tracks SET name = :name WHERE id = :id")
    suspend fun renameTrack(id: Long, name: String)

    @Insert
    suspend fun insertSegment(segment: TrackSegmentEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPoints(points: List<TrackPointEntity>)

    @Query("DELETE FROM tracks WHERE id = :id")
    suspend fun deleteTrack(id: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM track_segments WHERE id = :id)")
    suspend fun segmentExists(id: Long): Boolean

    // ---- Stats ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStats(stats: TrackStatsEntity)

    @Query("SELECT * FROM track_stats WHERE trackId = :trackId")
    suspend fun getStats(trackId: Long): TrackStatsEntity?

    // ---- Current track ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCurrentTrack(entity: CurrentTrackEntity)

    @Query("DELETE FROM current_track")
    suspend fun clearCurrentTrack()

    @Query("SELECT * FROM current_track WHERE id = 0 LIMIT 1")
    suspend fun getCurrentTrack(): CurrentTrackEntity?

    @Query("SELECT * FROM current_track WHERE id = 0 LIMIT 1")
    fun observeCurrentTrack(): Flow<CurrentTrackEntity?>
}
