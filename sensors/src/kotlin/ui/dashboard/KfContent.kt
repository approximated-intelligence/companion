package de.perigon.companion.sensors.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import de.perigon.companion.core.ui.access.RequireAccess
import de.perigon.companion.sensors.SensorsRequirements
import de.perigon.companion.sensors.domain.KalmanMode
import de.perigon.companion.sensors.domain.UpdateSource
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

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            RequireAccess(
                requirements = listOf(SensorsRequirements.location),
                inline = true,
            ) {
                LaunchedEffect(Unit) { vm.start() }
                KfCard(state, vm)
            }
        }
    }
}

@Composable
private fun KfCard(state: KfUiState, vm: KfViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Kalman Filter", style = MaterialTheme.typography.titleMedium)

            // Mode selector.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
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

            HorizontalDivider()

            // GNSS toggle.
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Feed GNSS to filter", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        gnssSubtitle(state),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (!state.gnssEnabled && state.isInitialized)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.gnssEnabled,
                    onCheckedChange = vm::setGnssEnabled,
                )
            }

            HorizontalDivider()

            // Numeric grid — 3 columns.
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

            // Mode-specific line.
            Text(
                modeSpecificLine(state),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            // Live charts.
            val samples = state.history
            KfChart(
                title = "Heights (m)",
                series = listOf(
                    ChartSeries("h", COLOR_H, samples.map { it.h }),
                    ChartSeries("baro", COLOR_BARO, samples.map { it.baroInput }),
                    ChartSeries("gps", COLOR_GPS, samples.map { it.gpsInput }),
                ),
            )
            KfChart(
                title = "Bias b (m)",
                series = listOf(
                    ChartSeries("b", COLOR_B, samples.map { it.b }),
                ),
                centerAtZero = true,
            )
            KfChart(
                title = "Uncertainty (m)",
                series = listOf(
                    ChartSeries("σ(h)", COLOR_SIGMA_H, samples.map { it.sigmaH }),
                    ChartSeries("σ(b)", COLOR_SIGMA_B, samples.map { it.sigmaB }),
                ),
            )
            KfChart(
                title = "Innovation (m)",
                series = listOf(
                    ChartSeries("Δgps", COLOR_DGPS, samples.map { it.deltaGps }),
                    ChartSeries("Δbaro", COLOR_DBARO, samples.map { it.deltaBaro }),
                ),
                centerAtZero = true,
            )

            HorizontalDivider()

            FilledTonalButton(onClick = vm::resetFilter) {
                Text("Reset filter")
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

@Composable
private fun KfChart(
    title: String,
    series: List<ChartSeries>,
    modifier: Modifier = Modifier,
    centerAtZero: Boolean = false,
    yFormat: (Float) -> String = { "%.1f".format(it) },
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
            val all = series.flatMap { it.values }.filterNotNull()
            if (all.size < 2) {
                drawCenteredText(
                    textMeasurer, "no data yet",
                    size.width / 2, size.height / 2,
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

            // Horizontal grid at 0%, 25%, 50%, 75%, 100%.
            for (i in 0..4) {
                val y = size.height * i / 4f
                drawLine(gridColor, Offset(0f, y), Offset(size.width, y), 1f)
            }
            // Zero line if the range straddles 0.
            if (yLo < 0f && yHi > 0f) {
                val zeroY = size.height * (1f - (0f - yLo) / yRange)
                drawLine(
                    color = labelColor.copy(alpha = 0.4f),
                    start = Offset(0f, zeroY),
                    end = Offset(size.width, zeroY),
                    strokeWidth = 1f,
                )
            }

            // Y labels, right side.
            val style = TextStyle(fontSize = 9.sp, color = labelColor)
            val hiLabel = textMeasurer.measure(AnnotatedString(yFormat(yHi)), style)
            drawText(
                textLayoutResult = hiLabel,
                topLeft = Offset(
                    size.width - hiLabel.size.width - 4.dp.toPx(),
                    2.dp.toPx(),
                ),
            )
            val loLabel = textMeasurer.measure(AnnotatedString(yFormat(yLo)), style)
            drawText(
                textLayoutResult = loLabel,
                topLeft = Offset(
                    size.width - loLabel.size.width - 4.dp.toPx(),
                    size.height - loLabel.size.height - 2.dp.toPx(),
                ),
            )

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
                    val x = size.width * i / (n - 1)
                    val y = size.height * (1f - (value - yLo) / yRange)
                    if (prevX != null && prevY != null) {
                        drawLine(s.color, Offset(prevX, prevY), Offset(x, y), 2f)
                    }
                    prevX = x
                    prevY = y
                }
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

private fun fmtM(value: Float?, decimals: Int): String =
    value?.let { "%.${decimals}f".format(it) } ?: "--"

private fun fmtSignedM(value: Float?, decimals: Int): String =
    value?.let { "%+.${decimals}f".format(it) } ?: "--"
