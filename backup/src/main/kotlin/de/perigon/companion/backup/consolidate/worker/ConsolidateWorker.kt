package de.perigon.companion.backup.consolidate.worker

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.hilt.work.HiltWorker
import androidx.work.*
import de.perigon.companion.backup.BackupChannels
import de.perigon.companion.backup.BackupTargets
import de.perigon.companion.core.data.UserNotificationDao
import de.perigon.companion.core.data.UserNotifications
import de.perigon.companion.core.feature.NotificationIds
import de.perigon.companion.core.feature.RequestCodes
import de.perigon.companion.core.prefs.FolderGrants
import de.perigon.companion.core.prefs.FolderKeys
import de.perigon.companion.core.saf.openFileDescriptor
import de.perigon.companion.core.saf.openInputStream
import de.perigon.companion.core.ui.WorkNotifier
import de.perigon.companion.backup.consolidate.data.ConsolidateFileEntity
import de.perigon.companion.backup.consolidate.data.ConsolidateRepository
import de.perigon.companion.backup.consolidate.data.ConsolidationPrefs
import de.perigon.companion.backup.consolidate.domain.ConsolidationKind
import de.perigon.companion.backup.consolidate.domain.ConsolidationQuality
import de.perigon.companion.backup.consolidate.domain.ConsolidationSettings
import de.perigon.companion.media.data.TransformJobEntity
import de.perigon.companion.media.data.TransformJobStatus
import de.perigon.companion.media.data.TransformQueue
import de.perigon.companion.backup.consolidate.domain.consolidateTarget
import de.perigon.companion.backup.data.FileHasher
import de.perigon.companion.backup.data.HashKey
import de.perigon.companion.media.transform.readPreservedExif
import de.perigon.companion.media.transform.writeExif
import de.perigon.companion.core.saf.ScannedFile
import de.perigon.companion.core.saf.collectFileNames
import de.perigon.companion.core.saf.listSubfolder
import de.perigon.companion.core.saf.navigateOrCreate
import de.perigon.companion.core.saf.setMtime
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.io.File

private const val TAG = "ConsolidateWorker"
private const val CONSOLIDATED_SUBFOLDER = "Consolidated"
private const val CAMERA_SUBFOLDER = "Camera"
private const val NOTIF_ID = NotificationIds.BACKUP + 2
private const val CONTENT_REQUEST_CODE = RequestCodes.BACKUP + 2
private const val CALLER_TAG = "consolidate"

/**
 * Downscaled copies of DCIM/Camera in DCIM/Consolidated.
 *
 * Images go through the app's one decoder (upright pixels, HEIC/HEIF/AVIF
 * included) and come out in the mode's still format. Videos use the mode's
 * video codec. Output names come from [consolidateTarget] and encode the
 * box that produced them: `<stem>_bb<box><srcext>`, with the target
 * extension appended when the format differs.
 */
@HiltWorker
class ConsolidateWorker @AssistedInject constructor(
    @Assisted private val ctx: Context,
    @Assisted params: WorkerParameters,
    private val transformQueue: TransformQueue,
    private val consolidateRepo: ConsolidateRepository,
    private val notificationDao: UserNotificationDao,
    private val folderGrants: FolderGrants,
    private val hasher: FileHasher,
    private val prefs: ConsolidationPrefs,
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
        val dcimUri = folderGrants.uri(FolderKeys.DCIM)
        if (dcimUri == null) {
            UserNotifications.error(notificationDao, CALLER_TAG, "DCIM folder not configured")
            return Result.failure()
        }
        val treeUri = dcimUri.toUri()
        val settings = prefs.get()

        setForeground(foregroundInfo("Scanning…"))

        val scanned = scanAndPersistSourceFiles(treeUri, settings)

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
            val sourceUri = entity.uri.toUri()
            val target = consolidateTarget(entity.path.substringAfterLast('/'), settings)
            // Unknown type, or the source is gone (getType resolves nothing
            // for a deleted document): drop the row rather than fail it on
            // every run.
            if (target == null || ctx.contentResolver.getType(sourceUri) == null) {
                consolidateRepo.deleteByIds(setOf(entity.id))
                continue
            }

            val format = settings.formatFor(target.kind)
            val box    = settings.boxFor(target.kind)
            val transformId = transformQueue.submitIfAbsent(
                sourceUri    = sourceUri,
                displayName  = target.name,
                mediaType    = if (target.kind == ConsolidationKind.VIDEO) "VIDEO" else "IMAGE",
                boxPx        = box,
                quality      = ConsolidationQuality.qualityFor(format),
                callerTag    = CALLER_TAG,
                outputFormat = format.name,
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
                        val outputPath = tj.outputPath
                        if (outputPath != null) {
                            try {
                                placeInConsolidated(
                                    dcimTreeUri = treeUri,
                                    tj = tj,
                                    tempFile = File(outputPath),
                                    sourceUri = entity.uri.toUri(),
                                    sourceMtime = entity.mtime,
                                )
                                consolidateRepo.markDone(entity.id, tj.displayName)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.w(TAG, "placing ${tj.displayName} failed", e)
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
    private suspend fun scanAndPersistSourceFiles(
        dcimTreeUri: Uri,
        settings: ConsolidationSettings,
    ): Int {
        val consolidatedNames = collectFileNames(ctx, dcimTreeUri, CONSOLIDATED_SUBFOLDER)
        val cameraFiles = walkCameraSubfolder(dcimTreeUri, settings)
        if (cameraFiles.isEmpty()) return 0

        val byKey = LinkedHashMap<HashKey, ScannedFile>(cameraFiles.size)
        for (sf in cameraFiles) {
            val size = sf.size ?: continue
            if (size == 0L) continue
            val target = consolidateTarget(sf.path.substringAfterLast('/'), settings) ?: continue
            if (target.name in consolidatedNames) continue
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
            openInputStream(ctx, byKey.getValue(key).uri.toUri())
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

    private fun walkCameraSubfolder(
        dcimTreeUri: Uri,
        settings: ConsolidationSettings,
    ): List<ScannedFile> =
        listSubfolder(ctx, dcimTreeUri, CAMERA_SUBFOLDER)
            ?.filter { sf -> consolidateTarget(sf.path.substringAfterLast('/'), settings) != null }
            ?.map { sf -> sf.copy(path = "DCIM/${sf.path}") }
            ?: emptyList()

    private fun placeInConsolidated(
        dcimTreeUri: Uri,
        tj: TransformJobEntity,
        tempFile: File,
        sourceUri: Uri,
        sourceMtime: Long,
    ) {
        try {
            if (tj.mediaType != "VIDEO") copyExif(sourceUri, tempFile)
            val folder = navigateOrCreate(ctx, dcimTreeUri, listOf(CONSOLIDATED_SUBFOLDER))
            folder.findFile(tj.displayName)?.delete()
            val doc = folder.createFile(tj.outputMimeType, tj.displayName)
                ?: error("Cannot create ${tj.displayName} in Consolidated")
            ctx.contentResolver.openOutputStream(doc.uri)?.use { out ->
                tempFile.inputStream().use { it.copyTo(out) }
            } ?: error("Cannot open output stream for ${doc.uri}")
            setMtime(ctx, doc.uri, sourceMtime)
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Best-effort: the pixels are the point of consolidation. A source whose
     * metadata can't be parsed still gets its copy — without EXIF, and the
     * log says why.
     */
    private fun copyExif(sourceUri: Uri, target: File) {
        try {
            val tags = openFileDescriptor(ctx, sourceUri)
                ?.use { pfd -> readPreservedExif(pfd.fileDescriptor) }
                ?: emptyMap()
            if (tags.isNotEmpty()) writeExif(target, tags)
        } catch (e: Exception) {
            Log.w(TAG, "EXIF not copied from $sourceUri", e)
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

    private val notifier = WorkNotifier(
        context = ctx,
        notificationId = NOTIF_ID,
        channelId = BackupChannels.CONSOLIDATE,
        navigateTo = BackupTargets.CONSOLIDATE,
        requestCode = CONTENT_REQUEST_CODE,
    )

    private fun foregroundInfo(sub: String): ForegroundInfo =
        notifier.foregroundInfo("Consolidating media", sub)
}
