package de.perigon.companion.media.ui.consolidate

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.*
import de.perigon.companion.backup.data.BackupStateRepository
import de.perigon.companion.backup.worker.BackupWorker
import de.perigon.companion.core.data.UserNotificationDao
import de.perigon.companion.core.di.ApplicationScope
import de.perigon.companion.core.prefs.AppPrefs
import de.perigon.companion.core.ui.SnackbarChannel
import de.perigon.companion.media.data.ConsolidateFileEntity
import de.perigon.companion.media.data.ConsolidateRepository
import de.perigon.companion.media.data.SafeToDeleteView
import de.perigon.companion.media.worker.ConsolidateWorker
import de.perigon.companion.util.FileHasher
import de.perigon.companion.util.HashKey
import de.perigon.companion.util.saf.ScannedFile
import de.perigon.companion.util.saf.collectFileNames
import de.perigon.companion.util.saf.listSubfolder
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

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
    val backupRunning: Boolean = false,
    val backupPackState: String = BackupWorker.STATE_DONE,
    val backupPackPosition: Int = 0,
    val backupPartNumber: Int = 0,
    val backupPartsTotal: Int = 0,
    val backupPackPercent: Int = 0,
    val backupFileIndex: Int = 0,
    val backupFilesTotal: Int = 0,
    val backupCurrentFile: String = "",
    val backupErrorType: String = BackupWorker.ERR_NONE,
    val backupErrorDetail: String = "",
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
    val dcimUri: String? = null,
) {
    val backupHasError: Boolean get() = backupErrorType != BackupWorker.ERR_NONE
    val selectedCount: Int get() = selectedIds.size
    val isDeleting: Boolean get() = deleteProgress != null

    // Every comparator carries a path tiebreak: with degenerate sort keys
    // (equal or missing mtimes, equal sizes) a stable sort would otherwise
    // pass DB emission order — i.e. scan/walk order — through to the screen.
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

@HiltViewModel
class ConsolidateViewModel @Inject constructor(
    @param:ApplicationContext private val ctx: Context,
    private val workManager: WorkManager,
    private val consolidateRepo: ConsolidateRepository,
    private val backupStateRepo: BackupStateRepository,
    private val notificationDao: UserNotificationDao,
    private val appPrefs: AppPrefs,
    private val hasher: FileHasher,
    @param:ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    private val _state = MutableStateFlow(ConsolidateUiState(dcimUri = appPrefs.dcimTreeUri()))
    val state: StateFlow<ConsolidateUiState> = _state.asStateFlow()

    val snackbar = SnackbarChannel()
    val notifications: UserNotificationDao get() = notificationDao

    private val _previewIntent = Channel<Intent>(Channel.BUFFERED)
    val previewIntents = _previewIntent.receiveAsFlow()

    init {
        viewModelScope.launch {
            appPrefs.observeDcimTreeUri().collect { uri ->
                _state.update { it.copy(dcimUri = uri) }
            }
        }
        observeConsolidateWorker()
        observeBackupWorker()
        observeCounts()
        observeSafeToDelete()
        cleanupStaleEntries()
    }

    fun onDcimGranted(uri: Uri) {
        val label = uri.lastPathSegment?.substringAfterLast(':') ?: "DCIM"
        viewModelScope.launch {
            appPrefs.setDcimTree(uri.toString(), label)
        }
    }

    private fun observeConsolidateWorker() {
        viewModelScope.launch {
            workManager.getWorkInfosForUniqueWorkFlow(ConsolidateWorker.WORK_NAME).collect { infos ->
                val info = infos.firstOrNull {
                    it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
                } ?: infos.firstOrNull() ?: return@collect

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
                val info = infos.firstOrNull {
                    it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
                } ?: infos.firstOrNull() ?: return@collect

                val p = info.progress
                val out = info.outputData
                _state.update { s ->
                    s.copy(
                        backupRunning = info.state == WorkInfo.State.RUNNING,
                        backupPackState = p.getString(BackupWorker.KEY_PACK_STATE)
                            ?: if (info.state == WorkInfo.State.SUCCEEDED)
                                BackupWorker.STATE_DONE else s.backupPackState,
                        backupPackPosition = p.getInt(BackupWorker.KEY_PACK_POSITION, s.backupPackPosition),
                        backupPartNumber = p.getInt(BackupWorker.KEY_PART_NUMBER, s.backupPartNumber),
                        backupPartsTotal = p.getInt(BackupWorker.KEY_PARTS_TOTAL, s.backupPartsTotal),
                        backupPackPercent = p.getInt(BackupWorker.KEY_PACK_PERCENT, s.backupPackPercent),
                        backupFileIndex = p.getInt(BackupWorker.KEY_FILE_INDEX, s.backupFileIndex),
                        backupFilesTotal = p.getInt(BackupWorker.KEY_FILES_TOTAL, s.backupFilesTotal),
                        backupCurrentFile = p.getString(BackupWorker.KEY_CURRENT_FILE)
                            ?: s.backupCurrentFile,
                        backupErrorType = when (info.state) {
                            WorkInfo.State.FAILED -> out.getString(BackupWorker.KEY_ERROR_TYPE)
                                ?: BackupWorker.ERR_B2_ERROR
                            WorkInfo.State.SUCCEEDED -> BackupWorker.ERR_NONE
                            else -> p.getString(BackupWorker.KEY_ERROR_TYPE) ?: s.backupErrorType
                        },
                        backupErrorDetail = when (info.state) {
                            WorkInfo.State.FAILED -> out.getString(BackupWorker.KEY_ERROR_DETAIL) ?: ""
                            WorkInfo.State.SUCCEEDED -> ""
                            else -> p.getString(BackupWorker.KEY_ERROR_DETAIL) ?: s.backupErrorDetail
                        },
                    )
                }
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
        workManager.enqueueUniqueWork(
            ConsolidateWorker.WORK_NAME,
            ExistingWorkPolicy.KEEP,
            ConsolidateWorker.buildRequest(),
        )
    }

    fun startBackup() {
        val req = OneTimeWorkRequestBuilder<de.perigon.companion.backup.worker.BackupWorker>()
            .addTag(BackupWorker.WORK_NAME)
            .build()
        workManager.enqueueUniqueWork(
            BackupWorker.WORK_NAME,
            ExistingWorkPolicy.KEEP,
            req,
        )
    }

    fun startConsolidateAndBackup() {
        startConsolidate()
        // App scope, not viewModelScope: leaving the screen must not cancel
        // the chained backup enqueue. isNotEmpty guards the initial-emission
        // race where all{} over an empty list would fire startBackup before
        // the consolidate work is even registered.
        appScope.launch {
            workManager.getWorkInfosForUniqueWorkFlow(ConsolidateWorker.WORK_NAME)
                .first { infos ->
                    infos.isNotEmpty() && infos.all {
                        it.state != WorkInfo.State.RUNNING && it.state != WorkInfo.State.ENQUEUED
                    }
                }
            startBackup()
        }
    }

    // ── Preview ──

    fun showPreview(uri: String) {
        val contentUri = Uri.parse(uri)
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
                    val success = deleteSafDocument(Uri.parse(item.uri))
                    if (success) {
                        deletedIds += item.id
                    } else {
                        failed++
                    }
                    _state.update { it.copy(deleteProgress = DeleteProgress(index + 1, items.size)) }
                }
            }

            if (deletedIds.isNotEmpty()) {
                consolidateRepo.deleteByIds(deletedIds)
            }

            _state.update { it.copy(selectionMode = false, selectedIds = emptySet(), deleteProgress = null) }

            val message = when {
                failed == 0 -> "Deleted ${deletedIds.size} file(s)"
                deletedIds.isEmpty() -> "Failed to delete all files"
                else -> "Deleted ${deletedIds.size} file(s), $failed failed"
            }
            snackbar.send(message)
        }
    }

    private fun deleteSafDocument(uri: Uri): Boolean {
        return try {
            DocumentsContract.deleteDocument(ctx.contentResolver, uri)
        } catch (_: Exception) {
            false
        }
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
                val count = withContext(Dispatchers.IO) { scanAndBackfill() }
                if (count > 0) snackbar.send("Found $count previously consolidated file(s)")
                else snackbar.send("No additional consolidated files found")
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

    private suspend fun scanAndBackfill(): Int {
        val dcimUri = appPrefs.dcimTreeUri() ?: return 0
        val treeUri = Uri.parse(dcimUri)

        val consolidatedNames = collectFileNames(ctx, treeUri, "Consolidated")
        if (consolidatedNames.isEmpty()) return 0

        val cameraFiles = listSubfolder(ctx, treeUri, "Camera") ?: return 0

        // Match camera file -> consolidated output name by stem/extension.
        val matched = mutableListOf<Pair<ScannedFile, String>>()
        for (consolidatedName in consolidatedNames) {
            val stem = consolidatedName
                .removeSuffix("_s.jpg")
                .removeSuffix("_s.mp4")
                .removeSuffix("_s.jpeg")
            val ext = when {
                consolidatedName.endsWith(".mp4") -> ".mp4"
                consolidatedName.endsWith(".jpeg") -> ".jpeg"
                else -> ".jpg"
            }
            val originalName = "$stem$ext"
            val camera = cameraFiles.firstOrNull {
                it.path.substringAfterLast('/') == originalName
            } ?: continue
            matched += camera to consolidatedName
        }

        if (matched.isEmpty()) return 0

        // Batch-hash via the shared cache: one lookup pass, only misses are
        // read. Files with unknown size or that fail to hash are skipped —
        // they'll be retried on the next backfill or scan.
        val byKey = LinkedHashMap<HashKey, ScannedFile>()
        val entries = matched.mapNotNull { (camera, consolidatedName) ->
            val size = camera.size ?: return@mapNotNull null
            val key = HashKey("DCIM/${camera.path}", camera.mtime, size)
            byKey.putIfAbsent(key, camera)
            BackfillMatch(camera, consolidatedName, key)
        }
        if (entries.isEmpty()) return 0

        val hashes = hasher.hashAllOrCached(byKey.keys.toList()) { key ->
            ctx.contentResolver.openInputStream(Uri.parse(byKey.getValue(key).uri))
                ?: error("openInputStream returned null")
        }

        val hashed = entries.mapNotNull { m -> hashes[m.key]?.let { sha -> m to sha } }
        if (hashed.isEmpty()) return 0

        val now = System.currentTimeMillis()
        val entities = hashed.map { (m, sha) ->
            ConsolidateFileEntity(
                path = m.key.path,
                uri = m.camera.uri,
                mtime = m.key.mtime,
                size = m.key.size,
                sha256 = sha,
                createdAt = now,
            )
        }
        consolidateRepo.insertScannedFiles(entities)

        var count = 0
        for ((m, sha) in hashed) {
            val entity = consolidateRepo.findByPathSha256(m.key.path, sha) ?: continue
            consolidateRepo.markDone(entity.id, m.consolidatedName)
            count++
        }

        return count
    }

    private fun cleanupStaleEntries() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val done = consolidateRepo.getAllDone()
                val staleIds = done.filter { entry ->
                    !isUriResolvable(Uri.parse(entry.uri))
                }.map { it.id }.toSet()
                if (staleIds.isNotEmpty()) {
                    consolidateRepo.deleteByIds(staleIds)
                }
            }
        }
    }

    private fun isUriResolvable(uri: Uri): Boolean {
        return try {
            ctx.contentResolver.openInputStream(uri)?.use { true } ?: false
        } catch (_: Exception) {
            false
        }
    }
}

private fun SortDirection.toggle() = when (this) {
    SortDirection.ASC -> SortDirection.DESC
    SortDirection.DESC -> SortDirection.ASC
}
