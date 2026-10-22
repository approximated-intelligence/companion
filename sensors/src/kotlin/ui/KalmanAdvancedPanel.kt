package de.perigon.companion.sensors.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.perigon.companion.sensors.domain.KalmanModel
import de.perigon.companion.sensors.domain.KalmanTuning
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * Advanced altitude-filter tuning expander.
 *
 * Lives on both the Track and KF tabs. Each knob is a slider with three
 * pieces of context: what the parameter is physically, what visible effect
 * a change has, and a "Reset" link when the current value differs from the
 * built-in default.
 *
 * PROCESS NOISE IS EXPRESSED AS PROCESS NOISE. Every "Q" slider below shows
 * the variance rate directly (m²/s for states, m²/s³ for their derivatives).
 * The value shown IS the value the filter uses — no σ-to-variance conversion
 * needed, no square root. This matters because the two models share
 * `biasNoiseQ` (both model b as a random walk); if the slider expressed σ,
 * the 3-state's internal q_b = σ² would require a hand-computed square root
 * to compare against 2-state's q_b. Now 0.001 means 0.001 in both models.
 *
 * MEASUREMENT NOISE IS STILL SHOWN AS σ. The convention for measurement
 * sliders is "the RMS error of this measurement, in metres" — that's what
 * a user reasons about for a barometer or GPS. The filter internally squares
 * it (R = σ²), but the units on the slider are σ. This split is deliberate:
 * process noise is a filter design choice the user is tuning; measurement
 * noise is a physical property the user is estimating.
 *
 * Model-aware: 3-state knobs are "Vertical acceleration (q_v)" and "Bias
 * process noise (q_b)"; 2-state knobs are "Altitude random walk (q_h)",
 * "Bias process noise (q_b)", and "Slope smoothing (q_v cascade)". The
 * "Bias process noise" slider is IDENTICAL in both models — same field,
 * same label, same range, same units. The panel shows only the active
 * model's other knobs.
 *
 * Not exposed: `huberDelta`, `iskfLambdaX/Y`, `vbakfPriorDof`, iteration
 * counts. These are method-internal, defaults are literature-recommended,
 * and there's no user-facing affordance for "inlier threshold in standard
 * deviations" that isn't just a number the user has to look up.
 *
 * [onPromote], when provided, renders an "Apply to Track settings" button
 * that persists the current overrides to TrackConfigPrefs. The KF tab
 * passes it; the Track tab does not.
 */
@Composable
fun KalmanAdvancedPanel(
    model: KalmanModel,
    tuning: KalmanTuning,
    onChange: (KalmanTuning) -> Unit,
    modifier: Modifier = Modifier,
    onPromote: (() -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Advanced tuning",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Collapse advanced tuning" else "Expand advanced tuning",
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text(
                    "Fine-grained filter parameters. Q sliders show variance rates " +
                        "directly (m²/s, m²/s³) — same number the filter uses. σ sliders " +
                        "show RMS error in metres. The defaults are correct for most " +
                        "rides; tune here only if you have a specific reason.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when (model) {
                    KalmanModel.THREE_STATE -> {
                        TuningSlider(
                            title = "Vertical acceleration (q_v)",
                            explanation = "How much vertical acceleration the filter " +
                                "expects from legitimate motion. Higher: tracks fast " +
                                "climbs and pitches, more jitter. Lower: smoother, " +
                                "lags on descents.",
                            value = tuning.accelNoiseQ,
                            default = KalmanTuning.DEFAULT_ACCEL_NOISE_Q,
                            units = "m²/s³",
                            range = 1e-3..1.0,
                            onChange = { onChange(tuning.copy(accelNoiseQ = it)) },
                            onReset  = { onChange(tuning.copy(accelNoiseQ = null)) },
                        )
                    }
                    KalmanModel.TWO_STATE -> {
                        TuningSlider(
                            title = "Altitude random walk (q_h)",
                            explanation = "How much the filter expects altitude to " +
                                "wander between measurements. Higher: tracks changes " +
                                "faster, less smooth. Lower: smoother, lags on moves.",
                            value = tuning.qH,
                            default = KalmanTuning.DEFAULT_QH,
                            units = "m²/s",
                            range = 0.05..2.0,
                            onChange = { onChange(tuning.copy(qH = it)) },
                            onReset  = { onChange(tuning.copy(qH = null)) },
                        )
                    }
                }
                // Shared bias process noise. Same field, same label, same
                // range, same semantics in both models. Default is a middle
                // ground for mixed car/bike/wild use (see KalmanTuning).
                TuningSlider(
                    title = "Bias process noise (q_b)",
                    explanation = "How fast the filter lets baro bias drift. Higher: " +
                        "tracks aircon and weather fronts faster, absorbs sensor " +
                        "noise. Lower: smoother, slower to follow real bias changes. " +
                        "Adjust against the b vs b* overlay: if b* stays flat while " +
                        "b drifts, lower this; if b lags behind real aircon transitions, " +
                        "raise it.",
                    value = tuning.biasNoiseQ,
                    default = KalmanTuning.DEFAULT_BIAS_NOISE_Q,
                    units = "m²/s",
                    range = 1e-5..3e-2,
                    onChange = { onChange(tuning.copy(biasNoiseQ = it)) },
                    onReset  = { onChange(tuning.copy(biasNoiseQ = null)) },
                )
                if (model == KalmanModel.TWO_STATE) {
                    TuningSlider(
                        title = "Slope smoothing (q_v cascade)",
                        explanation = "How quickly the derived v_z trusts a new " +
                            "altitude difference. Higher: faster response to " +
                            "grade changes, more jitter. Lower: smoother, lags " +
                            "on descents.",
                        value = tuning.derivedVelNoise,
                        default = KalmanTuning.DEFAULT_DERIVED_VEL_NOISE,
                        units = "m²/s³",
                        range = 5e-4..5e-2,
                        onChange = { onChange(tuning.copy(derivedVelNoise = it)) },
                        onReset  = { onChange(tuning.copy(derivedVelNoise = null)) },
                    )
                }
                // Measurement noise — shown as σ, in metres.
                TuningSlider(
                    title = "Barometer precision (σ_baro)",
                    explanation = "RMS noise of the phone's pressure sensor after " +
                        "filtering. Higher: less trust in baro, more GPS influence. " +
                        "Lower: sharper but noisier.",
                    value = tuning.baroSigmaM?.toDouble(),
                    default = KalmanTuning.DEFAULT_BARO_SIGMA_M.toDouble(),
                    units = "m",
                    range = 0.3..5.0,
                    onChange = { onChange(tuning.copy(baroSigmaM = it.toFloat())) },
                    onReset  = { onChange(tuning.copy(baroSigmaM = null)) },
                )
                // Linear scale: range includes 0 ("floor disabled").
                TuningSlider(
                    title = "GPS vertical floor (σ_gps, min)",
                    explanation = "Minimum σ used for GPS height. Some phone HALs " +
                        "smooth altitude during plateaus, so the reported " +
                        "verticalAccuracyMeters is optimistic and the filter follows " +
                        "false steps. A floor prevents that. 0 disables the floor.",
                    value = tuning.gpsVerticalMinM?.toDouble(),
                    default = KalmanTuning.DEFAULT_GPS_VERTICAL_MIN_M.toDouble(),
                    units = "m",
                    range = 0.0..30.0,
                    onChange = { onChange(tuning.copy(gpsVerticalMinM = it.toFloat())) },
                    onReset  = { onChange(tuning.copy(gpsVerticalMinM = null)) },
                    logScale = false,
                )
                if (onPromote != null && !tuning.isDefault) {
                    OutlinedButton(
                        onClick = onPromote,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Apply to Track settings")
                    }
                }
            }
        }
    }
}

@Composable
private fun TuningSlider(
    title: String,
    explanation: String,
    value: Double?,
    default: Double,
    units: String,
    range: ClosedFloatingPointRange<Double>,
    onChange: (Double) -> Unit,
    onReset: () -> Unit,
    /**
     * Log scale for knobs whose range spans orders of magnitude. Disabled
     * for knobs whose range includes 0 (log is undefined at 0).
     */
    logScale: Boolean = true,
) {
    val current = (value ?: default).coerceIn(range)
    val (sliderPos, toValue) = if (logScale) {
        val logLo = ln(range.start)
        val logHi = ln(range.endInclusive)
        val pos = ((ln(current) - logLo) / (logHi - logLo))
            .toFloat()
            .coerceIn(0f, 1f)
        val to = { p: Float -> exp(logLo + p * (logHi - logLo)).coerceIn(range) }
        pos to to
    } else {
        val span = (range.endInclusive - range.start).coerceAtLeast(1e-9)
        val pos = ((current - range.start) / span).toFloat().coerceIn(0f, 1f)
        val to = { p: Float -> (range.start + p * span).coerceIn(range) }
        pos to to
    }
    val isDefault = value == null

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            Text(
                formatTuningValue(current, units) + if (isDefault) " (default)" else "",
                style = MaterialTheme.typography.bodySmall,
                color = if (isDefault) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.primary,
            )
            if (!isDefault) {
                TextButton(onClick = onReset) { Text("Reset") }
            }
        }
        Text(
            explanation,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = sliderPos,
            onValueChange = { p -> onChange(toValue(p)) },
        )
        Text(
            "Range ${formatTuningValue(range.start, units)} – ${formatTuningValue(range.endInclusive, units)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

private fun formatTuningValue(v: Double, unit: String): String {
    val decimals = when {
        abs(v) < 0.001 -> 5
        abs(v) < 0.01  -> 4
        abs(v) < 0.1   -> 3
        abs(v) < 1.0   -> 2
        else           -> 1
    }
    return "%.${decimals}f %s".format(v, unit)
}
