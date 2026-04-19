package de.perigon.companion.backup.ui.restore

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.*
import de.perigon.companion.backup.data.BackupStateRepository
import de.perigon.companion.backup.data.RestoreSelectionEntity
import de.perigon.companion.backup.worker.RestoreNotifier
import de.perigon.companion.backup.worker.RestoreWorker
import de.perigon.companion.core.prefs.AppPrefs
import de.perigon.companion.core.prefs.CredentialStore
import de.perigon.companion.core.ui.SnackbarChannel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "Restore"

enum class RestorePhase {
    READY,
    LISTING,
    WORKING,
    DONE,
    ERROR,
}

@Immutable
data class RestoreUiState(
    val phase: RestorePhase = RestorePhase.READY,
    val tree: TreeNode.Folder = TreeNode.Folder("", emptyList(), selectionState = SelectionState.NONE),
    val hasFiles: Boolean = false,
    val restoreState: String = RestoreNotifier.STATE_DONE,
    val packPosition: Int = 0,
    val packsTotal: Int = 0,
    val fileIndex: Int = 0,
    val filesTotal: Int = 0,
    val currentFile: String = "",
    val errors: List<String> = emptyList(),
    val isRunning: Boolean = false,
    val dcimUri: String? = null,
)

@HiltViewModel
class RestoreViewModel @Inject constructor(
    @param:ApplicationContext private val ctx: Context,
    private val stateRepo: BackupStateRepository,
    private val workManager: WorkManager,
    private val appPrefs: AppPrefs,
    private val creds: CredentialStore,
) : ViewModel() {

    private val _state = MutableStateFlow(RestoreUiState(dcimUri = appPrefs.dcimTreeUri()))
    val state: StateFlow<RestoreUiState> = _state.asStateFlow()

    val snackbar = SnackbarChannel()

    init {
        viewModelScope.launch {
            appPrefs.observeDcimTreeUri().collect { uri ->
                _state.update { it.copy(dcimUri = uri) }
            }
        }

        viewModelScope.launch {
            workManager.getWorkInfosForUniqueWorkFlow(RestoreWorker.WORK_NAME).collect { infos ->
                val info = infos.firstOrNull {
                    it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
                } ?: infos.firstOrNull() ?: return@collect

                val p   = info.progress
                val out = info.outputData
                _state.update { s -> s.copy(
                    isRunning    = info.state == WorkInfo.State.RUNNING,
                    phase        = when (info.state) {
                        WorkInfo.State.RUNNING,
                        WorkInfo.State.ENQUEUED  -> RestorePhase.WORKING
                        WorkInfo.State.SUCCEEDED -> RestorePhase.DONE
                        WorkInfo.State.FAILED    -> RestorePhase.ERROR
                        else                     -> s.phase
                    },
                    restoreState = p.getString(RestoreNotifier.KEY_RESTORE_STATE)
                        ?: if (info.state == WorkInfo.State.SUCCEEDED) RestoreNotifier.STATE_DONE
                        else s.restoreState,
                    packPosition = p.getInt(RestoreNotifier.KEY_PACK_POSITION, s.packPosition),
                    packsTotal   = p.getInt(RestoreNotifier.KEY_PACKS_TOTAL,   s.packsTotal),
                    fileIndex    = p.getInt(RestoreNotifier.KEY_FILE_INDEX,     s.fileIndex),
                    filesTotal   = p.getInt(RestoreNotifier.KEY_FILES_TOTAL,    s.filesTotal),
                    currentFile  = p.getString(RestoreNotifier.KEY_CURRENT_FILE) ?: s.currentFile,
                    errors       = when (info.state) {
                        WorkInfo.State.FAILED    ->
                            listOf(out.getString(RestoreNotifier.KEY_ERROR_DETAIL) ?: "Unknown error")
                        WorkInfo.State.SUCCEEDED ->
                            out.getString(RestoreNotifier.KEY_ERROR_DETAIL)
                                ?.split("\n")?.filter { it.isNotEmpty() } ?: emptyList()
                        else -> s.errors
                    },
                )}
            }
        }
    }

    fun onDcimGranted(uri: Uri) {
        val label = uri.lastPathSegment?.substringAfterLast(':') ?: "DCIM"
        viewModelScope.launch {
            appPrefs.setDcimTree(uri.toString(), label)
        }
    }

    // ---------------------------------------------------------------------------
    // File list
    // ---------------------------------------------------------------------------

    fun loadFileList() {
        viewModelScope.launch {
            _state.update { it.copy(phase = RestorePhase.LISTING) }
            try {
                val rows = stateRepo.getRestoreFiles()
                val tree = buildRestoreTree(rows)
                _state.update { it.copy(phase = RestorePhase.READY, tree = tree, hasFiles = rows.isNotEmpty()) }
            } catch (e: Exception) {
                Log.e(TAG, "loadFileList failed", e)
                _state.update { it.copy(phase = RestorePhase.ERROR, errors = listOf(e.message ?: "Unknown error")) }
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Tree interaction
    // ---------------------------------------------------------------------------

    fun toggleVersion(path: String, sha256: String) {
        _state.update { it.copy(tree = it.tree.toggleVersion(path, sha256)) }
    }

    fun toggleFolder(folderPath: String) {
        val node = findFolder(_state.value.tree, folderPath)
        val newSelected = node?.selectionState != SelectionState.ALL
        _state.update { it.copy(tree = it.tree.setFolderSelected(folderPath, newSelected)) }
    }

    fun toggleFolderExpanded(folderPath: String) {
        _state.update { it.copy(tree = it.tree.toggleFolderExpanded(folderPath)) }
    }

    fun toggleVersionsExpanded(filePath: String) {
        _state.update { it.copy(tree = it.tree.toggleVersionsExpanded(filePath)) }
    }

    fun selectAll()   { _state.update { it.copy(tree = it.tree.setAllSelected(true)) } }
    fun deselectAll() { _state.update { it.copy(tree = it.tree.setAllSelected(false)) } }

    // ---------------------------------------------------------------------------
    // Worker launch
    // ---------------------------------------------------------------------------

    fun rebuildIndex() {
        checkCredentials() ?: return
        enqueueWorker(RestoreWorker.MODE_REBUILD)
    }

    fun rebuildAndRestoreAll() {
        checkCredentials() ?: return
        enqueueWorker(RestoreWorker.MODE_REBUILD_AND_RESTORE)
    }

    fun restoreSelected() {
        checkCredentials() ?: return
        val selected = _state.value.tree.selectedVersions()
        if (selected.isEmpty()) { snackbar.send("No files selected"); return }

        viewModelScope.launch {
            stateRepo.setRestoreSelections(selected.map { it.toSelectionEntity() })
            enqueueWorker(RestoreWorker.MODE_RESTORE)
        }
    }

    fun cancelRestore() {
        workManager.cancelUniqueWork(RestoreWorker.WORK_NAME)
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun enqueueWorker(mode: String) {
        val req = OneTimeWorkRequestBuilder<RestoreWorker>()
            .setInputData(workDataOf(RestoreWorker.KEY_MODE to mode))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(RestoreWorker.WORK_NAME)
            .build()
        workManager.enqueueUniqueWork(RestoreWorker.WORK_NAME, ExistingWorkPolicy.KEEP, req)
        _state.update { it.copy(phase = RestorePhase.WORKING, errors = emptyList()) }
    }

    private fun checkCredentials(): Unit? {
        if (appPrefs.b2Config() == null || creds.b2RwAppKey() == null ||
            appPrefs.naclPkHex() == null || creds.phoneSecretKey() == null ||
            appPrefs.phonePkHex() == null) {
            snackbar.send("Credentials not configured")
            return null
        }
        return Unit
    }

    private fun findFolder(root: TreeNode.Folder, path: String): TreeNode.Folder? {
        if (path.isEmpty() || path == root.name) return root
        var current: TreeNode.Folder = root
        for (part in path.split("/")) {
            current = current.children.filterIsInstance<TreeNode.Folder>()
                .firstOrNull { it.name == part } ?: return null
        }
        return current
    }
}

private fun RestoreFileVersion.toSelectionEntity() = RestoreSelectionEntity(
    path = path, sha256 = sha256, startPack = startPack, startPart = startPart,
    startPartOffset = startPartOffset, endPack = endPack, endPart = endPart,
    numParts = numParts, size = size, mtime = mtime,
)
