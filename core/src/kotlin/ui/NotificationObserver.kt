package de.perigon.companion.core.ui

import androidx.compose.material3.SnackbarHostState
import de.perigon.companion.core.data.UserNotificationDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Reusable helper: observes unread notifications, shows snackbar, marks as read.
 * Call from a LaunchedEffect or viewModelScope.
 *
 * Screens that display one feature's messages inline (with full history)
 * pass that feature's `source` in [excludeSources]. Example: Mirror renders
 * `source = "mirror"` entries inline in its Activity panel, so
 * ConsolidateScreen and BackupScreen pass `excludeSources = setOf("mirror")`
 * to avoid duplicating those as snackbars on other screens.
 *
 * Usage in a Composable:
 *   LaunchedEffect(Unit) {
 *       NotificationObserver.observe(
 *           scope = this,
 *           dao = vm.notifications,
 *           snackbarHost = snackbar,
 *           excludeSources = setOf("mirror"),
 *       )
 *   }
 */
object NotificationObserver {

    fun observe(
        scope: CoroutineScope,
        dao: UserNotificationDao,
        snackbarHost: SnackbarHostState,
        excludeSources: Set<String> = emptySet(),
    ) {
        scope.launch {
            val flow = if (excludeSources.isEmpty()) {
                dao.observeUnread()
            } else {
                dao.observeUnreadExcluding(excludeSources.toList())
            }
            flow.collect { unread ->
                for (notification in unread) {
                    snackbarHost.showSnackbar(notification.message)
                    dao.markRead(notification.id)
                }
            }
        }
    }
}
