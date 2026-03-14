package de.perigon.companion.media.domain

import android.net.Uri
import de.perigon.companion.posts.data.PostMediaEntity

enum class MediaType { IMAGE, VIDEO }

/**
 * Status within MediaPrep UI only - not persisted.
 *   EXISTING    - already in the post, loaded from DB
 *   NEW         - just picked, persisted to DB but not yet processed
 *   COMPRESSING - actively being processed (publish time)
 *   DONE        - processing complete
 *   ERROR       - processing failed
 */
enum class ItemStatus { EXISTING, NEW, COMPRESSING, DONE, ERROR }

/**
 * Normalized crop rectangle. All values 0..1 relative to dimensions
 * AFTER orientation + fine rotation have been applied.
 * (0,0,1,1) means no crop (full frame).
 */
data class CropRect(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f,
) {
    val isFullFrame: Boolean
        get() = left == 0f && top == 0f && right == 1f && bottom == 1f
}

/**
 * Orientation transform: coarse rotation in 90° steps + optional horizontal flip.
 * Vertical flip = horizontal flip + 180° rotation, so two fields suffice.
 */
data class OrientationTransform(
    val rotationDegrees: Int = 0,
    val flipHorizontal: Boolean = false,
) {
    val isIdentity: Boolean
        get() = rotationDegrees == 0 && !flipHorizontal

    fun rotate90Cw(): OrientationTransform = copy(
        rotationDegrees = (rotationDegrees + 90) % 360,
    )

    fun rotate90Ccw(): OrientationTransform = copy(
        rotationDegrees = (rotationDegrees + 270) % 360,
    )

    fun toggleFlipHorizontal(): OrientationTransform = copy(
        flipHorizontal = !flipHorizontal,
    )

    fun toggleFlipVertical(): OrientationTransform =
        copy(
            rotationDegrees = (rotationDegrees + 180) % 360,
            flipHorizontal = !flipHorizontal,
        )
}

/**
 * Complete set of non-destructive edit parameters for a media item.
 * Maps 1:1 to the transform columns on PostMediaEntity.
 */
data class TransformIntent(
    val cropRect: CropRect = CropRect(),
    val orientation: OrientationTransform = OrientationTransform(),
    val fineRotationDegrees: Float = 0f,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = Long.MAX_VALUE,
) {
    val isIdentity: Boolean
        get() = cropRect.isFullFrame &&
                orientation.isIdentity &&
                fineRotationDegrees == 0f &&
                trimStartMs == 0L &&
                trimEndMs == Long.MAX_VALUE
}

fun PostMediaEntity.toTransformIntent() = TransformIntent(
    cropRect = CropRect(cropLeft, cropTop, cropRight, cropBottom),
    orientation = OrientationTransform(orientationDegrees, flipHorizontal),
    fineRotationDegrees = fineRotationDegrees,
    trimStartMs = trimStartMs,
    trimEndMs = trimEndMs,
)

/**
 * UI item for MediaPrep screen. Wraps a PostMediaEntity
 * with transient UI state.
 */
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


enum class BackupState { NONE, PENDING, CONFIRMED }

data class UnifiedMediaItem(
    val stem:            String,
    val originalUri:     Uri,
    val originalMediaId: Long,
    val consolidatedUri: Uri?        = null,
    val dateTaken:       Long        = 0L,
    val mediaType:       MediaType,
    val backupState:     BackupState = BackupState.NONE,
)
