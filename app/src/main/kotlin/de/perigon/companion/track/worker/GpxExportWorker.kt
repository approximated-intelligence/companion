package de.perigon.companion.track.worker

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.*
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import de.perigon.companion.core.data.UserNotificationDao
import de.perigon.companion.core.data.UserNotifications
import de.perigon.companion.core.ui.NotificationChannels
import de.perigon.companion.track.data.TrackRepository
import de.perigon.companion.track.domain.GpxExporter
import de.perigon.companion.util.saf.hasPersistedWriteGrant
import de.perigon.companion.util.saf.writeFileFromStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

private const val TAG = "GpxExportWorker"
private const val NOTIF_ID = 4010

@HiltWorker
class GpxExportWorker @AssistedInject constructor(
    @Assisted private val ctx: Context,
    @Assisted params: WorkerParameters,
    private val trackRepository: TrackRepository,
    private val notificationDao: UserNotificationDao,
) : CoroutineWorker(ctx, params) {

    companion object {
        const val WORK_NAME = "gpx_export"
        const val WORK_TAG = "gpx_export_worker"

        const val KEY_TRACK_IDS = "track_ids"
        const val KEY_FOLDER_URI = "folder_uri"
        const val KEY_EXPORTED = "exported"
        const val KEY_FAILED = "failed"
        const val KEY_CURRENT_FILE = "current_file"
        const val KEY_PROGRESS = "progress"
        const val KEY_TOTAL = "total"

        fun buildRequest(trackIds: LongArray, folderUri: Uri): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<GpxExportWorker>()
                .addTag(WORK_TAG)
                .setInputData(workDataOf(
                    KEY_TRACK_IDS to trackIds,
                    KEY_FOLDER_URI to folderUri.toString(),
                ))
                .build()
    }

    override suspend fun doWork(): Result {
        val trackIds = inputData.getLongArray(KEY_TRACK_IDS)
        if (trackIds == null || trackIds.isEmpty()) return fail("No tracks to export")

        val folderUriStr = inputData.getString(KEY_FOLDER_URI)
            ?: return fail("No export folder specified")
        val folderUri = Uri.parse(folderUriStr)

        if (!hasPersistedWriteGrant(ctx, folderUri)) {
            return fail("Write permission lost for export folder — re-select it in Settings")
        }

        setForeground(foregroundInfo("Exporting…"))

        var exported = 0
        var failed = 0

        for ((index, id) in trackIds.withIndex()) {
            val track = trackRepository.getTrackById(id)
            if (track == null) { failed++; continue }

            val segments = trackRepository.getSegmentsWithPoints(id)
            if (segments.isEmpty()) { failed++; continue }

            val filename = "${track.name.sanitizeFilename()}.gpx"
            emitProgress(index, trackIds.size, filename)
            setForeground(foregroundInfo("${index + 1} / ${trackIds.size}", filename))

            try {
                val bytes = ByteArrayOutputStream()
                    .also { GpxExporter.export(track.name, segments, it) }
                    .toByteArray()

                writeFileFromStream(
                    context = ctx,
                    treeUri = folderUri,
                    subfolders = emptyList(),
                    displayName = filename,
                    mimeType = "application/gpx+xml",
                    source = ByteArrayInputStream(bytes),
                )
                exported++
            } catch (e: Exception) {
                Log.e(TAG, "Failed to export ${track.name}: ${e.message}", e)
                failed++
            }
        }

        val message = when {
            failed == 0 -> "Exported $exported track(s)"
            exported == 0 -> "Export failed for all ${trackIds.size} track(s)"
            else -> "Exported $exported, failed $failed"
        }

        if (failed > 0) {
            UserNotifications.error(notificationDao, "gpx_export", message)
        } else {
            UserNotifications.success(notificationDao, "gpx_export", message)
        }

        return Result.success(workDataOf(
            KEY_EXPORTED to exported,
            KEY_FAILED to failed,
        ))
    }

    private suspend fun fail(error: String): Result {
        UserNotifications.error(notificationDao, "gpx_export", "GPX export failed: $error")
        return Result.failure(workDataOf("error" to error))
    }

    private suspend fun emitProgress(index: Int, total: Int, currentFile: String) {
        setProgress(workDataOf(
            KEY_PROGRESS to index,
            KEY_TOTAL to total,
            KEY_CURRENT_FILE to currentFile,
        ))
    }

    private fun foregroundInfo(title: String, sub: String = ""): ForegroundInfo {
        val notif = NotificationCompat.Builder(ctx, NotificationChannels.BACKUP)
            .setContentTitle("GPX Export: $title")
            .setContentText(sub)
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            ForegroundInfo(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIF_ID, notif)
    }
}

private fun String.sanitizeFilename(): String =
    replace(Regex("[^a-zA-Z0-9._-]"), "_").take(64)
