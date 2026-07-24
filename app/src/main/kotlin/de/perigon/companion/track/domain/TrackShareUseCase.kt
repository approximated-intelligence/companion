package de.perigon.companion.track.domain

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import de.perigon.companion.track.data.TrackRepository
import java.io.File

class TrackShareUseCase(
    private val context: Context,
    private val trackRepository: TrackRepository,
) {
    suspend fun shareAsGpx(trackId: Long): Intent? {
        val track    = trackRepository.getTrackById(trackId) ?: return null
        val segments = trackRepository.getSegmentsWithPoints(trackId)
        if (segments.isEmpty()) return null
        val name = track.name.sanitizeFilename()
        val file = File(context.cacheDir, "gpx_share/$name.gpx").also { it.parentFile?.mkdirs() }
        file.outputStream().use { GpxExporter.export(track.name, segments, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/gpx+xml"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, track.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}

private fun String.sanitizeFilename(): String =
    replace(Regex("[^a-zA-Z0-9._-]"), "_").take(64)
