package de.perigon.companion.track.data

import android.os.SystemClock
import android.util.Log
import androidx.room.*
import de.perigon.companion.track.domain.Background
import de.perigon.companion.track.domain.ImportedTrack
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// ---------------------------------------------------------------------------
// Entities
// ---------------------------------------------------------------------------

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
    @PrimaryKey val id: Int = 0,
    val trackId: Long,
    val state: CurrentTrackState,
)

// ---------------------------------------------------------------------------
// View — flat ordered stream for external consumers
// ---------------------------------------------------------------------------

@DatabaseView(
    viewName = "track_point_rows",
    value = "SELECT t.id AS trackId, t.date AS trackDate, t.name AS trackName, s.id AS segmentId, s.startedAt AS segmentStartedAt, p.id AS pointId, p.lat, p.lon, p.ele, p.undulation, p.accuracyM, p.speedMs, p.bearing, p.time, p.provider FROM track_points p JOIN track_segments s ON s.id = p.segmentId JOIN tracks t ON t.id = s.trackId ORDER BY t.id ASC, s.id ASC, p.id ASC"
)
data class TrackPointRow(
    val trackId: Long,
    val trackDate: String,
    val trackName: String,
    val segmentId: Long,
    val segmentStartedAt: Long,
    val pointId: Long,
    val lat: Float,
    val lon: Float,
    val ele: Float?,
    val undulation: Float?,
    val accuracyM: Float?,
    val speedMs: Float?,
    val bearing: Float?,
    val time: Long,
    val provider: String,
)

// ---------------------------------------------------------------------------
// Summary projection
// ---------------------------------------------------------------------------

data class TrackSummary(
    val id: Long,
    val date: String,
    val name: String,
    val createdAt: Long,
    val pointCount: Int,
    @Embedded(prefix = "stats_") val stats: TrackStatsEntity?,
)

// ---------------------------------------------------------------------------
// DAO
// ---------------------------------------------------------------------------

@Dao
interface TrackDao {

    // -- ordered segment ids for a track --
    @Query("SELECT id FROM track_segments WHERE trackId = :trackId ORDER BY startedAt ASC, id ASC")
    suspend fun getSegmentIds(trackId: Long): List<Long>

    // -- ordered points for a segment --
    @Query("SELECT * FROM track_points WHERE segmentId = :segmentId ORDER BY time ASC, id ASC")
    suspend fun getPointsForSegment(segmentId: Long): List<TrackPointEntity>

    // -- flat view for external consumers --
    @Query("SELECT * FROM track_point_rows WHERE trackId = :trackId")
    suspend fun getPointRows(trackId: Long): List<TrackPointRow>

    @Query("SELECT * FROM track_point_rows")
    fun observeAllPointRows(): Flow<List<TrackPointRow>>

    // -- summaries --
    @Query("SELECT t.id, t.date, t.name, t.createdAt, COALESCE(SUM(pc.cnt), 0) AS pointCount, ts.trackId AS stats_trackId, ts.durationMs AS stats_durationMs, ts.recordingLengthMs AS stats_recordingLengthMs, ts.distanceM AS stats_distanceM, ts.timeMovingMs AS stats_timeMovingMs FROM tracks t LEFT JOIN track_segments s ON s.trackId = t.id LEFT JOIN (SELECT segmentId, COUNT(*) AS cnt FROM track_points GROUP BY segmentId) pc ON pc.segmentId = s.id LEFT JOIN track_stats ts ON ts.trackId = t.id GROUP BY t.id ORDER BY t.createdAt DESC")
    fun observeSummaries(): Flow<List<TrackSummary>>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun getTrackById(id: Long): TrackEntity?

    @Query("SELECT * FROM tracks WHERE date = :date LIMIT 1")
    suspend fun getByDate(date: String): TrackEntity?

    @Insert
    suspend fun insertTrack(track: TrackEntity): Long

    @Query("UPDATE tracks SET name = :name WHERE id = :id")
    suspend fun renameTrack(id: Long, name: String)

    @Query("DELETE FROM tracks WHERE id = :id")
    suspend fun deleteTrack(id: Long)

    @Insert
    suspend fun insertSegment(segment: TrackSegmentEntity): Long

    @Query("SELECT EXISTS(SELECT 1 FROM track_segments WHERE id = :id)")
    suspend fun segmentExists(id: Long): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPoints(points: List<TrackPointEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStats(stats: TrackStatsEntity)

    @Query("SELECT * FROM track_stats WHERE trackId = :trackId")
    suspend fun getStats(trackId: Long): TrackStatsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCurrentTrack(entity: CurrentTrackEntity)

    @Query("DELETE FROM current_track")
    suspend fun clearCurrentTrack()

    @Query("SELECT * FROM current_track WHERE id = 0 LIMIT 1")
    suspend fun getCurrentTrack(): CurrentTrackEntity?

    @Query("SELECT * FROM current_track WHERE id = 0 LIMIT 1")
    fun observeCurrentTrack(): Flow<CurrentTrackEntity?>
}

// ---------------------------------------------------------------------------
// Repository
// ---------------------------------------------------------------------------

private val RECORDING_NAME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

class TrackRepository(private val dao: TrackDao) {

    suspend fun getPointRows(trackId: Long): List<TrackPointRow> = dao.getPointRows(trackId)

    suspend fun getTrackById(id: Long): TrackEntity? {
        return dao.getTrackById(id)
    }

    /** Segments ordered by startedAt, each with points ordered by time. */
    suspend fun getSegmentsWithPoints(trackId: Long): List<List<TrackPointEntity>> =
        dao.getSegmentIds(trackId).map { segId -> dao.getPointsForSegment(segId) }

    fun observeSummaries(): Flow<List<TrackSummary>> = dao.observeSummaries()

    fun observeCurrentTrack(): Flow<CurrentTrackEntity?> = dao.observeCurrentTrack()

    suspend fun getCurrentTrack(): CurrentTrackEntity? = dao.getCurrentTrack()

    suspend fun getStats(trackId: Long): TrackStatsEntity? = dao.getStats(trackId)

    @Transaction
    suspend fun startRecording(autoSplitOnDayRollover: Boolean): Pair<Long, Long> {
        val today   = LocalDate.now().toString()
        val current = dao.getCurrentTrack()
        val (trackId, segmentId) = when {
            current == null -> newTrack(today)
            current.state == CurrentTrackState.PAUSED -> {
                val track = dao.getTrackById(current.trackId)
                if (track != null && track.date == today)
                    current.trackId to dao.insertSegment(TrackSegmentEntity(trackId = current.trackId))
                else
                    newTrack(today)
            }
            current.state == CurrentTrackState.RECORDING -> {
                val track = dao.getTrackById(current.trackId)
                if (track != null && (!autoSplitOnDayRollover || track.date == today))
                    current.trackId to dao.insertSegment(TrackSegmentEntity(trackId = current.trackId))
                else
                    newTrack(today)
            }
            else -> newTrack(today)
        }
        dao.upsertCurrentTrack(CurrentTrackEntity(trackId = trackId, state = CurrentTrackState.RECORDING))
        return trackId to segmentId
    }

    @Transaction
    suspend fun handleDayRollover(autoSplitOnDayRollover: Boolean): Pair<Long, Long> {
        val today   = LocalDate.now().toString()
        val current = dao.getCurrentTrack()
        val (trackId, segmentId) = if (autoSplitOnDayRollover || current == null)
            newTrack(today)
        else
            current.trackId to dao.insertSegment(TrackSegmentEntity(trackId = current.trackId))
        dao.upsertCurrentTrack(CurrentTrackEntity(trackId = trackId, state = CurrentTrackState.RECORDING))
        return trackId to segmentId
    }

    suspend fun pauseRecording(trackId: Long) {
        dao.upsertCurrentTrack(CurrentTrackEntity(trackId = trackId, state = CurrentTrackState.PAUSED))
    }

    suspend fun stopRecording() = dao.clearCurrentTrack()

    @Transaction
    suspend fun startNewSegment(trackId: Long): Long {
        val segId = dao.insertSegment(TrackSegmentEntity(trackId = trackId))
        dao.upsertCurrentTrack(CurrentTrackEntity(trackId = trackId, state = CurrentTrackState.RECORDING))
        return segId
    }

    suspend fun renameTrack(id: Long, name: String) = dao.renameTrack(id, name)

    suspend fun flushPoints(points: List<TrackPointEntity>) {
        if (points.isEmpty()) return
        if (!dao.segmentExists(points.first().segmentId)) {
            Log.w("TrackRepository", "Skipping flush: segment ${points.first().segmentId} no longer exists")
            return
        }
        dao.insertPoints(points)
    }

    suspend fun upsertStats(stats: TrackStatsEntity) = dao.upsertStats(stats)

    suspend fun ensureStats(trackId: Long) {
        if (dao.getStats(trackId) != null) return
        val segments = getSegmentsWithPoints(trackId)
        if (segments.isEmpty()) return
        dao.upsertStats(computeTrackStats(trackId, segments))
    }

    suspend fun deleteTrack(id: Long) = dao.deleteTrack(id)

    suspend fun importTrack(imported: ImportedTrack): Long {
        val date = imported.segments
            .flatMap { it.points }
            .mapNotNull { it.time }
            .minOrNull()
            ?.let { LocalDate.ofEpochDay(it / 86_400_000).toString() }
            ?: LocalDate.now().toString()
        val trackId = dao.insertTrack(TrackEntity(date = date, name = imported.name))
        for (seg in imported.segments) {
            val startedAt = seg.points.mapNotNull { it.time }.minOrNull()
                ?: System.currentTimeMillis()
            val segId = dao.insertSegment(TrackSegmentEntity(trackId = trackId, startedAt = startedAt))
            dao.insertPoints(seg.points.map { pt ->
                TrackPointEntity(
                    segmentId = segId,
                    lat       = pt.lat,
                    lon       = pt.lon,
                    ele       = pt.ele,
                    accuracyM = pt.accuracyM,
                    time      = pt.time ?: System.currentTimeMillis(),
                )
            })
        }
        return trackId
    }

    private suspend fun newTrack(date: String): Pair<Long, Long> {
        val trackId = dao.insertTrack(TrackEntity(date = date, name = LocalDateTime.now().format(RECORDING_NAME_FORMAT)))
        val segId   = dao.insertSegment(TrackSegmentEntity(trackId = trackId))
        return trackId to segId
    }
}

// ---------------------------------------------------------------------------
// Stats
// ---------------------------------------------------------------------------

private const val MOVING_THRESHOLD_MS = 0.5f

fun computeTrackStats(trackId: Long, segments: List<List<TrackPointEntity>>): TrackStatsEntity {
    val allPoints = segments.flatten()
    if (allPoints.isEmpty()) return TrackStatsEntity(trackId, 0L, 0L, 0f, 0L)

    val durationMs = allPoints.last().time - allPoints.first().time
    var recordingLengthMs = 0L
    var distanceM         = 0f
    var timeMovingMs      = 0L

    for (seg in segments) {
        if (seg.isEmpty()) continue
        recordingLengthMs += seg.last().time - seg.first().time
        for (i in 1 until seg.size) {
            val a = seg[i - 1]
            val b = seg[i]
            distanceM    += haversineM(a.lat, a.lon, b.lat, b.lon)
            timeMovingMs += movingMs(a, b)
        }
    }

    return TrackStatsEntity(
        trackId           = trackId,
        durationMs        = durationMs,
        recordingLengthMs = recordingLengthMs,
        distanceM         = distanceM,
        timeMovingMs      = timeMovingMs,
    )
}

private fun movingMs(a: TrackPointEntity, b: TrackPointEntity): Long {
    val intervalMs = b.time - a.time
    if (intervalMs <= 0) return 0L
    val speed = b.speedMs ?: a.speedMs
    if (speed != null) return if (speed >= MOVING_THRESHOLD_MS) intervalMs else 0L
    val derived = haversineM(a.lat, a.lon, b.lat, b.lon) / (intervalMs / 1000f)
    return if (derived >= MOVING_THRESHOLD_MS) intervalMs else 0L
}

private fun haversineM(lat1: Float, lon1: Float, lat2: Float, lon2: Float): Float {
    val r    = 6_371_000.0
    val dLat = Math.toRadians((lat2 - lat1).toDouble())
    val dLon = Math.toRadians((lon2 - lon1).toDouble())
    val h    = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
               Math.cos(Math.toRadians(lat1.toDouble())) *
               Math.cos(Math.toRadians(lat2.toDouble())) *
               Math.sin(dLon / 2) * Math.sin(dLon / 2)
    return (2 * r * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h))).toFloat()
}

// ---------------------------------------------------------------------------
// Point buffer
// ---------------------------------------------------------------------------

class TrackPointBuffer(
    private val repository: TrackRepository,
    private var intervalMs: Long,
    private var mode: String,
) {
    private val pending          = mutableListOf<TrackPointEntity>()
    private var lastFlushElapsed = SystemClock.elapsedRealtime()
    private var segmentId        = 0L

    fun updateConfig(mode: String, intervalMs: Long) {
        this.mode       = mode
        this.intervalMs = intervalMs
    }

    fun setSegmentId(id: Long) { segmentId = id }

    fun add(entity: TrackPointEntity) { pending.add(entity.copy(segmentId = segmentId)) }

    fun shouldFlush(): Boolean = Background.shouldFlush(
        bufferSize         = pending.size,
        lastFlushElapsedMs = SystemClock.elapsedRealtime() - lastFlushElapsed,
        intervalMs         = intervalMs,
        mode               = mode,
    )

    suspend fun flush() {
        if (pending.isEmpty()) return
        val batch = pending.toList()
        pending.clear()
        lastFlushElapsed = SystemClock.elapsedRealtime()
        repository.flushPoints(batch)
    }

    suspend fun flushRemaining() = flush()

    fun pendingCount(): Int = pending.size
}
