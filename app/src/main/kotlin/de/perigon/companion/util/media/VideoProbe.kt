package de.perigon.companion.util.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri

/**
 * Probes video metadata via MediaMetadataRetriever.
 * Returns display-oriented dimensions (native rotation applied).
 * Pure utility — no state, no dependencies beyond Context for URI resolution.
 */
object VideoProbe {

    /**
     * Returns (width, height) in display orientation.
     * Accounts for container rotation metadata (90°/270° swap).
     * Returns (0, 0) if metadata is unavailable.
     */
    fun displayDimensions(context: Context, uri: Uri): Pair<Int, Int> {
        return try {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull() ?: 0
                val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull() ?: 0
                val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull() ?: 0
                if (rotation == 90 || rotation == 270) h to w else w to h
            } finally {
                retriever.release()
            }
        } catch (_: Exception) {
            0 to 0
        }
    }
}
