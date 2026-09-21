package de.perigon.companion.core.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.perigon.companion.core.prefs.AppPrefs
import de.perigon.companion.core.saf.dcimPickerInitialUri
import de.perigon.companion.core.saf.hasDcimWriteGrant
import de.perigon.companion.core.saf.isDcimTree
import de.perigon.companion.core.saf.persistSafGrant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

private val MEDIA_PERMISSIONS = listOf(
    Manifest.permission.READ_MEDIA_IMAGES,
    Manifest.permission.READ_MEDIA_VIDEO,
)

private fun hasMediaAccess(context: Context): Boolean {
    fun granted(p: String) =
        ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    val full = MEDIA_PERMISSIONS.all(::granted)
    // "Select photos" (Android 14+, and minSdk is 34): IMAGES/VIDEO stay
    // denied, only READ_MEDIA_VISUAL_USER_SELECTED is granted. That is enough
    // for MediaStore to return the user-selected subset — which is exactly
    // what the user asked for — so the gate must accept it.
    val partial = granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    return full || partial
}

@HiltViewModel
class AppAccessViewModel @Inject constructor(
    private val appPrefs: AppPrefs,
) : ViewModel() {

    private val _dcimUri = MutableStateFlow(appPrefs.dcimTreeUri())

    /**
     * The DCIM tree the gate checks against. Updated SYNCHRONOUSLY on pick
     * (see [onDcimGranted]) rather than only via the prefs flow: the prefs
     * write is async, and checking the old URI in the meantime would bounce
     * the user straight back to the prompt they just answered.
     */
    val dcimUri: StateFlow<String?> = _dcimUri.asStateFlow()

    init {
        // External changes (Settings screen, clear-all) still flow in.
        viewModelScope.launch {
            appPrefs.observeDcimTreeUri().collect { _dcimUri.value = it }
        }
    }

    fun onDcimGranted(uri: Uri) {
        _dcimUri.value = uri.toString()
        val label = uri.lastPathSegment?.substringAfterLast(':') ?: "DCIM"
        viewModelScope.launch { appPrefs.setDcimTree(uri.toString(), label) }
    }
}

/**
 * The single access gate for the whole app, wrapped around the nav graph.
 *
 * Both requirements are app-wide, not screen-local:
 *  - media permission, to read photos and videos at all;
 *  - a WRITABLE grant on the DCIM folder itself, because every feature
 *    stores under it — PostMedia and its Originals, consolidation output,
 *    restore targets.
 *
 * Grant state is NOT observable: neither checkSelfPermission nor
 * persistedUriPermissions emits when a grant is made (dialog, folder picker)
 * or revoked (system settings). So both checks are derived from an explicit
 * [accessEpoch], bumped at every point a grant can change. Deriving from the
 * stored URI alone missed the commonest case: re-granting the SAME folder
 * leaves the URI byte-identical, nothing recomposes, and the gate stayed shut
 * until the app was restarted.
 *
 * Opening the gate resumes wherever the user was headed: launch requests
 * (shares, notification taps) wait in MainActivity's channel and are
 * delivered once the nav graph composes.
 */
@Composable
fun AppAccessGate(
    vm: AppAccessViewModel = hiltViewModel(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val dcimUri by vm.dcimUri.collectAsStateWithLifecycle()

    var accessEpoch by remember { mutableIntStateOf(0) }
    val mediaOk = remember(accessEpoch) { hasMediaAccess(context) }
    val dcimOk = remember(dcimUri, accessEpoch) { hasDcimWriteGrant(context, dcimUri) }

    var mediaRequested by rememberSaveable { mutableStateOf(false) }
    var pickError by rememberSaveable { mutableStateOf<String?>(null) }

    // Returning from system Settings (or any other app) may have changed
    // either grant in either direction.
    LifecycleResumeEffect(Unit) {
        accessEpoch++
        onPauseOrDispose { }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        mediaRequested = true
        accessEpoch++   // partial grant counts
    }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        // Validate BEFORE persisting: a rejected pick must leave no grant.
        if (!isDcimTree(uri)) {
            pickError = "That's not the DCIM folder. Select the folder named DCIM " +
                "at the top level of your storage."
            return@rememberLauncherForActivityResult
        }
        pickError = null
        persistSafGrant(context, uri, write = true)
        vm.onDcimGranted(uri)
        accessEpoch++
    }

    if (!mediaOk) {
        AccessRequest(
            icon = Icons.Default.PhotoLibrary,
            title = "Photo access required",
            body = "Companion needs access to your photos and videos to prepare media " +
                "for backup and publishing. \"Select photos\" works too.",
            actionLabel = "Grant access",
            onAction = {
                // POST_NOTIFICATIONS is requested alongside but NEVER gates the
                // app — denying it previously dead-ended everything.
                permissionLauncher.launch(
                    (MEDIA_PERMISSIONS + Manifest.permission.POST_NOTIFICATIONS).toTypedArray()
                )
            },
            secondary = if (mediaRequested) {
                {
                    OutlinedButton(onClick = {
                        context.startActivity(Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        ))
                    }) { Text("Open app settings") }
                }
            } else null,
        )
        return
    }

    // Write access, always: posts, consolidation and restore all WRITE into
    // DCIM. A read-only grant was never enough for anything but browsing.
    if (!dcimOk) {
        AccessRequest(
            icon = Icons.Default.FolderOpen,
            title = "DCIM folder access required",
            body = "Companion stores post media, consolidated photos and restored files " +
                "under your DCIM folder, and reads originals from it. Select DCIM to continue.",
            actionLabel = "Select DCIM folder",
            onAction = { folderPicker.launch(dcimPickerInitialUri()) },
            error = pickError,
        )
        return
    }

    content()
}

@Composable
private fun AccessRequest(
    icon: ImageVector,
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
    error: String? = null,
    secondary: (@Composable () -> Unit)? = null,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (error != null) {
                Text(
                    error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
            Button(onClick = onAction) {
                Icon(icon, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(actionLabel)
            }
            secondary?.invoke()
        }
    }
}
