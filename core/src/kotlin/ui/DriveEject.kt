package de.perigon.companion.core.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

private const val VOLUME_ID_EXTRA = "android.os.storage.extra.VOLUME_ID"
private const val SHOW_FRAGMENT_ARGS = ":settings:show_fragment_args"
private const val BROWSE_ACTION = "android.provider.action.BROWSE"
private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

/**
 * Per-drive eject affordance.
 *
 * Android has no public API that unmounts a volume: ACTION_MEDIA_EJECT is a
 * protected broadcast, and the Settings StorageUnmountReceiver requires
 * MOUNT_UNMOUNT_FILESYSTEMS (signature). What an ordinary app CAN do is open
 * the system UI that has the eject button, for the correct volume. Two real
 * routes exist, tried in this order:
 *
 *  1. The volume's Settings page (Settings$StorageVolumeSettingsActivity),
 *     exported for SystemUI, which hosts the Eject button. This is the
 *     DIRECT route: the user lands on a page titled with the drive label and
 *     an Eject action. Passed VolumeInfo.EXTRA_VOLUME_ID via the Settings
 *     fragment-args extra. Best effort: the activity is an internal Settings
 *     detail, and a non-exported build throws ActivityNotFoundException /
 *     SecurityException, which we catch.
 *
 *  2. Files (DocumentsUI) at the volume root. Built the same way Settings
 *     itself builds it via VolumeInfo.buildBrowseIntent(): action
 *     "android.provider.action.BROWSE", data
 *     content://com.android.externalstorage.documents/root/<UUID>. Files
 *     shows an eject button at the top-level view of a public volume. Public
 *     intent contract; works on every OEM.
 *
 *  3. The Storage overview. Last resort if both targeted routes fail (e.g. an
 *     OEM has moved both Settings and Files).
 *
 * "Safe to unplug" remains the durability claim: writeFileAtomic fsyncs, so
 * once the worker's last write returns, the bytes are on the medium. The
 * dialog's primary action runs the chain above.
 *
 * [volumeUuid] is the volume UUID ("XXXX-XXXX"), read from the SAF tree
 * document id. Required for the targeted routes to work; when absent the
 * primary action falls straight through to the Storage overview.
 */
@Composable
fun DriveEjectButton(
    driveLabel: String,
    writing: Boolean,
    ejecting: Boolean = false,
    volumeUuid: String? = null,
) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }

    val blocked = writing || ejecting
    TextButton(
        onClick = { if (!blocked) open = true },
        enabled = !blocked,
    ) {
        Text(
            when {
                ejecting -> "Ejecting…"
                writing  -> "Writing…"
                else     -> "Eject"
            }
        )
    }

    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("Eject \"$driveLabel\"") },
            text = {
                Text(
                    "All app writes are flushed to the medium — pulling the " +
                        "drive now cannot corrupt our data.\n\n" +
                        "To unmount it properly, Android's Settings page for " +
                        "this volume has an Eject button. Android does not let " +
                        "apps unmount storage directly."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    open = false
                    // Direct route first (specific volume's Settings page),
                    // then the public Files route, then the Storage overview.
                    // Each is a startActivity; the previous throwing is the
                    // signal to fall through.
                    if (volumeUuid != null && openVolumeSettings(ctx, volumeUuid)) return@TextButton
                    if (volumeUuid != null && openFilesAtVolume(ctx, volumeUuid)) return@TextButton
                    openStorageOverview(ctx)
                }) { Text("Eject") }
            },
            dismissButton = {
                TextButton(onClick = { open = false }) { Text("Done") }
            },
        )
    }
}

/**
 * The per-volume Settings page, where the eject button lives. Settings'
 * StorageVolumeSettingsActivity is exported for SystemUI; the fragment reads
 * VolumeInfo.EXTRA_VOLUME_ID from the `:settings:show_fragment_args` extra.
 *
 * Best effort — the class name is an internal Settings detail. Some OEM
 * builds drop or rename it, and non-exported builds throw
 * ActivityNotFoundException / SecurityException. Both are handled by the
 * caller: return false and let the next route try.
 */
private fun openVolumeSettings(ctx: Context, volumeUuid: String): Boolean {
    val args = Bundle().apply { putString(VOLUME_ID_EXTRA, volumeUuid) }
    val candidates = listOf(
        Intent().apply {
            setClassName(
                "com.android.settings",
                "com.android.settings.Settings\$StorageVolumeSettingsActivity",
            )
            putExtra(SHOW_FRAGMENT_ARGS, args)
        },
        Intent().apply {
            setClassName(
                "com.android.settings",
                "com.android.settings.Settings\$PublicVolumeSettingsActivity",
            )
            putExtra(SHOW_FRAGMENT_ARGS, args)
        },
    )
    for (intent in candidates) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { ctx.startActivity(intent) }.isSuccess) return true
    }
    return false
}

/**
 * Files (DocumentsUI) at the root of [volumeUuid]. Same intent Settings builds
 * in VolumeInfo.buildBrowseIntent(): the system Files app shows the volume's
 * eject button at this level.
 */
private fun openFilesAtVolume(ctx: Context, volumeUuid: String): Boolean {
    val rootUri: Uri = DocumentsContract.buildRootUri(
        EXTERNAL_STORAGE_AUTHORITY,
        volumeUuid,
    )
    val intent = Intent(BROWSE_ACTION).apply {
        addCategory(Intent.CATEGORY_DEFAULT)
        data = rootUri
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return runCatching { ctx.startActivity(intent) }.isSuccess
}

private fun openStorageOverview(ctx: Context) {
    val candidates = listOf(
        Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )
    for (intent in candidates) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { ctx.startActivity(intent) }.isSuccess) return
    }
}
