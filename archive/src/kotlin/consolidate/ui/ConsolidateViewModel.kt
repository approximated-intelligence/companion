package de.perigon.companion.archive.consolidate.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import de.perigon.companion.archive.consolidate.data.ConsolidateFileEntity
import de.perigon.companion.archive.consolidate.data.ConsolidateRepository
import de.perigon.companion.archive.consolidate.data.ConsolidationPrefs
import de.perigon.companion.archive.consolidate.data.SafeToDeleteView
import de.perigon.companion.archive.consolidate.domain.consolidateTarget
import de.perigon.companion.archive.consolidate.worker.ConsolidateWorker
import de.perigon.companion.archive.data.BackupStateRepository
import de.perigon.companion.archive.data.FileHasher
import de.perigon.companion.archive.data.HashFailure
import de.perigon.companion.archive.data.HashKey
import de.perigon.companion.archive.data.userMessage
import de.perigon.companion.archive.ui.BackupDestinationSelection
import de.perigon.companion.archive.ui.BackupProgress
import de.perigon.companion.archive.ui.currentInfo
import de.perigon.companion.archive.ui.toBackupProgress
import de.perigon.companion.archive.worker.BackupRequests
import de.perigon.companion.archive.worker.BackupWorker
import de.perigon.companion.archive.worker.enqueueBackup
import de.perigon.companion.core.data.UserNotificationDao
import de.perigon.companion.core.di.ApplicationScope
import de.perigon.companion.core.prefs.FolderGrants
import de.perigon.companion.core.prefs.FolderKeys
import de.perigon.companion.core.saf.ScannedFile
import de.perigon.companion.core.saf.collectFileNames
import de.perigon.companion.core.saf.isReadable
import de.perigon.companion.core.saf.listSubfolder
import de.perigon.companion.core.saf.openInputStream
import de.perigon.companion.core.ui.SnackbarChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

private const val TAG = "ConsolidateViewModel"

// Sentinel size for files the SAF provider reported no size for. Matches
// BackupSourceScanner.UNKNOWN_SIZE so userMessage() classification works.
private const val UNKNOWN_SIZE = -1L

enum class SortField { NAME, MODIFIED, SIZE, TYPE }
enum class SortDirection { ASC, DESC }
enum class ViewMode { LIST, GRID }

@Immutable
data class DeleteProgress(
    val current: Int,
    val total: Int,
)

@Immutable
data class ConsolidateUiState(
    val consolidateRunning: Boolean = false,
    val consolidateTotal: Int = 0,
    val consolidateProcessed: Int = 0,
    val consolidateFailed: Int = 0,
    val consolidateCurrentFile: String = "",
    val backup: BackupProgress = BackupProgress(),
    val confirmedCount: Int = 0,
    val consolidatedCount: Int = 0,
    val safeToDelete: List<SafeToDeleteView> = emptyList(),
    val selectionMode: Boolean = false,
    val selectedIds: Set<Long> = emptySet(),
    val sortField: SortField = SortField.MODIFIED,
    val sortDirection: SortDirection = SortDirection.DESC,
    val viewMode: ViewMode = ViewMode.LIST,
    val confirmDeleteCount: Int? = null,
    val deleteProgress: DeleteProgress? = null,
    val confirmUnprotectId: Long? = null,
    val backfillRunning: Boolean = false,
) {
    val selectedCount: Int get() = selectedIds.size
    val isDeleting: Boolean get() = deleteProgress != null

    // Every comparator carries a path tiebreak: with degenerate sort keys a
    // stable sort would otherwise pass DB emission order through.
    val sortedSafeToDelete: List<SafeToDeleteView> get() {
        val comparator = when (sortField) {
            SortField.NAME -> compareBy<SafeToDeleteView>(
                { it.path.substringAfterLast('/').lowercase() }, { it.path })
            SortField.MODIFIED -> compareBy({ it.mtime }, { it.path })
            SortField.SIZE -> compareBy({ it.size }, { it.path })
            SortField.TYPE -> compareBy(
                { it.path.substringAfterLast('.').lowercase() }, { it.path })
        }
        return if (sortDirection == SortDirection.ASC) safeToDelete.sortedWith(comparator)
               else safeToDelete.sortedWith(comparator.reversed())
    }
}

/** DCIM access is owned by the setup gate; this screen never asks for it. */
@HiltViewModel
class ConsolidateViewModel @Inject constructor(
    @param:ApplicationContext private val ctx: Context,
    private val workManager: WorkManager,
    private val consolidateRepo: ConsolidateRepository,
    private val backupStateRepo: BackupStateRepository,
    private val notificationDao: UserNotificationDao,
    private val folderGrants: FolderGrants,
    private val hasher: FileHasher,
    @param:ApplicationScope private val appScope: CoroutineScope,
    private val consolidationPrefs: ConsolidationPrefs,
    private val destinationSelection: BackupDestinationSelection,
) : ViewModel() {

    private val _state = MutableStateFlow(ConsolidateUiState())
    val state: StateFlow<ConsolidateUiState> = _state.asStateFlow()

    val snackbar = SnackbarChannel()
    val notifications: UserNotificationDao get() = notificationDao

    private val _previewIntent = Channel<Intent>(Channel.BUFFERED)
    val previewIntents = _previewIntent.receiveAsFlow()

    init {
        observeConsolidateWorker()
        observeBackupWorker()
        observeCounts()
        observeSafeToDelete()
        cleanupStaleEntries()
    }

    private fun observeConsolidateWorker() {
        viewModelScope.launch {
            workManager.getWorkInfosForUniqueWorkFlow(ConsolidateWorker.WORK_NAME).collect { infos ->
                val info = infos.currentInfo() ?: return@collect
                val p = info.progress
                _state.update { s ->
                    s.copy(
                        consolidateRunning = info.state == WorkInfo.State.RUNNING
                                || info.state == WorkInfo.State.ENQUEUED,
                        consolidateTotal = p.getInt(ConsolidateWorker.KEY_TOTAL, s.consolidateTotal),
                        consolidateProcessed = p.getInt(ConsolidateWorker.KEY_PROCESSED, s.consolidateProcessed),
                        consolidateFailed = p.getInt(ConsolidateWorker.KEY_FAILED, s.consolidateFailed),
                        consolidateCurrentFile = p.getString(ConsolidateWorker.KEY_CURRENT_FILE)
                            ?: s.consolidateCurrentFile,
                    )
                }
                if (info.state == WorkInfo.State.SUCCEEDED || info.state == WorkInfo.State.FAILED) {
                    _state.update { it.copy(consolidateRunning = false, consolidateCurrentFile = "") }
                }
            }
        }
    }

    private fun observeBackupWorker() {
        viewModelScope.launch {
            workManager.getWorkInfosForUniqueWorkFlow(BackupWorker.WORK_NAME).collect { infos ->
                val info = infos.currentInfo() ?: return@collect
                _state.update { s -> s.copy(backup = info.toBackupProgress(s.backup)) }
            }
        }
    }

    private fun observeCounts() {
        viewModelScope.launch {
            backupStateRepo.observeConfirmedCount().collect { count ->
                _state.update { it.copy(confirmedCount = count) }
            }
        }
        viewModelScope.launch {
            consolidateRepo.observeDoneCount().collect { count ->
                _state.update { it.copy(consolidatedCount = count) }
            }
        }
    }

    private fun observeSafeToDelete() {
        viewModelScope.launch {
            consolidateRepo.observeSafeToDelete().collect { files ->
                _state.update { s ->
                    s.copy(
                        safeToDelete = files,
                        selectedIds = s.selectedIds.intersect(files.map { it.id }.toSet()),
                    )
                }
            }
        }
    }

    // ── Worker actions ──

    fun startConsolidate() {
        enqueueConsolidate()
    }

    private fun enqueueConsolidate(): Operation =
        workManager.enqueueUniqueWork(
            ConsolidateWorker.WORK_NAME,
            ExistingWorkPolicy.KEEP,
            ConsolidateWorker.buildRequest(),
        )

    fun startBackup() {
        val destinations = destinationSelection.current()
        if (destinations.isEmpty()) {
            snackbar.send("Select at least one destination")
            return
        }
        workManager.enqueueBackup(BackupRequests.manual(destinations))
    }

    /**
     * Cancel a running consolidation. Safe when nothing is running; a no-op.
     * The worker's CoroutineWorker cancellation signal turns into a clean
     * CANCELLED state, which observeConsolidateWorker maps to
     * consolidateRunning = false.
     */
    fun cancelConsolidate() {
        workManager.cancelUniqueWork(ConsolidateWorker.WORK_NAME)
    }

    /**
     * Cancel a running backup. Same reasoning as [cancelConsolidate]: the
     * unique-work name covers both the manual button path and the scheduled
     * trigger (which funnels into the same slot with KEEP).
     */
    fun cancelBackup() {
        workManager.cancelUniqueWork(BackupWorker.WORK_NAME)
    }

    /**
     * Consolidate, then back up once consolidation is finished. App scope:
     * leaving the screen must not cancel the chained backup. Awaiting the
     * enqueue first keeps a PREVIOUS finished run from firing the backup early.
     */
    fun startConsolidateAndBackup() {
        val destinations = destinationSelection.current()
        if (destinations.isEmpty()) {
            snackbar.send("Select at least one destination")
            return
        }
        val wm = workManager
        val enqueued = enqueueConsolidate()
        appScope.launch {
            enqueued.await()
            wm.getWorkInfosForUniqueWorkFlow(ConsolidateWorker.WORK_NAME)
                .first { infos -> infos.isNotEmpty() && infos.all { it.state.isFinished } }
            wm.enqueueBackup(BackupRequests.manual(destinations))
        }
    }

    // ── Preview ──

    fun showPreview(uri: String) {
        val contentUri = uri.toUri()
        val mime = ctx.contentResolver.getType(contentUri) ?: "image/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        _previewIntent.trySend(intent)
    }

    // ── Selection mode ──

    fun enterSelectionMode(id: Long) {
        _state.update { it.copy(selectionMode = true, selectedIds = setOf(id)) }
    }

    fun toggleSelected(id: Long) {
        _state.update { s ->
            val next = if (id in s.selectedIds) s.selectedIds - id else s.selectedIds + id
            if (next.isEmpty()) s.copy(selectionMode = false, selectedIds = emptySet())
            else s.copy(selectedIds = next)
        }
    }

    fun selectAll() {
        _state.update { s ->
            s.copy(selectedIds = s.sortedSafeToDelete.filter { !it.isProtected }.map { it.id }.toSet())
        }
    }

    fun exitSelectionMode() {
        _state.update { it.copy(selectionMode = false, selectedIds = emptySet()) }
    }

    // ── Protection ──

    fun protect(id: Long) {
        viewModelScope.launch { consolidateRepo.protect(id) }
    }

    fun requestUnprotect(id: Long) {
        _state.update { it.copy(confirmUnprotectId = id) }
    }

    fun confirmUnprotect() {
        val id = _state.value.confirmUnprotectId ?: return
        viewModelScope.launch { consolidateRepo.unprotect(id) }
        _state.update { it.copy(confirmUnprotectId = null) }
    }

    fun dismissUnprotect() {
        _state.update { it.copy(confirmUnprotectId = null) }
    }

    // ── Deletion ──

    fun requestDelete() {
        val ids = _state.value.selectedIds
        if (ids.isEmpty()) return
        val count = _state.value.safeToDelete.count { it.id in ids && !it.isProtected }
        if (count == 0) return
        _state.update { it.copy(confirmDeleteCount = count) }
    }

    fun dismissDelete() {
        _state.update { it.copy(confirmDeleteCount = null) }
    }

    fun confirmDelete() {
        val ids = _state.value.selectedIds.toSet()
        val items = _state.value.safeToDelete.filter { it.id in ids && !it.isProtected }
        _state.update { it.copy(confirmDeleteCount = null) }
        if (items.isEmpty()) return

        viewModelScope.launch {
            _state.update { it.copy(deleteProgress = DeleteProgress(0, items.size)) }
            val deletedIds = mutableSetOf<Long>()
            var failed = 0

            withContext(Dispatchers.IO) {
                for ((index, item) in items.withIndex()) {
                    if (deleteSafDocument(item.uri.toUri())) deletedIds += item.id else failed++
                    _state.update { it.copy(deleteProgress = DeleteProgress(index + 1, items.size)) }
                }
            }

            if (deletedIds.isNotEmpty()) consolidateRepo.deleteByIds(deletedIds)

            _state.update { it.copy(selectionMode = false, selectedIds = emptySet(), deleteProgress = null) }

            snackbar.send(when {
                failed == 0 -> "Deleted ${deletedIds.size} file(s)"
                deletedIds.isEmpty() -> "Failed to delete all files"
                else -> "Deleted ${deletedIds.size} file(s), $failed failed"
            })
        }
    }

    private fun deleteSafDocument(uri: Uri): Boolean = try {
        DocumentsContract.deleteDocument(ctx.contentResolver, uri)
    } catch (e: Exception) {
        Log.w(TAG, "delete failed: $uri", e)
        false
    }

    // ── View controls ──

    fun setSortField(field: SortField) {
        _state.update { s ->
            if (s.sortField == field) s.copy(sortDirection = s.sortDirection.toggle())
            else s.copy(sortField = field, sortDirection = SortDirection.ASC)
        }
    }

    fun setViewMode(mode: ViewMode) {
        _state.update { it.copy(viewMode = mode) }
    }

    // ── Backfill via SAF ──

    fun backfillExisting() {
        if (_state.value.backfillRunning) return
        _state.update { it.copy(backfillRunning = true) }

        viewModelScope.launch {
            try {
                val (count, failures) = withContext(Dispatchers.IO) { scanAndBackfill() }
                snackbar.send(
                    if (count > 0) "Found $count previously consolidated file(s)"
                    else "No additional consolidated files found"
                )
                if (failures.isNotEmpty()) {
                    // Backfill is a screen action; surface skips inline rather
                    // than in the ledger so the user sees them immediately.
                    val first = failures.first().userMessage()
                    val suffix = if (failures.size > 1) " (+${failures.size - 1} more)" else ""
                    snackbar.send("${failures.size} file(s) skipped — $first$suffix")
                }
            } catch (e: Exception) {
                snackbar.send("Backfill failed: ${e.message}")
            } finally {
                _state.update { it.copy(backfillRunning = false) }
            }
        }
    }

    private data class BackfillMatch(
        val camera: ScannedFile,
        val consolidatedName: String,
        val key: HashKey,
    )

    private suspend fun scanAndBackfill(): Pair<Int, List<HashFailure>> {
        val treeUri = folderGrants.uri(FolderKeys.DCIM)?.toUri() ?: return 0 to emptyList()
        val settings = consolidationPrefs.get()

        val consolidatedNames = collectFileNames(ctx, treeUri, "Consolidated")
        if (consolidatedNames.isEmpty()) return 0 to emptyList()

        val cameraFiles = listSubfolder(ctx, treeUri, "Camera") ?: return 0 to emptyList()

        // Forward mapping only — the same consolidateTarget the worker uses.
        // Because the name now carries the box, changing the box between runs
        // means old output is not found here and gets re-consolidated at the
        // new box; the previous run's output remains in Consolidated/.
        val matched = cameraFiles.mapNotNull { camera ->
            val target = consolidateTarget(camera.path.substringAfterLast('/'), settings)
                ?: return@mapNotNull null
            if (target.name in consolidatedNames) camera to target.name else null
        }
        if (matched.isEmpty()) return 0 to emptyList()

        // Pre-hash skips (unknown size) go into the failure list.
        val preFailures = mutableListOf<HashFailure>()
        val byKey = LinkedHashMap<HashKey, ScannedFile>()
        val entries = matched.mapNotNull { (camera, consolidatedName) ->
            val size = camera.size
            if (size == null) {
                preFailures += HashFailure(
                    key = HashKey("DCIM/${camera.path}", camera.mtime, UNKNOWN_SIZE),
                    cause = IllegalStateException("size not reported by SAF provider"),
                )
                return@mapNotNull null
            }
            val key = HashKey("DCIM/${camera.path}", camera.mtime, size)
            byKey.putIfAbsent(key, camera)
            BackfillMatch(camera, consolidatedName, key)
        }
        if (entries.isEmpty()) return 0 to preFailures

        val batch = hasher.hashAllOrCached(byKey.keys.toList()) { key ->
            openInputStream(ctx, byKey.getValue(key).uri.toUri())
                ?: error("openInputStream returned null")
        }

        val allFailures = preFailures + batch.failures
        if (allFailures.isNotEmpty()) {
            Log.w(TAG, "dropped ${allFailures.size} file(s) from backfill " +
                "(unreadable or unknown size)")
        }

        val hashed = entries.mapNotNull { m -> batch.hashes[m.key]?.let { sha -> m to sha } }
        if (hashed.isEmpty()) return 0 to allFailures

        val now = System.currentTimeMillis()
        consolidateRepo.insertScannedFiles(hashed.map { (m, sha) ->
            ConsolidateFileEntity(
                path = m.key.path,
                uri = m.camera.uri,
                mtime = m.key.mtime,
                size = m.key.size,
                sha256 = sha,
                createdAt = now,
            )
        })

        var count = 0
        for ((m, sha) in hashed) {
            val entity = consolidateRepo.findByPathSha256(m.key.path, sha) ?: continue
            consolidateRepo.markDone(entity.id, m.consolidatedName)
            count++
        }
        return count to allFailures
    }

    private fun cleanupStaleEntries() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val staleIds = consolidateRepo.getAllDone()
                    .filter { !isReadable(ctx, it.uri) }
                    .map { it.id }
                    .toSet()
                if (staleIds.isNotEmpty()) consolidateRepo.deleteByIds(staleIds)
            }
        }
    }
}

private fun SortDirection.toggle() = when (this) {
    SortDirection.ASC -> SortDirection.DESC
    SortDirection.DESC -> SortDirection.ASC
}
