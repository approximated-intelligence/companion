package de.perigon.companion.sensors.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.perigon.companion.core.ui.ToggleRow
import de.perigon.companion.core.ui.access.RequireAccess
import de.perigon.companion.sensors.SensorsRequirements
import de.perigon.companion.sensors.domain.KalmanMode
import kotlin.math.abs
import kotlin.math.max

private val COLOR_H = Color(0xFF4CAF50)
private val COLOR_BARO = Color(0xFF2196F3)
private val COLOR_GPS = Color(0xFFFF9800)
private val COLOR_B = Color(0xFF9C27B0)
private val COLOR_SIGMA_H = Color(0xFF1976D2)
private val COLOR_SIGMA_B = Color(0xFFC2185B)
private val COLOR_DGPS = Color(0xFFEF6C00)
private val COLOR_DBARO = Color(0xFF00897B)

data class ChartSeries(
    val label: String,
    val color: Color,
    val values: List<Float?>,
)

@Composable
fun KfContent(vm: KfViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        vm.start()
        onPauseOrDispose { vm.stop() }
    }

    // Gate sits at the content level, not inside an item: the LazyColumn
    // either renders in full or the access panel replaces it. The former
    // single-Card layout forced the whole tab into one LazyColumn item,
    // which compounded the Card's 16 dp inner padding with the LazyColumn's
    // 16 dp — 32 dp of horizontal dead space per side, enough to make the
    // four mode chips wrap on a standard phone. Same effective width as the
    // Track tab now.
    RequireAccess(
        requirements = listOf(SensorsRequirements.location),
        inline = true,
    ) {
        LaunchedEffect(Unit) { vm.start() }

        val samples = state.history
        val windowSec =
            (KfViewModel.HISTORY_SIZE * KfViewModel.HISTORY_INTERVAL_MS / 1000).toInt()

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Kalman Filter", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Sandbox — nothing here is saved or shared with the recorder.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Mode chips + description as one item: they describe each other.
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        KalmanMode.entries.forEach { m ->
                            FilterChip(
                                selected = state.mode == m,
                                onClick = { vm.setMode(m) },
                                label = { Text(m.shortLabel()) },
                            )
                        }
                    }
                    Text(
                        state.mode.longDescription(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (state.mode == KalmanMode.ISKF) {
                item {
                    ToggleRow(
                        title = "Inverse-Hessian covariance",
                        subtitle = "Exact robust posterior covariance instead of the nominal Riccati one. Affects σ only; slightly more cost per update.",
                        checked = state.iskfInverseHessian,
                        onCheckedChange = vm::setIskfInverseHessian,
                    )
                }
            }

            item { HorizontalDivider() }
            item { Text("Inputs", style = MaterialTheme.typography.titleSmall) }
            item {
                ToggleRow(
                    title = "Feed GNSS",
                    subtitle = gnssSubtitle(state),
                    checked = state.gnssEnabled,
                    onCheckedChange = vm::setGnssEnabled,
                )
            }
            item {
                ToggleRow(
                    title = "Feed barometer",
                    subtitle = baroSubtitle(state),
                    checked = state.baroEnabled,
                    onCheckedChange = vm::setBaroEnabled,
                    enabled = state.hasBarometer,
                )
            }
            item {
                ToggleRow(
                    title = "Apply geoid to GPS height",
                    subtitle = geoidSubtitle(state),
                    checked = state.geoidEnabled,
                    onCheckedChange = vm::setGeoidEnabled,
                )
            }

            item { HorizontalDivider() }
            item { Text("Filter state", style = MaterialTheme.typography.titleSmall) }
            item {
                // The four numeric rows are one tabular block; keeping them in
                // one item gives them the tight internal spacing that reads
                // as a table rather than four unrelated lines.
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    NumericRow(
                        listOf(
                            "h (m)" to fmtM(state.h, 1),
                            "v_z (m/s)" to fmtM(state.v, 2),
                            "b (m)" to fmtSignedM(state.b, 1),
                        )
                    )
                    NumericRow(
                        listOf(
                            "σ(h) (m)" to fmtM(state.sigmaH, 2),
                            "σ(v) (m/s)" to fmtM(state.sigmaV, 2),
                            "σ(b) (m)" to fmtM(state.sigmaB, 2),
                        )
                    )
                    NumericRow(
                        listOf(
                            "Δgps (m)" to fmtSignedM(state.deltaGps, 1),
                            "Δbaro (m)" to fmtSignedM(state.deltaBaro, 1),
                            "last" to (state.lastUpdateSource?.name ?: "--"),
                        )
                    )
                    NumericRow(
                        listOf(
                            "gps in (m)" to fmtM(state.gpsInput, 1),
                            "baro in (m)" to fmtM(state.baroInput, 1),
                            "geoid (m)" to fmtSignedM(state.geoidUndulationM, 1),
                        )
                    )
                    Text(
                        modeSpecificLine(state),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item { HorizontalDivider() }
            item { Text("Live charts", style = MaterialTheme.typography.titleSmall) }
            item {
                KfChart(
                    title = "Heights (m)",
                    series = listOf(
                        ChartSeries("h", COLOR_H, samples.map { it.h }),
                        ChartSeries("baro", COLOR_BARO, samples.map { it.baroInput }),
                        ChartSeries("gps", COLOR_GPS, samples.map { it.gpsInput }),
                    ),
                    windowSeconds = windowSec,
                )
            }
            item {
                KfChart(
                    title = "Bias b (m)",
                    series = listOf(
                        ChartSeries("b", COLOR_B, samples.map { it.b }),
                    ),
                    centerAtZero = true,
                    windowSeconds = windowSec,
                )
            }
            item {
                KfChart(
                    title = "Uncertainty (m)",
                    series = listOf(
                        ChartSeries("σ(h)", COLOR_SIGMA_H, samples.map { it.sigmaH }),
                        ChartSeries("σ(b)", COLOR_SIGMA_B, samples.map { it.sigmaB }),
                    ),
                    windowSeconds = windowSec,
                )
            }
            item {
                KfChart(
                    title = "Innovation (m)",
                    series = listOf(
                        ChartSeries("Δgps", COLOR_DGPS, samples.map { it.deltaGps }),
                        ChartSeries("Δbaro", COLOR_DBARO, samples.map { it.deltaBaro }),
                    ),
                    centerAtZero = true,
                    windowSeconds = windowSec,
                )
            }

            item { HorizontalDivider() }
            item {
                FilledTonalButton(onClick = vm::resetFilter) {
                    Text("Reset filter")
                }
            }
        }
    }
}

@Composable
private fun NumericRow(items: List<Pair<String, String>>) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { (label, value) ->
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    value,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/**
 * Rolling time-series chart.
 *
 * Layout: the value axis is drawn as five labelled gridlines (0/25/50/75/100
 * % of the plot height) with a reserved right-hand label column, so the
 * newest data — the rightmost part of every series — is never obscured by a
 * number sitting on top of it. Values are formatted with a precision derived
 * from the visible range, so a 0.01 m σ range doesn't render as five
 * identical "0.0"s and a 2000 m altitude swing doesn't burn four digits on
 * tenths.
 */
@Composable
private fun KfChart(
    title: String,
    series: List<ChartSeries>,
    modifier: Modifier = Modifier,
    centerAtZero: Boolean = false,
    windowSeconds: Int = 90,
) {
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val textMeasurer = rememberTextMeasurer()

    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            series.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(s.color, CircleShape))
                    Spacer(Modifier.width(4.dp))
                    Text(s.label, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(110.dp)
        ) {
            // Reserve a right-hand column for the value labels.
            val labelColWidth = 56.dp.toPx()
            val plotWidth = size.width - labelColWidth
            val plotHeight = size.height

            val all = series.flatMap { it.values }.filterNotNull()
            if (all.size < 2) {
                drawCenteredText(
                    textMeasurer, "no data yet",
                    plotWidth / 2, plotHeight / 2,
                    TextStyle(fontSize = 11.sp, color = labelColor),
                )
                return@Canvas
            }

            val dataLo = all.min()
            val dataHi = all.max()
            val (yLo, yHi) = if (centerAtZero) {
                val sym = max(abs(dataLo), abs(dataHi)).coerceAtLeast(0.01f)
                -sym * 1.1f to sym * 1.1f
            } else {
                val range = (dataHi - dataLo).coerceAtLeast(0.01f)
                val pad = range * 0.08f
                (dataLo - pad) to (dataHi + pad)
            }
            val yRange = (yHi - yLo).coerceAtLeast(0.001f)

            // Derive precision from the visible range. Thresholds chosen so a
            // "5 m" range still shows 1 decimal and a "0.01 m" range shows 4.
            val decimals = when {
                yRange >= 100f -> 0
                yRange >= 10f -> 1
                yRange >= 1f -> 2
                yRange >= 0.1f -> 3
                else -> 4
            }
            val valueFormat = "%.${decimals}f"

            val labelStyle = TextStyle(fontSize = 9.sp, color = labelColor.copy(alpha = 0.85f))
            val gridStroke = 1.dp.toPx()
            val seriesStroke = 2.dp.toPx()
            val zeroStroke = 1.dp.toPx()

            // Five labelled gridlines: 0% (top) through 100% (bottom), with
            // the value interpolated at each.
            for (i in 0..4) {
                val y = plotHeight * i / 4f
                drawLine(gridColor, Offset(0f, y), Offset(plotWidth, y), gridStroke)
                val value = yHi - (yHi - yLo) * (i / 4f)
                val layout = textMeasurer.measure(
                    AnnotatedString(valueFormat.format(value)),
                    labelStyle,
                )
                val labelX = plotWidth + 4.dp.toPx()
                val labelY = (y - layout.size.height / 2f)
                    .coerceIn(0f, (plotHeight - layout.size.height).coerceAtLeast(0f))
                drawText(textLayoutResult = layout, topLeft = Offset(labelX, labelY))
            }

            // Zero reference line, only when the range actually straddles 0.
            if (yLo < 0f && yHi > 0f) {
                val zeroY = plotHeight * (1f - (0f - yLo) / yRange)
                drawLine(
                    color = labelColor.copy(alpha = 0.4f),
                    start = Offset(0f, zeroY),
                    end = Offset(plotWidth, zeroY),
                    strokeWidth = zeroStroke,
                )
            }

            // Series.
            for (s in series) {
                val n = s.values.size
                if (n < 2) continue
                var prevX: Float? = null
                var prevY: Float? = null
                for (i in 0 until n) {
                    val value = s.values[i]
                    if (value == null) {
                        prevX = null; prevY = null
                        continue
                    }
                    val x = plotWidth * i / (n - 1)
                    val y = plotHeight * (1f - (value - yLo) / yRange)
                    if (prevX != null && prevY != null) {
                        drawLine(s.color, Offset(prevX, prevY), Offset(x, y), seriesStroke)
                    }
                    prevX = x
                    prevY = y
                }
            }
        }
        Text(
            "last $windowSeconds s",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier
                .align(Alignment.End)
                .padding(top = 2.dp),
        )
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

private fun KalmanMode.shortLabel(): String = when (this) {
    KalmanMode.GAUSSIAN -> "Gaussian"
    KalmanMode.HUBER -> "Huber"
    KalmanMode.VBAKF -> "VBAKF"
    KalmanMode.ISKF -> "ISKF"
}

private fun KalmanMode.longDescription(): String = when (this) {
    KalmanMode.GAUSSIAN ->
        "Reference filter — standard Riccati update, no outlier rejection."
    KalmanMode.HUBER ->
        "Huber M-estimation. Downweights measurement outliers; covariance via Joseph form with the effective measurement noise."
    KalmanMode.VBAKF ->
        "Variational Bayes estimation of the measurement noise (inverse-gamma prior). Adapts R online per sample."
    KalmanMode.ISKF ->
        "Iteratively Saturated KF (Yang & Boyd 2025). Convex robust MAP with separate process and measurement outlier terms."
}

private fun modeSpecificLine(state: KfUiState): String = when (state.mode) {
    KalmanMode.GAUSSIAN ->
        "Reference mode — no per-update weights."
    KalmanMode.HUBER ->
        "w = ${state.huberWeight?.let { "%.2f".format(it) } ?: "--"}  " +
            "(Huber weight; 1.0 means full trust, lower means downweighted)"
    KalmanMode.VBAKF ->
        state.vbEstimatedR?.let {
            "R_est = %.3f m²  (σ = %.2f m)".format(it, kotlin.math.sqrt(it))
        } ?: "R_est = --"
    KalmanMode.ISKF ->
        "‖s‖ = ${state.iskfProcessOutlierNorm?.let { "%.2f m".format(it) } ?: "--"} · " +
            "o = ${state.iskfMeasOutlier?.let { "%+.2f m".format(it) } ?: "--"}"
}

private fun gnssSubtitle(state: KfUiState): String = when {
    state.gnssEnabled -> "GPS + baro fusion"
    !state.isInitialized -> "GNSS off — filter not seeded yet; enable to seed"
    else -> "Baro only (seeded earlier from GPS)"
}

private fun baroSubtitle(state: KfUiState): String {
    if (!state.hasBarometer) return "No pressure sensor on this device"
    val p = state.baroPressureHpa?.let { "%.1f hPa".format(it) } ?: "--"
    val z = state.baroInput?.let { "%.1f m".format(it) } ?: "--"
    return when {
        state.baroEnabled -> "Fused · P = $p · h_baro = $z"
        else              -> "Not fused · P = $p · h_baro = $z"
    }
}

private fun geoidSubtitle(state: KfUiState): String {
    val n = state.geoidUndulationM?.let { "%+.1f m".format(it) } ?: "--"
    return "Undulation at current position: $n"
}

private fun fmtM(value: Float?, decimals: Int): String =
    value?.let { "%.${decimals}f".format(it) } ?: "--"

private fun fmtSignedM(value: Float?, decimals: Int): String =
    value?.let { "%+.${decimals}f".format(it) } ?: "--"
