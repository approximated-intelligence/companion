package de.perigon.companion.sensors.data

import de.perigon.companion.core.feature.RecordedTrackSource
import de.perigon.companion.core.feature.RecordedTrackSummary
import de.perigon.companion.core.feature.RenderableRecordedTrack
import de.perigon.companion.core.geo.LatLon
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges the sensors module's Double-precision track storage to the core
 * renderer contract, which stays Float ([LatLon]). Rendering is a canvas
 * pixel grid (~1 m/px at useful zoom); the upcast from Double storage to
 * Float LatLon happens exactly once, here, and never touches the
 * distance/GPX-export path that the source-of-truth values flow through.
 */
@Singleton
class SensorsRecordedTrackSource @Inject constructor(
    private val repository: TrackRepository,
) : RecordedTrackSource {

    override fun observeSummaries(): Flow<List<RecordedTrackSummary>> =
        repository.observeSummaries().map { tracks ->
            tracks.map { t ->
                RecordedTrackSummary(
                    id         = t.id,
                    name       = t.name,
                    date       = t.date,
                    pointCount = t.pointCount,
                )
            }
        }

    override suspend fun renderable(id: Long): RenderableRecordedTrack? {
        val entity = repository.getTrackById(id) ?: return null
        val segments = repository.getSegmentsWithPoints(id)
        if (segments.isEmpty()) return null
        return RenderableRecordedTrack(
            name     = entity.name,
            firstTimestamp = segments.firstOrNull()?.firstOrNull()?.time,
            segments = segments.map { seg -> seg.map { LatLon(it.lat.toFloat(), it.lon.toFloat()) } },
        )
    }
}
