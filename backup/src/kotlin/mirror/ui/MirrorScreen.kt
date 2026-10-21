package de.perigon.companion.backup.mirror.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import de.perigon.companion.backup.mirror.worker.MirrorWorker
import de.perigon.companion.core.data.NotificationType
import de.perigon.companion.core.data.UserNotificationEntity
import de.perigon.companion.core.feature.CommonRequirements
import de.perigon.companion.core.feature.isSatisfied
import de.perigon.companion.core.prefs.FolderKeys
import de.perigon.companion.core.saf.DriveInfo
import de.perigon.companion.core.saf.enumerateConnectedDrives
import de.perigon.companion.core.saf.isExternalVolume
import de.perigon.companion.core.ui.AppTopBar
import de.perigon.companion.core.ui.NotificationObserver
import de.perigon.companion.core.ui.SectionHeader
import de.perigon.companion.core.ui.formatDateTime
import kotlinx.coroutines.launch

@Composable
fun MirrorScreen(
    navController: NavController,
    vm: MirrorViewModel = hiltViewModel(),
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val syncRunning by vm.syncRunning.collectAsStateWithLifecycle()
    val verifyRunning by vm.verifyRunning.collectAsStateWithLifecycle()
    val latestSync by vm.latestSync.collectAsStateWithLifecycle()
    val latestVerify by vm.latestVerify.collectAsStateWithLifecycle()
    val notifications by vm.recentMirrorNotifications.collectAsStateWithLifecycle()

    // Snackbars for OTHER sources' messages. Mirror's own messages are shown
    // inline in the Activity panel below, so they are excluded here.
    LaunchedEffect(Unit) {
        NotificationObserver.observe(
            scope = this,
            dao = vm.notifications,
            snackbarHost = snackbar,
            excludeSources = setOf("mirror"),
        )
    }

    // Mirror notifications are displayed inline; mark them read so they do
    // not linger as unread in the shared ledger or on other screens.
    LaunchedEffect(notifications) {
        if (notifications.isNotEmpty()) vm.markMirrorRead()
    }

    var epoch by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        epoch++
        onPauseOrDispose { }
    }
    val drives: List<DriveInfo> = remember(epoch) { enumerateConnectedDrives(ctx) }
    val dcimOk: Boolean = remember(epoch) {
        CommonRequirements.dcim.isSatisfied(ctx) { key ->
            if (key == FolderKeys.DCIM) vm.folderGrants.uri(FolderKeys.DCIM) else null
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (!isExternalVolume(uri)) {
            scope.launch { snackbar.showSnackbar("Pick a removable drive, not internal storage") }
            return@rememberLauncherForActivityResult
        }
        try {
            ctx.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            epoch++
            scope.launch { snackbar.showSnackbar("Drive added") }
        } catch (e: Exception) {
            scope.launch { snackbar.showSnackbar("Grant failed: ${e.message}") }
        }
    }

    LaunchedEffect(Unit) {
        vm.snackbar.events.collect { snackbar.showSnackbar(it) }
    }

    Scaffold(
        topBar = { AppTopBar(navController) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionHeader(
                "Mirror to thumb drives",
                "One-way copy of DCIM and configured folders as plain files, into Mirror/ on each drive. " +
                    "Nothing on the drive is deleted. Copy is by (path, size): a matching size is assumed unchanged.",
            )

            if (!dcimOk) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            "DCIM access missing",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            "Mirror cannot read DCIM without it. Grant DCIM in Settings → Access.",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            if (drives.isEmpty()) {
                Text(
                    "No drives connected. Plug in a thumb drive and press Add Drive, " +
                        "then grant access to the drive root.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "${drives.size} drive(s) connected:",
                    style = MaterialTheme.typography.bodySmall,
                )
                drives.forEach { d ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(d.label, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                d.uuid,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            val busy = syncRunning || verifyRunning
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { vm.sync() },
                    enabled = drives.isNotEmpty() && !busy && dcimOk,
                    modifier = Modifier.fillMaxWidth(0.5f),
                ) { Text("Sync") }
                OutlinedButton(
                    onClick = { vm.verify() },
                    enabled = drives.isNotEmpty() && !busy && dcimOk,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Check") }
            }

            if (syncRunning) {
                val info = latestSync
                val phase = info?.progress?.getString(MirrorWorker.KEY_PHASE).orEmpty()
                val done = info?.progress?.getInt(MirrorWorker.KEY_DONE, 0) ?: 0
                val total = info?.progress?.getInt(MirrorWorker.KEY_TOTAL, 0) ?: 0
                val idx = info?.progress?.getInt(MirrorWorker.KEY_DEST_INDEX, 0) ?: 0
                val of = info?.progress?.getInt(MirrorWorker.KEY_DEST_TOTAL, 0) ?: 0
                val label = info?.progress?.getString(MirrorWorker.KEY_DEST_LABEL).orEmpty()
                val current = info?.progress?.getString(MirrorWorker.KEY_CURRENT).orEmpty()

                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "Syncing $idx/$of — $label",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        LinearProgressIndicator(
                            progress = { if (total > 0) done.toFloat() / total else 0f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            buildString {
                                append(phase)
                                if (total > 0) append("  $done / $total")
                                if (current.isNotEmpty()) {
                                    append("  —  ")
                                    append(current.substringAfterLast('/'))
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (verifyRunning) {
                val info = latestVerify
                val phase = info?.progress?.getString(MirrorWorker.KEY_PHASE).orEmpty()
                val done = info?.progress?.getInt(MirrorWorker.KEY_DONE, 0) ?: 0
                val total = info?.progress?.getInt(MirrorWorker.KEY_TOTAL, 0) ?: 0
                val idx = info?.progress?.getInt(MirrorWorker.KEY_DEST_INDEX, 0) ?: 0
                val of = info?.progress?.getInt(MirrorWorker.KEY_DEST_TOTAL, 0) ?: 0
                val label = info?.progress?.getString(MirrorWorker.KEY_DEST_LABEL).orEmpty()
                val current = info?.progress?.getString(MirrorWorker.KEY_CURRENT).orEmpty()

                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "Checking $idx/$of — $label",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        LinearProgressIndicator(
                            progress = { if (total > 0) done.toFloat() / total else 0f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            buildString {
                                append(phase)
                                if (total > 0) append("  $done / $total")
                                if (current.isNotEmpty()) {
                                    append("  —  ")
                                    append(current.substringAfterLast('/'))
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Activity", style = MaterialTheme.typography.titleSmall)
                        if (notifications.isNotEmpty() && !busy) {
                            TextButton(onClick = vm::clearHistory) {
                                Text("Clear")
                            }
                        }
                    }

                    if (notifications.isEmpty()) {
                        Text(
                            "No mirror activity yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        notifications.forEachIndexed { index, n ->
                            if (index > 0) {
                                HorizontalDivider()
                            }
                            MirrorNotificationRow(n)
                        }
                    }
                }
            }

            OutlinedButton(
                onClick = { picker.launch(null) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            ) {
                Icon(Icons.Default.Add, null, Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Add drive (grant access)")
            }

            Text(
                "Check reads every file back and compares its hash. Files still on the phone are " +
                    "hashed directly; files deleted from the phone are hashed against the SUMS/ " +
                    "log in Mirror/. Media whose drive copy hasn't been indexed by Android yet " +
                    "reports as unverifiable — re-check later. Page cache means a check run " +
                    "seconds after sync only proves the write entered the page cache; fsync " +
                    "already guaranteed durability. A check run later reads disk.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MirrorNotificationRow(n: UserNotificationEntity) {
    val (icon, tint) = when (n.type) {
        NotificationType.SUCCESS ->
            Icons.Default.CheckCircle to MaterialTheme.colorScheme.primary
        NotificationType.ERROR ->
            Icons.Default.Error to MaterialTheme.colorScheme.error
        NotificationType.INFO ->
            Icons.Default.Info to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = tint)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(n.message, style = MaterialTheme.typography.bodyMedium)
            if (!n.detail.isNullOrBlank()) {
                Text(
                    n.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                formatDateTime(n.createdAt, "MMM d, HH:mm:ss"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}
