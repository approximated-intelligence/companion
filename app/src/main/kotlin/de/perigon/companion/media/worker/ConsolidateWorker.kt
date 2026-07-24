package de.perigon.companion.media.worker

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.*
import de.perigon.companion.MainActivity
import de.perigon.companion.core.data.UserNotificationDao
import de.perigon.companion.core.data.UserNotifications
import de.perigon.companion.core.prefs.AppPrefs
import de.perigon.companion.core.ui.NotificationChannels
import de.perigon.companion.media.data.ConsolidateFileEntity
import de.perigon.companion.media.data.ConsolidateRepository
import de.perigon.companion.media.data.TransformJobStatus
import de.perigon.companion.media.data.TransformQueue
import de.perigon.companion.util.FileHasher
import de.perigon.companion.util.HashKey
import de.perigon.companion.util.saf.ScannedFile
import de.perigon.companion.util.saf.collectFileNames
import de.perigon.companion.util.saf.navigateOrCreate
import de.perigon.companion.util.saf.setMtime
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.io.File

private const val TAG = "ConsolidateWorker"
private const val CONSOLIDATED_SUBFOLDER = "Consolidated"
private const val CAMERA_SUBFOLDER = "Camera"
// Unique app-wide: 43 belongs to RestoreNotifier — sharing it let concurrent
// restore + consolidate runs clobber each other's foreground notifications.
private const val NOTIF_ID = 4030
private const val CONTENT_REQUEST_CODE = 9022
private const val CALLER_TAG = "consolidate"

@HiltWorker
class ConsolidateWorker @AssistedInject constructor(
    @Assisted private val ctx: Context,
    @Assisted params: WorkerParameters,
    private val transformQueue: TransformQueue,
    private val consolidateRepo: ConsolidateRepository,
    private val notificationDao: UserNotificationDao,
    private val appPrefs: AppPrefs,
    private val hasher: FileHasher,
) : CoroutineWorker(ctx, params) {

    companion object {
        const val WORK_NAME = "consolidate"
        const val WORK_TAG = "consolidate_worker"

        const val KEY_TOTAL = "total"
        const val KEY_PROCESSED = "processed"
        const val KEY_FAILED = "failed"
        const val KEY_CURRENT_FILE = "current_file"

        // Hash-phase progress, distinct from transform-phase total/processed
        // so no field ever changes meaning mid-run.
        const val KEY_HASHED = "hashed"
        const val KEY_MISSES = "misses"

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

        val scanned = scanAndPersistSourceFiles(treeUri)

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

        // Submit (or adopt) one transform job per pending file. submitIfAbsent
        // dedups on (callerTag, displayName) inside a single transaction, so a
        // concurrent second trigger cannot create duplicate jobs.
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

            val mediaType = if (isVideo) "VIDEO" else "IMAGE"
            val boxPx = if (isVideo) TransformQueue.DEFAULT_VIDEO_BOX_PX
                        else TransformQueue.DEFAULT_BOX_PX

            val transformId = transformQueue.submitIfAbsent(
                sourceUri = Uri.parse(entity.uri),
                displayName = outName,
                mediaType = mediaType,
                boxPx = boxPx,
                callerTag = CALLER_TAG,
            )
            pendingJobs[transformId] = entity
        }

        var processed = 0
        var failed = 0

        // Event-driven: each Room invalidation of transform_jobs delivers a
        // snapshot; process completions as they land, report the RUNNING job
        // as the current file, and return once all our jobs are settled.
        transformQueue.jobs.first { allTransformJobs ->
            // jobs deleted out from under us (e.g. UI "clear finished") count as failed
            val visibleIds = allTransformJobs.map { it.id }.toSet()
            val vanished = pendingJobs.keys.filter { it !in visibleIds }
            vanished.forEach { pendingJobs.remove(it); failed++; processed++ }

            val ours = allTransformJobs.filter { it.id in pendingJobs }

            ours.firstOrNull { it.status == TransformJobStatus.RUNNING }?.let { running ->
                emitProgress(pending.size, processed, failed, running.displayName)
            }

            for (tj in ours) {
                when (tj.status) {
                    TransformJobStatus.DONE -> {
                        val entity = pendingJobs.remove(tj.id) ?: continue
                        if (tj.outputPath != null) {
                            try {
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

            pendingJobs.isEmpty()
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
     * Hashing is batched through the shared [FileHasher] cache (one lookup pass,
     * misses only are read) so backup and consolidate share cached digests.
     * Hash progress (throttled) is surfaced via notification and progress data.
     *
     * Skipped: files already consolidated, empty files (nothing to transform),
     * files with unknown size, and files that can't be opened/hashed — the
     * latter two are retried on the next scan.
     */
    private suspend fun scanAndPersistSourceFiles(dcimTreeUri: Uri): Int {
        val consolidatedNames = collectFileNames(ctx, dcimTreeUri, CONSOLIDATED_SUBFOLDER)

        val cameraFiles = walkCameraSubfolder(dcimTreeUri)
        if (cameraFiles.isEmpty()) return 0

        val byKey = LinkedHashMap<HashKey, ScannedFile>(cameraFiles.size)
        for (sf in cameraFiles) {
            val size = sf.size ?: continue
            if (size == 0L) continue
            val stem = sf.path.substringAfterLast('/').substringBeforeLast('.')
            val isVideo = sf.path.endsWith(".mp4", ignoreCase = true)
            val outName = if (isVideo) "${stem}_s.mp4" else "${stem}_s.jpg"
            if (outName in consolidatedNames) continue
            byKey.putIfAbsent(HashKey(sf.path, sf.mtime, size), sf)
        }
        if (byKey.isEmpty()) return 0

        val hashes = hasher.hashAllOrCached(
            byKey.keys.toList(),
            onProgress = { hashed, misses, key ->
                val name = key.path.substringAfterLast('/')
                emitProgress(
                    total = 0, processed = 0, failed = 0, currentFile = name,
                    hashed = hashed, misses = misses,
                )
                setForeground(foregroundInfo("Hashing $hashed / $misses"))
            },
        ) { key ->
            ctx.contentResolver.openInputStream(Uri.parse(byKey.getValue(key).uri))
                ?: error("openInputStream returned null")
        }

        val dropped = byKey.size - hashes.size
        if (dropped > 0) Log.w(TAG, "dropped $dropped unreadable file(s) from consolidate scan")

        val now = System.currentTimeMillis()
        val entities = byKey.mapNotNull { (key, sf) ->
            hashes[key]?.let { sha ->
                ConsolidateFileEntity(
                    path = sf.path, uri = sf.uri,
                    mtime = key.mtime, size = key.size,
                    sha256 = sha, createdAt = now,
                )
            }
        }

        if (entities.isNotEmpty()) consolidateRepo.insertScannedFiles(entities)
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
            ?.map { sf -> sf.copy(path = "DCIM/${sf.path}") }
            ?: emptyList()
    }

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

    private suspend fun emitProgress(
        total: Int,
        processed: Int,
        failed: Int,
        currentFile: String,
        hashed: Int = 0,
        misses: Int = 0,
    ) {
        setProgress(workDataOf(
            KEY_TOTAL to total,
            KEY_PROCESSED to processed,
            KEY_FAILED to failed,
            KEY_CURRENT_FILE to currentFile,
            KEY_HASHED to hashed,
            KEY_MISSES to misses,
        ))
    }

    private fun foregroundInfo(sub: String): ForegroundInfo {
        val contentIntent = Intent(ctx, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_NAVIGATE_TO, "Consolidate")
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPi = PendingIntent.getActivity(
            ctx, CONTENT_REQUEST_CODE, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notif = NotificationCompat.Builder(ctx, NotificationChannels.CONSOLIDATE)
            .setContentTitle("Consolidating media")
            .setContentText(sub)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentIntent(contentPi)
            .setOngoing(true)
            .build()
        return ForegroundInfo(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
}
