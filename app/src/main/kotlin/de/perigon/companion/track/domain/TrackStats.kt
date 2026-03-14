package de.perigon.companion.track.domain

import de.perigon.companion.track.data.TrackPointEntity
import de.perigon.companion.track.data.TrackStatsEntity
import de.perigon.companion.track.data.TrackWithSegments
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Speed threshold for "in movement" — 0.5 m/s ≈ slow walking */
private const val MOVING_THRESHOLD_MS = 0.5f

/**
 * Compute stats for a track from its full point data.
 * Pure function — no I/O.
 */
fun computeTrackStats(track: TrackWithSegments): TrackStatsEntity {
    val allPoints = track.segments
        .flatMap { it.points }
        .sortedBy { it.time }

    if (allPoints.isEmpty()) {
        return TrackStatsEntity(
            trackId          = track.track.id,
            durationMs       = 0L,
            recordingLengthMs = 0L,
            distanceM        = 0f,
            timeMovingMs     = 0L,
        )
    }

    val durationMs = allPoints.last().time - allPoints.first().time

    var recordingLengthMs = 0L
    var distanceM = 0f
    var timeMovingMs = 0L

    for (seg in track.segments) {
        val pts = seg.points.sortedBy { it.time }
        if (pts.isEmpty()) continue

        recordingLengthMs += pts.last().time - pts.first().time

        for (i in 1 until pts.size) {
            val a = pts[i - 1]
            val b = pts[i]
            distanceM += haversineM(a.lat, a.lon, b.lat, b.lon)
            timeMovingMs += movingMs(a, b)
        }
    }

    return TrackStatsEntity(
        trackId          = track.track.id,
        durationMs       = durationMs,
        recordingLengthMs = recordingLengthMs,
        distanceM        = distanceM,
        timeMovingMs     = timeMovingMs,
    )
}

private fun movingMs(a: TrackPointEntity, b: TrackPointEntity): Long {
    val intervalMs = b.time - a.time
    if (intervalMs <= 0) return 0L

    // Prefer reported speed if available on either point
    val speed = b.speedMs ?: a.speedMs
    if (speed != null) {
        return if (speed >= MOVING_THRESHOLD_MS) intervalMs else 0L
    }

    // Fall back to derived speed from position delta
    val dist = haversineM(a.lat, a.lon, b.lat, b.lon)
    val derivedSpeed = dist / (intervalMs / 1000f)
    return if (derivedSpeed >= MOVING_THRESHOLD_MS) intervalMs else 0L
}

private fun haversineM(lat1: Float, lon1: Float, lat2: Float, lon2: Float): Float {
    val r = 6_371_000.0
    val dLat = Math.toRadians((lat2 - lat1).toDouble())
    val dLon = Math.toRadians((lon2 - lon1).toDouble())
    val h = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1.toDouble())) *
            cos(Math.toRadians(lat2.toDouble())) *
            sin(dLon / 2).pow(2)
    return (2 * r * atan2(sqrt(h), sqrt(1 - h))).toFloat()
}
