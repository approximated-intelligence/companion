package de.perigon.companion.backup.mirror.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import de.perigon.companion.backup.mirror.worker.MirrorWorker
import de.perigon.companion.backup.mirror.worker.enqueueMirrorSync
import de.perigon.companion.backup.mirror.worker.enqueueMirrorVerify
import de.perigon.companion.core.data.UserNotificationDao
import de.perigon.companion.core.prefs.FolderGrants
import de.perigon.companion.core.ui.SnackbarChannel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class MirrorViewModel @Inject constructor(
    private val workManager: WorkManager,
    val folderGrants: FolderGrants,
    private val notificationDao: UserNotificationDao,
) : ViewModel() {

    val snackbar = SnackbarChannel()

    val notifications: UserNotificationDao get() = notificationDao

    val syncRunning: StateFlow<Boolean> =
        workManager.getWorkInfosForUniqueWorkFlow(MirrorWorker.WORK_SYNC)
            .map { infos -> infos.any { !it.state.isFinished } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val verifyRunning: StateFlow<Boolean> =
        workManager.getWorkInfosForUniqueWorkFlow(MirrorWorker.WORK_VERIFY)
            .map { infos -> infos.any { !it.state.isFinished } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val latestSync: StateFlow<WorkInfo?> =
        workManager.getWorkInfosForUniqueWorkFlow(MirrorWorker.WORK_SYNC)
            .map { infos -> infos.firstOrNull { it.state == WorkInfo.State.RUNNING } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val latestVerify: StateFlow<WorkInfo?> =
        workManager.getWorkInfosForUniqueWorkFlow(MirrorWorker.WORK_VERIFY)
            .map { infos -> infos.firstOrNull { it.state == WorkInfo.State.RUNNING } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun sync() { workManager.enqueueMirrorSync() }
    fun verify() { workManager.enqueueMirrorVerify() }
}
