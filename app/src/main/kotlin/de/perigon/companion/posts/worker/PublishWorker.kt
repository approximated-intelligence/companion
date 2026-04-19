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
import de.perigon.companion.posts.data.PostRepository
import de.perigon.companion.posts.data.toDomain
import de.perigon.companion.util.network.GitHubClient
import de.perigon.companion.util.network.TreeFile
import de.perigon.companion.util.network.MediaBackend
import de.perigon.companion.util.network.S3BackendFactory
import de.perigon.companion.util.network.HttpMediaBackendFactory
import de.perigon.companion.posts.domain.PostPublishState
import de.perigon.companion.posts.data.PostMediaFileStore
import de.perigon.companion.posts.data.PostMediaPlacement
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
    private val postDao:          PostDao,
    private val postMediaDao:     PostMediaDao,
    private val postRepository:   PostRepository,
    private val http:             HttpClient,
    private val appPrefs:         AppPrefs,
    private val credentialStore:  CredentialStore,
    private val mediaStore:       PostMediaFileStore,
    private val transformQueue:   TransformQueue,
    private val notificationDao:  UserNotificationDao,
    private val s3Factory:        S3BackendFactory,
    private val httpMediaFactory: HttpMediaBackendFactory,
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
        const val STAGE_UPLOADING_MEDIA  = "UPLOADING_MEDIA"
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
        val repo  = appPrefs.githubRepo()  ?: return null
        return GitHubClient(http, token, owner, repo)
    }

    /**
     * Returns the active external media backend (S3 or HTTP), or null if
     * media should go through GitHub (commitTree path).
     * Also returns the static base URL for media references in markdown.
     */
    private fun mediaBackend(): Pair<MediaBackend, String>? {
        // S3
        val s3Config   = appPrefs.s3MediaConfig()
        val s3Secret   = credentialStore.s3SecretKey()
        if (s3Config != null && s3Secret != null) {
            val backend = s3Factory.create(s3Config.endpoint, s3Config.bucket, s3Config.keyId, s3Secret, s3Config.region)
            return backend to s3Config.staticUrl.trimEnd('/')
        }
        // HTTP digest
        val httpConfig = appPrefs.httpMediaConfig()
        val httpPass   = credentialStore.httpMediaPassword()
        if (httpConfig != null && httpPass != null) {
            val backend = httpMediaFactory.create(httpConfig.endpoint, httpConfig.user, httpPass, httpConfig.authType)
            return backend to httpConfig.staticUrl.trimEnd('/')
        }
        return null
    }

    override suspend fun doWork(): Result {
        val postId = inputData.getLong(KEY_POST_ID, -1L)
        if (postId == -1L) return fail(postId, "Missing post ID")

        val github = githubClient()
            ?: return fail(postId, "GitHub credentials not configured")

        val post = postDao.getById(postId)
            ?: return fail(postId, "Post $postId not found")

        val mediaList = postMediaDao.getForPost(postId)
        val sorted    = mediaList.sortedWith(compareBy(
            { if (it.id == post.pinnedMediaId) 0 else 1 },
            { it.position },
        ))

        // ---- Phase 1: Process media and collect bytes ----
        emitProgress(stage = STAGE_PROCESSING_MEDIA, progress = 0, filesTotal = sorted.size)

        data class PreparedMedia(
            val entity:   PostMediaEntity,
            val bytes:    ByteArray,
            val filename: String,
            val mimeType: String,
        )

        val prepared      = mutableListOf<PreparedMedia>()
        val mediaErrors   = mutableListOf<String>()
        val mediaWarnings = mutableListOf<String>()
        val uploadErrors  = mutableListOf<String>()

        for ((index, media) in sorted.withIndex()) {
            val ext      = if (media.mimeType.startsWith("video")) "mp4" else "jpg"
            val filename = "${media.contentHash}.$ext"

            emitProgress(
                stage       = STAGE_PROCESSING_MEDIA,
                progress    = index * 80 / sorted.size.coerceAtLeast(1),
                fileIndex   = index + 1,
                filesTotal  = sorted.size,
                currentFile = filename,
            )

            try {
                val result = processMediaWithIntent(media)
                if (result != null) {
                    if (result.warning != null) mediaWarnings += "$filename: ${result.warning}"
                    val placement = persistToPostMedia(media, result.bytes, post.slug, index)
                    if (placement != null) {
                        postMediaDao.upsert(media.copy(mediaStoreUri = placement.uri))
                    }
                    prepared += PreparedMedia(media, result.bytes, filename, media.mimeType)
                } else {
                    mediaErrors += "$filename: source file no longer accessible. Open the post and re-add this media."
                }
            } catch (e: Exception) {
                mediaErrors += "$filename: transform failed - ${e.message}"
            }
        }

        if (prepared.isEmpty() && sorted.isNotEmpty()) {
            postDao.updatePublishState(postId, PostPublishState.NEEDS_FIXING)
            return fail(
                postId,
                "All media processing failed — file access may have expired",
                mediaErrors.joinToString("\n") +
                    "\n\nOpen the post, remove the affected media, and re-add it from your gallery.",
            )
        }

        // ---- Phase 2: Upload media to external backend if configured ----
        val backendPair   = mediaBackend()
        val externalMedia = backendPair != null
        val mediaBaseUrl  = backendPair?.second

        if (externalMedia) {
            val (backend, _) = backendPair!!
            emitProgress(stage = STAGE_UPLOADING_MEDIA, progress = 80)
            for ((index, pm) in prepared.withIndex()) {
                if (pm.bytes.isEmpty()) continue
                val key         = "static/${post.slug}/${pm.filename}"
                val contentType = if (pm.mimeType.startsWith("video")) "video/mp4" else "image/jpeg"
                try {
                    backend.putObject(key, pm.bytes, contentType)
                } catch (e: Exception) {
                    uploadErrors += "${pm.filename}: upload failed - ${e.message}"
                }
                emitProgress(
                    stage       = STAGE_UPLOADING_MEDIA,
                    progress    = 80 + (index + 1) * 5 / prepared.size.coerceAtLeast(1),
                    fileIndex   = index + 1,
                    filesTotal  = prepared.size,
                    currentFile = pm.filename,
                )
            }
        }

        // ---- Phase 3: Build post markdown ----
        val mediaEntries = prepared.map { pm ->
            val (w, h) = if (pm.mimeType.startsWith("video")) {
                videoDimensions(pm.bytes) ?: (null to null)
            } else {
                imageDimensions(pm.bytes) ?: (null to null)
            }
            MediaEntry(filename = pm.filename, mimeType = pm.mimeType, width = w, height = h)
        }

        val tags       = post.tags.split(",").map { it.trim() }.filter { it.isNotBlank() }
        val domainPost = post.toDomain().copy(teaser = post.teaser, tags = tags)
        val siteUrl    = appPrefs.siteUrl()
        val markdown   = JekyllPostBuilder.build(domainPost, mediaEntries, siteUrl, mediaBaseUrl)
        val postPath   = "_posts/${post.localDate}-${post.slug}.md"

        // ---- Phase 4: Commit to GitHub ----
        emitProgress(stage = STAGE_COMMITTING, progress = 85)

        try {
            if (externalMedia) {
                // Media lives externally — just put the markdown file
                val existingSha = github.getFileSha(postPath)
                github.putFile(
                    path    = postPath,
                    content = markdown.toByteArray(Charsets.UTF_8),
                    message = "Publish: ${post.title}",
                    sha     = existingSha,
                )
            } else {
                // Media goes to GitHub — atomic tree commit
                val treeFiles = mutableListOf<TreeFile>()

                for (pm in prepared) {
                    if (pm.bytes.isNotEmpty()) {
                        treeFiles += TreeFile(
                            path    = "static/${post.slug}/${pm.filename}",
                            content = pm.bytes,
                        )
                    }
                }

                treeFiles += TreeFile(
                    path    = postPath,
                    content = markdown.toByteArray(Charsets.UTF_8),
                )

                val currentFilenames = prepared.map { it.filename }.toSet()
                val oldMediaPaths    = collectOldMediaPaths(github, post.slug, currentFilenames)

                github.commitTree(
                    message       = "Publish: ${post.title}",
                    additions     = treeFiles,
                    deletions     = oldMediaPaths,
                    keepFilenames = currentFilenames,
                )
            }
        } catch (e: Exception) {
            postDao.updatePublishState(postId, PostPublishState.NEEDS_FIXING)
            return fail(postId, "Git commit failed: ${e.message}")
        }

        // ---- Done ----
        emitProgress(stage = STAGE_DONE, progress = 100)

        val allErrors  = mediaErrors + uploadErrors
        val finalState = if (allErrors.isNotEmpty()) PostPublishState.NEEDS_FIXING else PostPublishState.PUBLISHED
        postDao.updatePublishState(postId, finalState, System.currentTimeMillis())

        if (finalState == PostPublishState.PUBLISHED) {
            postRepository.unprotectAllMedia(postId)
        }

        if (allErrors.isNotEmpty() || mediaWarnings.isNotEmpty()) {
            val summary = buildString {
                if (mediaErrors.isNotEmpty()) {
                    appendLine("Media processing:")
                    mediaErrors.forEach { appendLine("  $it") }
                }
                if (uploadErrors.isNotEmpty()) {
                    appendLine("Media upload:")
                    uploadErrors.forEach { appendLine("  $it") }
                }
                if (mediaWarnings.isNotEmpty()) {
                    appendLine("Warnings:")
                    mediaWarnings.forEach { appendLine("  $it") }
                }
            }.trim()

            if (allErrors.isNotEmpty()) {
                UserNotifications.error(
                    notificationDao, "publish",
                    "${post.title}: published with ${allErrors.size} issue(s)",
                    summary,
                    relatedPostId = postId,
                )
                return Result.failure(workDataOf(
                    KEY_POST_ID_OUT  to postId,
                    KEY_ERROR        to "${allErrors.size} issue(s) during publish",
                    KEY_ERROR_DETAIL to summary,
                ))
            } else {
                UserNotifications.info(
                    notificationDao, "publish",
                    "${post.title}: published with warnings",
                    summary,
                )
            }
        }

        return Result.success()
    }

    private suspend fun collectOldMediaPaths(
        github:           GitHubClient,
        slug:             String,
        currentFilenames: Set<String>,
    ): Set<String> {
        val prefix = "static/$slug/"
        return try {
            github.getTree()
                .filter { it.path.startsWith(prefix) }
                .map { it.path }
                .filter { path -> path.substringAfterLast('/') !in currentFilenames }
                .toSet()
        } catch (e: Exception) {
            android.util.Log.e("PublishWorker", "collectOldMediaPaths failed", e)
            emptySet()
        }
    }

    private data class ProcessedMediaResult(val bytes: ByteArray, val warning: String? = null)

    private suspend fun processMediaWithIntent(media: PostMediaEntity): ProcessedMediaResult? {
        if (media.mediaStoreUri.isNotBlank() && mediaStore.isPostMediaUri(media.mediaStoreUri)) {
            val existingBytes = tryReadUri(media.mediaStoreUri)
            if (existingBytes != null) {
                if (!media.hasTransformIntent) return ProcessedMediaResult(existingBytes)
                val sourceUri = resolveSourceUri(media)
                if (sourceUri != null) return ProcessedMediaResult(transformFromUri(sourceUri, media))
                return ProcessedMediaResult(existingBytes, warning = "edits not applied, original file missing")
            }
        }

        val sourceUri = resolveSourceUri(media) ?: return null
        return ProcessedMediaResult(transformFromUri(sourceUri, media))
    }

    private suspend fun transformFromUri(sourceUri: String, media: PostMediaEntity): ByteArray {
        val isVideo     = media.mimeType.startsWith("video")
        val ext         = if (isVideo) "mp4" else "jpg"
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

        val tempFile = File(completedJob.outputPath!!)
        return try { tempFile.readBytes() } finally { tempFile.delete() }
    }

    private suspend fun persistToPostMedia(
        media:    PostMediaEntity,
        bytes:    ByteArray,
        slug:     String,
        position: Int,
    ): PostMediaPlacement? {
        val isVideo  = media.mimeType.startsWith("video")
        val tempFile = File(applicationContext.cacheDir, "pub_${media.id}.${if (isVideo) "mp4" else "jpg"}")
        return try {
            tempFile.writeBytes(bytes)
            if (isVideo) mediaStore.placeVideoResult(tempFile, slug, position)
            else         mediaStore.placeImageResult(tempFile, slug, position)
        } catch (e: Exception) {
            android.util.Log.e("PublishWorker", "persistToPostMedia failed", e)
            tempFile.delete()
            null
        }
    }

    private fun resolveSourceUri(media: PostMediaEntity): String? {
        if (media.sourceUri.isNotBlank()     && tryReadUri(media.sourceUri)     != null) return media.sourceUri
        if (media.mediaStoreUri.isNotBlank() && tryReadUri(media.mediaStoreUri) != null) return media.mediaStoreUri
        return mediaStore.resolveUri(media.contentHash, media.mimeType)?.toString()
    }

    private fun tryReadUri(uriStr: String): ByteArray? = try {
        mediaStore.openInputStream(uriStr)?.use { it.readBytes() }
    } catch (e: Exception) {
        android.util.Log.e("PublishWorker", "tryReadUri failed: $uriStr", e)
        null
    }

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
