package de.perigon.companion.media.ui.consolidate

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.*
import de.perigon.companion.core.data.UserNotificationDao
import de.perigon.companion.core.ui.SnackbarChannel
import de.perigon.companion.media.worker.ConsolidateWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@Immutable
data class ConsolidateUiState(
    val isRunning:   Boolean = false,
    val total:       Int     = 0,
    val processed:   Int     = 0,
    val failed:      Int     = 0,
    val currentFile: String  = "",
)

@HiltViewModel
class ConsolidateViewModel @Inject constructor(
    @param:ApplicationContext private val ctx: Context,
    private val workManager: WorkManager,
    private val notificationDao: UserNotificationDao,
) : ViewModel() {

    private val _state = MutableStateFlow(ConsolidateUiState())
    val state: StateFlow<ConsolidateUiState> = _state.asStateFlow()

    val snackbar = SnackbarChannel()

    /** Expose for screen-level notification observation */
    val notifications: UserNotificationDao get() = notificationDao

    init {
        observeWorker()
    }

    private fun observeWorker() {
        viewModelScope.launch {
            workManager.getWorkInfosForUniqueWorkFlow(ConsolidateWorker.WORK_NAME).collect { infos ->
                val info = infos.firstOrNull {
                    it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
                } ?: infos.firstOrNull() ?: return@collect

                val p = info.progress
                _state.update { s -> s.copy(
                    isRunning   = info.state == WorkInfo.State.RUNNING || info.state == WorkInfo.State.ENQUEUED,
                    total       = p.getInt(ConsolidateWorker.KEY_TOTAL, s.total),
                    processed   = p.getInt(ConsolidateWorker.KEY_PROCESSED, s.processed),
                    failed      = p.getInt(ConsolidateWorker.KEY_FAILED, s.failed),
                    currentFile = p.getString(ConsolidateWorker.KEY_CURRENT_FILE) ?: s.currentFile,
                )}

                if (info.state == WorkInfo.State.SUCCEEDED || info.state == WorkInfo.State.FAILED) {
                    _state.update { it.copy(isRunning = false, currentFile = "") }
                }
            }
        }
    }

    fun consolidateAll() {
        if (_state.value.isRunning) return
        _state.update { it.copy(isRunning = true, total = 0, processed = 0, failed = 0, currentFile = "Starting…") }
        workManager.enqueueUniqueWork(
            ConsolidateWorker.WORK_NAME,
            ExistingWorkPolicy.KEEP,
            ConsolidateWorker.buildRequest(),
        )
    }
}
