package de.perigon.companion.core.ui

import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

/**
 * Prevents the display from auto-timing-out while this composable is in
 * composition. Use on screens the user is expected to watch for extended
 * periods.
 *
 * Sets FLAG_KEEP_SCREEN_ON on the Activity window and clears it on dispose.
 * Does NOT prevent manual screen-off (power button) and does NOT hold a CPU
 * wake-lock — those are separate concerns.
 */
@Composable
fun KeepScreenOn() {
    val activity = LocalActivity.current ?: return
    DisposableEffect(Unit) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}