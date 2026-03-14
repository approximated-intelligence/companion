package de.perigon.companion.track.data

import android.os.SystemClock
import android.util.Log
import de.perigon.companion.track.domain.Background
import de.perigon.companion.track.domain.ImportedTrack
import de.perigon.companion.track.domain.computeTrackStats
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val RECORDING_NAME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/**
 * Single owner of all track persistence and recording lifecycle state.
 * All current_track transitions happen here, atomically with their related track/segment ops.
 */
class TrackRepository(
    private val dao: TrackDao,
) {

    // ---- Queries ----

    suspend fun getTrackWithSegments(id: Long): TrackWithSegments? =
        dao.getTrackWithSegments(id)

    fun observeAll(): Flow<List<TrackWithSegments>> = dao.observeAll()

    fun observeSummaries(): Flow<List<TrackSummary>> = dao.observeSummaries()

    fun observeCurrentTrack(): Flow<CurrentTrackEntity?> = dao.observeCurrentTrack()

    suspend fun getCurrentTrack(): CurrentTrackEntity? = dao.getCurrentTrack()

    suspend fun getStats(trackId: Long): TrackStatsEntity? = dao.getStats(trackId)

    // ---- Recording lifecycle — all current_track writes live here ----

    /**
     * Called by the service on ACTION_START.
     * Handles all cases: fresh start, resume after pause, crash recovery, day rollover.
     * Returns (trackId, segmentId) for the service to use.
     */
    @androidx.room.Transaction
    suspend fun startRecording(autoSplitOnDayRollover: Boolean): Pair<Long, Long> {
        val today = LocalDate.now().toString()
        val current = dao.getCurrentTrack()

        val (trackId, segmentId) = when {
            current == null -> {
                // Fresh start
                newTrack(today)
            }
            current.state == CurrentTrackState.PAUSED -> {
                val track = dao.getTrackById(current.trackId)
                if (track != null && track.date == today) {
                    // Resume paused track — new segment
                    val segId = dao.insertSegment(TrackSegmentEntity(trackId = current.trackId))
                    current.trackId to segId
                } else {
                    // Day rolled over while paused — start fresh
                    newTrack(today)
                }
            }
            current.state == CurrentTrackState.RECORDING -> {
                // Crash recovery — same track, new segment
                val track = dao.getTrackById(current.trackId)
                if (track != null && (!autoSplitOnDayRollover || track.date == today)) {
                    val segId = dao.insertSegment(TrackSegmentEntity(trackId = current.trackId))
                    current.trackId to segId
                } else {
                    // Day rolled over during crash
                    newTrack(today)
                }
            }
            else -> newTrack(today)
        }

        dao.upsertCurrentTrack(CurrentTrackEntity(trackId = trackId, state = CurrentTrackState.RECORDING))
        return trackId to segmentId
    }

    /**
     * Called by the service on day rollover while recording.
     * If autoSplit: new track. Otherwise: new segment on current track.
     */
    @androidx.room.Transaction
    suspend fun handleDayRollover(autoSplitOnDayRollover: Boolean): Pair<Long, Long> {
        val today = LocalDate.now().toString()
        val current = dao.getCurrentTrack()

        val (trackId, segmentId) = if (autoSplitOnDayRollover || current == null) {
            newTrack(today)
        } else {
            val segId = dao.insertSegment(TrackSegmentEntity(trackId = current.trackId))
            current.trackId to segId
        }

        dao.upsertCurrentTrack(CurrentTrackEntity(trackId = trackId, state = CurrentTrackState.RECORDING))
        return trackId to segmentId
    }

    /**
     * Called by the service on ACTION_PAUSE.
     */
    suspend fun pauseRecording(trackId: Long) {
        dao.upsertCurrentTrack(CurrentTrackEntity(trackId = trackId, state = CurrentTrackState.PAUSED))
    }

    /**
     * Called by the service on ACTION_STOP.
     */
    suspend fun stopRecording() {
        dao.clearCurrentTrack()
    }

    /**
     * Called when a new fix arrives and the gap since the last accepted fix
     * exceeds the auto-segment threshold. Creates a new segment on the current track.
     */
    @androidx.room.Transaction
    suspend fun startNewSegment(trackId: Long): Long {
        val segId = dao.insertSegment(TrackSegmentEntity(trackId = trackId))
        dao.upsertCurrentTrack(CurrentTrackEntity(trackId = trackId, state = CurrentTrackState.RECORDING))
        return segId
    }

    // ---- Other writes ----

    suspend fun renameTrack(id: Long, name: String) = dao.renameTrack(id, name)

    suspend fun flushPoints(points: List<TrackPointEntity>) {
        if (points.isEmpty()) return
        val segmentId = points.first().segmentId
        if (!dao.segmentExists(segmentId)) {
            Log.w("TrackRepository", "Skipping flush: segment $segmentId no longer exists")
            return
        }
        dao.insertPoints(points)
    }

    suspend fun upsertStats(stats: TrackStatsEntity) = dao.upsertStats(stats)

    /**
     * Compute and persist stats for a track if not already present.
     * Called lazily when a TrackRow first renders with null stats.
     */
    suspend fun ensureStats(trackId: Long) {
        if (dao.getStats(trackId) != null) return
        val track = dao.getTrackWithSegments(trackId) ?: return
        dao.upsertStats(computeTrackStats(track))
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
            val segId = dao.insertSegment(TrackSegmentEntity(trackId = trackId))
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

    // ---- Private helpers ----

    private suspend fun newTrack(date: String): Pair<Long, Long> {
        val name = LocalDateTime.now().format(RECORDING_NAME_FORMAT)
        val trackId = dao.insertTrack(TrackEntity(date = date, name = name))
        val segmentId = dao.insertSegment(TrackSegmentEntity(trackId = trackId))
        return trackId to segmentId
    }
}

/**
 * In-memory buffer for track points. Flushes to Room periodically.
 */
class TrackPointBuffer(
    private val repository: TrackRepository,
    private var intervalMs: Long,
    private var mode: String,
) {
    private val pending = mutableListOf<TrackPointEntity>()
    private var lastFlushElapsed = SystemClock.elapsedRealtime()
    private var segmentId: Long = 0L

    fun updateConfig(mode: String, intervalMs: Long) {
        this.intervalMs = intervalMs
        this.mode = mode
    }

    fun setSegmentId(id: Long) {
        segmentId = id
    }

    fun add(entity: TrackPointEntity) {
        pending.add(entity.copy(segmentId = segmentId))
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
