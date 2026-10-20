package de.perigon.companion.sensors.data

import android.os.SystemClock
import android.util.Log
import androidx.room.*
import de.perigon.companion.sensors.domain.Background
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
    // Barometric altitude from TYPE_PRESSURE via SensorManager.getAltitude
    // (standard atmosphere). Null when the sensor is disabled or absent.
    val baroAlt: Float? = null,
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
    // Point count the stats were computed from. Kept so callers can detect
    // staleness: pause-triggered ensureStats used to freeze a partial value
    // for the rest of the track's life. Compare against TrackSummary.pointCount.
    // Default 0 also self-heals pre-migration rows on first read.
    val pointCount: Int = 0,
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
    value = "SELECT t.id AS trackId, t.date AS trackDate, t.name AS trackName, s.id AS segmentId, s.startedAt AS segmentStartedAt, p.id AS pointId, p.lat, p.lon, p.ele, p.undulation, p.baroAlt, p.accuracyM, p.speedMs, p.bearing, p.time, p.provider FROM track_points p JOIN track_segments s ON s.id = p.segmentId JOIN tracks t ON t.id = s.trackId ORDER BY t.id ASC, s.id ASC, p.id ASC"
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
    val baroAlt: Float?,
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
    // The extra stats_pointCount column carries the point count at which the
    // stored stats were computed, so callers can detect staleness (see
    // ensureStats). TrackSummary.pointCount is the live count from the DB.
    @Query("SELECT t.id, t.date, t.name, t.createdAt, COALESCE(SUM(pc.cnt), 0) AS pointCount, ts.trackId AS stats_trackId, ts.pointCount AS stats_pointCount, ts.durationMs AS stats_durationMs, ts.recordingLengthMs AS stats_recordingLengthMs, ts.distanceM AS stats_distanceM, ts.timeMovingMs AS stats_timeMovingMs FROM tracks t LEFT JOIN track_segments s ON s.trackId = t.id LEFT JOIN (SELECT segmentId, COUNT(*) AS cnt FROM track_points GROUP BY segmentId) pc ON pc.segmentId = s.id LEFT JOIN track_stats ts ON ts.trackId = t.id GROUP BY t.id ORDER BY t.createdAt DESC")
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

    /**
     * Recomputes stats after the flush so the paused track shows the true
     * total up to this point. Without this the stats froze at whatever value
     * the UI's ensureStats happened to compute mid-track.
     */
    suspend fun pauseRecording(trackId: Long) {
        dao.upsertCurrentTrack(CurrentTrackEntity(trackId = trackId, state = CurrentTrackState.PAUSED))
        ensureStats(trackId, force = true)
    }

    /**
     * Same reasoning as [pauseRecording]: recompute before clearing
     * current_track, so a reopened track row reflects the full ride, not the
     * partial value from an earlier pause.
     */
    suspend fun stopRecording(trackId: Long) {
        dao.clearCurrentTrack()
        ensureStats(trackId, force = true)
    }

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

    /**
     * Recomputes stats for [trackId] if missing, stale, or [force].
     *
     * Staleness is detected by comparing the point count stored alongside the
     * stats with the current DB count. Without this check the UI's
     * LaunchedEffect (which only fires when stats == null) froze the value
     * computed at the first pause, so a resumed-and-stopped track displayed
     * a fraction of its real distance. The 0 default on the new column
     * self-heals any pre-migration rows on their first read.
     */
    suspend fun ensureStats(trackId: Long, force: Boolean = false) {
        // FK guard: deleteActiveTrack can race this call; a missing track
        // means the stats row would fail the foreign key.
        if (dao.getTrackById(trackId) == null) return
        val segments = getSegmentsWithPoints(trackId)
        if (segments.isEmpty()) return
        val currentPointCount = segments.sumOf { it.size }
        if (!force) {
            val existing = dao.getStats(trackId)
            if (existing != null && existing.pointCount == currentPointCount) return
        }
        dao.upsertStats(computeTrackStats(trackId, segments))
    }

    suspend fun deleteTrack(id: Long) = dao.deleteTrack(id)

    private suspend fun newTrack(date: String): Pair<Long, Long> {
        val trackId = dao.insertTrack(TrackEntity(date = date, name = LocalDateTime.now().format(RECORDING_NAME_FORMAT)))
        val segId   = dao.insertSegment(TrackSegmentEntity(trackId = trackId))
        return trackId to segId
    }
}

// ---------------------------------------------------------------------------
// Stats
// ---------------------------------------------------------------------------

// Below this speed a within-segment step is considered stationary. 0.5 m/s
// is ~1.8 km/h — slower than a walking cyclist, faster than GPS jitter.
// Threshold on *speed*, not on derived distance/time, so sparse-fix tracks
// (intervalMs 30 s and up) do not undercount moving time.
private const val MOVING_THRESHOLD_MS = 0.5f

/**
 * All three time columns are gap-aware:
 *
 *  - [TrackStatsEntity.durationMs]: max(t) - min(t), wall clock across the
 *    whole track. Uses min/max rather than first/last because DST jumps and
 *    GPS clock rollbacks can make the last flattened point earlier than the
 *    first, which would yield a negative duration.
 *
 *  - [TrackStatsEntity.recordingLengthMs]: sum over segments of
 *    (last.time - first.time). Never spans the gap between two segments —
 *    that is the whole reason segments exist. Single-point segments
 *    contribute 0 (no interval was recorded).
 *
 *  - [TrackStatsEntity.timeMovingMs]: sum over segments of within-segment
 *    intervals where the recorded speed meets the moving threshold.
 */
fun computeTrackStats(trackId: Long, segments: List<List<TrackPointEntity>>): TrackStatsEntity {
    val allPoints = segments.flatten()
    if (allPoints.isEmpty()) {
        return TrackStatsEntity(
            trackId           = trackId,
            pointCount        = 0,
            durationMs        = 0L,
            recordingLengthMs = 0L,
            distanceM         = 0f,
            timeMovingMs      = 0L,
        )
    }

    val earliest = allPoints.minOf { it.time }
    val latest   = allPoints.maxOf { it.time }
    val durationMs = (latest - earliest).coerceAtLeast(0L)

    var recordingLengthMs = 0L
    var distanceM         = 0f
    var timeMovingMs      = 0L

    for (seg in segments) {
        if (seg.size < 2) continue
        recordingLengthMs += (seg.last().time - seg.first().time).coerceAtLeast(0L)
        for (i in 1 until seg.size) {
            val a = seg[i - 1]
            val b = seg[i]
            distanceM    += haversineM(a.lat, a.lon, b.lat, b.lon)
            timeMovingMs += movingMs(a, b)
        }
    }

    return TrackStatsEntity(
        trackId           = trackId,
        pointCount        = allPoints.size,
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
    val distance = haversineM(a.lat, a.lon, b.lat, b.lon)
    return if (distance >= 0.5f) intervalMs else 0L
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
    private var lastAcceptedMs   = 0L

    fun updateConfig(mode: String, intervalMs: Long) {
        this.mode       = mode
        this.intervalMs = intervalMs
    }

    fun setSegmentId(id: Long) {
        segmentId = id
        lastAcceptedMs = 0L
    }

    fun add(entity: TrackPointEntity): Boolean {
        if (mode == RecordingMode.CONTINUOUS && entity.time - lastAcceptedMs < intervalMs) return false
        lastAcceptedMs = entity.time
        pending.add(entity.copy(segmentId = segmentId))
        return true
    }

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
