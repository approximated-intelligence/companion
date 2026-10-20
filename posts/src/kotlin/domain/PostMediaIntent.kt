package de.perigon.companion.posts.domain

import de.perigon.companion.core.transform.CropRect
import de.perigon.companion.core.transform.MediaType
import de.perigon.companion.core.transform.OrientationTransform
import de.perigon.companion.core.transform.TransformIntent
import de.perigon.companion.posts.data.PostMediaEntity

/** Pure: the stored transform columns of a post media row as media's intent type. */
fun PostMediaEntity.toTransformIntent() = TransformIntent(
    cropRect = CropRect(cropLeft, cropTop, cropRight, cropBottom),
    orientation = OrientationTransform(orientationDegrees, flipHorizontal),
    fineRotationDegrees = fineRotationDegrees,
    trimStartMs = trimStartMs,
    trimEndMs = trimEndMs,
)

/** Status within the MediaPrep UI only — not persisted. */
enum class ItemStatus { EXISTING, NEW, COMPRESSING, DONE, ERROR }

/** UI item for the MediaPrep screen: a PostMediaEntity with transient UI state. */
data class MediaPrepItem(
    val entity: PostMediaEntity,
    val type: MediaType,
    val status: ItemStatus,
    val errorMessage: String? = null,
) {
    val id: Long get() = entity.id
    val hasEdits: Boolean get() = entity.hasTransformIntent
    val displayUri: String get() = entity.mediaStoreUri.ifEmpty { entity.sourceUri }
}
