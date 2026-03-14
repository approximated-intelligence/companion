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
        val track = trackRepository.getTrackWithSegments(trackId) ?: return null
        val file = exportToCache(track)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/gpx+xml"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, track.track.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun exportToCache(track: de.perigon.companion.track.data.TrackWithSegments): File {
        val dir = File(context.cacheDir, "gpx_share")
        dir.mkdirs()
        val file = File(dir, "${track.track.name.sanitizeFilename()}.gpx")
        file.outputStream().use { GpxExporter.export(track, it) }
        return file
    }

    private fun String.sanitizeFilename(): String =
        replace(Regex("[^a-zA-Z0-9._-]"), "_").take(64)
}
