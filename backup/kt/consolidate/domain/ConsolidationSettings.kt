package de.perigon.companion.backup.consolidate.domain

/**
 * Consolidation output settings.
 *
 * Mirrors the python-tools convention: mode is the user-facing switch,
 * quality is a constant per (format, kind). Boxes are this pipeline's
 * "quality" axis and are exposed per kind.
 */
enum class ConsolidationMode(val displayName: String) {
    COMPATIBLE("Compatible"),   // JPEG stills, H.264 video
    EFFICIENT("Efficient"),     // AVIF stills, AV1 video
}

enum class ConsolidationKind { IMAGE, PNG, VIDEO }

/**
 * One still format or one video format, with the extension that names
 * the output. FORMAT fixes the encoder: picking AVIF means AvifWriter,
 * picking AV1 means the platform AV1 encoder via Media3.
 *
 * A single value space that the job row stores as one string. Which
 * half of the enum applies is decided by the job's mediaType, not by
 * separate columns: the video values are unused on IMAGE rows and the
 * still values on VIDEO rows.
 */
enum class OutputFormat(
    val displayName: String,
    val mimeType: String,
    val extension: String,
) {
    JPEG("JPEG",  "image/jpeg", "jpg"),
    PNG ("PNG",   "image/png",  "png"),
    AVIF("AVIF",  "image/avif", "avif"),
    H264("H.264", "video/mp4",  "mp4"),
    AV1 ("AV1",   "video/mp4",  "mp4"),
}

object ConsolidationQuality {
    const val JPEG_QUALITY = 85
    const val AVIF_QUALITY = 60
    const val PNG_QUALITY = 100

    fun qualityFor(format: OutputFormat): Int = when (format) {
        OutputFormat.JPEG -> JPEG_QUALITY
        OutputFormat.AVIF -> AVIF_QUALITY
        else              -> PNG_QUALITY
    }
}

data class ConsolidationSettings(
    val mode:       ConsolidationMode = ConsolidationMode.COMPATIBLE,
    val bboxPhotos: Int = 960,    // matches TransformQueue.DEFAULT_BOX_PX
    val bboxPngs:   Int = 1920,   // matches python-tools DEFAULT_BBOX_PNGS
    val bboxVideos: Int = 640,    // matches TransformQueue.DEFAULT_VIDEO_BOX_PX
) {
    fun formatFor(kind: ConsolidationKind): OutputFormat = when (kind) {
        ConsolidationKind.VIDEO ->
            if (mode == ConsolidationMode.EFFICIENT) OutputFormat.AV1
            else OutputFormat.H264
        ConsolidationKind.PNG -> OutputFormat.PNG
        ConsolidationKind.IMAGE ->
            if (mode == ConsolidationMode.EFFICIENT) OutputFormat.AVIF
            else OutputFormat.JPEG
    }

    fun boxFor(kind: ConsolidationKind): Int = when (kind) {
        ConsolidationKind.VIDEO -> bboxVideos
        ConsolidationKind.PNG   -> bboxPngs
        ConsolidationKind.IMAGE -> bboxPhotos
    }
}

const val MIN_BBOX = 16
