package de.perigon.companion.archive.backup.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.perigon.companion.core.saf.driveUuidOf
import de.perigon.companion.core.saf.isExternalVolume
import de.perigon.companion.core.saf.persistSafGrant
import de.perigon.companion.core.ui.DriveEjectButton
import de.perigon.companion.core.ui.rememberConnectedDrives
import de.perigon.companion.core.ui.rememberEjectingUuids
import kotlinx.coroutines.launch

/**
 * Destination picker + drive-grant button, shared by every screen that can
 * trigger a backup (Backup, Consolidate). State lives in the
 * [BackupDestinationSelection] singleton, so switching screens preserves the
 * selection; granting a drive here makes it usable on any screen.
 *
 * The section owns its own grant launcher — that is what makes granting
 * orthogonal: any screen that embeds this composable can grant drives,
 * no separate wiring required.
 *
 * [currentDestination] is the worker's active destination string. Only the
 * drive whose treeUri matches it is disabled for eject; every other drive is
 * idle and can be ejected even while B2 is still uploading. [ejectingUuids]
 * comes from the process-wide eject bus so a drive the system is already
 * unmounting shows "Ejecting…" and cannot be re-selected.
 */
@Composable
fun BackupDestinationSection(
    snackbarHost: SnackbarHostState,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    currentDestination: String = "",
    vm: BackupDestinationViewModel = hiltViewModel(),
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // Bumped after a successful grant so rememberConnectedDrives re-enumerates.
    // persistedUriPermissions changed but no volume callback will fire for the
    // grant itself — a drive that is already mounted just became visible.
    var refreshKey by remember { mutableIntStateOf(0) }

    val drives = rememberConnectedDrives(refreshKey = refreshKey)
    val ejectingUuids = rememberEjectingUuids(vm.ejectBus)
    val writeToB2 by vm.writeToB2.collectAsStateWithLifecycle()
    val writeToDriveUris by vm.writeToDriveUris.collectAsStateWithLifecycle()

    // Reconcile whenever the attached set changes: a drive that goes away
    // loses its selection rather than silently re-appearing checked next time.
    LaunchedEffect(drives) {
        vm.reconcile(drives.map { it.treeUri.toString() }.toSet())
    }

    val grantLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (!isExternalVolume(uri)) {
            scope.launch { snackbarHost.showSnackbar("Pick a removable drive, not internal storage") }
            return@rememberLauncherForActivityResult
        }
        try {
            persistSafGrant(ctx, uri, write = true)
            refreshKey++
        } catch (e: Exception) {
            scope.launch { snackbarHost.showSnackbar("Grant failed: ${e.message}") }
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Destinations for this run", style = MaterialTheme.typography.titleSmall)
        Text(
            "Thumb drives are written first, B2 second. That means you can eject " +
                "each drive as soon as it says Done, even while B2 is still uploading. " +
                "B2 is authoritative; a thumb drive is a best-effort copy.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        DestinationRow(
            icon = Icons.Default.Cloud,
            title = "Backblaze B2",
            subtitle = "Authoritative backup — required for restore and safe-to-delete.",
            checked = writeToB2,
            enabled = enabled,
            onToggle = vm::toggleB2,
        )

        if (drives.isEmpty()) {
            Text(
                "No thumb drive attached. Plug one in and grant access below.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            drives.forEach { drive ->
                val driveUri = drive.treeUri.toString()
                val dest = "THUMBDRIVE:$driveUri"
                val uuid = driveUuidOf(drive.treeUri)
                val isWriting = currentDestination == dest
                val isEjecting = uuid != null && uuid in ejectingUuids
                DestinationRow(
                    icon = Icons.Default.Usb,
                    title = drive.label,
                    subtitle = if (isEjecting) "${drive.uuid} — ejecting" else drive.uuid,
                    checked = driveUri in writeToDriveUris,
                    enabled = enabled && !isEjecting,
                    onToggle = { vm.toggleDrive(driveUri) },
                    trailingContent = {
                        DriveEjectButton(
                            driveLabel = drive.label,
                            writing = isWriting,
                            ejecting = isEjecting,
                            volumeUuid = uuid,
                        )
                    },
                )
            }
        }

        OutlinedButton(
            onClick = { grantLauncher.launch(null) },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
        ) {
            Icon(Icons.Default.Add, null, Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Add thumb drive (grant access)")
        }
    }
}

@Composable
private fun DestinationRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    trailingContent: @Composable RowScope.() -> Unit = {},
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Checkbox(checked = checked, onCheckedChange = { onToggle() }, enabled = enabled)
            Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text(subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            trailingContent()
        }
    }
}
