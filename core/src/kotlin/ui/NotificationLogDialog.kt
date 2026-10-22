package de.perigon.companion.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.perigon.companion.core.data.NotificationType
import de.perigon.companion.core.data.UserNotificationDao
import de.perigon.companion.core.data.UserNotificationEntity
import kotlinx.coroutines.launch

/**
 * Full per-feature history, backed by the `user_notifications` table.
 *
 * Most callers pass a single [sources] entry — the feature's own source —
 * so the dialog shows exactly that feature's log. The Archive screen passes
 * both `"consolidate"` and `"backup"`, because consolidation and backup can
 * be triggered from one surface and the user needs a merged view; splitting
 * them would hide exactly the history that explains a bad run.
 *
 * A LazyColumn inside an AlertDialog is a known trap — it can starve the
 * confirm/dismiss buttons of height (the dialog measures the list at its
 * intrinsic size and the buttons fall off the bottom). The fix is to use a
 * bare [Dialog] with a Column, put the list at weight(1f, fill = false) inside
 * a bounded heightIn, and keep the buttons outside the list.
 *
 * Clear deletes only rows whose `source` is in [sources]; other features'
 * logs are untouched. The running worker's newly-posted rows continue to
 * appear; the dialog does not try to suppress them.
 */
@Composable
fun NotificationLogDialog(
    title: String,
    sources: List<String>,
    dao: UserNotificationDao,
    onDismiss: () -> Unit,
    limit: Int = 100,
) {
    val rows by dao.observeRecentBySources(sources, limit)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var confirmClear by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(20.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (rows.isNotEmpty()) {
                        TextButton(onClick = { confirmClear = true }) {
                            Text("Clear")
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))

                if (rows.isEmpty()) {
                    Text(
                        "Nothing logged yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .heightIn(max = 480.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(rows, key = { it.id }) { row ->
                            NotificationLogRow(row)
                        }
                    }
                }

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End),
                ) { Text("Close") }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear this log?") },
            text = {
                Text(
                    "Removes every entry written by " +
                        (if (sources.size == 1) "this feature." else "these features.") +
                        " Other features' logs are untouched."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch { dao.deleteAllForSources(sources) }
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun NotificationLogRow(n: UserNotificationEntity) {
    val (icon, tint) = when (n.type) {
        NotificationType.SUCCESS ->
            Icons.Default.CheckCircle to MaterialTheme.colorScheme.primary
        NotificationType.ERROR ->
            Icons.Default.Error to MaterialTheme.colorScheme.error
        NotificationType.INFO ->
            Icons.Default.Info to MaterialTheme.colorScheme.onSurfaceVariant
    }

    val detail = n.detail

    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = tint)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(n.message, style = MaterialTheme.typography.bodyMedium)
            if (!detail.isNullOrBlank()) {
                Text(
                    detail,
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
