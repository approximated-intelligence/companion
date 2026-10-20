package de.perigon.companion.core.transform

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
