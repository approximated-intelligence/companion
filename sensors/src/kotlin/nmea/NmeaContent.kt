package de.perigon.companion.sensors.nmea

import android.location.Location
import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.perigon.companion.core.ui.access.RequireAccess
import de.perigon.companion.sensors.SensorsRequirements
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * NMEA tab content. Four sections (design §7):
 *   1. Sentence-type status chips
 *   2. Parsed-values table
 *   3. Android Location comparison
 *   4. Filter chips + raw sentence log
 *
 * Lifecycle: [LifecycleResumeEffect] acquires the stream on resume and
 * releases on pause/dispose. The [RequireAccess] gate shows the access
 * panel when location permission is missing; its inner `LaunchedEffect`
 * re-invokes `vm.start()` (idempotent) so granting permission while the
 * tab is displayed starts the stream without needing a tab switch.
 */
@Composable
fun NmeaContent(vm: NmeaViewModel) {
    val parsed by vm.parsed.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val lastSeen by vm.lastSeen.collectAsStateWithLifecycle()
    val latestLoc by vm.latestLocation.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        vm.start()
        onPauseOrDispose { vm.stop() }
    }

    RequireAccess(
        requirements = listOf(SensorsRequirements.location),
        inline = true,
    ) {
        LaunchedEffect(Unit) { vm.start() }

        // Filter state is UI-scoped, session-only (design §7.3). Reset to
        // "All types" on tab reopen because the rememberSaveable keyed on
        // Unit is recreated when this composable is disposed (tab switch
        // out and back). If the user wants persistence within a tab visit,
        // they retain the filter — that's the intent.
        var filterTypes by rememberSaveable {
            mutableStateOf(PARSE_TYPES.toSet())
        }

        val filtered = remember(recent, filterTypes) {
            recent.filter { it.type in filterTypes }
        }

        // --- 7.4 auto-scroll ---
        // Only auto-scroll when the user is already at the bottom. Any
        // scroll gesture (even a tiny one) drops us out of auto-scroll
        // until the user returns to the bottom — the standard "tail -f
        // pauses when you scroll up" behaviour.
        val listState = rememberLazyListState()
        val atBottom by remember {
            androidx.compose.runtime.derivedStateOf {
                val info = listState.layoutInfo
                val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                last >= info.totalItemsCount - 1
            }
        }
        LaunchedEffect(filtered.size) {
            if (atBottom && filtered.isNotEmpty()) {
                listState.animateScrollToItem(filtered.size - 1)
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                top = 16.dp, bottom = 32.dp,
            ),
        ) {
            // ===== Section 7.1 — sentence-type status =====
            item { SentenceStatusSection(lastSeen) }

            // ===== Section 7.2 — parsed values =====
            item {
                ParsedValuesSection(
                    parsed = parsed,
                    lastSeen = lastSeen,
                )
            }
            item { AndroidLocationSection(latestLoc) }

            // ===== Section 7.3 — filter chips =====
            item { HorizontalDivider() }
            item {
                FilterChipsRow(
                    selected = filterTypes,
                    onToggle = { type ->
                        filterTypes = if (type in filterTypes) filterTypes - type
                                      else filterTypes + type
                    },
                    onSelectAll = { filterTypes = PARSE_TYPES.toSet() },
                )
            }

            // ===== Section 7.4 — raw log =====
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Raw log",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    CopyButton(filtered)
                }
            }

            if (filtered.isEmpty() && recent.isEmpty()) {
                item {
                    Text(
                        "This device is not emitting NMEA. GNSS Data Source " +
                            "and NMEA Recording will not be available.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            } else if (filtered.isEmpty()) {
                item {
                    Text(
                        "No sentences match the current filter.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(filtered, key = { "${it.wallMs}-${it.raw.hashCode()}" }) { s ->
                    SelectionContainer {
                        Text(
                            "${formatWallTime(s.wallMs)}  ${s.raw}",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                            ),
                            color = if (s is NmeaSentence.Corrupt)
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

// =============================================================================
// Section 7.1 — sentence-type status
// =============================================================================

/**
 * Fixed type list, in the design doc's example order (§7.1). Covers the
 * seven parse-set types plus GLL — the eight types whose presence the
 * tab is asking about. GSV and GLL are parsed only for type recognition,
 * so their chips are populated by [NmeaSentence.Other] arrivals; the
 * other six are populated by their typed variants.
 */
private val CHIP_TYPES = listOf(
    "GGA", "RMC", "GSA", "GSV",
    "VTG", "GST", "GNS", "GLL",
)

private val PARSE_TYPES = listOf(
    "GGA", "RMC", "GSA", "GSV", "VTG", "GST", "GNS",
)

@Composable
private fun SentenceStatusSection(lastSeen: Map<String, Long>) {
    // 1 Hz ticker to keep the elapsed labels fresh without a full
    // recomposition of the whole page. `produceState` is the standard
    // idiom for a UI-only clock.
    val now by produceState(initialValue = SystemClock.elapsedRealtime()) {
        while (true) {
            kotlinx.coroutines.delay(1_000L)
            value = SystemClock.elapsedRealtime()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Sentences", style = MaterialTheme.typography.titleSmall)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            CHIP_TYPES.forEach { type ->
                SentenceChip(type = type, lastSeenMs = lastSeen[type], now = now)
            }
        }
    }
}

@Composable
private fun SentenceChip(type: String, lastSeenMs: Long?, now: Long) {
    val (icon, tint, label) = when {
        lastSeenMs == null ->
            Triple(Icons.Default.Remove, MaterialTheme.colorScheme.outline, "--")
        now - lastSeenMs < 2_000L ->
            Triple(Icons.Default.Check, MaterialTheme.colorScheme.primary,
                "${(now - lastSeenMs) / 1000}s")
        now - lastSeenMs < 60_000L ->
            Triple(Icons.Default.Schedule, Color(0xFFB8860B),
                "${(now - lastSeenMs) / 1000}s")
        else ->
            Triple(Icons.Default.Remove, MaterialTheme.colorScheme.outline, "--")
    }

    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(type, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(4.dp))
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.height(14.dp))
            Spacer(Modifier.width(2.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = tint,
            )
        }
    }
}

// =============================================================================
// Section 7.2 — parsed values table
// =============================================================================

@Composable
private fun ParsedValuesSection(
    parsed: NmeaStream.ParsedSnapshot,
    lastSeen: Map<String, Long>,
) {
    // Only render the section if at least one parse-set type has arrived.
    // The per-row rule (design §7.2) is "only rendered when its backing
    // sentence has been seen at least once".
    if (PARSE_TYPES.none { it in lastSeen }) return

    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Parsed values", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))

            if ("GGA" in lastSeen || "RMC" in lastSeen || "GNS" in lastSeen) {
                ValueRow("UTC time", formatDateTime(parsed.datetime))
            }
            if ("GGA" in lastSeen || "RMC" in lastSeen || "GNS" in lastSeen) {
                ValueRow("Position",
                    parsed.position?.let { "%.6f, %.6f".format(Locale.US, it.lat, it.lon) }
                        ?: "--")
            }
            if ("GGA" in lastSeen || "GSA" in lastSeen) {
                ValueRow("Fix quality", fixQualityLabel(parsed.fixQuality, parsed.fixDimension))
            }
            if ("GGA" in lastSeen) {
                ValueRow("Satellites used", parsed.satellitesUsed?.toString() ?: "--")
                ValueRow("Altitude (MSL)",
                    parsed.altitudeMslM?.let { "%.2f m".format(Locale.US, it) } ?: "--")
                ValueRow("Geoid separation",
                    parsed.geoidSepM?.let { "%.1f m".format(Locale.US, it) } ?: "--")
            }
            if ("GGA" in lastSeen || "GSA" in lastSeen) {
                ValueRow("HDOP", parsed.hdop?.let { "%.2f".format(Locale.US, it) } ?: "--")
            }
            if ("GSA" in lastSeen) {
                ValueRow("VDOP", parsed.vdop?.let { "%.2f".format(Locale.US, it) } ?: "--")
                ValueRow("PDOP", parsed.pdop?.let { "%.2f".format(Locale.US, it) } ?: "--")
            }
            if ("RMC" in lastSeen || "VTG" in lastSeen) {
                ValueRow("Speed", speedLabel(parsed.speedKnots))
                ValueRow("Course",
                    parsed.courseDeg?.let { "%.1f°".format(Locale.US, it) } ?: "--")
            }
            if ("GST" in lastSeen) {
                ValueRow("Horizontal σ",
                    parsed.horizontalSigmaM?.let { "%.2f m".format(Locale.US, it) } ?: "--")
                ValueRow("Vertical σ",
                    parsed.verticalSigmaM?.let { "%.2f m".format(Locale.US, it) } ?: "--")
            }
        }
    }
}

@Composable
private fun ValueRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

// =============================================================================
// Section 7.2 (continued) — Android Location comparison
// =============================================================================

@Composable
private fun AndroidLocationSection(location: Location?) {
    if (location == null) return

    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "Android Location (for comparison)",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                "Values from the platform's most recent fix. Compare against the " +
                    "NMEA-derived fields above to spot HAL quantization and " +
                    "vertical-accuracy differences.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            ValueRow("Altitude",
                if (location.hasAltitude()) "%.1f m".format(Locale.US, location.altitude) else "--")
            ValueRow("Speed",
                if (location.hasSpeed()) "%.2f m/s".format(Locale.US, location.speed) else "--")
            ValueRow("Bearing",
                if (location.hasBearing()) "%.1f°".format(Locale.US, location.bearing) else "--")
            ValueRow("Horizontal accuracy",
                if (location.hasAccuracy()) "%.1f m".format(Locale.US, location.accuracy) else "--")
            ValueRow("Vertical accuracy",
                if (location.hasVerticalAccuracy())
                    "%.1f m".format(Locale.US, location.verticalAccuracyMeters)
                else "--")
        }
    }
}

// =============================================================================
// Section 7.3 — filter chips
// =============================================================================

@Composable
private fun FilterChipsRow(
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Filter log", style = MaterialTheme.typography.titleSmall)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FilterChip(
                selected = selected.size == PARSE_TYPES.size,
                onClick = onSelectAll,
                label = { Text("All") },
            )
            PARSE_TYPES.forEach { type ->
                FilterChip(
                    selected = type in selected,
                    onClick = { onToggle(type) },
                    label = { Text(type) },
                )
            }
        }
    }
}

// =============================================================================
// Section 7.4 — copy button
// =============================================================================

@Composable
private fun CopyButton(filtered: List<NmeaSentence>) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    IconButton(
        onClick = {
            if (filtered.isEmpty()) return@IconButton
            val text = filtered.joinToString("\n") { "${formatWallTime(it.wallMs)}  ${it.raw}" }
            scope.launch {
                clipboard.setClipEntry(
                    ClipEntry(android.content.ClipData.newPlainText("nmea_log", text))
                )
            }
        },
        enabled = filtered.isNotEmpty(),
    ) {
        Icon(Icons.Default.ContentCopy, contentDescription = "Copy filtered log")
    }
}

// =============================================================================
// Helpers
// =============================================================================

private val WALL_TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.US)

private fun formatWallTime(wallMs: Long): String =
    WALL_TIME.format(java.time.Instant.ofEpochMilli(wallMs).atZone(java.time.ZoneId.systemDefault()))

private fun formatDateTime(dt: LocalDateTime?): String {
    if (dt == null) return "--"
    return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US).format(dt)
}

private fun fixQualityLabel(ggaQuality: Int?, gsaDimension: Int?): String = when {
    ggaQuality == null && gsaDimension == null -> "--"
    ggaQuality == 0 -> "none"
    ggaQuality == 1 && gsaDimension == 2 -> "2D"
    ggaQuality == 1 && gsaDimension == 3 -> "3D"
    ggaQuality == 1 -> "GPS"
    ggaQuality == 2 -> "DGPS"
    ggaQuality == 3 -> "PPS"
    ggaQuality == 4 -> "RTK"
    ggaQuality == 5 -> "Float RTK"
    ggaQuality == 6 -> "Estimated"
    ggaQuality == 7 -> "Manual"
    ggaQuality == 8 -> "Simulation"
    ggaQuality != null -> "Q$ggaQuality"
    else -> "--"
}

private fun speedLabel(knots: Double?): String {
    if (knots == null) return "--"
    val mps = knots * 0.514444
    val kmh = mps * 3.6
    return "%.2f m/s (%.1f km/h)".format(Locale.US, mps, kmh)
}
