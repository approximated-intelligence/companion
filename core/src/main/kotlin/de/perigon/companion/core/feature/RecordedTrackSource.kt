package de.perigon.companion.core.feature

import de.perigon.companion.core.geo.LatLon
import kotlinx.coroutines.flow.Flow

/** What a renderer needs to list a track in its picker. */
data class RecordedTrackSummary(
    val id: Long,
    val name: String,
    val date: String,
    val pointCount: Int,
)

/** A track ready to be handed to a renderer. */
data class RenderableRecordedTrack(
    val name: String,
    val firstTimestamp: Long?,
    val segments: List<List<LatLon>>,
)

/**
 * Optional contract: a build with a recorder offers this so the renderer can
 * list and load tracks without depending on the recorder module. Consumed as
 * Optional<RecordedTrackSource>, same shape as MediaInbox in the other
 * direction.
 */
interface RecordedTrackSource {
    fun observeSummaries(): Flow<List<RecordedTrackSummary>>
    suspend fun renderable(id: Long): RenderableRecordedTrack?
}
