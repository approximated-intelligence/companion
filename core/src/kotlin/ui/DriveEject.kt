package de.perigon.companion.core.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.Settings
import android.util.Log
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

private const val TAG = "DriveEject"
private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

// AOSP Settings declares this action on Settings$StorageVolumeSettingsActivity,
// gated on data scheme "content", host "com.android.externalstorage.documents",
// MIME "vnd.android.document/root". Not exported as a DocumentsContract constant.
private const val ACTION_DOCUMENT_ROOT_SETTINGS =
    "android.provider.action.DOCUMENT_ROOT_SETTINGS"

/**
 * Opens the system eject UI for a thumb drive.
 *
 * Chain, in order:
 *   1. DocumentsContract.ejectRoot() — best-effort programmatic unmount.
 *   2. ACTION_DOCUMENT_ROOT_SETTINGS intent — opens the volume's Settings
 *      page, which has the eject button. Primary route.
 *   3. Files at the volume root — same eject affordance, less prominent.
 *   4. Storage overview — last resort.
 *
 * [writing] disables the button. [ejecting] reflects the system-side
 * eject in progress, cleared on remount by ExternalDriveEjectBus.
 * [volumeUuid] is the fsUuid ("XXXX-XXXX") from the SAF tree document id.
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
                        "Opening this volume's Storage settings, where the " +
                        "system eject button is."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    open = false
                    if (volumeUuid == null) {
                        openStorageOverview(ctx)
                        return@TextButton
                    }
                    tryEjectRoot(ctx, volumeUuid)
                    if (!openVolumeSettings(ctx, volumeUuid)) {
                        if (!openFilesAtVolume(ctx, volumeUuid)) {
                            openStorageOverview(ctx)
                        }
                    }
                }) { Text("Open Settings") }
            },
            dismissButton = {
                TextButton(onClick = { open = false }) { Text("Done") }
            },
        )
    }
}

private fun tryEjectRoot(ctx: Context, fsUuid: String): Boolean {
    val rootUri: Uri = DocumentsContract.buildRootUri(EXTERNAL_STORAGE_AUTHORITY, fsUuid)
    return try {
        DocumentsContract.ejectRoot(ctx.contentResolver, rootUri)
        Log.i(TAG, "ejectRoot($fsUuid) accepted")
        true
    } catch (e: IllegalStateException) {
        Log.w(TAG, "ejectRoot($fsUuid) refused: ${e.message}")
        false
    } catch (e: SecurityException) {
        Log.w(TAG, "ejectRoot($fsUuid) denied: ${e.message}")
        false
    } catch (e: Exception) {
        Log.w(TAG, "ejectRoot($fsUuid) failed: ${e.javaClass.simpleName}: ${e.message}")
        false
    }
}

private fun openVolumeSettings(ctx: Context, fsUuid: String): Boolean {
    val rootUri: Uri = DocumentsContract.buildRootUri(EXTERNAL_STORAGE_AUTHORITY, fsUuid)
    val intent = Intent(ACTION_DOCUMENT_ROOT_SETTINGS).apply {
        addCategory(Intent.CATEGORY_DEFAULT)
        setDataAndType(rootUri, DocumentsContract.Root.MIME_TYPE_ITEM)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return try {
        ctx.startActivity(intent)
        Log.i(TAG, "volumeSettings($fsUuid) launched")
        true
    } catch (e: Exception) {
        Log.w(TAG, "volumeSettings($fsUuid) failed: ${e.javaClass.simpleName}: ${e.message}")
        false
    }
}

private fun openFilesAtVolume(ctx: Context, fsUuid: String): Boolean {
    val rootUri: Uri = DocumentsContract.buildRootUri(EXTERNAL_STORAGE_AUTHORITY, fsUuid)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        addCategory(Intent.CATEGORY_DEFAULT)
        setDataAndType(rootUri, DocumentsContract.Root.MIME_TYPE_ITEM)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return try {
        ctx.startActivity(intent)
        Log.i(TAG, "browse($fsUuid) launched")
        true
    } catch (e: Exception) {
        Log.w(TAG, "browse($fsUuid) failed: ${e.javaClass.simpleName}: ${e.message}")
        false
    }
}

private fun openStorageOverview(ctx: Context) {
    val candidates = listOf(
        Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )
    for (intent in candidates) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { ctx.startActivity(intent) }.isSuccess) {
            Log.i(TAG, "openStorageOverview: launched ${intent.action}")
            return
        }
    }
}
