package de.perigon.companion.posts.worker

import android.content.Context
import android.net.Uri
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.hilt.work.HiltWorker
import androidx.work.*
import de.perigon.companion.core.data.UserNotificationDao
import de.perigon.companion.core.data.UserNotifications
import de.perigon.companion.core.prefs.AppPrefs
import de.perigon.companion.core.prefs.CredentialStore
import de.perigon.companion.media.data.TransformJobStatus
import de.perigon.companion.media.data.TransformQueue
import de.perigon.companion.posts.data.PostDao
import de.perigon.companion.posts.data.PostMediaDao
import de.perigon.companion.posts.data.PostMediaEntity
import de.perigon.companion.posts.data.toDomain
import de.perigon.companion.util.network.GitHubClient
import de.perigon.companion.util.network.TreeFile
import de.perigon.companion.posts.domain.PostPublishState
import de.perigon.companion.posts.data.PostMediaFileStore
import de.perigon.companion.posts.domain.JekyllPostBuilder
import de.perigon.companion.posts.domain.MediaEntry
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.ktor.client.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import java.io.File

@HiltWorker
class PublishWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val postDao: PostDao,
    private val postMediaDao: PostMediaDao,
    private val http: HttpClient,
    private val appPrefs: AppPrefs,
    private val credentialStore: CredentialStore,
    private val mediaStore: PostMediaFileStore,
    private val transformQueue: TransformQueue,
    private val notificationDao: UserNotificationDao,
) : CoroutineWorker(ctx, params) {

    companion object {
        const val WORK_TAG         = "publish_worker"
        const val KEY_POST_ID      = "post_id"
        const val KEY_POST_ID_OUT  = "post_id_out"
        const val KEY_ERROR        = "error"
        const val KEY_ERROR_DETAIL = "error_detail"
        const val KEY_PROGRESS     = "progress"
        const val KEY_STAGE        = "stage"
        const val KEY_CURRENT_FILE = "current_file"
        const val KEY_FILE_INDEX   = "file_index"
        const val KEY_FILES_TOTAL  = "files_total"

        const val STAGE_PROCESSING_MEDIA = "PROCESSING_MEDIA"
        const val STAGE_COMMITTING       = "COMMITTING"
        const val STAGE_DONE             = "DONE"

        fun buildRequest(postId: Long): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<PublishWorker>()
                .addTag(WORK_TAG)
                .setInputData(workDataOf(KEY_POST_ID to postId))
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build())
                .build()
    }

    private fun githubClient(): GitHubClient? {
        val token = credentialStore.githubToken() ?: return null
        val owner = appPrefs.githubOwner() ?: return null
        val repo  = appPrefs.githubRepo() ?: return null
        return GitHubClient(http, token, owner, repo)
    }

    override suspend fun doWork(): Result {
        val postId = inputData.getLong(KEY_POST_ID, -1L)
        if (postId == -1L) return fail(postId, "Missing post ID")

        val github = githubClient()
            ?: return fail(postId, "GitHub credentials not configured")

        val post = postDao.getById(postId)
            ?: return fail(postId, "Post $postId not found")

        val mediaList = postMediaDao.getForPost(postId)

        val sorted = mediaList.sortedWith(compareBy(
            { if (it.id == post.pinnedMediaId) 0 else 1 },
            { it.position },
        ))

        // ---- Phase 1: Process media and collect bytes ----

        emitProgress(stage = STAGE_PROCESSING_MEDIA, progress = 0, filesTotal = sorted.size)

        data class PreparedMedia(
            val entity: PostMediaEntity,
            val bytes: ByteArray,
            val filename: String,
            val mimeType: String,
        )

        val prepared = mutableListOf<PreparedMedia>()
        val errors = mutableListOf<String>()

        for ((index, media) in sorted.withIndex()) {
            val ext = if (media.mimeType.startsWith("video")) "mp4" else "jpg"
            val filename = "${media.contentHash}.$ext"

            emitProgress(
                stage       = STAGE_PROCESSING_MEDIA,
                progress    = index * 80 / sorted.size.coerceAtLeast(1),
                fileIndex   = index + 1,
                filesTotal  = sorted.size,
                currentFile = filename,
            )

            try {
                val bytes = processMediaWithIntent(media)
                // val bytes = if (media.hasTransformIntent) {
                //     processMediaWithIntent(media)
                // } else {
                //     readMediaBytes(media)
                // }

                if (bytes != null) {
                    prepared += PreparedMedia(media, bytes, filename, media.mimeType)
                } else {
                    errors += "Media $filename: file missing locally"
                }
            } catch (e: Exception) {
                errors += "Media $filename: ${e.message}"
            }
        }

        if (prepared.isEmpty() && sorted.isNotEmpty()) {
            postDao.updatePublishState(postId, PostPublishState.NEEDS_FIXING)
            return fail(postId, "All media processing failed", errors.joinToString("; "))
        }

        // ---- Phase 2: Build post markdown ----
        val mediaEntries = prepared.map { pm ->
            val (w, h) = if (pm.mimeType.startsWith("video")) {
                videoDimensions(pm.bytes) ?: (null to null)
            } else {
                imageDimensions(pm.bytes) ?: (null to null)
            }
            MediaEntry(filename = pm.filename, mimeType = pm.mimeType, width = w, height = h)
        }
        // val mediaEntries = prepared.map { MediaEntry(filename = it.filename, mimeType = it.mimeType) }
        val tags         = post.tags.split(",").map { it.trim() }.filter { it.isNotBlank() }
        val domainPost   = post.toDomain().copy(teaser = post.teaser, tags = tags)
        val siteUrl      = appPrefs.siteUrl()
        val markdown     = JekyllPostBuilder.build(domainPost, mediaEntries, siteUrl)
        val postPath     = "_posts/${post.localDate}-${post.slug}.md"

        // ---- Phase 3: Upload files ----

        emitProgress(stage = STAGE_COMMITTING, progress = 85)

        // disabled // try {
        // disabled //     for ((index, pm) in prepared.withIndex()) {
        // disabled //         if (pm.bytes.isEmpty()) continue
        // disabled //         val path = "static/${post.slug}/${pm.filename}"
        // disabled //         val sha = github.getFileSha(path)
        // disabled //         github.putFile(path, pm.bytes, "Publish media: ${pm.filename}", sha)
        // disabled //         emitProgress(
        // disabled //             stage       = STAGE_COMMITTING,
        // disabled //             progress    = 85 + (index + 1) * 10 / prepared.size.coerceAtLeast(1),
        // disabled //             fileIndex   = index + 1,
        // disabled //             filesTotal  = prepared.size + 1,
        // disabled //             currentFile = pm.filename,
        // disabled //         )
        // disabled //     }
        // disabled //     val postSha = github.getFileSha(postPath)
        // disabled //     github.putFile(postPath, markdown.toByteArray(Charsets.UTF_8), "Publish: ${post.title}", postSha)
        // disabled // } catch (e: Exception) {
        // disabled //     postDao.updatePublishState(postId, PostPublishState.NEEDS_FIXING)
        // disabled //     return fail(postId, "Upload failed", e.message ?: "Unknown error")
        // disabled // }

        // ---- Phase 3: Atomic commit ----

        val treeFiles = mutableListOf<TreeFile>()

        // Add media files
        for (pm in prepared) {
            if (pm.bytes.isNotEmpty()) {
                treeFiles += TreeFile(
                    path = "static/${post.slug}/${pm.filename}",
                    content = pm.bytes,
                )
            }
        }

        // Add post markdown
        treeFiles += TreeFile(
            path = postPath,
            content = markdown.toByteArray(Charsets.UTF_8),
        )

        // Deletions: any old media for this slug that's no longer in the post.
        // We collect current filenames and delete anything under static/slug/ not in that set.
        val currentFilenames = prepared.map { it.filename }.toSet()
        val oldMediaPaths = collectOldMediaPaths(github, post.slug, currentFilenames)

        try {
            github.commitTree(
                message    = "Publish: ${post.title}",
                additions  = treeFiles,
                deletions  = oldMediaPaths,
                keepFilenames  = currentFilenames,
            )
        } catch (e: Exception) {
            postDao.updatePublishState(postId, PostPublishState.NEEDS_FIXING)
            return fail(postId, "Atomic commit failed", e.message ?: "Unknown error")
        }

        // ---- Done ----

        emitProgress(stage = STAGE_DONE, progress = 100)

        val finalState = if (errors.isNotEmpty())
            PostPublishState.NEEDS_FIXING else PostPublishState.PUBLISHED
        postDao.updatePublishState(postId, finalState, System.currentTimeMillis())

        if (errors.isNotEmpty()) {
            UserNotifications.error(
                notificationDao, "publish",
                "Published with issues: ${post.title}",
                errors.joinToString("; "),
                relatedPostId = postId,
            )
            return Result.failure(workDataOf(
                KEY_POST_ID_OUT  to postId,
                KEY_ERROR        to "Published with issues",
                KEY_ERROR_DETAIL to errors.joinToString("; "),
            ))
        }

        return Result.success()
    }

    /**
     * Find media paths under static/slug/ that are no longer part of the post.
     * Uses the tree fetched during commitTree — but since commitTree fetches
     * its own tree, we do a lightweight check here via getTree.
     */
    private suspend fun collectOldMediaPaths(
        github: GitHubClient,
        slug: String,
        currentFilenames: Set<String>,
    ): Set<String> {
        val prefix = "static/$slug/"
        return try {
            github.getTree()
                .filter { it.path.startsWith(prefix) }
                .map { it.path }
                .filter { path -> path.substringAfterLast('/') !in currentFilenames }
                .toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    // ---- Media processing (unchanged logic) ----

    private suspend fun processMediaWithIntent(media: PostMediaEntity): ByteArray? {
        val sourceUri = resolveSourceUri(media) ?: return null
        val isVideo = media.mimeType.startsWith("video")
        val ext = if (isVideo) "mp4" else "jpg"
        val displayName = "${media.contentHash}_processed.$ext"

        val jobId = transformQueue.submit(
            sourceUri           = Uri.parse(sourceUri),
            displayName         = displayName,
            mediaType           = if (isVideo) "VIDEO" else "IMAGE",
            boxPx               = if (isVideo) TransformQueue.DEFAULT_VIDEO_BOX_PX else TransformQueue.DEFAULT_BOX_PX,
            quality             = TransformQueue.DEFAULT_QUALITY,
            trimStartMs         = media.trimStartMs,
            trimEndMs           = media.trimEndMs,
            cropLeft            = media.cropLeft,
            cropTop             = media.cropTop,
            cropRight           = media.cropRight,
            cropBottom          = media.cropBottom,
            orientationDegrees  = media.orientationDegrees,
            flipHorizontal      = media.flipHorizontal,
            fineRotationDegrees = media.fineRotationDegrees,
            callerTag           = "publish",
        )

        val completedJob = transformQueue.observeJob(jobId).filterNotNull().first { job ->
            job.status == TransformJobStatus.DONE || job.status == TransformJobStatus.FAILED
        }

        if (completedJob.status == TransformJobStatus.FAILED) {
            throw Exception(completedJob.error ?: "Processing failed")
        }

        val tempFile = java.io.File(completedJob.outputPath!!)
        return try {
            tempFile.readBytes()
        } finally {
            tempFile.delete()
        }
    }

    private fun readMediaBytes(media: PostMediaEntity): ByteArray? {
        if (media.sourceUri.isNotBlank()) {
            val bytes = tryReadUri(media.sourceUri)
            if (bytes != null) return bytes
        }
        if (media.mediaStoreUri.isNotBlank()) {
            val bytes = tryReadUri(media.mediaStoreUri)
            if (bytes != null) return bytes
        }
        val resolved = mediaStore.resolveUri(media.contentHash, media.mimeType)
        if (resolved != null) {
            return tryReadUri(resolved.toString())
        }
        return null
    }

    private fun resolveSourceUri(media: PostMediaEntity): String? {
        if (media.sourceUri.isNotBlank()) {
            if (tryReadUri(media.sourceUri) != null) return media.sourceUri
        }
        if (media.mediaStoreUri.isNotBlank()) {
            if (tryReadUri(media.mediaStoreUri) != null) return media.mediaStoreUri
        }
        val resolved = mediaStore.resolveUri(media.contentHash, media.mimeType)
        return resolved?.toString()
    }

    private fun tryReadUri(uriStr: String): ByteArray? {
        return try {
            mediaStore.openInputStream(uriStr)?.use { it.readBytes() }
        } catch (_: Exception) {
            null
        }
    }

    // ---- Progress & error reporting ----

    private suspend fun fail(postId: Long, error: String, detail: String = ""): Result {
        UserNotifications.error(
            notificationDao, "publish",
            "Publish failed: $error",
            detail.takeIf { it.isNotBlank() },
            relatedPostId = postId,
        )
        return Result.failure(workDataOf(
            KEY_POST_ID_OUT  to postId,
            KEY_ERROR        to error,
            KEY_ERROR_DETAIL to detail,
        ))
    }

    private suspend fun emitProgress(
        stage:       String = STAGE_COMMITTING,
        progress:    Int    = 0,
        fileIndex:   Int    = 0,
        filesTotal:  Int    = 0,
        currentFile: String = "",
    ) {
        setProgress(workDataOf(
            KEY_STAGE        to stage,
            KEY_PROGRESS     to progress,
            KEY_FILE_INDEX   to fileIndex,
            KEY_FILES_TOTAL  to filesTotal,
            KEY_CURRENT_FILE to currentFile,
        ))
    }
}

fun imageDimensions(bytes: ByteArray): Pair<Int, Int>? {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
    return opts.outWidth to opts.outHeight
}

fun videoDimensions(bytes: ByteArray): Pair<Int, Int>? {
    val tmp = File.createTempFile("vdim", ".mp4")
    return try {
        tmp.writeBytes(bytes)
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(tmp.absolutePath)
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            if (w != null && h != null) w to h else null
        } finally {
            retriever.release()
        }
    } finally {
        tmp.delete()
    }
}