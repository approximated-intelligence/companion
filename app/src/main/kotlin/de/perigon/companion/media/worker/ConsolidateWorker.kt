package de.perigon.companion.media.worker

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.*
import de.perigon.companion.core.data.UserNotificationDao
import de.perigon.companion.core.data.UserNotifications
import de.perigon.companion.core.ui.NotificationChannels
import de.perigon.companion.media.data.MediaStoreRepository
import de.perigon.companion.media.data.MediaStoreWriter
import de.perigon.companion.media.data.TransformJobStatus
import de.perigon.companion.media.data.TransformQueue
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.io.File

private const val CONSOLIDATED_PATH = "DCIM/Consolidated"
private const val NOTIF_ID = 43

@HiltWorker
class ConsolidateWorker @AssistedInject constructor(
    @Assisted private val ctx: Context,
    @Assisted params: WorkerParameters,
    private val transformQueue: TransformQueue,
    private val mediaStoreRepo: MediaStoreRepository,
    private val notificationDao: UserNotificationDao,
) : CoroutineWorker(ctx, params) {

    companion object {
        const val WORK_NAME = "consolidate"
        const val WORK_TAG = "consolidate_worker"

        const val KEY_TOTAL = "total"
        const val KEY_PROCESSED = "processed"
        const val KEY_FAILED = "failed"
        const val KEY_CURRENT_FILE = "current_file"

        fun buildRequest(): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<ConsolidateWorker>()
                .addTag(WORK_TAG)
                .build()
    }

    override suspend fun doWork(): Result {
        setForeground(foregroundInfo("Scanning…"))

        val pending = mediaStoreRepo.collectPendingConsolidation()
        if (pending.isEmpty()) {
            UserNotifications.info(notificationDao, "consolidate", "Nothing to consolidate")
            return Result.success()
        }

        emitProgress(total = pending.size, processed = 0, failed = 0, currentFile = "Starting…")

        val pendingJobIds = mutableSetOf<Long>()
        var processed = 0
        var failed = 0

        for (item in pending) {
            val stem = item.displayName.substringBeforeLast('.')
            val outName = if (item.mediaType == "VIDEO") "${stem}_s.mp4" else "${stem}_s.jpg"
            val boxPx = if (item.mediaType == "VIDEO") TransformQueue.DEFAULT_VIDEO_BOX_PX
                        else TransformQueue.DEFAULT_BOX_PX

            val jobId = transformQueue.submit(
                sourceUri = android.net.Uri.parse(item.sourceUri),
                displayName = outName,
                mediaType = item.mediaType,
                boxPx = boxPx,
                callerTag = "consolidate",
            )
            pendingJobIds += jobId
        }

        while (pendingJobIds.isNotEmpty()) {
            val allJobs = transformQueue.jobs.first()
            val ourJobs = allJobs.filter { it.id in pendingJobIds }

            for (job in ourJobs) {
                when (job.status) {
                    TransformJobStatus.DONE -> {
                        if (job.outputPath != null) {
                            try {
                                placeInConsolidated(ctx, job.displayName, job.mediaType, File(job.outputPath))
                            } catch (_: Exception) {
                                failed++
                            }
                        }
                        pendingJobIds -= job.id
                        processed++
                        emitProgress(pending.size, processed, failed, job.displayName)
                        setForeground(foregroundInfo("$processed / ${pending.size}"))
                    }
                    TransformJobStatus.FAILED -> {
                        pendingJobIds -= job.id
                        failed++
                        processed++
                        emitProgress(pending.size, processed, failed, job.displayName)
                    }
                    else -> { /* still running or pending */ }
                }
            }

            if (pendingJobIds.isNotEmpty()) {
                kotlinx.coroutines.delay(500)
            }
        }

        val message = "Consolidation complete: $processed processed" +
            if (failed > 0) ", $failed failed" else ""

        if (failed > 0) {
            UserNotifications.error(notificationDao, "consolidate", message)
        } else {
            UserNotifications.success(notificationDao, "consolidate", message)
        }

        return if (failed > 0) Result.failure() else Result.success()
    }

    private fun placeInConsolidated(ctx: Context, displayName: String, mediaType: String, tempFile: File) {
        try {
            if (mediaType == "VIDEO") {
                MediaStoreWriter.insertVideo(ctx, displayName, CONSOLIDATED_PATH, tempFile)
            } else {
                tempFile.inputStream().use { stream ->
                    MediaStoreWriter.insertImage(ctx, displayName, "image/jpeg", CONSOLIDATED_PATH, stream)
                }
            }
        } finally {
            tempFile.delete()
        }
    }

    private suspend fun emitProgress(total: Int, processed: Int, failed: Int, currentFile: String) {
        setProgress(workDataOf(
            KEY_TOTAL to total,
            KEY_PROCESSED to processed,
            KEY_FAILED to failed,
            KEY_CURRENT_FILE to currentFile,
        ))
    }

    private fun foregroundInfo(sub: String): ForegroundInfo {
        val notif = NotificationCompat.Builder(ctx, NotificationChannels.CONSOLIDATE)
            .setContentTitle("Consolidating media")
            .setContentText(sub)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            ForegroundInfo(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIF_ID, notif)
    }
}
