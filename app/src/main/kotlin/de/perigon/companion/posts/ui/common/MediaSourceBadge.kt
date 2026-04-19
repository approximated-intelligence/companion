package de.perigon.companion.posts.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import de.perigon.companion.posts.data.MediaSourceStatus

/**
 * Overlay indicating where a thumbnail is sourced from.
 * Fills its parent. For ORIGINAL/POST_MEDIA shows a small badge bottom-end.
 * For GONE fills the area with a dark background and prominent broken-image icon
 * so that sibling overlays (close button, drag handle) remain visible.
 */
@Composable
fun MediaSourceBadge(
    status: MediaSourceStatus,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        when (status) {
            MediaSourceStatus.GONE -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.BrokenImage,
                        contentDescription = "File missing",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(32.dp),
                    )
                }
            }
            else -> {
                val (icon, tint) = when (status) {
                    MediaSourceStatus.ORIGINAL -> Icons.Default.CameraAlt to Color.White
                    MediaSourceStatus.POST_MEDIA -> Icons.Default.Inventory2 to MaterialTheme.colorScheme.primary
                    else -> return
                }
                Surface(
                    color = Color.Black.copy(alpha = 0.5f),
                    shape = MaterialTheme.shapes.extraSmall,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
                ) {
                    Icon(
                        icon, contentDescription = status.name,
                        tint = tint,
                        modifier = Modifier.padding(2.dp).size(12.dp),
                    )
                }
            }
        }
    }
}
