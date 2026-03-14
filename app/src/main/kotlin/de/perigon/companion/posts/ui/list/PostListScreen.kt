package de.perigon.companion.posts.ui.list

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import de.perigon.companion.core.ui.AppTopBar
import de.perigon.companion.core.ui.NotificationObserver
import de.perigon.companion.posts.data.PostEntity
import de.perigon.companion.posts.data.PostMediaEntity
import de.perigon.companion.posts.domain.PostPublishState

@Composable
fun PostListScreen(
    navController:    NavController,
    onNavigateToPost: (postId: Long) -> Unit,
    onNewPost:        () -> Unit,
    vm: PostListViewModel = hiltViewModel(),
) {
    val posts        by vm.posts.collectAsStateWithLifecycle()
    val leadMedia    by vm.leadMedia.collectAsStateWithLifecycle()
    val editPreviews by vm.editPreviews.collectAsStateWithLifecycle()
    val verifying    by vm.verifying.collectAsStateWithLifecycle()
    val context      = LocalContext.current
    val snackbarHost = remember { SnackbarHostState() }

    // Observe one-shot notifications from workers
    LaunchedEffect(Unit) {
        NotificationObserver.observe(this, vm.notifications, snackbarHost)
    }

    // Local snackbar events (verify results etc.)
    LaunchedEffect(Unit) {
        vm.snackbar.events.collect { snackbarHost.showSnackbar(it) }
    }

    // Collect share intents and fire them
    LaunchedEffect(Unit) {
        vm.shareIntents.collect { intent ->
            context.startActivity(intent)
        }
    }

    Scaffold(
        topBar           = { AppTopBar(navController) },
        snackbarHost     = { SnackbarHost(snackbarHost) },
        floatingActionButton = {
            FloatingActionButton(onClick = onNewPost) { Icon(Icons.Default.Add, "New post") }
        },
    ) { padding ->
        if (posts.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Article, null, modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("No posts yet", style = MaterialTheme.typography.titleMedium)
                    Text("Tap + to compose your first post",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                modifier       = Modifier.padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            ) {
                items(posts, key = { it.id }) { post ->
                    val lead = leadMedia[post.id]
                    PostRow(
                        post         = post,
                        lead         = lead,
                        editPreview  = lead?.let { editPreviews[it.id] },
                        resolveUri   = { entity -> vm.resolveMediaUri(entity) },
                        isVerifying  = post.id in verifying,
                        onClick      = { onNavigateToPost(post.id) },
                        onPublish    = { vm.publishPost(post.id) },
                        onUnpublish  = { vm.unpublishPost(post) },
                        onDelete     = { vm.deletePost(post) },
                        onVerify     = { vm.verifyPost(post) },
                        onShare      = { vm.buildShareIntent(post) },
                    )
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun PostRow(
    post:        PostEntity,
    lead:        PostMediaEntity?,
    editPreview: Bitmap?,
    resolveUri:  (PostMediaEntity) -> android.net.Uri,
    isVerifying: Boolean,
    onClick:     () -> Unit,
    onPublish:   () -> Unit,
    onUnpublish: () -> Unit,
    onDelete:    () -> Unit,
    onVerify:    () -> Unit,
    onShare:     () -> Unit,
) {
    val publishState = post.publishState

    var confirmPublish   by remember { mutableStateOf(false) }
    var confirmUnpublish by remember { mutableStateOf(false) }
    var confirmDelete    by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        if (lead != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(8.dp)),
            ) {
                if (editPreview != null) {
                    Image(
                        bitmap = editPreview.asImageBitmap(),
                        contentDescription = post.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    AsyncImage(
                        model              = resolveUri(lead),
                        contentDescription = post.title,
                        contentScale       = ContentScale.Crop,
                        modifier           = Modifier.fillMaxSize(),
                    )
                }
                if (post.motto.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Text(post.motto, color = Color.White,
                            style    = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically,
        ) {
            Text(post.localDate, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary)
            PublishBadge(state = publishState)
        }

        Spacer(Modifier.height(4.dp))
        Text(post.title.ifBlank { "Untitled" }, style = MaterialTheme.typography.titleSmall)

        if (post.teaser.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(post.teaser, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }

        val tags = post.tags.split(",").map { it.trim() }.filter { it.isNotBlank() }
        if (tags.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(tags.joinToString(" ") { "#$it" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary)
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment     = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { confirmDelete = true }) {
                Icon(Icons.Default.Delete, "Delete", Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.outline)
            }
            IconButton(onClick = onShare) {
                Icon(Icons.Default.Share, "Share", Modifier.size(20.dp))
            }
            if (publishState != PostPublishState.DRAFT) {
                IconButton(onClick = onVerify, enabled = !isVerifying) {
                    if (isVerifying) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.CloudSync, "Verify against GitHub", Modifier.size(20.dp))
                    }
                }
            }
            when (publishState) {
                PostPublishState.DRAFT -> {
                    FilledTonalButton(onClick = { confirmPublish = true }) {
                        Icon(Icons.Default.Publish, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Publish")
                    }
                }
                PostPublishState.QUEUED -> {
                    OutlinedButton(onClick = {}, enabled = false) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(4.dp))
                        Text("Queued")
                    }
                }
                PostPublishState.PUBLISHED -> {
                    OutlinedButton(onClick = { confirmUnpublish = true }) {
                        Icon(Icons.Default.Unpublished, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Unpublish")
                    }
                }
                PostPublishState.NEEDS_FIXING -> {
                    FilledTonalButton(
                        onClick = { confirmPublish = true },
                        colors  = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor   = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    ) {
                        Icon(Icons.Default.Publish, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Re-publish")
                    }
                    Spacer(Modifier.width(4.dp))
                    OutlinedButton(onClick = { confirmUnpublish = true }) {
                        Icon(Icons.Default.Unpublished, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Unpublish")
                    }
                }
            }
        }
    }

    if (confirmPublish) {
        AlertDialog(
            onDismissRequest = { confirmPublish = false },
            title         = { Text(if (publishState == PostPublishState.NEEDS_FIXING) "Re-publish post?" else "Publish post?") },
            text          = { Text("\"${post.title.ifBlank { "Untitled" }}\" will be queued for publishing to GitHub.") },
            confirmButton = { TextButton(onClick = { onPublish(); confirmPublish = false }) {
                Text(if (publishState == PostPublishState.NEEDS_FIXING) "Re-publish" else "Publish")
            }},
            dismissButton = { TextButton(onClick = { confirmPublish = false }) { Text("Cancel") } },
        )
    }
    if (confirmUnpublish) {
        AlertDialog(
            onDismissRequest = { confirmUnpublish = false },
            title         = { Text("Unpublish post?") },
            text          = { Text("\"${post.title.ifBlank { "Untitled" }}\" will be removed from GitHub and marked as draft.") },
            confirmButton = { TextButton(onClick = { onUnpublish(); confirmUnpublish = false }) { Text("Unpublish") } },
            dismissButton = { TextButton(onClick = { confirmUnpublish = false }) { Text("Cancel") } },
        )
    }
    if (confirmDelete) {
        val warning = if (post.publishState in listOf(
                PostPublishState.PUBLISHED, PostPublishState.NEEDS_FIXING))
            "This will also attempt to remove it from GitHub." else ""
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title         = { Text("Delete post?") },
            text          = { Text("\"${post.title.ifBlank { "Untitled" }}\" will be permanently deleted. $warning") },
            confirmButton = {
                TextButton(
                    onClick = { onDelete(); confirmDelete = false },
                    colors  = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PublishBadge(state: PostPublishState) {
    val (label, color) = when (state) {
        PostPublishState.DRAFT        -> "Draft"        to MaterialTheme.colorScheme.outline
        PostPublishState.QUEUED       -> "Queued"       to MaterialTheme.colorScheme.tertiary
        PostPublishState.PUBLISHED    -> "Published"    to MaterialTheme.colorScheme.primary
        PostPublishState.NEEDS_FIXING -> "Needs fixing" to MaterialTheme.colorScheme.error
    }
    Surface(color = color.copy(alpha = 0.12f), shape = MaterialTheme.shapes.small) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}
