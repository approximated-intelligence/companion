package de.perigon.companion.archive.consolidate.worker

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.hilt.work.HiltWorker
import androidx.work.*
import de.perigon.companion.archive.ArchiveChannels
import de.perigon.companion.archive.ArchiveTargets
import de.perigon.companion.archive.data.HashFailure
import de.perigon.companion.archive.data.userMessage
import de.perigon.companion.core.data.UserNotificationDao
import de.perigon.companion.core.data.UserNotifications
import de.perigon.companion.core.feature.NotificationIds
import de.perigon.companion.core.feature.RequestCodes
import de.perigon.companion.core.prefs.FolderGrants
import de.perigon.companion.core.prefs.FolderKeys
import de.perigon.companion.core.saf.openFileDescriptor
import de.perigon.companion.core.saf.openInputStream
import de.perigon.companion.core.ui.WorkNotifier
import de.perigon.companion.archive.consolidate.data.ConsolidateFileEntity
import de.perigon.companion.archive.consolidate.data.ConsolidateRepository
import de.perigon.companion.archive.consolidate.data.ConsolidationPrefs
import de.perigon.companion.archive.consolidate.domain.ConsolidationKind
import de.perigon.companion.archive.consolidate.domain.ConsolidationQuality
import de.perigon.companion.archive.consolidate.domain.ConsolidationSettings
import de.perigon.companion.media.data.TransformJobEntity
import de.perigon.companion.media.data.TransformJobStatus
import de.perigon.companion.media.data.TransformQueue
import de.perigon.companion.archive.consolidate.domain.consolidateTarget
import de.perigon.companion.archive.data.FileHasher
import de.perigon.companion.archive.data.HashKey
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

// Sentinel size for files the SAF provider reported no size for. Matches
// ArchiveSourceScanner.UNKNOWN_SIZE so userMessage() classification works.
private const val UNKNOWN_SIZE = -1L

/**
 * A per-file consolidation failure — the transform or write step could not
 * produce a copy in DCIM/Consolidated. [reason] is a short user-facing
 * phrase; the full exception (where applicable) is in logcat from the
 * recording site.
 *
 * Distinct from [HashFailure]: those are pre-transform skips (unreadable
 * source, unknown size); these are post-scan, per-file write failures. Kept
 * as separate types because the "what can the user do" answer differs — a
 * HashFailure usually means "remove .nomedia" or "re-grant permission",
 * a ConsolidationFailure means "this specific photo failed to re-encode,
 * inspect it" (or "the job vanished, re-run").
 */
data class ConsolidationFailure(
    val path: String,
    val reason: String,
)

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

        val (scanned, scanFailures) = scanAndPersistSourceFiles(treeUri, settings)

        if (scanFailures.isNotEmpty()) {
            // Same policy as backup: files the scan touched but could not
            // include are surfaced, not swallowed — un-indexed media (a
            // .nomedia folder, a not-yet-scanned file), a revoked
            // ACCESS_MEDIA_LOCATION grant, or a size the SAF provider did
            // not report.
            val shown = scanFailures.take(10).joinToString("\n") { it.userMessage() }
            val detail = if (scanFailures.size > 10) {
                "$shown\n… and ${scanFailures.size - 10} more"
            } else shown
            UserNotifications.info(
                notificationDao, CALLER_TAG,
                "${scanFailures.size} file(s) skipped during consolidation scan",
                detail,
            )
        }

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

        // Post-scan, per-file write/transform failures. Collected here and
        // surfaced in the terminal notification body with paths and reasons
        // (capped at 10, "… and N more"), so the user can inspect the
        // specific photo that failed to re-encode rather than just seeing
        // "2 failed" with no idea which. Every recording site also Log.ws.
        val consolidationFailures = mutableListOf<ConsolidationFailure>()

        // Event-driven: each Room invalidation of transform_jobs delivers a
        // snapshot; process completions as they land, report the RUNNING job
        // as the current file, and return once all our jobs are settled.
        transformQueue.jobs.first { allTransformJobs ->
            // jobs deleted out from under us (e.g. UI "clear finished") count as failed
            val visibleIds = allTransformJobs.map { it.id }.toSet()
            val vanished = pendingJobs.keys.filter { it !in visibleIds }
            vanished.forEach { tjId ->
                val entity = pendingJobs.remove(tjId)
                Log.w(TAG, "transform job $tjId vanished; path=${entity?.path ?: "(unknown)"}")
                if (entity != null) {
                    consolidationFailures += ConsolidationFailure(
                        path = entity.path,
                        reason = "transform job vanished from the queue",
                    )
                }
                failed++
                processed++
            }

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
                                consolidationFailures += ConsolidationFailure(
                                    path = entity.path,
                                    reason = "write failed: ${e.message ?: e.javaClass.simpleName}",
                                )
                                failed++
                            }
                        } else {
                            Log.w(TAG, "${entity.path}: transform reported DONE with no output file")
                            consolidationFailures += ConsolidationFailure(
                                path = entity.path,
                                reason = "transform produced no output file",
                            )
                            failed++
                        }
                        processed++
                        emitProgress(pending.size, processed, failed, tj.displayName)
                        setForeground(foregroundInfo("$processed / ${pending.size}"))
                    }
                    TransformJobStatus.FAILED -> {
                        val entity = pendingJobs.remove(tj.id)
                        Log.w(TAG, "${entity?.path ?: "job ${tj.id}"}: transform failed: ${tj.error}")
                        if (entity != null) {
                            consolidationFailures += ConsolidationFailure(
                                path = entity.path,
                                reason = "transform failed: ${tj.error ?: "unknown"}",
                            )
                        }
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
            val shown = consolidationFailures.take(10).joinToString("\n") {
                "${it.path}: ${it.reason}"
            }
            val detail = if (consolidationFailures.size > 10) {
                "$shown\n… and ${consolidationFailures.size - 10} more"
            } else shown
            UserNotifications.error(notificationDao, CALLER_TAG, message, detail)
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
     * Returns the number of new rows inserted alongside the list of files the
     * scanner could not include — callers surface those so un-indexed media
     * (a .nomedia folder, a not-yet-scanned file), a revoked
     * ACCESS_MEDIA_LOCATION grant, or a size the SAF provider did not report
     * does not silently miss consolidation.
     *
     * Skipped without surfacing: files already consolidated (by design),
     * empty files (size 0 — nothing to transform), and files whose target
     * type [consolidateTarget] does not handle (non-media). These are
     * intentional filters, not failures.
     */
    private suspend fun scanAndPersistSourceFiles(
        dcimTreeUri: Uri,
        settings: ConsolidationSettings,
    ): Pair<Int, List<HashFailure>> {
        val consolidatedNames = collectFileNames(ctx, dcimTreeUri, CONSOLIDATED_SUBFOLDER)
        val cameraFiles = walkCameraSubfolder(dcimTreeUri, settings)
        if (cameraFiles.isEmpty()) return 0 to emptyList()

        // Pre-hash skips go straight into the failure list.
        val preFailures = mutableListOf<HashFailure>()
        val byKey = LinkedHashMap<HashKey, ScannedFile>(cameraFiles.size)
        for (sf in cameraFiles) {
            val size = sf.size
            if (size == null) {
                preFailures += HashFailure(
                    key = HashKey(sf.path, sf.mtime, UNKNOWN_SIZE),
                    cause = IllegalStateException("size not reported by SAF provider"),
                )
                continue
            }
            if (size == 0L) continue
            val target = consolidateTarget(sf.path.substringAfterLast('/'), settings) ?: continue
            if (target.name in consolidatedNames) continue
            byKey.putIfAbsent(HashKey(sf.path, sf.mtime, size), sf)
        }
        if (byKey.isEmpty()) return 0 to preFailures

        val batch = hasher.hashAllOrCached(
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

        val allFailures = preFailures + batch.failures
        if (allFailures.isNotEmpty()) {
            Log.w(TAG, "dropped ${allFailures.size} file(s) from consolidate scan " +
                "(unreadable or unknown size)")
        }

        val now = System.currentTimeMillis()
        val entities = byKey.mapNotNull { (key, sf) ->
            batch.hashes[key]?.let { sha ->
                ConsolidateFileEntity(
                    path = sf.path, uri = sf.uri,
                    mtime = key.mtime, size = key.size,
                    sha256 = sha, createdAt = now,
                )
            }
        }

        if (entities.isNotEmpty()) consolidateRepo.insertScannedFiles(entities)
        return entities.size to allFailures
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
        channelId = ArchiveChannels.CONSOLIDATE,
        navigateTo = ArchiveTargets.CONSOLIDATE,
        requestCode = CONTENT_REQUEST_CODE,
    )

    private fun foregroundInfo(sub: String): ForegroundInfo =
        notifier.foregroundInfo("Consolidating media", sub)
}
