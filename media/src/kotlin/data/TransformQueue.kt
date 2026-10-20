package de.perigon.companion.media.data

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.core.net.toUri
import androidx.room.withTransaction
import de.perigon.companion.media.domain.decodeUpright
import de.perigon.companion.media.transform.ImageOutputFormat
import de.perigon.companion.media.transform.ImageTransformParams
import de.perigon.companion.media.transform.ImageTransformer
import de.perigon.companion.media.transform.VideoCodec
import de.perigon.companion.media.transform.VideoProbe
import de.perigon.companion.media.transform.VideoTransformParams
import de.perigon.companion.media.transform.VideoTransformer
import de.perigon.companion.media.transform.decodeLongSide
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TransformQueue"

/**
 * Singleton queue that orchestrates media transforms via Room-backed persistence.
 * Images: decodeUpright (the app's one decoder) → [ImageTransformer.render].
 * Videos: [VideoTransformer].
 *
 * Lifecycle: created at app start, runs until process death.
 * The HandlerThread provides a stable Looper for Media3 Transformer.
 */
@Singleton
class TransformQueue @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val db: MediaDatabase,
    private val dao: TransformJobDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // HandlerThread provides a stable Looper for Media3 Transformer.
    // Lives for the process lifetime — intentional, since TransformQueue is @Singleton.
    private val thread = HandlerThread("transform-queue").apply { start() }
    private val handler = Handler(thread.looper)

    private val wakeSignal = Channel<Unit>(Channel.CONFLATED)

    val jobs: Flow<List<TransformJobEntity>> = dao.observeAll()

    init {
        scope.launch {
            resetInterruptedJobs()
            wakeSignal.trySend(Unit)
        }
        scope.launch { processLoop() }
    }

    suspend fun submit(
        sourceUri: Uri,
        displayName: String,
        mediaType: String,
        boxPx: Int = DEFAULT_BOX_PX,
        quality: Int = DEFAULT_QUALITY,
        trimStartMs: Long = 0L,
        trimEndMs: Long = Long.MAX_VALUE,
        cropLeft: Float = 0f,
        cropTop: Float = 0f,
        cropRight: Float = 1f,
        cropBottom: Float = 1f,
        orientationDegrees: Int = 0,
        flipHorizontal: Boolean = false,
        fineRotationDegrees: Float = 0f,
        callerTag: String = "",
        outputFormat: String = "JPEG",
    ): Long {
        val entity = TransformJobEntity(
            sourceUri           = sourceUri.toString(),
            displayName         = displayName,
            mediaType           = mediaType,
            status              = TransformJobStatus.PENDING,
            boxPx               = boxPx,
            quality             = quality,
            trimStartMs         = trimStartMs,
            trimEndMs           = trimEndMs,
            cropLeft            = cropLeft,
            cropTop             = cropTop,
            cropRight           = cropRight,
            cropBottom          = cropBottom,
            orientationDegrees  = orientationDegrees,
            flipHorizontal      = flipHorizontal,
            fineRotationDegrees = fineRotationDegrees,
            callerTag           = callerTag,
            outputFormat        = outputFormat,
        )
        val id = dao.insert(entity)
        wakeSignal.trySend(Unit)
        return id
    }

    /**
     * Submit a job unless an active (PENDING/RUNNING) job with the same
     * (callerTag, displayName) already exists; returns the existing or new id.
     * Check-and-insert run in one transaction, so concurrent submitters for
     * the same output slot cannot race into duplicate jobs.
     */
    suspend fun submitIfAbsent(
        sourceUri: Uri,
        displayName: String,
        mediaType: String,
        boxPx: Int = DEFAULT_BOX_PX,
        quality: Int = DEFAULT_QUALITY,
        callerTag: String,
        outputFormat: String = "JPEG",
    ): Long {
        val id = db.withTransaction {
            val existing = dao.findActiveByTag(callerTag)
                .firstOrNull { it.displayName == displayName }
            existing?.id ?: dao.insert(TransformJobEntity(
                sourceUri   = sourceUri.toString(),
                displayName = displayName,
                mediaType   = mediaType,
                status      = TransformJobStatus.PENDING,
                boxPx       = boxPx,
                quality     = quality,
                callerTag   = callerTag,
                outputFormat = outputFormat,
            ))
        }
        wakeSignal.trySend(Unit)
        return id
    }

    suspend fun cancel(jobId: Long) {
        val job = dao.getById(jobId) ?: return
        if (job.status == TransformJobStatus.PENDING) {
            dao.updateStatus(jobId, TransformJobStatus.FAILED, error = "Cancelled")
        }
    }

    suspend fun remove(jobId: Long) {
        val job = dao.getById(jobId) ?: return
        if (job.status == TransformJobStatus.RUNNING) return
        if (job.outputPath != null) File(job.outputPath).delete()
        dao.delete(jobId)
    }

    // Bulk clears delete the rows' output files first — DAO-only deletion
    // leaked finished jobs' temp files in cacheDir indefinitely.

    suspend fun clearCompleted() {
        deleteOutputFiles { it.status == TransformJobStatus.DONE }
        dao.deleteByStatus(TransformJobStatus.DONE)
    }

    suspend fun clearFailed() {
        deleteOutputFiles { it.status == TransformJobStatus.FAILED }
        dao.deleteByStatus(TransformJobStatus.FAILED)
    }

    suspend fun clearFinished() {
        deleteOutputFiles {
            it.status == TransformJobStatus.DONE || it.status == TransformJobStatus.FAILED
        }
        dao.deleteFinished()
    }

    private suspend fun deleteOutputFiles(predicate: (TransformJobEntity) -> Boolean) {
        jobs.first()
            .filter(predicate)
            .mapNotNull { it.outputPath }
            .forEach { File(it).delete() }
    }

    fun observeJob(jobId: Long): Flow<TransformJobEntity?> = dao.observeById(jobId)

    fun hasPending(): Flow<Boolean> = dao.countByStatus(TransformJobStatus.PENDING)
        .map { it > 0 }
        .distinctUntilChanged()

    private suspend fun resetInterruptedJobs() = dao.resetRunningToPending()

    /**
     * Suspends on wakeSignal when idle (no polling). Guarded end-to-end:
     * an exception outside processJob's own handling previously killed the
     * loop silently, leaving all future jobs PENDING forever and hanging
     * every worker waiting on them. Now: log, back off, keep serving.
     */
    private suspend fun processLoop() {
        while (true) {
            try {
                val next = dao.nextPending()
                if (next == null) {
                    wakeSignal.receive()
                    continue
                }
                processJob(next)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "process loop error; retrying", e)
                delay(1_000)
            }
        }
    }

    /**
     * Every job is processed — there is no "already processed, copy as-is"
     * shortcut. The former one keyed on MediaStore RELATIVE_PATH, which SAF
     * document URIs (all our sources) never carry, so it could not fire; and
     * had it fired, "DCIM/PostMedia" also matched PostMedia/Originals and
     * would have shipped raw originals. Reuse of processed output is the
     * caller's decision (PublishWorker), not the queue's.
     */
    private suspend fun processJob(job: TransformJobEntity) {
        val sourceUri = job.sourceUri.toUri()
        val ext       = job.outputExtension
        val stem      = job.displayName.substringBeforeLast('.')
        val tempFile  = File(context.cacheDir, "tq_${job.id}_$stem.$ext")

        try {
            // RUNNING update inside the guarded region: if it throws, the
            // job fails below instead of hot-retrying in the outer loop.
            dao.updateStatus(job.id, TransformJobStatus.RUNNING)

            // A transform that never completes would otherwise wedge the
            // single serial loop forever (and be resurrected as PENDING on
            // every restart): poison job. Bound it.
            withTimeout(TRANSFORM_TIMEOUT_MS) {
                if (job.mediaType == "VIDEO") {
                    val (srcW, srcH) = VideoProbe.displayDimensions(context, sourceUri)
                    VideoTransformer.transform(
                        context = context,
                        sourceUri = sourceUri,
                        outFile = tempFile,
                        params = job.toVideoParams(sourceWidth = srcW, sourceHeight = srcH),
                        handler = handler,
                    )
                } else {
                    tempFile.writeBytes(processImage(sourceUri, job.toImageParams()))
                }
            }

            dao.updateCompleted(job.id, tempFile.absolutePath)
            Log.d(TAG, "Completed ${job.id} exists=${tempFile.exists()} size=${tempFile.length()}")
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Job ${job.id} timed out: ${job.displayName}")
            tempFile.delete()
            dao.updateStatus(job.id, TransformJobStatus.FAILED, error = "Transform timed out")
        } catch (e: CancellationException) {
            tempFile.delete()
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Job ${job.id} failed: ${job.displayName}", e)
            tempFile.delete() // half-written output — don't leak into cacheDir
            dao.updateStatus(job.id, TransformJobStatus.FAILED, error = e.message)
        }
    }

    /**
     * Same decoder as editor and previews: upright pixels, every platform
     * format (HEIC/AVIF included), sampled during decode just enough that the
     * crop still reaches the output box.
     */
    private fun processImage(sourceUri: Uri, params: ImageTransformParams): ByteArray {
        val decoded = decodeUpright(context.contentResolver, sourceUri, params.decodeLongSide())
        return ImageTransformer.render(decoded, params)
    }

    companion object {
        const val DEFAULT_BOX_PX       = 960
        const val DEFAULT_VIDEO_BOX_PX = 640
        const val DEFAULT_QUALITY      = 85

        private const val TRANSFORM_TIMEOUT_MS = 15L * 60 * 1000
    }
}

private fun TransformJobEntity.toImageParams() = ImageTransformParams(
    orientationDegrees  = orientationDegrees,
    flipHorizontal      = flipHorizontal,
    fineRotationDegrees = fineRotationDegrees,
    cropLeft            = cropLeft,
    cropTop             = cropTop,
    cropRight           = cropRight,
    cropBottom          = cropBottom,
    boxPx               = boxPx,
    quality             = quality,
    outputFormat        = when (outputFormat) {
        "PNG"  -> ImageOutputFormat.PNG
        "AVIF" -> ImageOutputFormat.AVIF
        else   -> ImageOutputFormat.JPEG
    },
)

private fun TransformJobEntity.toVideoParams(
    sourceWidth: Int = 0,
    sourceHeight: Int = 0,
) = VideoTransformParams(
    orientationDegrees  = orientationDegrees,
    flipHorizontal      = flipHorizontal,
    fineRotationDegrees = fineRotationDegrees,
    cropLeft            = cropLeft,
    cropTop             = cropTop,
    cropRight           = cropRight,
    cropBottom          = cropBottom,
    trimStartMs         = trimStartMs,
    trimEndMs           = trimEndMs,
    boxPx               = boxPx,
    sourceWidth         = sourceWidth,
    sourceHeight        = sourceHeight,
    videoCodec          = if (outputFormat == "AV1") VideoCodec.AV1 else VideoCodec.H264,
)
