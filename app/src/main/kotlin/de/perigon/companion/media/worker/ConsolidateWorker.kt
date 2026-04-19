package de.perigon.companion.media.worker

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.*
import de.perigon.companion.core.data.UserNotificationDao
import de.perigon.companion.core.data.UserNotifications
import de.perigon.companion.core.prefs.AppPrefs
import de.perigon.companion.core.ui.NotificationChannels
import de.perigon.companion.media.data.ConsolidateFileEntity
import de.perigon.companion.media.data.ConsolidateRepository
import de.perigon.companion.media.data.TransformJobDao
import de.perigon.companion.media.data.TransformJobEntity
import de.perigon.companion.media.data.TransformJobStatus
import de.perigon.companion.media.data.TransformQueue
import de.perigon.companion.util.saf.ScannedFile
import de.perigon.companion.util.saf.collectFileNames
import de.perigon.companion.util.saf.navigateOrCreate
import de.perigon.companion.util.saf.setMtime
import de.perigon.companion.util.saf.walkDocumentTree
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.io.File

private const val CONSOLIDATED_SUBFOLDER = "Consolidated"
private const val CAMERA_SUBFOLDER = "Camera"
private const val NOTIF_ID = 43
private const val CALLER_TAG = "consolidate"

@HiltWorker
class ConsolidateWorker @AssistedInject constructor(
    @Assisted private val ctx: Context,
    @Assisted params: WorkerParameters,
    private val transformQueue: TransformQueue,
    private val transformJobDao: TransformJobDao,
    private val consolidateRepo: ConsolidateRepository,
    private val notificationDao: UserNotificationDao,
    private val appPrefs: AppPrefs,
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
        val dcimUri = appPrefs.dcimTreeUri()
        if (dcimUri == null) {
            UserNotifications.error(notificationDao, CALLER_TAG, "DCIM folder not configured")
            return Result.failure()
        }
        val treeUri = Uri.parse(dcimUri)

        setForeground(foregroundInfo("Scanning…"))

        // Step 1: Scan Camera via SAF and persist source files
        val scanned = scanAndPersistSourceFiles(treeUri)

        // Step 2: Query DB for files without a done tombstone
        val pending = consolidateRepo.getPending()
        if (pending.isEmpty()) {
            if (scanned == 0) {
                UserNotifications.info(notificationDao, CALLER_TAG, "Nothing to consolidate")
            } else {
                UserNotifications.info(notificationDao, CALLER_TAG, "All files already consolidated")
            }
            return Result.success()
        }

        emitProgress(total = pending.size, processed = 0, failed = 0, currentFile = "Starting…")

        // Step 3: Adopt orphaned transform jobs or submit new ones
        val activeJobs = transformJobDao.findActiveByTag(CALLER_TAG)
        val activeByName = activeJobs.associateBy { it.displayName }

        val pendingJobs = mutableMapOf<Long, ConsolidateFileEntity>()

        for (entity in pending) {
            val mimeType = ctx.contentResolver.getType(Uri.parse(entity.uri))
            if (mimeType == null) {
                consolidateRepo.deleteByIds(setOf(entity.id))
                continue
            }
            val isVideo = mimeType.startsWith("video")

            val stem = entity.path.substringAfterLast('/').substringBeforeLast('.')
            val outName = if (isVideo) "${stem}_s.mp4" else "${stem}_s.jpg"

            val existing = activeByName[outName]
            if (existing != null) {
                pendingJobs[existing.id] = entity
            } else {
                val mediaType = if (isVideo) "VIDEO" else "IMAGE"
                val boxPx = if (isVideo) TransformQueue.DEFAULT_VIDEO_BOX_PX
                            else TransformQueue.DEFAULT_BOX_PX

                val transformId = transformQueue.submit(
                    sourceUri = Uri.parse(entity.uri),
                    displayName = outName,
                    mediaType = mediaType,
                    boxPx = boxPx,
                    callerTag = CALLER_TAG,
                )
                pendingJobs[transformId] = entity
            }
        }

        // Step 4: Wait for all transform jobs to complete
        var processed = 0
        var failed = 0

        while (pendingJobs.isNotEmpty()) {
            val allTransformJobs = transformQueue.jobs.first()
            val ours = allTransformJobs.filter { it.id in pendingJobs }

            for (tj in ours) {
                when (tj.status) {
                    TransformJobStatus.DONE -> {
                        val entity = pendingJobs.remove(tj.id) ?: continue
                        if (tj.outputPath != null) {
                            try {
                                // Pass source mtime so the consolidated file keeps
                                // the original capture timestamp.
                                placeInConsolidated(
                                    dcimTreeUri = treeUri,
                                    displayName = tj.displayName,
                                    mediaType = tj.mediaType,
                                    tempFile = File(tj.outputPath),
                                    sourceMtime = entity.mtime,
                                )
                                consolidateRepo.markDone(entity.id, tj.displayName)
                            } catch (_: Exception) {
                                failed++
                            }
                        } else {
                            failed++
                        }
                        processed++
                        emitProgress(pending.size, processed, failed, tj.displayName)
                        setForeground(foregroundInfo("$processed / ${pending.size}"))
                    }
                    TransformJobStatus.FAILED -> {
                        pendingJobs.remove(tj.id)
                        failed++
                        processed++
                        emitProgress(pending.size, processed, failed, tj.displayName)
                    }
                    else -> { /* still running or pending */ }
                }
            }

            if (pendingJobs.isNotEmpty()) {
                kotlinx.coroutines.delay(500)
            }
        }

        val message = "Consolidation complete: $processed processed" +
            if (failed > 0) ", $failed failed" else ""

        if (failed > 0) {
            UserNotifications.error(notificationDao, CALLER_TAG, message)
        } else {
            UserNotifications.success(notificationDao, CALLER_TAG, message)
        }

        return if (failed > 0) Result.failure() else Result.success()
    }

    /**
     * Scan DCIM/Camera via SAF and insert new source files into consolidate_files.
     */
    private suspend fun scanAndPersistSourceFiles(dcimTreeUri: Uri): Int {
        // Get existing consolidated names to skip already-done files
        val consolidatedNames = collectFileNames(ctx, dcimTreeUri, CONSOLIDATED_SUBFOLDER)

        // Walk Camera subfolder
        val cameraFiles = walkCameraSubfolder(dcimTreeUri)
        if (cameraFiles.isEmpty()) return 0

        val now = System.currentTimeMillis()
        val entities = cameraFiles.filter { sf ->
            val stem = sf.path.substringAfterLast('/').substringBeforeLast('.')
            val isVideo = sf.path.endsWith(".mp4", ignoreCase = true)
            val outName = if (isVideo) "${stem}_s.mp4" else "${stem}_s.jpg"
            outName !in consolidatedNames
        }.map { sf ->
            ConsolidateFileEntity(
                path = sf.path,
                uri = sf.uri,
                mtime = sf.mtime,
                size = sf.size,
                createdAt = now,
            )
        }

        if (entities.isNotEmpty()) {
            consolidateRepo.insertScannedFiles(entities)
        }

        return entities.size
    }

    private fun walkCameraSubfolder(dcimTreeUri: Uri): List<ScannedFile> {
        return de.perigon.companion.util.saf.listSubfolder(ctx, dcimTreeUri, CAMERA_SUBFOLDER)
            ?.filter { sf ->
                val name = sf.path.substringAfterLast('/')
                val lower = name.lowercase()
                lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                    lower.endsWith(".mp4") || lower.endsWith(".png")
            }
            ?.map { sf ->
                // Prefix with DCIM/ for consistent path format
                sf.copy(path = "DCIM/${sf.path}")
            }
            ?: emptyList()
    }

    /**
     * Write consolidated output to DCIM/Consolidated/ via SAF, then stamp the
     * file's last-modified time to match the source so chronological ordering
     * in galleries reflects capture time, not processing time.
     */
    private fun placeInConsolidated(
        dcimTreeUri: Uri,
        displayName: String,
        mediaType: String,
        tempFile: File,
        sourceMtime: Long,
    ) {
        try {
            val folder = navigateOrCreate(ctx, dcimTreeUri, listOf(CONSOLIDATED_SUBFOLDER))
            folder.findFile(displayName)?.delete()
            val mimeType = if (mediaType == "VIDEO") "video/mp4" else "image/jpeg"
            val doc = folder.createFile(mimeType, displayName)
                ?: error("Cannot create $displayName in Consolidated")
            ctx.contentResolver.openOutputStream(doc.uri)?.use { out ->
                tempFile.inputStream().use { it.copyTo(out) }
            } ?: error("Cannot open output stream for ${doc.uri}")
            setMtime(ctx, doc.uri, sourceMtime)
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
