package de.perigon.companion.core.transform

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
