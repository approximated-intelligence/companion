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
import de.perigon.companion.core.data.UserNotificationEntity
import de.perigon.companion.core.prefs.FolderGrants
import de.perigon.companion.core.saf.ExternalDriveEjectBus
import de.perigon.companion.core.ui.SnackbarChannel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MirrorViewModel @Inject constructor(
    private val workManager: WorkManager,
    val folderGrants: FolderGrants,
    val ejectBus: ExternalDriveEjectBus,
    private val notificationDao: UserNotificationDao,
) : ViewModel() {

    val snackbar = SnackbarChannel()

    /** For the cross-source snackbar observer, which excludes this feature's own source. */
    val notifications: UserNotificationDao get() = notificationDao

    /**
     * Every recent "mirror"-sourced notification, independent of read state.
     * Rendered inline as the Mirror screen's Activity panel — persistent, not
     * a snackbar. Reads the last N regardless of readAt so the panel is a
     * stable log even after the messages have been marked read elsewhere.
     */
    val recentMirrorNotifications: StateFlow<List<UserNotificationEntity>> =
        notificationDao.observeRecentBySource("mirror", limit = 20)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

    fun sync() {
        workManager.enqueueMirrorSync()
    }

    fun verify() {
        workManager.enqueueMirrorVerify()
    }

    /**
     * Mirror notifications are rendered inline; mark them read so they don't
     * linger as unread in the shared ledger (badge counts, other screens'
     * observers). Called from the screen whenever new entries arrive.
     */
    fun markMirrorRead() {
        viewModelScope.launch { notificationDao.markAllReadForSource("mirror") }
    }

    /** Clear the inline Activity log. Does not touch anything on the drives. */
    fun clearHistory() {
        viewModelScope.launch { notificationDao.deleteAllForSource("mirror") }
    }
}
