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
import de.perigon.companion.sensors.middleware.GnssSourceType
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
 * needed, no square root. This matters because the 2/3-state models share
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
 * Model-aware:
 *
 *   2-state:     q_h (RW on h), q_b (RW on b), derivedVelNoise (cascade v_z),
 *                derivedBiasRateNoise (cascade v_b)
 *   3-state:     q_v (accel on h/v_z), q_b (RW on b),
 *                derivedBiasRateNoise (cascade v_b)
 *   4-state:     q_v (accel on h/v_z), q_vb (jerk on b/v_b) — no cascade
 *   GPS 2-state: q_v ground, q_v airplane, σ_gps (reported-series noise),
 *                no bias, no cascade
 *
 * The "Bias process noise" slider (q_b) is shared by 2-state and 3-state;
 * the "Bias rate process noise" slider (q_vb) is 4-state only. "Bias rate
 * smoothing" (cascade q_vb) is 2/3-state only. The panel shows only the
 * active model's knobs.
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
    /**
     * Active GNSS source, used to resolve the GPS-only σ default that the
     * slider displays. Null → Location default. This parameter only makes
     * the "(default)" label match reality; the filter's actual R is
     * source-resolved independently, in
     * BackgroundService.gpsMeasurementSigma and KfViewModel.onFix. Without
     * it, an NMEA user sees "2.00 m (default)" while the filter runs at
     * 3.0 m.
     */
    sourceType: GnssSourceType? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val gpsOnlyDefault = if (sourceType == GnssSourceType.NMEA) {
        KalmanTuning.DEFAULT_GPS_ONLY_SIGMA_M_NMEA
    } else {
        KalmanTuning.DEFAULT_GPS_ONLY_SIGMA_M_LOCATION
    }

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
                    KalmanModel.THREE_STATE, KalmanModel.FOUR_STATE -> {
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
                    KalmanModel.GPS_TWO_STATE -> {
                        TuningSlider(
                            title = "Vertical acceleration, ground (q_v)",
                            explanation = "How much vertical acceleration the filter " +
                                "expects on foot, on a bike, or in a car. Higher: " +
                                "tracks hill ramps faster, more jitter. Lower: " +
                                "smoother, lags on ramps.",
                            value = tuning.gpsOnlyAccelQ,
                            default = KalmanTuning.DEFAULT_GPS_ONLY_ACCEL_Q,
                            units = "m²/s³",
                            range = 1e-4..0.5,
                            onChange = { onChange(tuning.copy(gpsOnlyAccelQ = it)) },
                            onReset  = { onChange(tuning.copy(gpsOnlyAccelQ = null)) },
                        )
                        TuningSlider(
                            title = "Vertical acceleration, airplane (q_v)",
                            explanation = "How much vertical acceleration the filter " +
                                "expects during a takeoff ramp or fast descent. " +
                                "Active when airplane mode is on. Much looser than " +
                                "the ground value so v_z can track sustained ramps.",
                            value = tuning.gpsOnlyAccelQAirplane,
                            default = KalmanTuning.DEFAULT_GPS_ONLY_ACCEL_Q_AIRPLANE,
                            units = "m²/s³",
                            range = 1e-2..5.0,
                            onChange = { onChange(tuning.copy(gpsOnlyAccelQAirplane = it)) },
                            onReset  = { onChange(tuning.copy(gpsOnlyAccelQAirplane = null)) },
                        )
                    }
                }
                // Shared bias process noise (2/3-state only — 4-state has no
                // random walk on b, GPS 2-state has no bias at all). Same
                // field, same label, same semantics in 2-state and 3-state.
                when (model) {
                    KalmanModel.TWO_STATE, KalmanModel.THREE_STATE -> {
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
                    }
                    KalmanModel.FOUR_STATE -> {
                        TuningSlider(
                            title = "Bias rate process noise (q_vb)",
                            explanation = "How fast the filter allows the bias *rate* to " +
                                "change. Higher: tracks fast aircon transients, weaker " +
                                "detector. Lower: smoother b, sharper aircon CUSUM.",
                            value = tuning.biasRateQ,
                            default = KalmanTuning.DEFAULT_BIAS_RATE_Q,
                            units = "m²/s³",
                            range = 1e-10..1e-5,
                            onChange = { onChange(tuning.copy(biasRateQ = it)) },
                            onReset  = { onChange(tuning.copy(biasRateQ = null)) },
                        )
                    }
                    KalmanModel.GPS_TWO_STATE -> Unit  // no bias in this model
                }
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
                if (model == KalmanModel.TWO_STATE || model == KalmanModel.THREE_STATE) {
                    TuningSlider(
                        title = "Bias rate smoothing (q_vb cascade)",
                        explanation = "How quickly the derived v_b trusts a new " +
                            "bias difference. Higher: faster response, more jitter. " +
                            "Lower: smoother. Cascade v_b is diagnostic only — for " +
                            "detection, use the 4-state model's v_b state.",
                        value = tuning.derivedBiasRateNoise,
                        default = KalmanTuning.DEFAULT_DERIVED_BIAS_RATE_NOISE,
                        units = "m²/s³",
                        range = 1e-7..1e-3,
                        onChange = { onChange(tuning.copy(derivedBiasRateNoise = it)) },
                        onReset  = { onChange(tuning.copy(derivedBiasRateNoise = null)) },
                    )
                }
                // Measurement noise — shown as σ, in metres.
                if (model != KalmanModel.GPS_TWO_STATE) {
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
                } else {
                    // GPS 2-state has no baro. Its measurement noise is the
                    // per-sample noise of the *reported* altitude series, not
                    // verticalAccuracyMeters — see KalmanTuning.gpsOnlySigmaM.
                    TuningSlider(
                        title = "GPS measurement precision (σ_gps)",
                        explanation = "Per-sample noise of the *reported* altitude " +
                            "series. Not the quantization step: the chip's own filter " +
                            "can go deaf and then overshoot, and the reported accuracy " +
                            "under-reports those excursions. Override applies to both " +
                            "NMEA and Location.",
                        value = tuning.gpsOnlySigmaM?.toDouble(),
                        default = gpsOnlyDefault.toDouble(),
                        units = "m",
                        range = 0.1..10.0,
                        onChange = { onChange(tuning.copy(gpsOnlySigmaM = it.toFloat())) },
                        onReset  = { onChange(tuning.copy(gpsOnlySigmaM = null)) },
                    )
                    if (tuning.gpsOnlySigmaM != null) {
                        val sourceLabel =
                            if (sourceType == GnssSourceType.NMEA) "NMEA" else "Location"
                        Text(
                            "Override active — Reset reverts to the $sourceLabel " +
                                "default (${formatTuningValue(gpsOnlyDefault.toDouble(), "m")}).",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
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
