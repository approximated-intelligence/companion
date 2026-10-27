package de.perigon.companion.archive.restore.ui

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.*
import de.perigon.companion.archive.data.ArchiveStateRepository
import de.perigon.companion.archive.data.RestoreSelectionEntity
import de.perigon.companion.archive.restore.worker.RestoreNotifier
import de.perigon.companion.archive.restore.worker.RestoreWorker
import de.perigon.companion.archive.data.ArchivePrefs
import de.perigon.companion.archive.data.ArchiveSecrets
import androidx.core.net.toUri
import de.perigon.companion.archive.data.FileHasher
import de.perigon.companion.archive.data.HashKey
import de.perigon.companion.core.prefs.FolderGrants
import de.perigon.companion.core.prefs.FolderKeys
import de.perigon.companion.core.saf.ScannedFile
import de.perigon.companion.core.saf.listGrantedTrees
import de.perigon.companion.core.saf.openInputStream
import de.perigon.companion.core.saf.walkDocumentTree
import de.perigon.companion.core.ui.SnackbarChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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

/**
 * Local-filesystem scan state. IDLE after tree (re)build; SCANNING during
 * the walk; DONE when the tree reflects the most recent scan.
 *
 * Kept OFF `loadFileList`'s path: the tree is built from the backup index
 * alone, and the walk runs only when the filter is enabled. Re-enabling
 * the filter always re-scans — annotations go stale in exactly the case
 * the user notices first (a completed restore).
 */
enum class LocalScanState { IDLE, SCANNING, DONE }

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
    /** When true, hide files whose newest version already matches the local file. */
    val onlyRestorable: Boolean = false,
    /** Progress of the local-filesystem scan used to annotate versions. */
    val localScanState: LocalScanState = LocalScanState.IDLE,
)

@HiltViewModel
class RestoreViewModel @Inject constructor(
    @param:ApplicationContext private val ctx: Context,
    private val stateRepo: ArchiveStateRepository,
    private val workManager: WorkManager,
    private val appPrefs: ArchivePrefs,
    private val creds: ArchiveSecrets,
    private val folderGrants: FolderGrants,
    private val hasher: FileHasher,
) : ViewModel() {

    private val _state = MutableStateFlow(RestoreUiState())
    val state: StateFlow<RestoreUiState> = _state.asStateFlow()

    /** In-flight scan; cancelled by the next toggle-on so scans don't race. */
    private var localScanJob: Job? = null

    val snackbar = SnackbarChannel()

    init {
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


    // ---------------------------------------------------------------------------
    // File list
    // ---------------------------------------------------------------------------

    fun loadFileList() {
        viewModelScope.launch {
            _state.update { it.copy(phase = RestorePhase.LISTING) }
            try {
                val rows = stateRepo.getRestoreFiles()
                val tree = buildRestoreTree(rows)
                // A fresh tree has no annotations; the filter must start off,
                // otherwise a chip left on from a prior session would hide
                // files based on stale matches against the previous tree.
                _state.update {
                    it.copy(
                        phase = RestorePhase.READY,
                        tree = tree,
                        hasFiles = rows.isNotEmpty(),
                        onlyRestorable = false,
                        localScanState = LocalScanState.IDLE,
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadFileList failed", e)
                _state.update { it.copy(phase = RestorePhase.ERROR, errors = listOf(e.message ?: "Unknown error")) }
            }
        }
    }

    /**
     * Walk every granted tree and return a pure resolver from version to
     * [LocalMatch]. The walk runs on IO; the resolver runs later, against
     * whatever tree the caller has at that moment, so a selection made
     * during the scan is not clobbered.
     *
     * A root that was not granted, or whose walk threw, resolves to
     * UNVERIFIED: "can't see" is not "not there".
     */
    private suspend fun scanLocalFiles(tree: TreeNode.Folder): (RestoreFileVersion) -> LocalMatch =
        withContext(Dispatchers.IO) {
            val dcimUri = folderGrants.uri(FolderKeys.DCIM)
            val granted = listGrantedTrees(ctx)
            val safGrants = mutableMapOf<String, Uri>()
            if (dcimUri != null) safGrants["DCIM"] = dcimUri.toUri()
            for (treeUri in granted) {
                val path = treeUri.path ?: continue
                val root = path.substringAfterLast(':').substringBefore('/')
                if (root.isNotEmpty()) safGrants.putIfAbsent(root, treeUri)
            }
            if (safGrants.isEmpty()) {
                return@withContext { _: RestoreFileVersion -> LocalMatch.UNVERIFIED }
            }

            val scannedByPath = mutableMapOf<String, ScannedFile>()
            val failedRoots  = mutableSetOf<String>()
            for ((rootName, treeUri) in safGrants) {
                try {
                    walkDocumentTree(ctx, treeUri, prefix = rootName).forEach { sf ->
                        scannedByPath[sf.path] = sf
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "walk failed for $treeUri", e)
                    failedRoots += rootName
                }
            }

            // Keys for every version whose path exists in the walk. A missing
            // file resolves to MISSING without needing a sha256; only
            // present files need one.
            val byKey = mutableMapOf<HashKey, ScannedFile>()
            fun collect(n: TreeNode) {
                when (n) {
                    is TreeNode.Folder -> n.children.forEach(::collect)
                    is TreeNode.File -> n.versions.forEach { v ->
                        val sf = scannedByPath[v.path] ?: return@forEach
                        val size = sf.size ?: return@forEach
                        byKey.putIfAbsent(HashKey(v.path, sf.mtime, size), sf)
                    }
                }
            }
            tree.children.forEach(::collect)

            // hashAllOrCached, not lookupAll: a cache miss must rehash, not
            // report UNVERIFIED. Otherwise a restored file whose setMtime
            // failed (mtime is "now", cache misses) is offered for re-restore
            // even though its bytes match the archive.
            val hashesByKey: Map<HashKey, String> =
                if (byKey.isEmpty()) emptyMap()
                else try {
                    hasher.hashAllOrCached(
                        keys = byKey.keys.toList(),
                        open = { key ->
                            openInputStream(ctx, byKey.getValue(key).uri.toUri())
                                ?: error("openInputStream returned null for ${key.path}")
                        },
                    ).hashes
                } catch (e: Exception) {
                    Log.w(TAG, "local hash pass failed; annotating as UNVERIFIED", e)
                    emptyMap()
                }

            { v: RestoreFileVersion ->
                val root = v.path.substringBefore('/')
                when {
                    root !in safGrants -> LocalMatch.UNVERIFIED
                    root in failedRoots -> LocalMatch.UNVERIFIED
                    else -> {
                        val sf = scannedByPath[v.path]
                        when {
                            sf == null -> LocalMatch.MISSING
                            else -> {
                                val sha = sf.size?.let { size -> hashesByKey[HashKey(v.path, sf.mtime, size)] }
                                when {
                                    sha == null -> LocalMatch.UNVERIFIED
                                    sha == v.sha256 -> LocalMatch.MATCHES
                                    else -> LocalMatch.DIFFERS
                                }
                            }
                        }
                    }
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
        val s = _state.value
        if (s.filterActive) {
            val visible = s.tree.filterRestorable()
            val node = findFolder(visible, folderPath) ?: return
            val keys = node.collectVersionKeys()
            val newSelected = node.selectionState != SelectionState.ALL
            _state.update { it.copy(tree = it.tree.setSelectedWhere({ (it.path to it.sha256) in keys }, newSelected)) }
        } else {
            val node = findFolder(s.tree, folderPath)
            val newSelected = node?.selectionState != SelectionState.ALL
            _state.update { it.copy(tree = it.tree.setFolderSelected(folderPath, newSelected)) }
        }
    }

    fun toggleFolderExpanded(folderPath: String) {
        _state.update { it.copy(tree = it.tree.toggleFolderExpanded(folderPath)) }
    }

    fun toggleVersionsExpanded(filePath: String) {
        _state.update { it.copy(tree = it.tree.toggleVersionsExpanded(filePath)) }
    }

    fun selectAll() {
        _state.update { s ->
            val newTree = if (s.filterActive) {
                val keys = s.tree.filterRestorable().collectVersionKeys()
                s.tree.setSelectedWhere({ (it.path to it.sha256) in keys }, true)
            } else {
                s.tree.setAllSelected(true)
            }
            s.copy(tree = newTree)
        }
    }

    fun deselectAll() {
        _state.update { s ->
            val newTree = if (s.filterActive) {
                val keys = s.tree.filterRestorable().collectVersionKeys()
                s.tree.setSelectedWhere({ (it.path to it.sha256) in keys }, false)
            } else {
                s.tree.setAllSelected(false)
            }
            s.copy(tree = newTree)
        }
    }

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
        val selected = _state.value.visibleTree().selectedVersions()
        if (selected.isEmpty()) { snackbar.send("No files selected"); return }

        viewModelScope.launch {
            stateRepo.setRestoreSelections(selected.map { it.toSelectionEntity() })
            enqueueWorker(RestoreWorker.MODE_RESTORE)
        }
    }

    fun cancelRestore() {
        workManager.cancelUniqueWork(RestoreWorker.WORK_NAME)
    }

    /**
     * Turning the filter on always re-scans: files change between scans (a
     * restore completed, another app edited a file, new photos arrived) and
     * stale annotations are exactly what the user notices first. Off is
     * instant and leaves annotations in place.
     *
     * Off does NOT cancel an in-flight scan. The scan's hash work is what
     * warms the cache for the next toggle-on, and hashAllOrCached upserts
     * only at the very end of its batch — cancelling mid-scan would
     * discard every digest computed so far, making a rapid off-then-on
     * re-hash from scratch. Letting the scan finish turns the next
     * toggle-on into a cache-hit pass. The filter is off while it runs,
     * so the annotations it produces are inert until the user toggles on.
     */
    fun setOnlyRestorable(enabled: Boolean) {
        if (!enabled) {
            _state.update { it.copy(onlyRestorable = false) }
            return
        }
        localScanJob?.cancel()
        _state.update { it.copy(onlyRestorable = true, localScanState = LocalScanState.SCANNING) }
        localScanJob = viewModelScope.launch {
            try {
                val resolver = scanLocalFiles(_state.value.tree)
                // Resolve against the tree as it exists NOW, so selection
                // changes made during the scan apply to the annotated tree.
                // Matches are deselected at the end by design — a MATCHES
                // file shouldn't be restored, even if it was checked mid-scan.
                val newTree = withContext(Dispatchers.IO) {
                    _state.value.tree
                        .annotateLocalMatches(resolver)
                        .setSelectedWhere({ it.localMatch == LocalMatch.MATCHES }, false)
                }
                _state.update { it.copy(tree = newTree, localScanState = LocalScanState.DONE) }
            } catch (e: CancellationException) {
                // Superseded by a newer toggle-on; the newer scan owns the state.
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "local scan failed", e)
                _state.update { it.copy(onlyRestorable = false, localScanState = LocalScanState.IDLE) }
                snackbar.send("Local scan failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /**
     * The filter only takes effect once the scan is DONE: before then all
     * versions are UNVERIFIED, so filterRestorable() would return the whole
     * tree — the chip would look "on" but filtering would silently do
     * nothing. Every filter-aware operation gates on this.
     */
    private val RestoreUiState.filterActive: Boolean
        get() = onlyRestorable && localScanState == LocalScanState.DONE

    private fun RestoreUiState.visibleTree(): TreeNode.Folder =
        if (filterActive) tree.filterRestorable() else tree

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
