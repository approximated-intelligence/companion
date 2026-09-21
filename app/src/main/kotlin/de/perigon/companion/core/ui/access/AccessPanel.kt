package de.perigon.companion.core.ui.access

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import de.perigon.companion.core.feature.AccessRequirement
import de.perigon.companion.core.feature.runtimePermissions
import de.perigon.companion.core.saf.persistSafGrant
import de.perigon.companion.core.ui.StatusRow
import kotlinx.coroutines.launch

/** One user-visible grant action. */
internal sealed interface GrantStep {
    data class Runtime(val permissions: List<String>) : GrantStep
    data object Background : GrantStep
    data object Alarms : GrantStep
    data class Folder(val req: AccessRequirement.Folder) : GrantStep
}

/**
 * Pure: the ordered actions granting [missing]. All runtime permissions in ONE
 * system dialog first — background location is only grantable after
 * foreground location — then the settings-screen op, then one folder picker
 * per folder.
 */
internal fun grantSteps(missing: List<AccessRequirement>): List<GrantStep> = buildList {
    val perms = missing.runtimePermissions()
    if (perms.isNotEmpty()) add(GrantStep.Runtime(perms))
    if (missing.any { it is AccessRequirement.BackgroundLocation }) add(GrantStep.Background)
    if (missing.any { it is AccessRequirement.ExactAlarms }) add(GrantStep.Alarms)
    missing.filterIsInstance<AccessRequirement.Folder>().forEach { add(GrantStep.Folder(it)) }
}

private fun appSettingsIntent(pkg: String) =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))

/**
 * Status and grant actions for [requirements]. "Grant all" walks through
 * every missing grant in sequence; each row can also be granted alone.
 *
 * Grant state is not observable (neither permissions nor persisted URI grants
 * emit), so status derives from an epoch bumped on resume and after every
 * result. [onStatusChanged] lets an enclosing gate re-check too.
 */
@Composable
fun AccessPanel(
    requirements: List<AccessRequirement>,
    modifier: Modifier = Modifier,
    showSatisfied: Boolean = true,
    onStatusChanged: () -> Unit = {},
    vm: AccessViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var epoch by remember { mutableIntStateOf(0) }
    var queue by remember { mutableStateOf<List<GrantStep>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    // After one runtime request, a still-missing permission was denied for
    // good; only system settings can grant it now.
    var askedRuntime by rememberSaveable { mutableStateOf(false) }

    fun bump() { epoch++; onStatusChanged() }
    fun advance() { queue = queue.drop(1); bump() }

    LifecycleResumeEffect(Unit) {
        epoch++
        onPauseOrDispose { }
    }

    val satisfied = remember(epoch, requirements) {
        requirements.associateWith { vm.isSatisfied(context, it) }
    }
    val missing = requirements.filter { satisfied[it] != true }

    val runtimeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { askedRuntime = true; advance() }

    val backgroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { advance() }

    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { advance() }

    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val step = queue.firstOrNull() as? GrantStep.Folder
        if (uri == null || step == null) {
            queue = emptyList(); bump(); return@rememberLauncherForActivityResult
        }
        // Validate BEFORE persisting: a rejected pick must leave no grant.
        val problem = step.req.validate(uri)
        if (problem != null) {
            error = problem; queue = emptyList(); bump(); return@rememberLauncherForActivityResult
        }
        error = null
        persistSafGrant(context, uri, write = step.req.write)
        scope.launch { vm.setFolder(step.req.key, uri); advance() }
    }

    LaunchedEffect(queue) {
        when (val step = queue.firstOrNull()) {
            null -> Unit
            is GrantStep.Runtime -> runtimeLauncher.launch(step.permissions.toTypedArray())
            GrantStep.Background -> backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            GrantStep.Alarms -> settingsLauncher.launch(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.fromParts("package", context.packageName, null))
            )
            is GrantStep.Folder -> folderLauncher.launch(step.req.initialUri)
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (missing.isNotEmpty()) {
            Button(
                onClick = { error = null; queue = grantSteps(missing) },
                enabled = queue.isEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Grant all") }
        }
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        for (req in requirements) {
            val ok = satisfied[req] == true
            if (ok && !showSatisfied) continue
            StatusRow(
                title = if (req.optional) "${req.title} (optional)" else req.title,
                subtitle = if (ok && req is AccessRequirement.Folder) vm.folderLabel(req.key) ?: "Granted"
                           else req.rationale,
                ok = ok,
                optional = req.optional,
            ) {
                when {
                    req is AccessRequirement.Folder -> {
                        if (ok && req.optional) {
                            TextButton(onClick = { scope.launch { vm.clearFolder(req.key); bump() } }) {
                                Text("Clear")
                            }
                        }
                        FilledTonalButton(
                            onClick = { error = null; queue = listOf(GrantStep.Folder(req)) },
                            enabled = queue.isEmpty(),
                        ) { Text(if (ok) "Change" else "Select") }
                    }
                    ok -> Unit
                    req is AccessRequirement.Permissions && askedRuntime ->
                        OutlinedButton(onClick = { context.startActivity(appSettingsIntent(context.packageName)) }) {
                            Text("Settings")
                        }
                    else -> FilledTonalButton(
                        onClick = { error = null; queue = grantSteps(listOf(req)) },
                        enabled = queue.isEmpty(),
                    ) { Text("Grant") }
                }
            }
        }
    }
}
