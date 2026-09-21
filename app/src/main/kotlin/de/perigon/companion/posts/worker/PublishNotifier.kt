package de.perigon.companion.posts.worker

import android.content.Context
import de.perigon.companion.core.feature.NotificationIds
import de.perigon.companion.core.feature.RequestCodes
import de.perigon.companion.core.ui.WorkNotifier
import de.perigon.companion.posts.PostsChannels
import de.perigon.companion.posts.PostsTargets

fun publishNotifier(context: Context) = WorkNotifier(
    context = context,
    notificationId = NotificationIds.POSTS,
    channelId = PostsChannels.PUBLISH,
    navigateTo = PostsTargets.POSTS,
    requestCode = RequestCodes.POSTS,
)

fun unpublishNotifier(context: Context) = WorkNotifier(
    context = context,
    notificationId = NotificationIds.POSTS + 1,
    channelId = PostsChannels.PUBLISH,
    navigateTo = PostsTargets.POSTS,
    requestCode = RequestCodes.POSTS + 1,
)
