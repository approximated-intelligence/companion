package de.perigon.companion.core.transform

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
