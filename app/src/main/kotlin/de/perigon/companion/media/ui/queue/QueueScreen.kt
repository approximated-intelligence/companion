package de.perigon.companion.media.ui.queue

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import de.perigon.companion.media.data.TransformJobEntity
import de.perigon.companion.media.data.TransformJobStatus
import de.perigon.companion.core.ui.AppTopBar
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.perigon.companion.media.data.TransformQueue
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Composable
fun QueueScreen(
    navController: NavController,
    vm: QueueViewModel = hiltViewModel(),
) {
    val jobs by vm.jobs.collectAsStateWithLifecycle()

    val running  = jobs.count { it.status == TransformJobStatus.RUNNING }
    val pending  = jobs.count { it.status == TransformJobStatus.PENDING }
    val done     = jobs.count { it.status == TransformJobStatus.DONE }
    val failed   = jobs.count { it.status == TransformJobStatus.FAILED }

    Scaffold(
        topBar = { AppTopBar(navController) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (jobs.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.CheckCircle, null, Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Queue empty", style = MaterialTheme.typography.titleMedium)
                    }
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        buildString {
                            if (running > 0) append("$running running")
                            if (pending > 0) {
                                if (isNotEmpty()) append(" · ")
                                append("$pending pending")
                            }
                            if (done > 0) {
                                if (isNotEmpty()) append(" · ")
                                append("$done done")
                            }
                            if (failed > 0) {
                                if (isNotEmpty()) append(" · ")
                                append("$failed failed")
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (done > 0 || failed > 0) {
                        TextButton(onClick = vm::clearFinished) {
                            Text("Clear finished")
                        }
                    }
                }

                HorizontalDivider()

                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(jobs, key = { it.id }) { job ->
                        JobRow(
                            job = job,
                            onCancel = { vm.cancel(job.id) },
                            onRemove = { vm.remove(job.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun JobRow(
    job: TransformJobEntity,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = when (job.mediaType) {
                "VIDEO" -> Icons.Default.Videocam
                else -> Icons.Default.Image
            },
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Column(Modifier.weight(1f)) {
            Text(
                job.displayName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusBadge(job.status)
                if (job.callerTag.isNotBlank()) {
                    Text(
                        job.callerTag,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                if (job.status == TransformJobStatus.FAILED && job.error != null) {
                    Text(
                        job.error,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        when (job.status) {
            TransformJobStatus.RUNNING -> {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            TransformJobStatus.PENDING -> {
                IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, "Cancel", Modifier.size(18.dp))
                }
            }
            TransformJobStatus.DONE, TransformJobStatus.FAILED -> {
                IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Delete, "Remove", Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(status: TransformJobStatus) {
    val (label, color) = when (status) {
        TransformJobStatus.PENDING -> "Pending" to MaterialTheme.colorScheme.outline
        TransformJobStatus.RUNNING -> "Running" to MaterialTheme.colorScheme.tertiary
        TransformJobStatus.DONE    -> "Done"    to MaterialTheme.colorScheme.primary
        TransformJobStatus.FAILED  -> "Failed"  to MaterialTheme.colorScheme.error
    }
    Surface(color = color.copy(alpha = 0.12f), shape = MaterialTheme.shapes.small) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

// --- Merged from QueueViewModel.kt ---

@HiltViewModel
class QueueViewModel @Inject constructor(
    private val queue: TransformQueue,
) : ViewModel() {

    val jobs: StateFlow<List<TransformJobEntity>> = queue.jobs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun cancel(jobId: Long) {
        viewModelScope.launch { queue.cancel(jobId) }
    }

    fun remove(jobId: Long) {
        viewModelScope.launch { queue.remove(jobId) }
    }

    fun clearFinished() {
        viewModelScope.launch { queue.clearFinished() }
    }
}

