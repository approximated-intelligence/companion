package de.perigon.companion.posts.site.ui.list

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import de.perigon.companion.core.data.UserNotificationDao
import de.perigon.companion.core.prefs.AppPrefs
import de.perigon.companion.core.prefs.CredentialStore
import de.perigon.companion.posts.site.data.AssetRepository
import de.perigon.companion.posts.site.data.AssetDao
import de.perigon.companion.posts.site.data.AssetEntity
import de.perigon.companion.util.network.GitHubClient
import de.perigon.companion.core.ui.SnackbarChannel
import de.perigon.companion.posts.site.worker.GitHubFileWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@Immutable
data class AssetListUiState(
    val assets:     List<AssetEntity> = emptyList(),
    val isFetching: Boolean           = false,
    val isReseeding: Boolean          = false,
)

@HiltViewModel
class AssetListViewModel @Inject constructor(
    @param:ApplicationContext private val ctx: Context,
    private val assetDao: AssetDao,
    private val assetRepository: AssetRepository,
    private val appPrefs: AppPrefs,
    private val credentialStore: CredentialStore,
    private val workManager: WorkManager,
    private val http: HttpClient,
    private val notificationDao: UserNotificationDao,
) : ViewModel() {

    private val _state = MutableStateFlow(AssetListUiState())
    val state: StateFlow<AssetListUiState> = _state.asStateFlow()

    val snackbar = SnackbarChannel()

    /** Expose for screen-level notification observation */
    val notifications: UserNotificationDao get() = notificationDao

    private val handledWorkIds = mutableSetOf<UUID>()

    init {
        viewModelScope.launch { assetRepository.seedDefaultsIfEmpty() }

        viewModelScope.launch {
            assetDao.observeAll().collect { assets ->
                _state.update { it.copy(assets = assets) }
            }
        }

        observeWorkers()
    }

    private fun githubClient(): GitHubClient? {
        val token = credentialStore.githubToken() ?: return null
        val owner = appPrefs.githubOwner() ?: return null
        val repo  = appPrefs.githubRepo() ?: return null
        return GitHubClient(http, token, owner, repo)
    }

    fun fetchFromServer() {
        val github = githubClient() ?: run {
            snackbar.send("GitHub credentials not configured")
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isFetching = true) }
            try {
                assetRepository.fetchServerShas(github)
            } catch (e: Exception) {
                snackbar.send("Fetch failed: ${e.message}")
            } finally {
                _state.update { it.copy(isFetching = false) }
            }
        }
    }

    fun reseedFromBundled() {
        viewModelScope.launch {
            _state.update { it.copy(isReseeding = true) }
            try {
                assetRepository.reseedFromBundled()
                snackbar.send("Reset to bundled defaults")
            } catch (e: Exception) {
                snackbar.send("Reseed failed: ${e.message}")
            } finally {
                _state.update { it.copy(isReseeding = false) }
            }
        }
    }

    fun pushToServer(asset: AssetEntity) {
        if (asset.isOnDisk) {
            pushBinaryToServer(asset)
        } else {
            pushTextToServer(asset)
        }
    }

    private fun pushTextToServer(asset: AssetEntity) {
        val request = GitHubFileWorker.buildPutRequest(
            path     = asset.path,
            content  = asset.content,
            knownSha = asset.serverSha.takeIf { it.isNotEmpty() },
            message  = "Update ${asset.path}",
            callerId = asset.id,
        )
        workManager.enqueueUniqueWork(
            "asset_push_${asset.id}", ExistingWorkPolicy.REPLACE, request)
    }

    private fun pushBinaryToServer(asset: AssetEntity) {
        val request = GitHubFileWorker.buildBinaryPutRequest(
            path     = asset.path,
            knownSha = asset.serverSha.takeIf { it.isNotEmpty() },
            message  = "Update ${asset.path}",
            callerId = asset.id,
        )
        workManager.enqueueUniqueWork(
            "asset_push_${asset.id}", ExistingWorkPolicy.REPLACE, request)
    }

    fun pullFromServer(asset: AssetEntity) {
        val github = githubClient() ?: run {
            snackbar.send("GitHub credentials not configured")
            return
        }
        viewModelScope.launch {
            try {
                assetRepository.pullFromServer(asset.id, github)
            } catch (e: Exception) {
                snackbar.send("Pull failed: ${e.message}")
            }
        }
    }

    fun deleteFromServer(asset: AssetEntity) {
        if (asset.serverSha.isEmpty()) {
            snackbar.send("${asset.path} is not on the server")
            return
        }
        val request = GitHubFileWorker.buildDeleteRequest(
            path     = asset.path,
            knownSha = asset.serverSha,
            message  = "Delete ${asset.path}",
            callerId = asset.id,
        )
        workManager.enqueueUniqueWork(
            "asset_delete_${asset.id}", ExistingWorkPolicy.REPLACE, request)
    }

    /**
     * Observe workers for success callbacks only (marking assets as pushed).
     * Error notifications are handled by the notification system.
     */
    private fun observeWorkers() {
        viewModelScope.launch {
            workManager.getWorkInfosByTagFlow(GitHubFileWorker.WORK_TAG).collect { infos ->
                infos.forEach { info ->
                    if (info.id in handledWorkIds) return@forEach
                    if (info.state != WorkInfo.State.SUCCEEDED) return@forEach

                    handledWorkIds += info.id

                    val callerId = info.outputData.getLong(GitHubFileWorker.KEY_CALLER_ID, 0L)
                        .takeIf { it != 0L } ?: return@forEach

                    val newSha = info.outputData.getString(GitHubFileWorker.KEY_SERVER_SHA) ?: ""
                    assetRepository.markPushed(callerId, newSha)
                }
            }
        }
    }
}
