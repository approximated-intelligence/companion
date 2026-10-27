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
import de.perigon.companion.core.geo.CoordFormat
import de.perigon.companion.core.ui.CoordinateDisplay
import de.perigon.companion.core.ui.access.RequireAccess
import de.perigon.companion.sensors.SensorsRequirements
import de.perigon.companion.sensors.gnss.SatelliteObservation
import de.perigon.companion.sensors.gnss.UTCDateTime
import de.perigon.companion.sensors.gnss.sbasPrnFromNmeaId
import de.perigon.companion.sensors.middleware.deriveNmeaSigma
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
 * NMEA tab. Debug surface for the NMEA stream: per-sentence-type status
 * chips, parsed-values table, satellite table, SBAS status, and the raw
 * log with filter / freeze / last-cycle / truncate toggles.
 *
 * Observes `NmeaStream` directly, because it exists to answer "what does
 * the chip actually emit". Consumers of the merged `GnssFix` (Track tab,
 * GNSS tab, KF tab) do not use this tab's data.
 *
 * No warming control. Sentences flow while the Sensors screen is resumed
 * (its dashboard session keeps the active source warming the engine —
 * both sources use an active requestLocationUpdates, not PASSIVE) and
 * while the recorder is running. If neither is active — Sensors paused
 * and no recording — nothing is requesting fixes, and the log stays
 * empty regardless of what this tab does.
 */
@Composable
fun NmeaContent(
    vm: NmeaViewModel,
    coordFormat: CoordFormat,
    onFormatChange: (CoordFormat) -> Unit,
) {
    val parsed by vm.parsed.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val lastCycle by vm.lastCycleSentences.collectAsStateWithLifecycle()
    val lastSeen by vm.lastSeen.collectAsStateWithLifecycle()
    val latestLoc by vm.latestLocation.collectAsStateWithLifecycle()
    val streamState by vm.streamState.collectAsStateWithLifecycle()
    val appUndulation by vm.appUndulation.collectAsStateWithLifecycle()

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
                else -> null
            }
            if (errorMsg != null) item { ErrorBanner(errorMsg) }

            item {
                CurrentPositionCard(
                    parsed = parsed,
                    appUndulationM = appUndulation,
                    coordFormat = coordFormat,
                    onFormatChange = onFormatChange,
                )
            }
            item { StatusChips(lastSeen) }
            item { ParsedValues(parsed, lastSeen) }
            item { SatelliteSection(parsed.satellites) }
            item { SbasSection(parsed.satellites, parsed.sbasActive) }
            item {
                LocationComparison(
                    loc = latestLoc,
                    coordFormat = coordFormat,
                    onFormatChange = onFormatChange,
                    undulationAt = vm::undulationAt,
                )
            }

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
                            "No NMEA sentences received yet. The active GNSS " +
                                "source warms the engine while the Sensors screen is " +
                                "showing; sentences appear once the first cycle " +
                                "completes. If the log stays empty, check the active " +
                                "source in Settings → Sensors, or verify that location " +
                                "permission is granted.",
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

// ---- Current position (NMEA) ----

/**
 * Position + heights from the NMEA stream. Shows the chip's WGS84, both
 * MSL derivations (chip's own geoid vs XGM2019e), and the difference
 * between them, so a user seeing MSL drift from the platform's own
 * Location API can trace where the discrepancy comes from.
 */
@Composable
private fun CurrentPositionCard(
    parsed: NmeaStream.ParsedSnapshot,
    appUndulationM: Float?,
    coordFormat: CoordFormat,
    onFormatChange: (CoordFormat) -> Unit,
) {
    val pos = parsed.position
    val wgs84 = parsed.altitudeWgs84M
    val chipMsl = parsed.altitudeMslM
    val chipGeoid = parsed.geoidSepM
    val xgmMsl = if (wgs84 != null && appUndulationM != null) {
        wgs84 - appUndulationM.toDouble()
    } else null

    if (pos == null && wgs84 == null && chipMsl == null) return

    // The same function NmeaGnssSource.buildFixFromSnapshot calls. GNSS
    // card (fed by fix.sigma), this card, and the Height WGS84 tail all
    // now agree on the GST-vs-DOP fallback because there is only one
    // implementation. ParsedSnapshot and CycleSnapshot carry the same
    // four inputs, so the result is the same for the same fix.
    val sigma = deriveNmeaSigma(
        gstHorizontalM = parsed.horizontalSigmaM,
        gstVerticalM = parsed.verticalSigmaM,
        hdop = parsed.hdop,
        vdop = parsed.vdop,
    )

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Current position (NMEA)", style = MaterialTheme.typography.titleSmall)

            CoordinateDisplay(
                lat = pos?.lat,
                lon = pos?.lon,
                format = coordFormat,
                onFormatChange = onFormatChange,
                modifier = Modifier.fillMaxWidth(),
                lastFixTimeMs = parsed.timestamp?.toInstant()?.toEpochMilli(),
                horizontalSigmaM = sigma.horizontalM,
            )

            HeightRow(
                label = "Height WGS84",
                value = wgs84,
                tail = sigma.verticalM?.let { "± %.1f m".format(Locale.US, it) },
            )
            HeightRow(
                label = "Height MSL (chip)",
                value = chipMsl,
                tail = chipGeoid?.let { "geoid %.1f m".format(Locale.US, it) },
            )
            HeightRow(
                label = "Height MSL (XGM2019e)",
                value = xgmMsl,
                tail = appUndulationM?.let { "geoid %.1f m".format(Locale.US, it) },
            )

            if (chipMsl != null && xgmMsl != null) {
                val delta = kotlin.math.abs(xgmMsl - chipMsl)
                Text(
                    if (delta < 0.05) {
                        "Chip and XGM2019e geoid models agree at this position."
                    } else {
                        "Chip and XGM2019e geoid models differ by %.1f m at this position."
                            .format(Locale.US, delta)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun HeightRow(label: String, value: Double?, tail: String?) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                value?.let { "%.1f m".format(Locale.US, it) } ?: "--",
                style = MaterialTheme.typography.bodyLarge,
            )
            if (tail != null) {
                Text(
                    tail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---- Error ----

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

// ---- Status chips ----

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
private fun LocationComparison(
    loc: Location?,
    coordFormat: CoordFormat,
    onFormatChange: (CoordFormat) -> Unit,
    undulationAt: (Double, Double) -> Float,
) {
    if (loc == null) return
    val wgs84 = if (loc.hasAltitude()) loc.altitude else null
    val msl = if (wgs84 != null) {
        wgs84 - undulationAt(loc.latitude, loc.longitude)
    } else null
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Android Location (for comparison)", style = MaterialTheme.typography.titleSmall)
            Text(
                "Values from the platform's most recent fix. Compare against NMEA " +
                    "above to spot HAL quantization and vertical-accuracy differences.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CoordinateDisplay(
                lat = loc.latitude,
                lon = loc.longitude,
                format = coordFormat,
                onFormatChange = onFormatChange,
                modifier = Modifier.fillMaxWidth(),
                lastFixTimeMs = loc.time,
                horizontalSigmaM = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null,
            )
            PositionReadings(
                wgs84M = wgs84,
                wgs84SigmaM = if (loc.hasVerticalAccuracy()) loc.verticalAccuracyMeters.toDouble() else null,
                mslM = msl,
                speedMps = if (loc.hasSpeed()) loc.speed.toDouble() else null,
                headingDeg = if (loc.hasBearing()) loc.bearing.toDouble() else null,
            )
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
