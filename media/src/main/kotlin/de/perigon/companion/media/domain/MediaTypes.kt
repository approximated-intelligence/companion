package de.perigon.companion.media.domain

enum class MediaType { IMAGE, VIDEO }

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




