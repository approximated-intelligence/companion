package de.perigon.companion.core.ui

import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import de.perigon.companion.core.saf.DriveInfo
import de.perigon.companion.core.saf.enumerateConnectedDrives
import kotlinx.coroutines.delay

/**
 * The current set of connected+granted thumb drives, kept live.
 *
 * Updates on three signals:
 *
 *  1. StorageManager's volume-state callback, which fires on plug and unplug
 *     on well-behaved devices.
 *  2. Lifecycle resume, so a drive that was already plugged in while the
 *     screen was away shows up when the screen comes back to foreground.
 *  3. A 3 s poll while the screen is RESUMED, as a fallback for devices whose
 *     volume callback is unreliable (some OEMs only fire it for the primary
 *     volume).
 *
 * [refreshKey] is an out-of-band trigger. Bump it when something changes the
 * grant set but not the volume state — most importantly, after
 * ACTION_OPEN_DOCUMENT_TREE grants a new tree, at which point
 * `persistedUriPermissions` changes but no volume callback fires.
 */
@Composable
fun rememberConnectedDrives(refreshKey: Any? = null): List<DriveInfo> {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val drives = remember {
        mutableStateOf(enumerateConnectedDrives(ctx).sortedBy { it.uuid })
    }

    fun refresh() {
        val fresh = enumerateConnectedDrives(ctx).sortedBy { it.uuid }
        if (fresh != drives.value) {
            drives.value = fresh
        }
    }

    LifecycleResumeEffect(refreshKey) {
        refresh()
        onPauseOrDispose { }
    }

    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(StorageManager::class.java)
        val cb = object : StorageManager.StorageVolumeCallback() {
            override fun onStateChanged(volume: StorageVolume) {
                refresh()
            }
        }
        sm?.registerStorageVolumeCallback(ctx.mainExecutor, cb)
        onDispose {
            sm?.unregisterStorageVolumeCallback(cb)
        }
    }

    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(3000)
                refresh()
            }
        }
    }

    return drives.value
}
