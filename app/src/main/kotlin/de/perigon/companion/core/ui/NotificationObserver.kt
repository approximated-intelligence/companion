package de.perigon.companion.core.ui

import androidx.compose.material3.SnackbarHostState
import de.perigon.companion.core.data.UserNotificationDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Reusable helper: observes unread notifications, shows snackbar, marks as read.
 * Call from a LaunchedEffect or viewModelScope.
 *
 * Usage in a Composable:
 *   LaunchedEffect(Unit) {
 *       NotificationObserver.observe(this, dao, snackbarHost)
 *   }
 */
object NotificationObserver {

    fun observe(
        scope: CoroutineScope,
        dao: UserNotificationDao,
        snackbarHost: SnackbarHostState,
    ) {
        scope.launch {
            dao.observeUnread().collect { unread ->
                for (notification in unread) {
                    snackbarHost.showSnackbar(notification.message)
                    dao.markRead(notification.id)
                }
            }
        }
    }
}
