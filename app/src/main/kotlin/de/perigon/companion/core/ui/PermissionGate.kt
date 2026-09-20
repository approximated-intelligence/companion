package de.perigon.companion.core.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect

private val MEDIA_PERMISSIONS = listOf(
    Manifest.permission.READ_MEDIA_IMAGES,
    Manifest.permission.READ_MEDIA_VIDEO,
)

private fun hasMediaAccess(context: android.content.Context): Boolean {
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

@Composable
fun PermissionGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var mediaOk   by remember { mutableStateOf(hasMediaAccess(context)) }
    var requested by rememberSaveable { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        requested = true
        mediaOk = hasMediaAccess(context)   // partial grant counts
    }

    // Re-check when returning from system Settings — the old gate computed
    // grant state once in remember and stayed stuck until process death.
    LifecycleResumeEffect(Unit) {
        mediaOk = hasMediaAccess(context)
        onPauseOrDispose { }
    }

    if (mediaOk) { content(); return }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Text("Photo access required", style = MaterialTheme.typography.titleLarge)
            Text(
                "Companion needs access to your photos and videos to prepare media " +
                "for backup and publishing. \"Select photos\" works too.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = {
                // POST_NOTIFICATIONS is requested alongside but NEVER gates the
                // app — denying it previously dead-ended everything.
                launcher.launch((MEDIA_PERMISSIONS +
                    Manifest.permission.POST_NOTIFICATIONS).toTypedArray())
            }) { Text("Grant access") }
            if (requested) {
                OutlinedButton(onClick = {
                    context.startActivity(Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    ))
                }) { Text("Open app settings") }
            }
        }
    }
}
