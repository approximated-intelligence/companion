package de.perigon.companion.posts.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import de.perigon.companion.core.data.UserNotificationDao
import de.perigon.companion.core.data.UserNotifications
import de.perigon.companion.core.prefs.AppPrefs
import de.perigon.companion.core.prefs.CredentialStore
import de.perigon.companion.posts.data.PostDao
import de.perigon.companion.posts.data.PostMediaDao
import de.perigon.companion.util.network.GitHubClient
import de.perigon.companion.util.network.HttpMediaBackendFactory
import de.perigon.companion.util.network.MediaBackend
import de.perigon.companion.util.network.S3BackendFactory
import de.perigon.companion.posts.domain.PostPublishState
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.ktor.client.*

@HiltWorker
class UnpublishWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val postDao: PostDao,
    private val postMediaDao: PostMediaDao,
    private val http: HttpClient,
    private val appPrefs: AppPrefs,
    private val credentialStore: CredentialStore,
    private val notificationDao: UserNotificationDao,
    private val s3Factory: S3BackendFactory,
    private val httpMediaFactory: HttpMediaBackendFactory,
) : CoroutineWorker(ctx, params) {

    companion object {
        const val WORK_TAG         = "unpublish_worker"
        const val KEY_POST_ID      = "post_id"
        const val KEY_SLUG         = "slug"
        const val KEY_LOCAL_DATE   = "local_date"
        const val KEY_DELETE_LOCAL = "delete_local"
        const val KEY_ERROR        = "error"

        fun buildRequest(
            postId:      Long,
            slug:        String,
            localDate:   String,
            deleteLocal: Boolean = false,
        ): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<UnpublishWorker>()
                .addTag(WORK_TAG)
                .setInputData(workDataOf(
                    KEY_POST_ID      to postId,
                    KEY_SLUG         to slug,
                    KEY_LOCAL_DATE   to localDate,
                    KEY_DELETE_LOCAL to deleteLocal,
                ))
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

    private fun mediaBackend(): MediaBackend? {
        val s3Config = appPrefs.s3MediaConfig()
        val s3Secret = credentialStore.s3SecretKey()
        if (s3Config != null && s3Secret != null) {
            return s3Factory.create(s3Config.endpoint, s3Config.bucket, s3Config.keyId, s3Secret, s3Config.region)
        }
        val httpConfig = appPrefs.httpMediaConfig()
        val httpPass   = credentialStore.httpMediaPassword()
        if (httpConfig != null && httpPass != null) {
            return httpMediaFactory.create(httpConfig.endpoint, httpConfig.user, httpPass, httpConfig.authType)
        }
        return null
    }

    override suspend fun doWork(): Result {
        val postId      = inputData.getLong(KEY_POST_ID, -1L)
        val slug        = inputData.getString(KEY_SLUG)       ?: return fail("Missing slug")
        val localDate   = inputData.getString(KEY_LOCAL_DATE) ?: return fail("Missing local date")
        val deleteLocal = inputData.getBoolean(KEY_DELETE_LOCAL, false)

        val github = githubClient() ?: return fail("GitHub credentials not configured")

        val postPath    = "_posts/$localDate-$slug.md"
        val mediaPrefix = "static/$slug/"

        // One delta commit removes the post and its media:
        //  - deletions = exact expected path, STRICT: proven present (delete)
        //    or proven absent (tolerant no-op) even under tree truncation.
        //  - deleteWhere = any "_posts/*-$slug.md": catches the markdown when
        //    the post's date changed after publishing (path drift).
        //  - stalePrefix = everything under static/slug/ on GitHub.
        // A post that was never on GitHub yields a clean no-op (HEAD).
        try {
            github.commitTree(
                message     = "Unpublish: $slug",
                deletions   = setOf(postPath),
                deleteWhere = { path -> path.startsWith("_posts/") && path.endsWith("-$slug.md") },
                stalePrefix = mediaPrefix,
            )
        } catch (e: Exception) {
            if (postId != -1L) {
                postDao.updatePublishState(postId, PostPublishState.NEEDS_FIXING)
            }
            return fail("Atomic unpublish failed: ${e.message}")
        }

        // Media published to an external backend (S3/HTTP) is not touched
        // by the GitHub commit — delete it best-effort. Failures and
        // unsupported backends never fail the unpublish (the post is
        // already down); undeleted keys are surfaced for manual cleanup.
        cleanupExternalMedia(postId, slug)

        updateLocalState(postId, deleteLocal)
        return Result.success()
    }

    private suspend fun cleanupExternalMedia(postId: Long, slug: String) {
        val backend = mediaBackend() ?: return // no external backend configured

        val keys: List<String> = if (postId != -1L) {
            postMediaDao.getForPost(postId).map { media ->
                val ext = if (media.mimeType.startsWith("video")) "mp4" else "jpg"
                "static/$slug/${media.contentHash}.$ext"
            }
        } else emptyList()

        if (keys.isEmpty()) {
            if (postId == -1L) {
                UserNotifications.info(
                    notificationDao, "unpublish",
                    "Unpublished $slug: external media (if any) under static/$slug/ was not deleted (post id unknown) — remove manually if needed.",
                )
            }
            return
        }

        val undeleted = mutableListOf<String>()
        for (key in keys) {
            val gone = try {
                backend.deleteMedia(key)
            } catch (e: Exception) {
                android.util.Log.w("UnpublishWorker", "deleteMedia $key failed: ${e.message}")
                false
            }
            if (!gone) undeleted += key
        }

        if (undeleted.isNotEmpty()) {
            UserNotifications.info(
                notificationDao, "unpublish",
                "Unpublished $slug: ${undeleted.size} media file(s) remain on the external backend",
                "Delete manually:\n" + undeleted.joinToString("\n"),
            )
        }
    }

    private suspend fun updateLocalState(postId: Long, deleteLocal: Boolean) {
        if (postId == -1L) return
        if (deleteLocal) {
            postDao.delete(postId)
        } else {
            postDao.updatePublishState(postId, PostPublishState.DRAFT)
        }
    }

    private suspend fun fail(error: String): Result {
        UserNotifications.error(notificationDao, "unpublish", "Unpublish failed: $error")
        return Result.failure(workDataOf(KEY_ERROR to error))
    }
}
