package de.perigon.companion.sensors.ui.dashboard

import android.content.ClipData
import android.location.GnssStatus
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
import de.perigon.companion.core.ui.access.RequireAccess
import de.perigon.companion.core.ui.formatDateTime
import de.perigon.companion.sensors.SensorsRequirements
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val CoordFormat.label: String
    get() = when (this) {
        CoordFormat.DD -> "DD"
        CoordFormat.DDM -> "DDM"
        CoordFormat.DMS -> "DMS"
        CoordFormat.UTM -> "UTM"
        CoordFormat.PLUS_CODE -> "Plus"
    }

internal fun constellationColor(constellation: Int): Color = when (constellation) {
    GnssStatus.CONSTELLATION_GPS -> Color(0xFF26C6DA)
    GnssStatus.CONSTELLATION_GLONASS -> Color(0xFFE53935)
    GnssStatus.CONSTELLATION_GALILEO -> Color(0xFF1E88E5)
    GnssStatus.CONSTELLATION_BEIDOU -> Color(0xFFFFB300)
    GnssStatus.CONSTELLATION_QZSS -> Color(0xFFAB47BC)
    GnssStatus.CONSTELLATION_SBAS -> Color(0xFF795548)
    GnssStatus.CONSTELLATION_IRNSS -> Color(0xFFF4511E)
    else -> Color.Gray
}

internal fun constellationShort(constellation: Int): String = when (constellation) {
    GnssStatus.CONSTELLATION_GPS -> "GPS"
    GnssStatus.CONSTELLATION_GLONASS -> "GLO"
    GnssStatus.CONSTELLATION_GALILEO -> "GAL"
    GnssStatus.CONSTELLATION_BEIDOU -> "BDS"
    GnssStatus.CONSTELLATION_QZSS -> "QZS"
    GnssStatus.CONSTELLATION_SBAS -> "SBAS"
    GnssStatus.CONSTELLATION_IRNSS -> "IRNSS"
    else -> "?"
}

internal fun constellationDisplayOrder(): List<Int> = listOf(
    GnssStatus.CONSTELLATION_GPS,
    GnssStatus.CONSTELLATION_GALILEO,
    GnssStatus.CONSTELLATION_GLONASS,
    GnssStatus.CONSTELLATION_BEIDOU,
    GnssStatus.CONSTELLATION_QZSS,
    GnssStatus.CONSTELLATION_SBAS,
    GnssStatus.CONSTELLATION_IRNSS,
)

@Composable
fun GnssContent(vm: SensorDashboardViewModel) {
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
                GnssCard(
                    state = gnss,
                    format = format,
                    onFormatChange = vm::setCoordFormat,
                )
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

            Skyplot(state.satellites, Modifier.fillMaxWidth().aspectRatio(1f))
            ConstellationLegend(state.satellites)

            HorizontalDivider()

            Text(
                "Position (raw GPS)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                CoordFormat.entries.forEach { f ->
                    FilterChip(
                        selected = format == f,
                        onClick = { onFormatChange(f) },
                        label = { Text(f.label) },
                    )
                }
            }
            CoordinateValue(state, format)

            HorizontalDivider()

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                GnssDataCell("Elevation", state.altitude?.let  { "%.0f m".format(it) } ?: "--")
                GnssDataCell("Bearing",   state.bearing?.let   { "${it.toInt()}°" } ?: "--")
                GnssDataCell("Speed",     state.speedMs?.let   { "%.1f km/h".format(it * 3.6) } ?: "--")
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

/**
 * Renders the current position in the user-selected [CoordFormat]. The copy
 * button puts the displayed string on the clipboard verbatim — the user
 * shares exactly what they see, in whatever format they chose.
 */
@Composable
private fun CoordinateValue(state: GnssUiState, format: CoordFormat) {
    val lat = state.latitude
    val lon = state.longitude
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

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
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
            )
            IconButton(
                onClick = {
                    if (hasFix) {
                        scope.launch {
                            clipboard.setClipEntry(
                                ClipEntry(ClipData.newPlainText("coordinates", text))
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
                    tint = if (hasFix) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ConstellationLegend(satellites: List<SatelliteInfo>) {
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
private fun GnssDataCell(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Skyplot(satellites: List<SatelliteInfo>, modifier: Modifier = Modifier) {
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
            val r = maxRadius * (1f - sat.elevation / 90f)
            val azRad = Math.toRadians(sat.azimuth.toDouble())
            val x = center.x + kotlin.math.sin(azRad).toFloat() * r
            val y = center.y - kotlin.math.cos(azRad).toFloat() * r
            val dotRadius = 7.dp.toPx()
            val color = constellationColor(sat.constellation)

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
        }
    }
}

@Composable
private fun SignalStrengthChart(satellites: List<SatelliteInfo>, modifier: Modifier = Modifier) {
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val textMeasurer = rememberTextMeasurer()

    val byConst = remember(satellites) { satellites.groupBy { it.constellation } }
    val order = remember(byConst) { constellationDisplayOrder().filter { byConst.containsKey(it) } }

    Canvas(modifier = modifier) {
        if (order.isEmpty()) {
            drawCenteredText(
                textMeasurer, "waiting for satellites…",
                size.width / 2, size.height / 2,
                TextStyle(fontSize = 12.sp, color = labelColor)
            )
            return@Canvas
        }

        val maxSnr = 60f
        val chartTop = 6.dp.toPx()
        val chartBottom = size.height - 22.dp.toPx()
        val chartHeight = chartBottom - chartTop
        val groupWidth = size.width / order.size
        val barGap = 2.dp.toPx()
        val labelStyle = TextStyle(fontSize = 9.sp, color = labelColor)

        for (level in listOf(20, 40, 60)) {
            val y = chartBottom - chartHeight * (level / maxSnr)
            drawLine(
                color = gridColor,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1f,
            )
        }

        order.forEachIndexed { groupIdx, constellation ->
            val sats = byConst[constellation].orEmpty().sortedByDescending { it.snr }
            val groupX = groupIdx * groupWidth
            if (sats.isEmpty()) return@forEachIndexed

            val barWidth = (groupWidth - barGap * (sats.size + 1)) / sats.size
            sats.forEachIndexed { satIdx, sat ->
                val x = groupX + barGap * (satIdx + 1) + barWidth * satIdx
                val h = chartHeight * (sat.snr / maxSnr).coerceIn(0f, 1f)
                val y = chartBottom - h
                drawRect(
                    color = constellationColor(constellation).copy(
                        alpha = if (sat.usedInFix) 1f else 0.4f
                    ),
                    topLeft = Offset(x, y),
                    size = Size(barWidth, h),
                )
            }

            val label = constellationShort(constellation)
            val layout = textMeasurer.measure(AnnotatedString(label), style = labelStyle)
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(
                    groupX + (groupWidth - layout.size.width) / 2f,
                    chartBottom + 4.dp.toPx(),
                ),
            )
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
