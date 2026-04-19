package de.perigon.companion.posts.site.ui.list

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import de.perigon.companion.posts.site.data.AssetEntity
import de.perigon.companion.posts.site.data.AssetSyncState
import de.perigon.companion.core.ui.AppTopBar
import de.perigon.companion.core.ui.Route

@Composable
fun AssetListScreen(
    navController: NavController,
    vm: AssetListViewModel = hiltViewModel(),
) {
    val state        by vm.state.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        vm.snackbar.events.collect { snackbarHost.showSnackbar(it) }
    }

    Scaffold(
        topBar       = { AppTopBar(navController) },
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                Text("Site assets", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f))
                FilledTonalButton(
                    onClick  = vm::reseedFromBundled,
                    enabled  = !state.isReseeding,
                ) {
                    Icon(Icons.Default.RestartAlt, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Bundled")
                }
                FilledTonalButton(
                    onClick  = vm::fetchFromServer,
                    enabled  = !state.isFetching,
                ) {
                    if (state.isFetching) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                    } else {
                        Icon(Icons.Default.Sync, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text("Server")
                }
            }

            HorizontalDivider()

            LazyColumn(
                contentPadding      = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(state.assets, key = { it.id }) { asset ->
                    AssetRow(
                        asset    = asset,
                        onEdit   = { navController.navigate(Route.AssetEdit(asset.id)) },
                        onDiff   = { navController.navigate(Route.AssetDiff(asset.id)) },
                        onPush   = { vm.pushToServer(asset) },
                        onPull   = { vm.pullFromServer(asset) },
                        onDelete = { vm.deleteFromServer(asset) },
                    )
                }
            }
        }
    }
}

private fun canPull(asset: AssetEntity): Boolean {
    if (asset.serverSha.isEmpty()) return false
    return asset.syncState in listOf(
        AssetSyncState.SERVER_AHEAD,
        AssetSyncState.SERVER_ONLY,
        AssetSyncState.CONFLICT,
    ) || (!asset.isOnDisk && asset.content.isEmpty())
}

private fun canDiff(asset: AssetEntity): Boolean {
    if (asset.isOnDisk) return false
    if (asset.content.isEmpty()) return false
    return asset.syncState in listOf(
        AssetSyncState.LOCAL_AHEAD,
        AssetSyncState.SERVER_AHEAD,
        AssetSyncState.CONFLICT,
    )
}

private fun canEdit(asset: AssetEntity): Boolean = !asset.isOnDisk

private fun canPush(asset: AssetEntity): Boolean {
    if (asset.isOnDisk) {
        return asset.syncState in listOf(
            AssetSyncState.LOCAL_AHEAD,
            AssetSyncState.LOCAL_ONLY,
            AssetSyncState.CONFLICT,
        )
    }
    return when (asset.syncState) {
        AssetSyncState.LOCAL_AHEAD,
        AssetSyncState.LOCAL_ONLY -> asset.content.isNotEmpty()
        AssetSyncState.CONFLICT -> asset.content.isNotEmpty()
        else -> false
    }
}

@Composable
private fun AssetRow(
    asset:    AssetEntity,
    onEdit:   () -> Unit,
    onDiff:   () -> Unit,
    onPush:   () -> Unit,
    onPull:   () -> Unit,
    onDelete: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (asset.isOnDisk) {
                        Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.outline)
                    }
                    Text(
                        asset.path,
                        style    = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                SyncStateBadge(asset)
            }

            if (canEdit(asset)) {
                IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Edit, "Edit", Modifier.size(18.dp))
                }
            }

            if (canDiff(asset)) {
                IconButton(onClick = onDiff, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Difference, "Diff", Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.tertiary)
                }
            }

            if (canPull(asset)) {
                IconButton(onClick = onPull, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Download, "Pull", Modifier.size(18.dp))
                }
            }

            if (canPush(asset)) {
                IconButton(onClick = onPush, modifier = Modifier.size(36.dp)) {
                    val tint = if (asset.syncState == AssetSyncState.CONFLICT)
                        MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    Icon(Icons.Default.Upload, "Push", Modifier.size(18.dp), tint = tint)
                }
            }

            if (asset.syncState == AssetSyncState.IN_SYNC && !asset.isOnDisk) {
                IconButton(
                    onClick  = { confirmDelete = true },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(Icons.Default.DeleteOutline, "Delete from server",
                        Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }

    HorizontalDivider(Modifier.padding(horizontal = 16.dp))

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title   = { Text("Delete from server?") },
            text    = { Text("\"${asset.path}\" will be deleted from GitHub. Local copy is kept.") },
            confirmButton = {
                TextButton(
                    onClick = { onDelete(); confirmDelete = false },
                    colors  = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SyncStateBadge(asset: AssetEntity) {
    val (label, color) = when {
        !asset.isOnDisk && asset.content.isEmpty() && asset.serverSha.isNotEmpty() ->
            "Empty - pull from server" to MaterialTheme.colorScheme.error
        !asset.isOnDisk && asset.content.isEmpty() ->
            "Empty - not bundled" to MaterialTheme.colorScheme.error
        else -> when (asset.syncState) {
            AssetSyncState.IN_SYNC      -> "Synced"          to MaterialTheme.colorScheme.primary
            AssetSyncState.LOCAL_AHEAD  -> "Local changes"   to MaterialTheme.colorScheme.tertiary
            AssetSyncState.SERVER_AHEAD -> "Server changed"  to MaterialTheme.colorScheme.secondary
            AssetSyncState.CONFLICT     -> "Conflict"        to MaterialTheme.colorScheme.error
            AssetSyncState.LOCAL_ONLY   -> "Local only"      to MaterialTheme.colorScheme.tertiary
            AssetSyncState.SERVER_ONLY  -> "Server only"     to MaterialTheme.colorScheme.secondary
        }
    }
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = color,
    )
}
