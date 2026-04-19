package de.perigon.companion.posts.site.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import de.perigon.companion.core.data.UserNotificationDao
import de.perigon.companion.core.data.UserNotifications
import de.perigon.companion.core.prefs.AppPrefs
import de.perigon.companion.core.prefs.CredentialStore
import de.perigon.companion.posts.site.data.AssetRepository
import de.perigon.companion.util.network.GitHubClient
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.ktor.client.*

@HiltWorker
class GitHubFileWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val http: HttpClient,
    private val appPrefs: AppPrefs,
    private val credentialStore: CredentialStore,
    private val assetRepository: AssetRepository,
    private val notificationDao: UserNotificationDao,
) : CoroutineWorker(ctx, params) {

    companion object {
        const val WORK_TAG = "github_file_worker"

        const val KEY_OP        = "op"
        const val KEY_PATH      = "path"
        const val KEY_CONTENT   = "content"
        const val KEY_KNOWN_SHA = "known_sha"
        const val KEY_MESSAGE   = "message"
        const val KEY_CALLER_ID = "caller_id"
        const val KEY_IS_BINARY = "is_binary"

        const val KEY_SERVER_SHA = "server_sha"
        const val KEY_ERROR      = "error"

        const val OP_PUT    = "PUT"
        const val OP_DELETE = "DELETE"

        fun buildPutRequest(
            path:     String,
            content:  String,
            knownSha: String? = null,
            message:  String  = "Update $path",
            callerId: Long    = 0L,
        ): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<GitHubFileWorker>()
                .addTag(WORK_TAG)
                .addTag("$WORK_TAG/$path")
                .setInputData(workDataOf(
                    KEY_OP        to OP_PUT,
                    KEY_PATH      to path,
                    KEY_CONTENT   to content,
                    KEY_KNOWN_SHA to knownSha,
                    KEY_MESSAGE   to message,
                    KEY_CALLER_ID to callerId,
                    KEY_IS_BINARY to false,
                ))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

        fun buildBinaryPutRequest(
            path:     String,
            knownSha: String? = null,
            message:  String  = "Update $path",
            callerId: Long    = 0L,
        ): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<GitHubFileWorker>()
                .addTag(WORK_TAG)
                .addTag("$WORK_TAG/$path")
                .setInputData(workDataOf(
                    KEY_OP        to OP_PUT,
                    KEY_PATH      to path,
                    KEY_KNOWN_SHA to knownSha,
                    KEY_MESSAGE   to message,
                    KEY_CALLER_ID to callerId,
                    KEY_IS_BINARY to true,
                ))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

        fun buildDeleteRequest(
            path:     String,
            knownSha: String,
            message:  String = "Delete $path",
            callerId: Long   = 0L,
        ): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<GitHubFileWorker>()
                .addTag(WORK_TAG)
                .addTag("$WORK_TAG/$path")
                .setInputData(workDataOf(
                    KEY_OP        to OP_DELETE,
                    KEY_PATH      to path,
                    KEY_KNOWN_SHA to knownSha,
                    KEY_MESSAGE   to message,
                    KEY_CALLER_ID to callerId,
                ))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
    }

    private fun githubClient(): GitHubClient? {
        val token = credentialStore.githubToken() ?: return null
        val owner = appPrefs.githubOwner() ?: return null
        val repo  = appPrefs.githubRepo() ?: return null
        return GitHubClient(http, token, owner, repo)
    }

    override suspend fun doWork(): Result {
        val op       = inputData.getString(KEY_OP)       ?: return fail("Missing op")
        val path     = inputData.getString(KEY_PATH)     ?: return fail("Missing path")
        val message  = inputData.getString(KEY_MESSAGE)  ?: "Update $path"
        val knownSha = inputData.getString(KEY_KNOWN_SHA)
        val callerId = inputData.getLong(KEY_CALLER_ID, 0L)
        val isBinary = inputData.getBoolean(KEY_IS_BINARY, false)

        val github = githubClient()
            ?: return fail("GitHub credentials not configured", callerId)

        return try {
            when (op) {
                OP_PUT -> {
                    val bytes = if (isBinary) {
                        assetRepository.readOnDiskBytes(path)
                            ?: return fail("Binary file not found on disk: $path", callerId)
                    } else {
                        val content = inputData.getString(KEY_CONTENT) ?: ""
                        content.toByteArray(Charsets.UTF_8)
                    }
                    val sha = knownSha?.takeIf { it.isNotEmpty() }
                        ?: github.getFileSha(path)
                    github.putFile(
                        path    = path,
                        content = bytes,
                        message = message,
                        sha     = sha,
                    )
                    val newSha = github.getFileSha(path) ?: ""
                    Result.success(workDataOf(
                        KEY_SERVER_SHA to newSha,
                        KEY_CALLER_ID  to callerId,
                    ))
                }
                OP_DELETE -> {
                    val sha = knownSha?.takeIf { it.isNotEmpty() }
                        ?: github.getFileSha(path)
                    if (sha != null) {
                        github.deleteFile(path, sha, message)
                    }
                    Result.success(workDataOf(
                        KEY_SERVER_SHA to "",
                        KEY_CALLER_ID  to callerId,
                    ))
                }
                else -> fail("Unknown op: $op", callerId)
            }
        } catch (e: Exception) {
            fail(e.message ?: "Unknown error", callerId)
        }
    }

    private suspend fun fail(error: String, callerId: Long = 0L): Result {
        UserNotifications.error(
            notificationDao, "github_sync",
            "Sync failed: $error",
        )
        return Result.failure(workDataOf(
            KEY_ERROR      to error,
            KEY_CALLER_ID  to callerId,
        ))
    }
}
