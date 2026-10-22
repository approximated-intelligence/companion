package de.perigon.companion.archive.mirror.worker

import android.content.Context
import de.perigon.companion.archive.mirror.MirrorChannels
import de.perigon.companion.archive.mirror.MirrorTargets
import de.perigon.companion.core.feature.NotificationIds
import de.perigon.companion.core.feature.RequestCodes
import de.perigon.companion.core.ui.WorkNotifier

/**
 * The two foreground notifications the mirror can raise, distinguished by
 * ID and request code so a sync and a check never overwrite each other's
 * notification.
 */
class MirrorNotifier(private val ctx: Context) {

    val sync = WorkNotifier(
        context = ctx,
        notificationId = NotificationIds.BACKUP + 3,
        channelId = MirrorChannels.MIRROR,
        navigateTo = MirrorTargets.MIRROR,
        requestCode = RequestCodes.BACKUP + 3,
    )

    val verify = WorkNotifier(
        context = ctx,
        notificationId = NotificationIds.BACKUP + 4,
        channelId = MirrorChannels.MIRROR,
        navigateTo = MirrorTargets.MIRROR,
        requestCode = RequestCodes.BACKUP + 4,
    )
}
