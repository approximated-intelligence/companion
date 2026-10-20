package de.perigon.companion.backup.consolidate.domain

/**
 * Where a camera file lands in DCIM/Consolidated, and which kind of job
 * transforms it. The output name encodes the box and the source
 * extension; the target extension is appended only when it differs.
 * Format is decided by [ConsolidationSettings] at the call site — this
 * file only names the target given those settings.
 */
data class ConsolidateTarget(
    val name: String,
    val kind: ConsolidationKind,
)

private val VIDEO_EXTENSIONS          = setOf("mp4")
private val LOSSY_IMAGE_EXTENSIONS    = setOf("jpg", "jpeg", "heic", "heif", "avif")
private val LOSSLESS_IMAGE_EXTENSIONS = setOf("png")

/**
 * Pure: the consolidation target for a camera file name, or null when
 * the file is not something consolidation handles.
 *
 * Output name mirrors python-tools/tree-reencode.py: `<stem>_bb<box>`
 * followed by the source extension, with the target extension appended
 * only when the format differs. Switching mode or box produces a
 * distinct name, so a change never overwrites the previous run's output.
 *
 *  photo.jpg  -> photo_bb960.jpg       (Compatible, JPEG target)
 *  photo.jpg  -> photo_bb960.jpg.avif  (Efficient, AVIF target)
 *  photo.jpeg -> photo_bb960.jpeg      (Compatible; .jpeg is aliased to .jpg)
 *  photo.avif -> photo_bb960.avif      (Efficient; no format change)
 *  photo.avif -> photo_bb960.avif.jpg  (Compatible; format change)
 *  photo.heic -> photo_bb960.heic.jpg  (Compatible; format change)
 *  photo.png  -> photo_bb1920.png      (PNG stays PNG in both modes)
 *  video.mp4  -> video_bb640.mp4       (both modes; codec is content, not name)
 */
fun consolidateTarget(
    sourceName: String,
    settings: ConsolidationSettings,
): ConsolidateTarget? {
    val dot = sourceName.lastIndexOf('.')
    if (dot <= 0) return null
    val stem = sourceName.substring(0, dot)
    val ext  = sourceName.substring(dot + 1).lowercase()

    val kind = when (ext) {
        in VIDEO_EXTENSIONS          -> ConsolidationKind.VIDEO
        in LOSSLESS_IMAGE_EXTENSIONS -> ConsolidationKind.PNG
        in LOSSY_IMAGE_EXTENSIONS    -> ConsolidationKind.IMAGE
        else                         -> return null
    }

    val box           = settings.boxFor(kind)
    val targetExt     = settings.formatFor(kind).extension
    val withSourceExt = "${stem}_bb${box}.$ext"
    val name = if (sameFormat(ext, targetExt)) withSourceExt
               else "$withSourceExt.$targetExt"
    return ConsolidateTarget(name, kind)
}

/**
 * Pure: is [sourceExt] the same image format as [targetExt]?
 *
 * jpg and jpeg are the same format under two spellings. heic/heif/avif
 * are distinct from JPEG even though cameras often write them alongside
 * a same-stem JPEG. png and mp4 never reach here — their branches in
 * [consolidateTarget] return before this is called.
 */
private fun sameFormat(sourceExt: String, targetExt: String): Boolean {
    val normalizedSource = if (sourceExt == "jpeg") "jpg" else sourceExt
    return normalizedSource == targetExt
}
