package de.perigon.companion.posts.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import de.perigon.companion.core.data.UserNotificationDao
import de.perigon.companion.core.data.UserNotifications
import de.perigon.companion.core.prefs.AppPrefs
import de.perigon.companion.core.prefs.CredentialStore
import de.perigon.companion.posts.data.PostDao
import de.perigon.companion.util.network.GitHubClient
import de.perigon.companion.posts.domain.PostPublishState
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.ktor.client.*

@HiltWorker
class UnpublishWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val postDao: PostDao,
    private val http: HttpClient,
    private val appPrefs: AppPrefs,
    private val credentialStore: CredentialStore,
    private val notificationDao: UserNotificationDao,
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

    override suspend fun doWork(): Result {
        val postId      = inputData.getLong(KEY_POST_ID, -1L)
        val slug        = inputData.getString(KEY_SLUG)       ?: return fail("Missing slug")
        val localDate   = inputData.getString(KEY_LOCAL_DATE) ?: return fail("Missing local date")
        val deleteLocal = inputData.getBoolean(KEY_DELETE_LOCAL, false)

        val github = githubClient() ?: return fail("GitHub credentials not configured")

        val postPath = "_posts/$localDate-$slug.md"
        val mediaPrefix = "static/$slug/"

        // Collect all paths to delete from the tree
        val deletions = mutableSetOf<String>()

        try {
            val tree = github.getTree()

            // Post markdown file
            if (tree.any { it.path == postPath }) {
                deletions += postPath
            }

            // All media under static/slug/
            tree.filter { it.path.startsWith(mediaPrefix) }
                .forEach { deletions += it.path }
        } catch (e: Exception) {
            // If we can't fetch the tree, fall back to just the post path
            // and hope for the best — better than failing entirely
            deletions += postPath
        }

        if (deletions.isEmpty()) {
            // Nothing to delete — post wasn't on GitHub
            updateLocalState(postId, deleteLocal)
            return Result.success()
        }

        try {
            github.commitTree(
                message   = "Unpublish: $slug",
                deletions = deletions,
            )
        } catch (e: Exception) {
            if (postId != -1L) {
                postDao.updatePublishState(postId, PostPublishState.NEEDS_FIXING)
            }
            return fail("Atomic unpublish failed: ${e.message}")
        }

        updateLocalState(postId, deleteLocal)
        return Result.success()
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