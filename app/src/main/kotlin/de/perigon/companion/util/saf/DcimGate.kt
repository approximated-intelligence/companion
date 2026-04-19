package de.perigon.companion.util.saf

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Composable that checks for DCIM SAF grant and shows a prompt card if missing.
 * When granted, persists the URI via [onGranted] and shows [content].
 *
 * Usage:
 *   DcimGate(
 *       dcimUri = state.dcimUri,
 *       onGranted = { uri -> vm.setDcimUri(uri) },
 *   ) {
 *       // screen content that requires DCIM access
 *   }
 */
@Composable
fun DcimGate(
    dcimUri: String?,
    onGranted: (Uri) -> Unit,
    writeAccess: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val hasGrant = if (writeAccess) {
        hasDcimWriteGrant(context, dcimUri)
    } else {
        hasDcimGrant(context, dcimUri)
    }

    if (hasGrant) {
        content()
        return
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            persistSafGrant(context, uri, write = writeAccess)
            onGranted(uri)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Default.FolderOpen,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                "DCIM folder access required",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                "Grant access to your DCIM folder for backup, restore, and consolidation. "
                    + "This preserves original photo metadata (EXIF) and works across all features.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.height(4.dp))
            Button(onClick = { picker.launch(dcimPickerInitialUri()) }) {
                Icon(Icons.Default.FolderOpen, null, Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Select DCIM folder")
            }
        }
    }
}
