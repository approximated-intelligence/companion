package de.perigon.companion.sensors.ui.dashboard

import android.location.Location
import android.os.SystemClock
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.perigon.companion.core.ui.access.RequireAccess
import de.perigon.companion.sensors.SensorsRequirements
import de.perigon.companion.sensors.gnss.SatelliteObservation
import de.perigon.companion.sensors.gnss.UTCDateTime
import de.perigon.companion.sensors.gnss.sbasPrnFromNmeaId
import de.perigon.companion.sensors.nmea.NmeaSentence
import de.perigon.companion.sensors.nmea.NmeaStream
import de.perigon.companion.sensors.nmea.modeIndicatorLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Sentence types shown as status chips. Includes types the parser
 * recognises but does not extract fields for. The chip row is the
 * diagnostic value of this tab — it answers "what does the chipset
 * actually emit?" in one glance.
 */
private val SENTENCE_TYPES = listOf(
    "GGA", "RMC", "GSA", "GSV", "VTG", "GST", "GNS", "GLL",
)

/**
 * NMEA tab. Debug surface for the NMEA stream:
 *   - per-sentence-type status chips
 *   - full parsed-values table (all fields the parser extracts)
 *   - satellite table (from GSV, annotated with GSA active set)
 *   - SBAS status
 *   - Android Location comparison
 *   - raw log with filter, freeze, last-cycle, and truncate toggles
 *
 * The tab is orthogonal to the middleware's source toggle — it always
 * observes `NmeaStream` directly, because it exists to answer "what does
 * the chip actually emit". Consumers of the merged `GnssFix` (Track tab,
 * GNSS tab, KF tab) do not use this tab's data.
 *
 * The raw log is rendered as a single text window: one Text node holding
 * all lines joined by newlines, inside a Box with both vertical and
 * horizontal scroll. That is what makes horizontal scrolling move the
 * whole viewport instead of individual lines, and what eliminates the
 * inter-item padding that used to appear as visual "empty lines".
 */
@Composable
fun NmeaContent(vm: NmeaViewModel) {
    val parsed by vm.parsed.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val lastCycle by vm.lastCycleSentences.collectAsStateWithLifecycle()
    val lastSeen by vm.lastSeen.collectAsStateWithLifecycle()
    val latestLoc by vm.latestLocation.collectAsStateWithLifecycle()
    val requestGps by vm.requestGpsEnabled.collectAsStateWithLifecycle()
    val streamState by vm.streamState.collectAsStateWithLifecycle()
    val engineError by vm.engineError.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        vm.start()
        onPauseOrDispose { vm.stop() }
    }

    RequireAccess(
        requirements = listOf(SensorsRequirements.location),
        inline = true,
    ) {
        LaunchedEffect(Unit) { vm.start() }

        var filterTypes by rememberSaveable { mutableStateOf(SENTENCE_TYPES.toSet()) }
        var showLastCycle by rememberSaveable { mutableStateOf(false) }
        var isFrozen by rememberSaveable { mutableStateOf(false) }
        var truncateLines by rememberSaveable { mutableStateOf(false) }

        var frozenSourceList by remember { mutableStateOf<List<NmeaSentence>>(emptyList()) }

        val liveList = if (showLastCycle) lastCycle else recent

        // Freeze: capture the live list when the toggle turns on, and
        // re-capture when the underlying source swaps while frozen. This
        // lets the user switch between Freeze and Last-Cycle independently.
        LaunchedEffect(isFrozen, showLastCycle) {
            if (isFrozen) frozenSourceList = liveList
        }

        val source = if (isFrozen) frozenSourceList else liveList
        val displayList = remember(source, filterTypes) {
            source.filter { it.type in filterTypes && it.raw.isNotBlank() }
        }

        // A single string for the text window. Joining here is what
        // eliminates per-item padding (the previous LazyColumn inserted
        // Arrangement.spacedBy(12.dp) between each item, which read as
        // "empty lines" between sentences).
        val displayText = remember(displayList) {
            displayList.joinToString("\n") { it.raw }
        }

        val textWindowVerticalScroll = rememberScrollState()
        val textWindowHorizontalScroll = rememberScrollState()
        val listState = rememberLazyListState()

        // Auto-scroll the text window to bottom when new sentences arrive
        // unless the user has frozen the view. This is the standard
        // "tail -f pauses when you scroll up" semantic, via Freeze.
        LaunchedEffect(displayText, isFrozen) {
            if (!isFrozen) {
                textWindowVerticalScroll.scrollTo(textWindowVerticalScroll.maxValue)
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp),
        ) {
            val errorMsg = when (val s = streamState) {
                is NmeaStream.State.Error -> s.message
                else -> engineError
            }
            if (errorMsg != null) item { ErrorBanner(errorMsg) }

            item { EngineControl(requestGps, vm::setRequestGpsEnabled) }
            item { StatusChips(lastSeen) }
            item { ParsedValues(parsed, lastSeen) }
            item { SatelliteSection(parsed.satellites) }
            item { SbasSection(parsed.satellites, parsed.sbasActive) }
            item { LocationComparison(latestLoc) }

            item { HorizontalDivider() }
            item {
                FilterChips(filterTypes) { type ->
                    filterTypes = if (type in filterTypes) filterTypes - type else filterTypes + type
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = showLastCycle,
                            onClick = { showLastCycle = !showLastCycle },
                            label = { Text("Last Cycle") }
                        )
                        FilterChip(
                            selected = isFrozen,
                            onClick = { isFrozen = !isFrozen },
                            label = { Text("Freeze") }
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Truncate lines (disable soft wrap)",
                            style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = truncateLines,
                            onCheckedChange = { truncateLines = it }
                        )
                    }
                }
            }

            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Raw log", style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f))
                    CopyButton(displayList)
                }
            }

            // The text window. One Text node, one scroll state for each
            // axis — horizontal swipes move every line together, not just
            // the current one.
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(400.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant,
                            MaterialTheme.shapes.small)
                        .verticalScroll(textWindowVerticalScroll)
                        .horizontalScroll(textWindowHorizontalScroll)
                        .padding(8.dp)
                ) {
                    if (displayText.isEmpty()) {
                        Text(
                            "No NMEA sentences. Ensure GPS is enabled and 'Request GPS " +
                                "updates' is on (or track recording is active to keep the " +
                                "engine warm).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        SelectionContainer {
                            Text(
                                text = displayText,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                softWrap = !truncateLines,
                                overflow = TextOverflow.Clip,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---- Error + engine control ----

@Composable
private fun ErrorBanner(message: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun EngineControl(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Request GPS updates", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "NMEA sentences are produced by the GNSS engine, which runs only " +
                            "while something requests location updates. Keep this on so " +
                            "the tab receives data. Turn off only when another consumer " +
                            "(e.g. track recording) is keeping the engine warm.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = enabled, onCheckedChange = onToggle)
            }
        }
    }
}

// ---- Status chips ----

/**
 * Per-sentence-type arrival chips. The 1 Hz ticker keeps the elapsed
 * labels fresh without a full recomposition of the whole page.
 */
@Composable
private fun StatusChips(lastSeen: Map<String, Long>) {
    val now by produceState(initialValue = SystemClock.elapsedRealtime()) {
        while (true) { delay(1_000L); value = SystemClock.elapsedRealtime() }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Sentences", style = MaterialTheme.typography.titleSmall)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SENTENCE_TYPES.forEach { SentenceChip(it, lastSeen[it], now) }
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
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(type, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(4.dp))
            Icon(icon, null, tint = tint, modifier = Modifier.height(14.dp))
            Spacer(Modifier.width(2.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
        }
    }
}

// ---- Parsed values ----

@Composable
private fun ParsedValues(p: NmeaStream.ParsedSnapshot, lastSeen: Map<String, Long>) {
    if (SENTENCE_TYPES.none { it in lastSeen }) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Parsed values", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            if (lastSeen.any { it.key in setOf("GGA", "RMC", "GNS", "GLL") }) {
                ValueRow("UTC", formatUtcDateTime(p.timestamp))
                ValueRow("Position",
                    p.position?.let { "%.7f, %.7f".format(Locale.US, it.lat, it.lon) } ?: "--")
            }
            ValueRow("Fix quality", p.fixQuality?.label ?: "--")
            ValueRow("Fix dimension", p.fixDimension?.label ?: "--")
            ValueRow("Satellites used", p.satellitesUsed?.toString() ?: "--")
            ValueRow("Altitude (MSL)",
                p.altitudeMslM?.let { "%.2f m".format(Locale.US, it) } ?: "--")
            ValueRow("Geoid separation",
                p.geoidSepM?.let { "%.2f m".format(Locale.US, it) } ?: "--")
            ValueRow("Altitude (WGS84)",
                p.altitudeWgs84M?.let { "%.2f m".format(Locale.US, it) } ?: "--")
            ValueRow("HDOP", p.hdop?.let { "%.2f".format(Locale.US, it) } ?: "--")
            ValueRow("VDOP", p.vdop?.let { "%.2f".format(Locale.US, it) } ?: "--")
            ValueRow("PDOP", p.pdop?.let { "%.2f".format(Locale.US, it) } ?: "--")
            ValueRow("Speed",
                p.speedMps?.let { "%.2f m/s (%.1f km/h)".format(Locale.US, it, it * 3.6) } ?: "--")
            ValueRow("Course", p.courseDeg?.let { "%.1f°".format(Locale.US, it) } ?: "--")
            ValueRow("Magnetic variation",
                p.magVarDeg?.let { "%+.1f°".format(Locale.US, it) } ?: "--")
            ValueRow("Horizontal σ (GST)",
                p.horizontalSigmaM?.let { "%.2f m".format(Locale.US, it) } ?: "--")
            ValueRow("Vertical σ (GST)",
                p.verticalSigmaM?.let { "%.2f m".format(Locale.US, it) } ?: "--")
            ValueRow("Differential age",
                p.diffAgeS?.let { "%.1f s".format(Locale.US, it) } ?: "--")
            ValueRow("Differential station", p.diffStationId?.toString() ?: "--")
            p.rmcModeIndicator?.let { ValueRow("RMC mode", modeIndicatorLabel(it)) }
            p.vtgModeIndicator?.let { ValueRow("VTG mode", modeIndicatorLabel(it)) }
            p.gllModeIndicator?.let { ValueRow("GLL mode", modeIndicatorLabel(it)) }
            p.gnsModeIndicators?.let { ValueRow("GNS mode", it) }
            p.gsaSystemId?.let { ValueRow("GSA system", gsaSystemIdLabel(it)) }
            p.lastGsvSignalId?.let { ValueRow("GSV signal", "ID $it") }
        }
    }
}

@Composable
private fun ValueRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

// ---- Satellites ----

@Composable
private fun SatelliteSection(sats: List<SatelliteObservation>) {
    if (sats.isEmpty()) return
    val byConst = sats.groupBy { it.constellation }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Satellites in view (GSV)", style = MaterialTheme.typography.titleSmall)
            byConst.forEach { (c, list) ->
                Text(
                    "${c.label} — ${list.size} satellite(s), ${list.count { it.usedInFix }} used",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                list.forEach { s ->
                    Row(Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("PRN ${s.prn}", style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.width(64.dp))
                        Text("el ${s.elevationDeg?.toString() ?: "--"}°",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.width(56.dp))
                        Text("az ${s.azimuthDeg?.toString() ?: "--"}°",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.width(56.dp))
                        Text("SNR ${s.snrDbHz?.toString() ?: "--"}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.width(56.dp))
                        Text(
                            when {
                                s.isSbas && s.usedInFix -> "SBAS · used"
                                s.isSbas -> "SBAS"
                                s.usedInFix -> "used"
                                else -> ""
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (s.isSbas) Color(0xFF1976D2)
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

// ---- SBAS ----

@Composable
private fun SbasSection(sats: List<SatelliteObservation>, active: Boolean) {
    val sbasSats = sats.filter { it.isSbas }
    if (sbasSats.isEmpty() && !active) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("SBAS", style = MaterialTheme.typography.titleSmall)
            Text(
                if (active) "SBAS active" else "SBAS satellites in view, not used in fix",
                style = MaterialTheme.typography.bodyMedium,
                color = if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "SBAS PRN range 33–64 in NMEA; actual SBAS PRN = NMEA ID + 87.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            sbasSats.forEach { s ->
                Text(
                    "NMEA PRN ${s.prn} → SBAS PRN ${sbasPrnFromNmeaId(s.prn)}" +
                        (if (s.usedInFix) " · used in fix" else ""),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

// ---- Location comparison ----

@Composable
private fun LocationComparison(loc: Location?) {
    if (loc == null) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Android Location (for comparison)", style = MaterialTheme.typography.titleSmall)
            Text(
                "Values from the platform's most recent fix, delivered because the " +
                    "engine is running. Compare against NMEA fields above to spot " +
                    "HAL quantization and vertical-accuracy differences.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            ValueRow("Altitude",
                if (loc.hasAltitude()) "%.1f m".format(Locale.US, loc.altitude) else "--")
            ValueRow("Speed",
                if (loc.hasSpeed()) "%.2f m/s".format(Locale.US, loc.speed) else "--")
            ValueRow("Bearing",
                if (loc.hasBearing()) "%.1f°".format(Locale.US, loc.bearing) else "--")
            ValueRow("Horizontal accuracy",
                if (loc.hasAccuracy()) "%.1f m".format(Locale.US, loc.accuracy) else "--")
            ValueRow("Vertical accuracy",
                if (loc.hasVerticalAccuracy())
                    "%.1f m".format(Locale.US, loc.verticalAccuracyMeters)
                else "--")
        }
    }
}

// ---- Filter + copy ----

@Composable
private fun FilterChips(selected: Set<String>, onToggle: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Filter log", style = MaterialTheme.typography.titleSmall)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FilterChip(
                selected = selected.size == SENTENCE_TYPES.size,
                onClick = { SENTENCE_TYPES.forEach { if (it !in selected) onToggle(it) } },
                label = { Text("All") },
            )
            SENTENCE_TYPES.forEach {
                FilterChip(selected = it in selected, onClick = { onToggle(it) },
                    label = { Text(it) })
            }
        }
    }
}

/**
 * Copies the current filtered view to the clipboard. The user filtered
 * for a reason — copy what they see, not the whole stream.
 */
@Composable
private fun CopyButton(filtered: List<NmeaSentence>) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    IconButton(
        onClick = {
            if (filtered.isEmpty()) return@IconButton
            val text = filtered.joinToString("\n") { it.raw }
            scope.launch {
                clipboard.setClipEntry(
                    ClipEntry(android.content.ClipData.newPlainText("nmea_log", text))
                )
            }
        },
        enabled = filtered.isNotEmpty(),
    ) { Icon(Icons.Default.ContentCopy, contentDescription = "Copy filtered log") }
}

// ---- Helpers ----

private val UTC_DATETIME = DateTimeFormatter
    .ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US)
    .withZone(ZoneOffset.UTC)

private fun formatUtcDateTime(dt: UTCDateTime?): String =
    dt?.let { UTC_DATETIME.format(it.toInstant()) } ?: "--"

private fun gsaSystemIdLabel(id: Int): String = when (id) {
    1 -> "1 (GPS)"
    2 -> "2 (GLONASS)"
    3 -> "3 (Galileo)"
    4 -> "4 (BeiDou)"
    5 -> "5 (QZSS)"
    6 -> "6 (NavIC)"
    else -> id.toString()
}
