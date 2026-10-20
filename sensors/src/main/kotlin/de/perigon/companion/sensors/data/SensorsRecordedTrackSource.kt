package de.perigon.companion.sensors.data

import de.perigon.companion.core.feature.RecordedTrackSource
import de.perigon.companion.core.feature.RecordedTrackSummary
import de.perigon.companion.core.feature.RenderableRecordedTrack
import de.perigon.companion.core.geo.LatLon
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

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
            segments = segments.map { seg -> seg.map { LatLon(it.lat, it.lon) } },
        )
    }
}
