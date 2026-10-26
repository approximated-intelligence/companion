package de.perigon.companion.sensors.ui.dashboard

import android.content.ClipData
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.perigon.companion.core.geo.CoordFormat
import de.perigon.companion.core.geo.formatDd
import de.perigon.companion.core.geo.formatDdm
import de.perigon.companion.core.geo.formatDms
import de.perigon.companion.core.geo.formatPlusCode
import de.perigon.companion.core.geo.formatUtm
import de.perigon.companion.core.ui.CoordinateDisplay
import de.perigon.companion.core.ui.access.RequireAccess
import de.perigon.companion.core.ui.formatDateTime
import de.perigon.companion.sensors.SensorsRequirements
import de.perigon.companion.sensors.gnss.Constellation
import de.perigon.companion.sensors.gnss.SatelliteObservation
import kotlin.math.sqrt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun constellationColor(c: Constellation): Color = when (c) {
    Constellation.GPS      -> Color(0xFF26C6DA)
    Constellation.GLONASS  -> Color(0xFFE53935)
    Constellation.GALILEO  -> Color(0xFF1E88E5)
    Constellation.BEIDOU   -> Color(0xFFFFB300)
    Constellation.QZSS     -> Color(0xFFAB47BC)
    Constellation.SBAS     -> Color(0xFF795548)
    Constellation.NAVIC    -> Color(0xFFF4511E)
    Constellation.UNKNOWN  -> Color.Gray
}

internal fun constellationShort(c: Constellation): String = when (c) {
    Constellation.GPS      -> "GPS"
    Constellation.GLONASS  -> "GLO"
    Constellation.GALILEO  -> "GAL"
    Constellation.BEIDOU   -> "BDS"
    Constellation.QZSS     -> "QZS"
    Constellation.SBAS     -> "SBAS"
    Constellation.NAVIC    -> "IRNSS"
    Constellation.UNKNOWN  -> "?"
}

internal fun constellationDisplayOrder(): List<Constellation> = listOf(
    Constellation.GPS,
    Constellation.GALILEO,
    Constellation.GLONASS,
    Constellation.BEIDOU,
    Constellation.QZSS,
    Constellation.SBAS,
    Constellation.NAVIC,
)

@Composable
fun GnssContent(vm: DashboardViewModel) {
    val gnss by vm.gnssState.collectAsStateWithLifecycle()
    val format by vm.coordFormat.collectAsStateWithLifecycle()

    // No lifecycle effect here: GNSS registration is hoisted to
    // SensorsScreen's DisposableEffect so the listener feeds every tab
    // (needed by the Raw tab's declination) and survives screen-off.
    // The LaunchedEffect inside RequireAccess is the retry after an
    // app-internal permission grant — startGnss() is idempotent, so the
    // already-running case is a no-op.

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            RequireAccess(
                requirements = listOf(SensorsRequirements.location),
                inline = true,
            ) {
                LaunchedEffect(Unit) { vm.startGnss() }
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    GnssCard(
                        state = gnss,
                        format = format,
                        onFormatChange = vm::setCoordFormat,
                    )
                    SatelliteTable(gnss.satellites)
                }
            }
        }
    }
}

@Composable
private fun GnssCard(
    state: GnssUiState,
    format: CoordFormat,
    onFormatChange: (CoordFormat) -> Unit,
) {
    val usedSats = state.satellites.count { it.usedInFix }
    val totalSats = state.satellites.size

    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("GNSS Status", style = MaterialTheme.typography.titleMedium)

            if (state.setSource != null || state.fixSource != null) {
                val mismatch = state.setSource != null && state.fixSource != null &&
                    state.setSource != state.fixSource
                Text(
                    "Source: ${state.setSource ?: "--"} · last fix: ${state.fixSource ?: "--"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (mismatch) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Skyplot(state.satellites, Modifier.fillMaxWidth().aspectRatio(1f))
            ConstellationLegend(state.satellites)

            HorizontalDivider()

            Text(
                "Position (raw GPS)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CoordinateDisplay(
                lat = state.latitude,
                lon = state.longitude,
                format = format,
                onFormatChange = onFormatChange,
            )

            HorizontalDivider()

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                GnssDataCell("Elevation", state.altitude?.let { "%.0f m".format(it) } ?: "--")
                GnssDataCell("Bearing",   state.bearing?.let { "${it.toInt()}°" } ?: "--")
                GnssDataCell("Speed",     state.speedMs?.let { "%.1f km/h".format(it * 3.6) } ?: "--")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                GnssDataCell("Error",      state.accuracy?.let { "%.0f m".format(it) } ?: "--")
                GnssDataCell("Satellites", if (totalSats == 0) "--" else "$usedSats/$totalSats")
                GnssDataCell("TTFF",       state.ttffSeconds?.let { "${it}s" } ?: "--")
            }
            Text(
                "Last fix: " + (state.lastFixTimeMs?.let { formatDateTime(it, "yyyy-MM-dd HH:mm:ss") } ?: "--"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            Text(
                "Signal strength (C/N0 dBHz) by constellation",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SignalStrengthChart(state.satellites, Modifier.fillMaxWidth().height(140.dp))
        }
    }
}

@Composable
private fun ConstellationLegend(satellites: List<SatelliteObservation>) {
    val present = remember(satellites) { satellites.map { it.constellation }.toSet() }
    if (present.isEmpty()) return
    val order = constellationDisplayOrder().filter { it in present }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
    ) {
        order.forEach { c ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(constellationColor(c), CircleShape))
                Spacer(Modifier.width(4.dp))
                Text(constellationShort(c), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun SatelliteTable(satellites: List<SatelliteObservation>) {
    if (satellites.isEmpty()) return
    val order = constellationDisplayOrder()
    val sorted = satellites.sortedWith(
        compareBy({ order.indexOf(it.constellation) }, { it.prn })
    )
    val headerStyle = MaterialTheme.typography.labelSmall
    val bodyStyle = MaterialTheme.typography.bodySmall
    val headerColor = MaterialTheme.colorScheme.onSurfaceVariant

    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text("Satellites", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth()) {
                Text("Cst",  Modifier.width(44.dp), style = headerStyle, color = headerColor)
                Text("PRN",  Modifier.width(40.dp), style = headerStyle, color = headerColor)
                Text("El",   Modifier.width(40.dp), style = headerStyle, color = headerColor)
                Text("Az",   Modifier.width(44.dp), style = headerStyle, color = headerColor)
                Text("C/N0", Modifier.width(56.dp), style = headerStyle, color = headerColor)
                Text("Use",  Modifier.width(32.dp), style = headerStyle, color = headerColor)
            }
            sorted.forEach { sat ->
                val rowColor = if (sat.usedInFix) MaterialTheme.colorScheme.onSurface
                               else MaterialTheme.colorScheme.onSurfaceVariant
                val cno = sat.snrDbHz
                Row(Modifier.fillMaxWidth()) {
                    Text(constellationShort(sat.constellation), Modifier.width(44.dp),
                        style = bodyStyle, color = constellationColor(sat.constellation))
                    Text(sat.prn.toString(), Modifier.width(40.dp),
                        style = bodyStyle, color = rowColor)
                    Text(sat.elevationDeg?.let { "$it°" } ?: "--",
                        Modifier.width(40.dp), style = bodyStyle, color = rowColor)
                    Text(sat.azimuthDeg?.let { "$it°" } ?: "--",
                        Modifier.width(44.dp), style = bodyStyle, color = rowColor)
                    Text(cno?.toString() ?: "--", Modifier.width(56.dp),
                        style = bodyStyle, color = rowColor)
                    Text(if (sat.usedInFix) "yes" else "",
                        Modifier.width(32.dp), style = bodyStyle, color = rowColor)
                }
            }
        }
    }
}

@Composable
private fun GnssDataCell(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Skyplot(satellites: List<SatelliteObservation>, modifier: Modifier = Modifier) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val lowElevBand = Color(0x2EFFC107)
    val textMeasurer = rememberTextMeasurer()

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2, size.height / 2)
        val maxRadius = size.minDimension / 2 * 0.85f

        drawCircle(
            color = lowElevBand,
            radius = maxRadius * 5f / 6f,
            center = center,
            style = Stroke(width = maxRadius / 3f),
        )

        drawCircle(gridColor, maxRadius, center, style = Stroke(2f))
        drawCircle(gridColor, maxRadius * 2f / 3f, center, style = Stroke(1f))
        drawCircle(gridColor, maxRadius / 3f, center, style = Stroke(1f))

        drawLine(gridColor, Offset(center.x, center.y - maxRadius), Offset(center.x, center.y + maxRadius), strokeWidth = 1f)
        drawLine(gridColor, Offset(center.x - maxRadius, center.y), Offset(center.x + maxRadius, center.y), strokeWidth = 1f)

        val compassStyle = TextStyle(fontSize = 12.sp, color = labelColor, fontWeight = FontWeight.Medium)
        drawCenteredText(textMeasurer, "N", center.x, center.y - maxRadius - 18.dp.toPx(), compassStyle)
        drawCenteredText(textMeasurer, "S", center.x, center.y + maxRadius +  6.dp.toPx(), compassStyle)
        drawCenteredText(textMeasurer, "E", center.x + maxRadius +  6.dp.toPx(), center.y - 8.dp.toPx(), compassStyle)
        drawCenteredText(textMeasurer, "W", center.x - maxRadius - 18.dp.toPx(), center.y - 8.dp.toPx(), compassStyle)

        satellites.forEach { sat ->
            val elevation = sat.elevationDeg?.toFloat() ?: 0f
            val azimuth = sat.azimuthDeg?.toFloat() ?: 0f
            val r = maxRadius * (1f - elevation / 90f)
            val azRad = Math.toRadians(azimuth.toDouble())
            val x = center.x + kotlin.math.sin(azRad).toFloat() * r
            val y = center.y - kotlin.math.cos(azRad).toFloat() * r
            // Dot radius proportional to sqrt(C/N0): dot AREA is then
            // proportional to signal strength, so a satellite twice as
            // strong occupies twice the visual area. Unknown C/N0 gets a
            // fixed 5 dp (smaller than a typical strong satellite, still
            // visible). Clamped to [2 dp, 14 dp] so 0 dB-Hz stays visible
            // as a small dot and 45+ doesn't dominate the plot.
            val cno = sat.snrDbHz?.toFloat()
            val dotRadiusDp = if (cno == null) 5f
                              else (1.5f * sqrt(cno.coerceAtLeast(0f))).coerceIn(2f, 14f)
            val dotRadius = dotRadiusDp.dp.toPx()
            val color = constellationColor(sat.constellation)
            // High elevation + weak signal: usually antenna null or local
            // obstruction rather than sky conditions. Marker is a hint,
            // not a verdict — indoors, roof/window attenuation triggers
            // false positives. Visible only as a small black dot at the
            // satellite's center so the skyplot stays readable; the
            // constellation color is preserved.
            val elev = sat.elevationDeg ?: 0
            val isAnomaly = cno != null && cno < 25f && elev >= 60

            if (sat.usedInFix) {
                drawCircle(color = color, radius = dotRadius, center = Offset(x, y))
            } else {
                drawCircle(
                    color = color,
                    radius = dotRadius,
                    center = Offset(x, y),
                    style = Stroke(width = 1.5.dp.toPx()),
                )
            }
            if (isAnomaly) {
                drawCircle(
                    color = Color.Black,
                    radius = 1.5.dp.toPx(),
                    center = Offset(x, y),
                )
            }
        }
    }
}

@Composable
private fun SignalStrengthChart(satellites: List<SatelliteObservation>, modifier: Modifier = Modifier) {
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val textMeasurer = rememberTextMeasurer()

    val byConst = remember(satellites) { satellites.groupBy { it.constellation } }
    val order = remember(byConst) { constellationDisplayOrder().filter { byConst.containsKey(it) } }

    // One slot per PRN. Range per constellation is exactly
    // [min observed PRN, max observed PRN] — slot 0 and slot (count-1)
    // always contain a satellite; interior gaps remain as empty slots.
    // Recomputed every frame from current observations: the chart
    // breathes as satellites come and go, but slot width is uniform
    // across the whole chart at any instant.
    val observedRanges: Map<Constellation, IntRange> = remember(byConst) {
        byConst.mapValues { (_, sats) ->
            val prns = sats.map { it.prn }
            prns.min()..prns.max()
        }
    }

    Canvas(modifier = modifier) {
        if (order.isEmpty()) {
            drawCenteredText(
                textMeasurer, "waiting for satellites…",
                size.width / 2, size.height / 2,
                TextStyle(fontSize = 12.sp, color = labelColor)
            )
            return@Canvas
        }

        // 15-45 dB-Hz range. Below 15 a satellite barely contributes to
        // a fix on a phone; above 45 is unreachable with an inbuilt
        // antenna. Anchoring the baseline at 15 (instead of 0) spreads
        // the 20-40 band where phones actually operate across most of
        // the chart instead of compressing it into the upper half.
        val maxSnr = 45f
        val minSnr = 15f
        val snrRange = maxSnr - minSnr
        val chartTop = 6.dp.toPx()
        val chartBottom = size.height - 22.dp.toPx()
        val chartHeight = chartBottom - chartTop
        val labelStyle = TextStyle(fontSize = 9.sp, color = labelColor)

        // Right-hand label column so the numeric scale never overlaps a
        // bar. 32 dp fits "40" at 9 sp with margin; wider would cost too
        // much of a 379 dp plot on phones.
        val labelColWidth = 32.dp.toPx()
        val plotWidth = size.width - labelColWidth

        // 20 / 30 / 40 are the real decision boundaries on a phone:
        // <20 barely contributes to a fix, 30 is usable, 40 is close to
        // the practical antenna ceiling. Whole-tens keeps the labels
        // readable at 9 sp in the 32 dp right column.
        for (level in listOf(20, 30, 40)) {
            val y = chartBottom - chartHeight * ((level - minSnr) / snrRange)
            drawLine(
                color = gridColor,
                start = Offset(0f, y),
                end = Offset(plotWidth, y),
                strokeWidth = 1f,
            )
            val layout = textMeasurer.measure(
                AnnotatedString(level.toString()), labelStyle,
            )
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(
                    plotWidth + 4.dp.toPx(),
                    (y - layout.size.height / 2f)
                        .coerceIn(0f, (size.height - layout.size.height).coerceAtLeast(0f)),
                ),
            )
        }

        // Uniform slot width across the whole chart: every bar is the
        // same pixel size regardless of constellation. Groups expand in
        // proportion to their observed range so a wide-span constellation
        // doesn't collapse to needles while a narrow one gets sparse.
        val totalSlots = order.sumOf { observedRanges[it]?.count() ?: 0 }
        if (totalSlots == 0) return@Canvas
        val slotWidth = plotWidth / totalSlots
        // Bar is 85% of slot, 15% gap. Adjacent bars stay visibly
        // separated; dense constellations remain readable because each
        // satellite gets its own slot, not a share of a group.
        val barInner = (slotWidth * 0.85f).coerceAtLeast(1f)
        val barPad = (slotWidth - barInner) / 2f

        var cursorX = 0f
        order.forEach { constellation ->
            val range = observedRanges[constellation] ?: return@forEach
            val slotCount = range.count()
            val groupWidth = slotWidth * slotCount
            val groupX = cursorX
            cursorX += groupWidth

            // Label drawn unconditionally so its position stays anchored
            // to the group's actual column, not to a per-frame index.
            val label = constellationShort(constellation)
            val layout = textMeasurer.measure(AnnotatedString(label), style = labelStyle)
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(
                    groupX + (groupWidth - layout.size.width) / 2f,
                    chartBottom + 4.dp.toPx(),
                ),
            )

            val sats = byConst[constellation].orEmpty()
            val color = constellationColor(constellation)
            for (sat in sats) {
                val snr = sat.snrDbHz?.toFloat() ?: continue
                val slotIdx = sat.prn - range.first
                if (slotIdx !in 0 until slotCount) continue
                val x = groupX + slotIdx * slotWidth + barPad
                // Floor at 2% of chart height so satellites below 15 dB-Hz
                // still show a visible sliver (they exist, even if
                // unusable) rather than vanishing from the plot.
                val h = chartHeight * ((snr - minSnr) / snrRange).coerceIn(0.02f, 1f)
                val y = chartBottom - h
                drawRect(
                    color = color.copy(alpha = if (sat.usedInFix) 1f else 0.4f),
                    topLeft = Offset(x, y),
                    size = Size(barInner, h),
                )
            }
        }
    }
}

private fun DrawScope.drawCenteredText(
    textMeasurer: TextMeasurer,
    text: String,
    centerX: Float,
    topY: Float,
    style: TextStyle,
) {
    val layout = textMeasurer.measure(AnnotatedString(text), style = style)
    drawText(
        textLayoutResult = layout,
        topLeft = Offset(centerX - layout.size.width / 2f, topY),
    )
}
