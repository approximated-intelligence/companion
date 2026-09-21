package de.perigon.companion.media.domain

/** Where a camera file lands in DCIM/Consolidated, and how it is transformed. */
data class ConsolidateTarget(
    val name: String,
    val isVideo: Boolean,
)

private val VIDEO_EXTENSIONS = setOf("mp4")

/** Output name keeps the plain stem: "IMG_1.jpg" → "IMG_1_s.jpg". */
private val PLAIN_IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png")

/**
 * Output name carries the source extension: "IMG_1.heic" → "IMG_1_heic_s.jpg".
 * Cameras that write HEIC often write a same-stem JPEG too; without the tag
 * both would claim "IMG_1_s.jpg".
 */
private val TAGGED_IMAGE_EXTENSIONS = setOf("heic", "heif", "avif")

/**
 * Pure: the consolidation target for a camera file name, or null when the
 * file is not something consolidation handles.
 *
 * The ONLY name mapping — scan, submit and backfill all go forward through
 * it. There is deliberately no inverse: parsing "_heic_s.jpg" back would be
 * ambiguous for stems that contain such a suffix themselves.
 */
fun consolidateTarget(sourceName: String): ConsolidateTarget? {
    val dot = sourceName.lastIndexOf('.')
    if (dot <= 0) return null
    val stem = sourceName.substring(0, dot)
    return when (val ext = sourceName.substring(dot + 1).lowercase()) {
        in VIDEO_EXTENSIONS        -> ConsolidateTarget("${stem}_s.mp4", isVideo = true)
        in PLAIN_IMAGE_EXTENSIONS  -> ConsolidateTarget("${stem}_s.jpg", isVideo = false)
        in TAGGED_IMAGE_EXTENSIONS -> ConsolidateTarget("${stem}_${ext}_s.jpg", isVideo = false)
        else                       -> null
    }
}
