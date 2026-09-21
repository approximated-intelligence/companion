package de.perigon.companion.core.ui

import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * PendingIntent opening this app at [navigateTo]. Resolves the launcher
 * activity through the package manager, so no module needs the activity
 * class — every app shell gets its own automatically.
 */
fun navigatePendingIntent(ctx: Context, navigateTo: String, requestCode: Int): PendingIntent {
    val intent = requireNotNull(ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)) {
        "no launcher activity in ${ctx.packageName}"
    }.apply {
        putExtra(EXTRA_NAVIGATE_TO, navigateTo)
        addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
    return PendingIntent.getActivity(
        ctx, requestCode, intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
