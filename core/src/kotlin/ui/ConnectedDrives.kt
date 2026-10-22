package de.perigon.companion.core.ui

import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import de.perigon.companion.core.saf.DriveInfo
import de.perigon.companion.core.saf.enumerateConnectedDrives

/**
 * The current set of connected+granted thumb drives, kept live.
 *
 * Updates on two signals:
 *
 *  1. StorageManager's volume-state callback ([StorageVolumeCallback.onStateChanged]),
 *     which fires on plug and unplug on well-behaved devices.
 *  2. Lifecycle resume, so a drive that was already plugged in while the
 *     screen was away shows up when the screen comes back to foreground.
 *
 * [refreshKey] is an out-of-band trigger. Bump it when something changes
 * the grant set but not the volume state — most importantly, after
 * ACTION_OPEN_DOCUMENT_TREE grants a new tree, at which point
 * `persistedUriPermissions` changes but no volume callback fires.
 */
@Composable
fun rememberConnectedDrives(refreshKey: Any? = null): List<DriveInfo> {
    val ctx = LocalContext.current
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

    return drives.value
}
