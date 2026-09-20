package de.perigon.companion.posts.worker

import android.content.Context
import de.perigon.companion.core.ui.NotificationChannels
import de.perigon.companion.core.ui.WorkNotifier

/** Distinct ids so publish/unpublish/backup notifications never collide. */
private const val PUBLISH_NOTIF_ID = 43
private const val UNPUBLISH_NOTIF_ID = 44
private const val PUBLISH_REQUEST_CODE = 9030
private const val UNPUBLISH_REQUEST_CODE = 9031

fun publishNotifier(context: Context) = WorkNotifier(
    context = context,
    notificationId = PUBLISH_NOTIF_ID,
    channelId = NotificationChannels.BACKUP,
    navigateTo = "Posts",
    requestCode = PUBLISH_REQUEST_CODE,
    iconRes = android.R.drawable.ic_menu_upload,
)

fun unpublishNotifier(context: Context) = WorkNotifier(
    context = context,
    notificationId = UNPUBLISH_NOTIF_ID,
    channelId = NotificationChannels.BACKUP,
    navigateTo = "Posts",
    requestCode = UNPUBLISH_REQUEST_CODE,
    iconRes = android.R.drawable.ic_menu_delete,
)
