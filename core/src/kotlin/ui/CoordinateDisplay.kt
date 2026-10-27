package de.perigon.companion.core.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
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
 * Formatted lat/lon with a format selector, copy and share buttons.
 *
 * Shared by the Track live card, the GNSS tab and the NMEA tab so a chip
 * tap in any surface updates all of them (all observe the same
 * `TrackPrefs` DataStore key), and so no two surfaces drift in formatting
 * or copy semantics.
 *
 * Colors are caller-supplied: pass the container's contentColor so the
 * text and chips read correctly on primary/secondary/surfaceVariant
 * parents.
 *
 * Deliberately no inner Surface. The previous version painted the widget
 * on a surfaceColor well — default surfaceVariant on the GNSS tab, a
 * 10 % overlay of contentColor on the Track live card. The two looked
 * different for reasons unrelated to the widget: the Track card sits on
 * primaryContainer and the GNSS card on surface, so the same default
 * well read differently in each place, and the caller-side overrides
 * drifted. The widget now sits flat and the caller owns the decision —
 * a parent that wants a distinct well adds one via
 * `modifier = Modifier.background(...)`; a parent that wants the widget
 * to read as part of the surrounding card does not.
 *
 * The format chips stay interactive when no fix is available so the user
 * can pre-select a format while the engine locks; copy and share stay
 * disabled until a fix arrives (nothing to act on).
 *
 * Copy writes the *displayed* string verbatim under the plain-text label
 * "coordinates"; share opens a geo: intent. Age and σ annotations, when
 * provided, appear as a small muted line under the value.
 */
@Composable
fun CoordinateDisplay(
    lat: Double?,
    lon: Double?,
    format: CoordFormat,
    onFormatChange: (CoordFormat) -> Unit,
    modifier: Modifier = Modifier,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    lastFixTimeMs: Long? = null,
    horizontalSigmaM: Double? = null,
    /**
     * True while the caller has an active session but no fix has arrived
     * yet. Distinguishes "waiting for TTFF" from "nothing to show" — the
     * widget renders "Acquiring GPS…" instead of "--". Ignored once [lat]
     * and [lon] are non-null.
     */
    acquiring: Boolean = false,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }

    val hasFix = lat != null && lon != null && lat.isFinite() && lon.isFinite()

    val text: String = when {
        hasFix -> when (format) {
            CoordFormat.DD        -> formatDd(lat, lon)
            CoordFormat.DDM       -> formatDdm(lat, lon)
            CoordFormat.DMS       -> formatDms(lat, lon)
            CoordFormat.UTM       -> formatUtm(lat, lon) ?: "Out of UTM range (±80°/84°)"
            CoordFormat.PLUS_CODE -> formatPlusCode(lat, lon)
        }
        acquiring -> "Acquiring GPS…"
        else -> "--"
    }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }

    // Age annotation. Two failure modes to avoid; both render as
    // "just now" and both are easy to reintroduce:
    //
    //   1. Wrong clock. Callers pass epoch millis (System.currentTimeMillis,
    //      Location.getTime, or UTCDateTime.toEpochMilli). The ticker must
    //      read the same clock. elapsedRealtime() is monotonic since boot
    //      (~1e6) vs epoch (~1.7e12) — the subtraction goes hugely
    //      negative and coerceAtLeast(0L) clamps it to zero.
    //
    //   2. Stale nowMs. A cached "now" updated only on a fixed ticker lags
    //      the fix stream: with fixes at 1 Hz, the newest lastFixTimeMs is
    //      after the cached nowMs, the subtraction goes negative, and the
    //      clamp wins again. Keying LaunchedEffect on lastFixTimeMs
    //      restarts the ticker per fix, so nowMs is refreshed in step with
    //      the value it is subtracted from. The 10 s delay handles the
    //      no-fix-arriving case at formatAgeShort's native resolution.
    // Long, not the boxed mutableStateOf: this ticks every 10 s for as long
    // as a fix is displayed, and each tick would otherwise allocate a
    // java.lang.Long for the snapshot system to write.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    if (lastFixTimeMs != null) {
        LaunchedEffect(lastFixTimeMs) {
            while (true) {
                nowMs = System.currentTimeMillis()
                delay(10_000L)
            }
        }
    }
    val ageText: String? = if (lastFixTimeMs != null && hasFix) {
        formatAgeShort((nowMs - lastFixTimeMs).coerceAtLeast(0L))
    } else null
    val annotation: String? = listOfNotNull(
        horizontalSigmaM?.let { "±%.1f m".format(it) },
        ageText,
    ).takeIf { it.isNotEmpty() }?.joinToString(" · ")

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

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text,
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = contentColor,
                    maxLines = 2,
                )
                if (annotation != null) {
                    Text(
                        annotation,
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.55f),
                        maxLines = 1,
                    )
                }
            }
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
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                    contentDescription = if (copied) "Copied" else "Copy coordinates",
                    tint = when {
                        !hasFix -> contentColor.copy(alpha = 0.3f)
                        copied  -> contentColor
                        else    -> contentColor.copy(alpha = 0.7f)
                    },
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(
                onClick = { if (hasFix) context.shareAsGeo(lat, lon) },
                enabled = hasFix,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = "Share position",
                    tint = if (hasFix) contentColor.copy(alpha = 0.7f)
                           else contentColor.copy(alpha = 0.3f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

private fun Context.shareAsGeo(lat: Double, lon: Double) {
    startActivity(
        Intent.createChooser(
            Intent(Intent.ACTION_VIEW, "geo:$lat,$lon?z=16".toUri()).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            "Open location",
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}
