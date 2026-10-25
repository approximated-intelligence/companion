package de.perigon.companion.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import de.perigon.companion.core.geo.CoordFormat
import de.perigon.companion.core.geo.formatDd
import de.perigon.companion.core.geo.formatDdm
import de.perigon.companion.core.geo.formatDms
import de.perigon.companion.core.geo.formatPlusCode
import de.perigon.companion.core.geo.formatUtm
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val CoordFormat.label: String
    get() = when (this) {
        CoordFormat.DD        -> "DD"
        CoordFormat.DDM       -> "DDM"
        CoordFormat.DMS       -> "DMS"
        CoordFormat.UTM       -> "UTM"
        CoordFormat.PLUS_CODE -> "Plus"
    }

/**
 * Formatted lat/lon with a format selector and a copy-to-clipboard button.
 *
 * Shared by the GNSS tab and the Track live card so a chip tap in either
 * surface updates the other (both observe the same `TrackPrefs` DataStore
 * key), and so the two surfaces cannot drift in formatting or copy
 * semantics.
 *
 * Colors are caller-supplied. On the GNSS tab the defaults (onSurface /
 * surfaceVariant) are correct. On the Track live card the container is
 * `primaryContainer` / `secondaryContainer` — passing the card's
 * `contentColor` and a 10 % overlay of it keeps the widget anchored to the
 * container's own palette rather than fighting it.
 *
 * The format chips stay interactive when no fix is available so the user
 * can pre-select a format while the engine locks; the copy button stays
 * disabled until a fix arrives (nothing to copy).
 *
 * Copy writes the *displayed* string verbatim under the plain-text label
 * "coordinates", so pasting into a map app or a message preserves exactly
 * what the user saw.
 */
@Composable
fun CoordinateDisplay(
    lat: Double?,
    lon: Double?,
    format: CoordFormat,
    onFormatChange: (CoordFormat) -> Unit,
    modifier: Modifier = Modifier,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    surfaceColor: Color = MaterialTheme.colorScheme.surfaceVariant,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }

    val hasFix = lat != null && lon != null && lat.isFinite() && lon.isFinite()

    val text: String = if (hasFix) {
        when (format) {
            CoordFormat.DD        -> formatDd(lat, lon)
            CoordFormat.DDM       -> formatDdm(lat, lon)
            CoordFormat.DMS       -> formatDms(lat, lon)
            CoordFormat.UTM       -> formatUtm(lat, lon) ?: "Out of UTM range (±80°/84°)"
            CoordFormat.PLUS_CODE -> formatPlusCode(lat, lon)
        }
    } else {
        "--"
    }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            CoordFormat.entries.forEach { f ->
                FilterChip(
                    selected = format == f,
                    onClick = { onFormatChange(f) },
                    label = { Text(f.label) },
                    colors = FilterChipDefaults.filterChipColors(
                        labelColor = contentColor.copy(alpha = 0.8f),
                        selectedLabelColor = contentColor,
                        containerColor = Color.Transparent,
                        selectedContainerColor = contentColor.copy(alpha = 0.15f),
                    ),
                )
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = surfaceColor,
            shape = MaterialTheme.shapes.small,
        ) {
            Row(
                modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = FontFamily.Monospace,
                    color = contentColor,
                )
                IconButton(
                    onClick = {
                        if (hasFix) {
                            scope.launch {
                                clipboard.setClipEntry(
                                    ClipEntry(android.content.ClipData.newPlainText("coordinates", text))
                                )
                            }
                            copied = true
                        }
                    },
                    enabled = hasFix,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                        contentDescription = if (copied) "Copied" else "Copy coordinates",
                        tint = when {
                            !hasFix -> contentColor.copy(alpha = 0.3f)
                            copied  -> contentColor
                            else    -> contentColor.copy(alpha = 0.7f)
                        },
                    )
                }
            }
        }
    }
}