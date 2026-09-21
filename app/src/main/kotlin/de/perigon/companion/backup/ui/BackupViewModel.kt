package de.perigon.companion.backup.ui

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import de.perigon.companion.backup.data.BackupFolderEntity
import de.perigon.companion.backup.data.BackupSchedulePrefs
import de.perigon.companion.backup.data.BackupSourceScanner
import de.perigon.companion.backup.data.BackupStateRepository
import de.perigon.companion.backup.worker.BackupRequests
import de.perigon.companion.backup.worker.BackupWorker
import de.perigon.companion.backup.worker.enqueueBackup
import de.perigon.companion.backup.worker.syncBackupSchedule
import de.perigon.companion.core.data.UserNotificationDao
import de.perigon.companion.core.ui.SnackbarChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@Immutable
data class BackupUiState(
    val autoEnabled:     Boolean                  = true,
    val intervalHours:   Int                      = BackupSchedulePrefs.DEFAULT_INTERVAL_HOURS,
    val numPartsPerPack: Int                      = BackupSchedulePrefs.DEFAULT_NUM_PARTS,
    val confirmedCount:  Int                      = 0,
    val lastConfirmedAt: Long?                    = null,
    val folders:         List<BackupFolderEntity> = emptyList(),
    val progress:        BackupProgress           = BackupProgress(),
)

/** DCIM access is owned by the setup gate; this screen never asks for it. */
@HiltViewModel
class BackupViewModel @Inject constructor(
    private val workManager: WorkManager,
    private val stateRepo: BackupStateRepository,
    private val schedulePrefs: BackupSchedulePrefs,
    private val notificationDao: UserNotificationDao,
) : ViewModel() {

    private val _state = MutableStateFlow(
        BackupUiState(
            autoEnabled     = schedulePrefs.autoEnabled(),
            intervalHours   = schedulePrefs.intervalHours(),
            numPartsPerPack = schedulePrefs.numPartsPerPack(),
        )
    )
    val state: StateFlow<BackupUiState> = _state

    val snackbar = SnackbarChannel()

    val notifications: UserNotificationDao get() = notificationDao

    init {
        viewModelScope.launch { seedDefaultFolders() }

        viewModelScope.launch {
            // Execution slot: covers manual and scheduled runs alike.
            workManager.getWorkInfosForUniqueWorkFlow(BackupWorker.WORK_NAME).collect { infos ->
                val info = infos.currentInfo() ?: return@collect
                _state.update { s -> s.copy(progress = info.toBackupProgress(s.progress)) }
            }
        }

        viewModelScope.launch {
            stateRepo.observeConfirmedCount().collect { count ->
                _state.update { it.copy(confirmedCount = count) }
            }
        }

        viewModelScope.launch {
            stateRepo.observeLastConfirmedAt().collect { ts ->
                _state.update { it.copy(lastConfirmedAt = ts) }
            }
        }

        viewModelScope.launch {
            stateRepo.observeFolders().collect { folders ->
                _state.update { it.copy(folders = folders) }
            }
        }
    }

    private suspend fun seedDefaultFolders() {
        if (stateRepo.countDefaultFolders() > 0) return
        val now = System.currentTimeMillis()
        BackupSourceScanner.DEFAULT_FOLDERS.forEach { (uriPattern, displayName) ->
            stateRepo.insertFolderIgnore(BackupFolderEntity(
                uri = uriPattern,
                displayName = displayName,
                includeInBackup = true,
                isDefault = true,
                addedAt = now,
            ))
        }
    }

    fun startBackup() {
        workManager.enqueueBackup(BackupRequests.manual())
    }

    fun cancelBackup() {
        // The running execution only; the periodic schedule is a separate name.
        workManager.cancelUniqueWork(BackupWorker.WORK_NAME)
    }

    fun setAutoEnabled(enabled: Boolean) {
        viewModelScope.launch {
            schedulePrefs.setAutoEnabled(enabled)
            _state.update { it.copy(autoEnabled = enabled) }
            workManager.syncBackupSchedule(
                enabled       = enabled,
                intervalHours = schedulePrefs.intervalHours(),
                policy        = ExistingPeriodicWorkPolicy.UPDATE,
            )
        }
    }

    fun setInterval(hours: Int) {
        require(hours in BackupSchedulePrefs.INTERVAL_OPTIONS)
        viewModelScope.launch {
            schedulePrefs.setIntervalHours(hours)
            _state.update { it.copy(intervalHours = hours) }
            if (schedulePrefs.autoEnabled()) {
                workManager.syncBackupSchedule(
                    enabled       = true,
                    intervalHours = hours,
                    policy        = ExistingPeriodicWorkPolicy.UPDATE,
                )
            }
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
            stateRepo.upsertFolder(BackupFolderEntity(
                uri = uri.toString(),
                displayName = displayName,
                includeInBackup = true,
                isDefault = false,
                addedAt = System.currentTimeMillis(),
            ))
            snackbar.send("Folder '$displayName' added")
        }
    }

    fun removeFolder(id: Long) {
        viewModelScope.launch { stateRepo.deleteFolder(id) }
    }

    fun toggleFolder(id: Long, enabled: Boolean) {
        viewModelScope.launch { stateRepo.setFolderEnabled(id, enabled) }
    }
}
