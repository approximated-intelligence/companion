package de.perigon.companion.archive.backup.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import dagger.hilt.android.lifecycle.HiltViewModel
import de.perigon.companion.archive.data.BackupFolderEntity
import de.perigon.companion.archive.data.ArchiveStateRepository
import de.perigon.companion.core.ui.settings.SettingsRoute
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Compact read-out of the current backup sources, with a deep link to the
 * settings section that edits them. The card exists so the Consolidate
 * screen — where backups are triggered — shows *what* is about to be backed
 * up without duplicating the folder picker here. Editing stays in Settings,
 * where the folder picker already lives next to its siblings (schedule, B2
 * credentials); this card is the one-tap path to that section.
 */
@Composable
fun BackupSourcesCard(
    navController: NavController,
    modifier: Modifier = Modifier,
    vm: BackupSourcesCardViewModel = hiltViewModel(),
) {
    val folders by vm.folders.collectAsStateWithLifecycle()

    val summary = summarizeSources(folders)
    val hasAny = folders.any { it.includeInBackup }

    Card(modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Default.Folder, null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(Modifier.weight(1f)) {
                Text("Backup sources", style = MaterialTheme.typography.titleSmall)
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (hasAny) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            OutlinedButton(
                onClick = {
                    navController.navigate(SettingsRoute(anchor = "backup_sources")) {
                        launchSingleTop = true
                    }
                },
            ) {
                Icon(Icons.Default.Edit, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Edit")
            }
        }
    }
}

/** Pure: one-line "Sources: DCIM, Camera roll +1 more" summary. */
private fun summarizeSources(folders: List<BackupFolderEntity>): String {
    val enabled = folders.filter { it.includeInBackup }.map { it.displayName }
    return when {
        enabled.isEmpty() -> "None enabled — backups will be empty"
        enabled.size <= 2 -> enabled.joinToString(", ")
        else -> "${enabled.take(2).joinToString(", ")} +${enabled.size - 2} more"
    }
}

@HiltViewModel
class BackupSourcesCardViewModel @Inject constructor(
    stateRepo: ArchiveStateRepository,
) : ViewModel() {
    val folders: StateFlow<List<BackupFolderEntity>> = stateRepo.observeFolders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
