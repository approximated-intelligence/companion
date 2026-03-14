package de.perigon.companion.backup.ui

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.*
import de.perigon.companion.backup.data.BackupSourceScanner
import de.perigon.companion.backup.data.BackupFileDao
import de.perigon.companion.backup.data.BackupFileStatus
import de.perigon.companion.backup.data.BackupFolderDao
import de.perigon.companion.backup.data.BackupFolderEntity
import de.perigon.companion.backup.data.BackupIssueView
import de.perigon.companion.backup.data.BackupIssueViewDao
import de.perigon.companion.backup.data.BackupSchedulePrefs
import de.perigon.companion.core.data.UserNotificationDao
import de.perigon.companion.posts.data.PostMediaFileStore
import de.perigon.companion.core.ui.SnackbarChannel
import de.perigon.companion.backup.worker.BackupWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@Immutable
data class BackupUiState(
    val autoEnabled:      Boolean               = true,
    val intervalHours:    Int                    = BackupSchedulePrefs.DEFAULT_INTERVAL_HOURS,
    val numPartsPerPack:  Int                    = BackupSchedulePrefs.DEFAULT_NUM_PARTS,
    val confirmedCount:   Int                    = 0,
    val lastConfirmedAt:  Long?                  = null,
    val unresolvedIssues: List<BackupIssueView>  = emptyList(),
    val folders:          List<BackupFolderEntity> = emptyList(),
    val postMediaLabel:   String                 = "",
    val packState:        String                 = BackupWorker.STATE_DONE,
    val packPosition:     Int                    = 0,
    val partNumber:       Int                    = 0,
    val partsTotal:       Int                    = 0,
    val packPercent:      Int                    = 0,
    val fileIndex:        Int                    = 0,
    val filesTotal:       Int                    = 0,
    val currentFile:      String                 = "",
    val errorType:        String                 = BackupWorker.ERR_NONE,
    val errorDetail:      String                 = "",
    val isRunning:        Boolean                = false,
) {
    val hasError:  Boolean get() = errorType != BackupWorker.ERR_NONE
    val hasIssues: Boolean get() = unresolvedIssues.isNotEmpty()
}

@HiltViewModel
class BackupViewModel @Inject constructor(
    @param:ApplicationContext private val ctx: Context,
    private val workManager: WorkManager,
    private val backupFileDao: BackupFileDao,
    private val backupIssueViewDao: BackupIssueViewDao,
    private val backupFolderDao: BackupFolderDao,
    private val schedulePrefs: BackupSchedulePrefs,
    private val postMediaFileStore: PostMediaFileStore,
    private val notificationDao: UserNotificationDao,
) : ViewModel() {

    private val _state = MutableStateFlow(
        BackupUiState(
            autoEnabled     = schedulePrefs.autoEnabled(),
            intervalHours   = schedulePrefs.intervalHours(),
            numPartsPerPack = schedulePrefs.numPartsPerPack(),
            postMediaLabel  = postMediaFileStore.rootLabel(),
        )
    )
    val state: StateFlow<BackupUiState> = _state

    val snackbar = SnackbarChannel()

    val notifications: UserNotificationDao get() = notificationDao

    init {
        viewModelScope.launch { seedDefaultFolders() }

        viewModelScope.launch {
            workManager.getWorkInfosForUniqueWorkFlow(BackupWorker.WORK_NAME).collect { infos ->
                val info = infos.firstOrNull {
                    it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
                } ?: infos.firstOrNull() ?: return@collect

                val p   = info.progress
                val out = info.outputData
                _state.update { s -> s.copy(
                    isRunning    = info.state == WorkInfo.State.RUNNING,
                    packState    = p.getString(BackupWorker.KEY_PACK_STATE)
                                   ?: if (info.state == WorkInfo.State.SUCCEEDED)
                                          BackupWorker.STATE_DONE else s.packState,
                    packPosition = p.getInt(BackupWorker.KEY_PACK_POSITION, s.packPosition),
                    partNumber   = p.getInt(BackupWorker.KEY_PART_NUMBER,   s.partNumber),
                    partsTotal   = p.getInt(BackupWorker.KEY_PARTS_TOTAL,   s.partsTotal),
                    packPercent  = p.getInt(BackupWorker.KEY_PACK_PERCENT,  s.packPercent),
                    fileIndex    = p.getInt(BackupWorker.KEY_FILE_INDEX,    s.fileIndex),
                    filesTotal   = p.getInt(BackupWorker.KEY_FILES_TOTAL,   s.filesTotal),
                    currentFile  = p.getString(BackupWorker.KEY_CURRENT_FILE) ?: s.currentFile,
                    errorType    = when (info.state) {
                        WorkInfo.State.FAILED    -> out.getString(BackupWorker.KEY_ERROR_TYPE) ?: BackupWorker.ERR_B2_ERROR
                        WorkInfo.State.SUCCEEDED -> BackupWorker.ERR_NONE
                        else -> p.getString(BackupWorker.KEY_ERROR_TYPE) ?: s.errorType
                    },
                    errorDetail  = when (info.state) {
                        WorkInfo.State.FAILED    -> out.getString(BackupWorker.KEY_ERROR_DETAIL) ?: ""
                        WorkInfo.State.SUCCEEDED -> ""
                        else -> p.getString(BackupWorker.KEY_ERROR_DETAIL) ?: s.errorDetail
                    },
                )}
            }
        }

        viewModelScope.launch {
            backupFileDao.countByStatus(BackupFileStatus.CONFIRMED).collect { count ->
                _state.update { it.copy(confirmedCount = count) }
            }
        }

        viewModelScope.launch {
            backupFileDao.lastUpdatedAt(BackupFileStatus.CONFIRMED).collect { ts ->
                _state.update { it.copy(lastConfirmedAt = ts) }
            }
        }

        viewModelScope.launch {
            backupIssueViewDao.observeAll().collect { issues ->
                _state.update { it.copy(unresolvedIssues = issues) }
            }
        }

        viewModelScope.launch {
            backupFolderDao.observeAll().collect { folders ->
                _state.update { it.copy(folders = folders) }
            }
        }
    }

    private suspend fun seedDefaultFolders() {
        if (backupFolderDao.countDefaults() > 0) return
        val now = System.currentTimeMillis()
        BackupSourceScanner.DEFAULT_FOLDERS.forEach { (pathPattern, displayName) ->
            backupFolderDao.insertIgnore(BackupFolderEntity(
                uri = pathPattern,
                displayName = displayName,
                includeInBackup = true,
                isDefault = true,
                addedAt = now,
            ))
        }
    }

    fun startBackup() {
        val req = OneTimeWorkRequestBuilder<BackupWorker>()
            .addTag(BackupWorker.WORK_NAME)
            .build()
        workManager.enqueueUniqueWork(
            BackupWorker.WORK_NAME,
            ExistingWorkPolicy.KEEP,
            req,
        )
    }

    fun cancelBackup() {
        workManager.cancelUniqueWork(BackupWorker.WORK_NAME)
    }

    fun clearIssues() {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            backupFileDao.resetIssues(now = now)
            snackbar.send("Issues cleared - files will retry on next backup")
        }
    }

    fun setAutoEnabled(enabled: Boolean) {
        viewModelScope.launch {
            schedulePrefs.setAutoEnabled(enabled)
            _state.update { it.copy(autoEnabled = enabled) }
            if (enabled) applySchedule(schedulePrefs.intervalHours())
            else workManager.cancelUniqueWork(BackupWorker.WORK_NAME)
        }
    }

    fun setInterval(hours: Int) {
        require(hours in BackupSchedulePrefs.INTERVAL_OPTIONS)
        viewModelScope.launch {
            schedulePrefs.setIntervalHours(hours)
            _state.update { it.copy(intervalHours = hours) }
            if (schedulePrefs.autoEnabled()) applySchedule(hours)
        }
    }

    fun setNumPartsPerPack(numParts: Int) {
        require(numParts in BackupSchedulePrefs.NUM_PARTS_OPTIONS)
        viewModelScope.launch {
            schedulePrefs.setNumPartsPerPack(numParts)
            _state.update { it.copy(numPartsPerPack = numParts) }
        }
    }

    fun addFolder(uri: Uri, displayName: String) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            backupFolderDao.upsert(BackupFolderEntity(
                uri = uri.toString(),
                displayName = displayName,
                includeInBackup = true,
                isDefault = false,
                addedAt = now,
            ))
            snackbar.send("Folder '$displayName' added")
        }
    }

    fun removeFolder(id: Long) {
        viewModelScope.launch { backupFolderDao.delete(id) }
    }

    fun toggleFolder(id: Long, enabled: Boolean) {
        viewModelScope.launch { backupFolderDao.setEnabled(id, enabled) }
    }

    private fun applySchedule(hours: Int) {
        val req = PeriodicWorkRequestBuilder<BackupWorker>(hours.toLong(), TimeUnit.HOURS)
            .addTag(BackupWorker.WORK_NAME)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .build()
            )
            .build()
        workManager.enqueueUniquePeriodicWork(
            BackupWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            req,
        )
    }
}
