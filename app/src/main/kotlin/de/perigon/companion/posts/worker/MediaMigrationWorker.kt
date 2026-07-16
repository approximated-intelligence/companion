package de.perigon.companion.posts.worker

// TODO(media-migration): this ENTIRE FILE is one-shot legacy migration.
// Delete it (plus the button in PostListScreen/ViewModel, the contentHash
// fallbacks in PostMediaPuller/UnpublishWorker/verify, and eventually the
// posts.mediaMigrated column) after all repos are migrated.

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import de.perigon.companion.core.data.UserNotificationDao
import de.perigon.companion.core.data.UserNotifications
import de.perigon.companion.core.prefs.AppPrefs
import de.perigon.companion.core.prefs.CredentialStore
import de.perigon.companion.posts.data.PostDao
import de.perigon.companion.posts.data.PostEntity
import de.perigon.companion.posts.data.PostMediaDao
import de.perigon.companion.posts.data.PostMediaFileStore
import de.perigon.companion.posts.data.toDomain
import de.perigon.companion.posts.domain.JekyllPostBuilder
import de.perigon.companion.posts.domain.MediaEntry
import de.perigon.companion.posts.domain.PostPublishState
import de.perigon.companion.util.network.BlobAddition
import de.perigon.companion.util.network.GitHubClient
import de.perigon.companion.util.network.HttpMediaBackendFactory
import de.perigon.companion.util.network.MediaBackend
import de.perigon.companion.util.network.S3BackendFactory
import de.perigon.companion.util.network.TreeFile
import de.perigon.companion.util.sha256Hex
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*

/**
 * One-shot migration: rename published media from contentHash (hash of the
 * SOURCE) to processedHash (hash of the PUBLISHED bytes) — WITHOUT any
 * reprocessing. Per unmigrated published post:
 *
 *  1. Download each media exactly as published (static URL, GitHub fallback).
 *  2. sha256 the downloaded bytes = processedHash; measure dimensions from
 *     the same bytes (values match the legacy markdown — same bytes).
 *  3. Upload under the new name, refresh the local PostMedia copy, persist
 *     processedHash (per-media resume marker).
 *  4. Rebuild the markdown (only filenames change) and commit; GitHub-mode
 *     prunes the old names in the same commit, external mode deletes old keys.
 *  5. Record publishedPath/publishedMediaBase, set mediaMigrated (post-level
 *     completion marker).
 *
 * Crash-safe at every step: reruns resume per media via processedHash and
 * per post via mediaMigrated. Unfetchable media → post NEEDS_FIXING, next.
 */
@HiltWorker
class MediaMigrationWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val postDao: PostDao,
    private val postMediaDao: PostMediaDao,
    private val mediaFileStore: PostMediaFileStore,
    private val http: HttpClient,
    private val appPrefs: AppPrefs,
    private val credentialStore: CredentialStore,
    private val notificationDao: UserNotificationDao,
    private val s3Factory: S3BackendFactory,
    private val httpMediaFactory: HttpMediaBackendFactory,
) : CoroutineWorker(ctx, params) {

    companion object {
        const val WORK_NAME = "media_migration"

        const val KEY_TOTAL   = "total"
        const val KEY_DONE    = "done"
        const val KEY_FAILED  = "failed"
        const val KEY_CURRENT = "current"

        fun buildRequest(): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<MediaMigrationWorker>()
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

    private fun mediaBackend(): Pair<MediaBackend, String>? {
        val s3Config = appPrefs.s3MediaConfig()
        val s3Secret = credentialStore.s3SecretKey()
        if (s3Config != null && s3Secret != null) {
            return s3Factory.create(s3Config.endpoint, s3Config.bucket, s3Config.keyId, s3Secret, s3Config.region) to s3Config.staticUrl.trimEnd('/')
        }
        val httpConfig = appPrefs.httpMediaConfig()
        val httpPass   = credentialStore.httpMediaPassword()
        if (httpConfig != null && httpPass != null) {
            return httpMediaFactory.create(httpConfig.endpoint, httpConfig.user, httpPass, httpConfig.authType) to httpConfig.staticUrl.trimEnd('/')
        }
        return null
    }

    override suspend fun doWork(): Result {
        val github = githubClient()
            ?: return fail("GitHub credentials not configured")

        val posts = postDao.getUnmigratedPublished()
        if (posts.isEmpty()) {
            UserNotifications.info(notificationDao, "media-migration", "Nothing to migrate")
            return Result.success()
        }

        val backendPair = mediaBackend()
        var done = 0
        var failed = 0

        for ((index, post) in posts.withIndex()) {
            setProgress(workDataOf(
                KEY_TOTAL to posts.size, KEY_DONE to done, KEY_FAILED to failed,
                KEY_CURRENT to post.slug,
            ))
            try {
                migratePost(post, github, backendPair)
                done++
            } catch (e: Exception) {
                failed++
                postDao.updatePublishState(post.id, PostPublishState.NEEDS_FIXING)
                UserNotifications.error(
                    notificationDao, "media-migration",
                    "Migration failed for '${post.slug}' (${index + 1}/${posts.size})",
                    e.message,
                    relatedPostId = post.id,
                )
            }
        }

        val msg = "Media migration: $done migrated" + if (failed > 0) ", $failed failed" else ""
        if (failed > 0) UserNotifications.error(notificationDao, "media-migration", msg)
        else UserNotifications.success(notificationDao, "media-migration", msg)
        return if (failed > 0) Result.failure() else Result.success()
    }

    private suspend fun migratePost(
        post: PostEntity,
        github: GitHubClient,
        backendPair: Pair<MediaBackend, String>?,
    ) {
        val slug = post.slug
        val mediaList = postMediaDao.getForPost(post.id)
            .sortedWith(compareBy({ if (it.id == post.pinnedMediaId) 0 else 1 }, { it.position }))

        data class Migrated(val filename: String, val mimeType: String, val w: Int?, val h: Int?, val blobSha: String?)
        val migrated = mutableListOf<Migrated>()
        val legacyExternalKeys = mutableListOf<String>()

        for (media in mediaList) {
            val ext = if (media.mimeType.startsWith("video")) "mp4" else "jpg"
            // Resume-aware fetch: by new name if already renamed, else legacy name.
            val fetchName = "${media.processedHash ?: media.contentHash}.$ext"
            val bytes = fetchPublishedBytes(post, slug, fetchName, github)
                ?: error("published media not fetchable: static/$slug/$fetchName")

            val processedHash = media.processedHash ?: run {
                val hash = sha256Hex(bytes)
                val newKey = "static/$slug/$hash.$ext"
                if (backendPair != null) {
                    backendPair.first.putObject(newKey, bytes,
                        if (media.mimeType.startsWith("video")) "video/mp4" else "image/jpeg")
                    legacyExternalKeys += "static/$slug/${media.contentHash}.$ext"
                }
                // Refresh the local PostMedia mirror under the hash name.
                mediaFileStore.writeFromBytes(hash, ext, bytes)?.let { uri ->
                    postMediaDao.upsert(media.copy(mediaStoreUri = uri, processedHash = hash))
                } ?: postMediaDao.updateProcessedHash(media.id, hash)
                hash
            }

            val (w, h) = if (media.mimeType.startsWith("video")) {
                videoDimensions(bytes) ?: (null to null)
            } else {
                imageDimensions(bytes) ?: (null to null)
            }

            val blobSha = if (backendPair == null) github.createBlob(bytes) else null
            migrated += Migrated("$processedHash.$ext", media.mimeType, w, h, blobSha)
        }

        // Rebuild markdown — identical content, new filenames.
        val domainPost = post.toDomain()
        val mediaBaseUrl = backendPair?.second
        val markdown = JekyllPostBuilder.build(
            domainPost,
            migrated.map { MediaEntry(it.filename, it.mimeType, it.w, it.h) },
            appPrefs.siteUrl(),
            mediaBaseUrl,
        )
        val postPath = post.publishedPath ?: "_posts/${post.localDate}-$slug.md"

        if (backendPair != null) {
            github.putFile(
                path = postPath,
                content = markdown.toByteArray(Charsets.UTF_8),
                message = "Migrate media names: $slug",
                sha = github.getFileSha(postPath),
            )
            for (key in legacyExternalKeys) {
                runCatching { backendPair.first.deleteMedia(key) }
            }
        } else {
            github.commitTree(
                message       = "Migrate media names: $slug",
                additions     = listOf(TreeFile(postPath, markdown.toByteArray(Charsets.UTF_8))),
                blobAdditions = migrated.mapNotNull { m -> m.blobSha?.let { BlobAddition("static/$slug/${m.filename}", it) } },
                stalePrefix   = "static/$slug/",
                keepFilenames = migrated.map { it.filename }.toSet(),
            )
        }

        postDao.updatePublishedInfo(post.id, postPath, mediaBaseUrl)
        postDao.setMediaMigrated(post.id, true)
    }

    /** Fetch the published bytes as-is: recorded/current static base, then GitHub. */
    private suspend fun fetchPublishedBytes(
        post: PostEntity,
        slug: String,
        name: String,
        github: GitHubClient,
    ): ByteArray? {
        val bases = listOfNotNull(
            post.publishedMediaBase?.trimEnd('/'),
            appPrefs.s3MediaConfig()?.staticUrl?.trimEnd('/'),
            appPrefs.httpMediaConfig()?.staticUrl?.trimEnd('/'),
        ).distinct()
        for (base in bases) {
            try {
                val resp = http.get("$base/static/$slug/$name")
                if (resp.status.isSuccess()) return resp.readRawBytes()
            } catch (_: Exception) { }
        }
        return try {
            github.getFileBinary("static/$slug/$name")
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun fail(error: String): Result {
        UserNotifications.error(notificationDao, "media-migration", "Media migration failed: $error")
        return Result.failure()
    }
}
