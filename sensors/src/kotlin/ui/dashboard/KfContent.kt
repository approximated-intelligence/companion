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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
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
import de.perigon.companion.core.ui.KeepScreenOn
import de.perigon.companion.core.ui.ToggleRow
import de.perigon.companion.core.ui.access.RequireAccess
import de.perigon.companion.sensors.SensorsRequirements
import de.perigon.companion.sensors.domain.KalmanMode
import de.perigon.companion.sensors.domain.KalmanModel
import de.perigon.companion.sensors.ui.BaroCalibrationDialog
import de.perigon.companion.sensors.ui.KalmanAdvancedPanel
import de.perigon.companion.sensors.ui.KalmanModeSelector
import de.perigon.companion.sensors.ui.KalmanModelSelector
import kotlin.math.abs
import kotlin.math.max

private val COLOR_H = Color(0xFF4CAF50)
private val COLOR_H_DIFF = Color(0xFF00897B)
private val COLOR_BARO = Color(0xFF2196F3)
private val COLOR_GPS = Color(0xFFFF9800)
private val COLOR_B = Color(0xFF9C27B0)
// Smoothed b uses cyan, not a darker purple — the two lines must be
// distinguishable on a shared axis when they nearly overlap. On a well-
// tuned filter they will, and cyan-vs-purple is unambiguous.
private val COLOR_B_SMOOTH = Color(0xFF00BCD4)
private val COLOR_VZ = Color(0xFF00838F)
private val COLOR_VB = Color(0xFFE91E63)
private val COLOR_SIGMA_H = Color(0xFF1976D2)
private val COLOR_SIGMA_B = Color(0xFFC2185B)
private val COLOR_DGPS = Color(0xFFEF6C00)
private val COLOR_DBARO = Color(0xFF00897B)
private val COLOR_CUSUM_POS = Color(0xFFD32F2F)
private val COLOR_CUSUM_NEG = Color(0xFF1976D2)
private val COLOR_REF = Color(0xFFB00020)

data class ChartSeries(
    val label: String,
    val color: Color,
    val values: List<Float?>,
    /**
     * Optional per-sample σ. When non-null, dashed ±σ bands are drawn
     * around the main series in a lighter tint of its color. The bands
     * are visual decorations of the primary series (they don't get their
     * own legend entry). Null elements (including a null element in a
     * non-null [sigma] list) mean "no band at this sample" and leave a
     * gap.
     */
    val sigma: List<Float?>? = null,
)

@Composable
fun KfContent(vm: KfViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()

    // LifecycleResumeEffect: the sandbox runs only while this tab is the
    // visibly-selected content. ON_PAUSE fires on tab switch, app
    // backgrounding, and manual screen-off, so the filter stops in all
    // three cases — the strictest reading of "only while the tab is
    // shown". KeepScreenOn below prevents idle screen timeout from firing
    // ON_PAUSE and stopping the filter mid-convergence.
    //
    // A previous version kept the filter running with the screen off via
    // KfSandboxService (a foreground service). That service is deleted:
    // the sandbox is a diagnostic, not a recorder. Leave it on the tab and
    // the screen stays lit; turn the screen off and the filter stops — the
    // alternative (run overnight in the background) was a feature the
    // user did not ask for and had no visible cost until the battery died.
    KeepScreenOn()
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

        var showCalibrate by remember { mutableStateOf(false) }

        // Read the model's features from the enum (which delegates to the
        // class) rather than comparing enum identity. This is the whole
        // point of the enum feature properties — a new no-bias model would
        // be picked up here automatically, without touching this file.
        val modelHasBias = state.model.hasBias
        val modelHasBaro = state.model.hasBaroMeasurement

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Kalman Filter", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Sandbox — nothing here is saved or shared with the recorder. " +
                            "Runs as a foreground service while this tab is open, so " +
                            "the filter keeps sampling with the screen off.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Model chips + description as one item: they describe each other.
            item {
                KalmanModelSelector(
                    currentModel = state.model,
                    onModelSelected = vm::setModel,
                )
            }

            // Mode chips + description as one item: they describe each other.
            item {
                KalmanModeSelector(
                    currentMode = state.mode,
                    onModeSelected = vm::setMode,
                )
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
                // Rate is fixed in the sandbox — 1 s, same as the recorder's
                // KF-clamped rate. Show it so the user knows what they are
                // observing; there is no user override here because the
                // sandbox's purpose is to show the filter under realistic
                // KF conditions.
                Text(
                    "GNSS rate: 1s (fixed — the filter needs frequent GPS)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 2.dp),
                )
            }
            // Baro toggle is hidden for no-baro models — baro isn't in that
            // model's measurement map, so the toggle would be a lie. An
            // explicit status line is shown when the toggle is hidden so
            // the user can verify the sensor is actually off.
            if (modelHasBaro) {
                item {
                    ToggleRow(
                        title = "Feed barometer",
                        subtitle = baroSubtitle(state),
                        checked = state.baroEnabled,
                        onCheckedChange = vm::setBaroEnabled,
                        enabled = state.hasBarometer,
                    )
                }
            } else {
                item {
                    Text(
                        "Baro sensor: off (this model doesn't fuse baro)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
            item {
                ToggleRow(
                    title = "Apply geoid to GPS height",
                    subtitle = geoidSubtitle(state),
                    checked = state.geoidEnabled,
                    onCheckedChange = vm::setGeoidEnabled,
                )
            }
            // Airplane mode for no-baro models only: switches the model's
            // q_v. Fusion models don't need this — their baro exclusion is
            // handled by the "Feed barometer" toggle above.
            if (!modelHasBaro) {
                item {
                    ToggleRow(
                        title = "Airplane mode",
                        subtitle = "Switches the GPS-only model to a looser q_v for takeoff ramps.",
                        checked = state.airplaneMode,
                        onCheckedChange = vm::setAirplaneMode,
                    )
                }
            }

            item { HorizontalDivider() }
            item { Text("Sandbox", style = MaterialTheme.typography.titleSmall) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(
                        onClick = { showCalibrate = true },
                        // Seed is possible without GPS in two cases: the
                        // fusion models accept a baro-only seed (h snaps to
                        // refAlt, b to baro − refAlt), which is useful at a
                        // surveyed point before GPS locks; and any model
                        // with an h already in state can be re-seeded.
                        // GPS height is only required for a filter that has
                        // never been seeded and has no baro reference.
                        enabled = state.h != null ||
                                  state.gpsInput != null ||
                                  (modelHasBaro && state.baroInput != null),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Seed altitude…")
                    }
                    Text(
                        seedHint(state, modelHasBaro),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item { HorizontalDivider() }
            // Advanced tuning expander. Model-aware: shows only the active
            // model's knobs plus the cross-model baro sigma and GPS floor.
            // "Apply to Track settings" is the explicit promotion path from
            // this sandbox to the persisted config; the panel itself never
            // writes config.
            item {
                KalmanAdvancedPanel(
                    model = state.model,
                    tuning = state.tuning,
                    onChange = vm::setTuning,
                    onPromote = vm::applyToTrackSettings,
                )
            }

            item { HorizontalDivider() }
            item { Text("Filter state", style = MaterialTheme.typography.titleSmall) }
            item {
                // The numeric rows are one tabular block; keeping them in
                // one item gives them the tight internal spacing that reads
                // as a table rather than unrelated lines.
                //
                // v_z and σ(v) are shown in every model. In the 2-state the
                // filter derives them with a cascade position-difference
                // estimator — a leaf of the main filter, no feedback into h
                // or b — so the debug tab shows the same velocity quantities
                // for every model, just sourced differently.
                //
                // v_b and σ(v_b) follow the same pattern: 4-state exposes
                // them as states; 2/3-state exposes them via the cascade;
                // no-bias models have no bias so those rows are hidden.
                //
                // NIS² means are the calibration check: expected ≈ 1.0 under
                // a well-specified model. Drift above 1 indicates the filter
                // is overconfident (R_gps underreported, or P too small);
                // below 1, underconfident. Robust methods (Huber, VBAKF,
                // ISKF) inflate S on rejected samples, so their NIS tends to
                // sit below 1 on outlier-heavy data — that is the intended
                // signature, not a calibration failure.
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    NumericRow(
                        listOf(
                            "h (m)" to fmtM(state.h, 1),
                            "v_z (m/s)" to fmtM(state.v, 2),
                            "b (m)" to if (modelHasBias) fmtSignedM(state.b, 1) else "--",
                        )
                    )
                    NumericRow(
                        listOf(
                            "σ(h) (m)" to fmtM(state.sigmaH, 2),
                            "σ(v) (m/s)" to fmtM(state.sigmaV, 2),
                            "σ(b) (m)" to if (modelHasBias) fmtM(state.sigmaB, 2) else "--",
                        )
                    )
                    if (modelHasBias) {
                        NumericRow(
                            listOf(
                                "v_b (mm/s)" to (state.vB?.let { "%+.2f".format(it * 1000f) } ?: "--"),
                                "σ(v_b) (mm/s)" to (state.sigmaVB?.let { "%.2f".format(it * 1000f) } ?: "--"),
                                "aircon" to if (state.airconDetected) "yes" else "--",
                            )
                        )
                        NumericRow(
                            listOf(
                                "CUSUM+" to (state.cusumPos?.let { "%.2f".format(it) } ?: "--"),
                                "CUSUM−" to (state.cusumNeg?.let { "%.2f".format(it) } ?: "--"),
                                "thr" to "8.0",
                            )
                        )
                    }
                    NumericRow(
                        listOf(
                            "Δgps (m)" to fmtSignedM(state.deltaGps, 1),
                            "Δbaro (m)" to if (modelHasBias) fmtSignedM(state.deltaBaro, 1) else "--",
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
                    NumericRow(
                        listOf(
                            "NIS² gps" to fmtNis(state.nisGpsMean),
                            "NIS² baro" to if (modelHasBias) fmtNis(state.nisBaroMean) else "--",
                            "expected" to "1.0",
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
                // Filtered h alongside its inputs. This is the "what the
                // filter sees and produces" chart; the smoother overlays are
                // in the dedicated charts below.
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
                // Difference chart for h: h's absolute scale varies (stationary
                // vs. climbing vs. descending), so overlaying h and h* would
                // compress the correction into invisibility. The residual-to-
                // smoothed IS the informative envelope here.
                //
                // Reading: near-zero → filter tracks as well as hindsight
                // would; systematic drift at steps → filter over/under-
                // trusts measurements; oscillation at turns → process noise
                // too small for real motion.
                KfChart(
                    title = "h − h* (m)",
                    series = listOf(
                        ChartSeries("h − h*", COLOR_H_DIFF, samples.map {
                            if (it.h != null && it.hSmoothed != null) it.h - it.hSmoothed else null
                        }),
                    ),
                    centerAtZero = true,
                    windowSeconds = windowSec,
                )
            }
            item {
                // v_z with its ±σ band. The band visualizes the filter's
                // own confidence in the velocity estimate: a band much wider
                // than the trace means the estimate is loose, which is
                // normal right after seed or during a GPS-only stretch.
                // In the 2-state the same series is the cascade output with
                // its own scalar covariance.
                KfChart(
                    title = "Vertical speed (m/s)",
                    series = listOf(
                        ChartSeries(
                            "v_z",
                            COLOR_VZ,
                            samples.map { it.v },
                            sigma = samples.map { it.sigmaV },
                        ),
                    ),
                    centerAtZero = true,
                    windowSeconds = windowSec,
                )
            }
            if (modelHasBias) {
                item {
                    // Overlay chart, not difference. For a slow-moving state like
                    // b, the RTS correction is a predictable ramp — the smoother
                    // pulls past b toward the value implied by the final
                    // measurements — so the difference chart collapses to a
                    // linear-to-zero line that hides the underlying trajectory
                    // and looks like an artifact.
                    //
                    // Overlay makes both curves visible:
                    //   - b_filtered drifts (the filter's live estimate)
                    //   - b_smoothed is flatter (hindsight, conditioned on the
                    //     whole buffer)
                    //   - vertical gap = how much hindsight would correct
                    //
                    // When b_filtered and b_smoothed track together, qB is well
                    // matched (or too low — the filter can't keep up with real
                    // drift). When b_filtered drifts away from b_smoothed *with
                    // the gap growing backward in time*, qB is too high — the
                    // filter is chasing noise that hindsight rejects. This is the
                    // direct visual comparison the difference chart obscured.
                    //
                    // Not centered at zero: b is a signed offset, not a residual.
                    // Forcing a symmetric axis doubles the visible span when b is
                    // parked at, say, +40 m and compresses the b-vs-b* gap that
                    // the chart exists to show.
                    KfChart(
                        title = "Bias b (m)",
                        series = listOf(
                            ChartSeries("b", COLOR_B, samples.map { it.b }),
                            ChartSeries("b*", COLOR_B_SMOOTH, samples.map { it.bSmoothed }),
                        ),
                        centerAtZero = false,
                        windowSeconds = windowSec,
                    )
                }
                item {
                    // v_b with its ±σ band, same convention as v_z above. In
                    // the 4-state this is a state and σ(v_b) comes from P[3,3];
                    // in 2/3-state it's the cascade output with its own scalar
                    // covariance. Cascade is visibly noisier; swap models to
                    // compare the same trajectory under both.
                    //
                    // Reading: a trace that stays well inside the band means
                    // v_b is poorly determined (typical right after seed). A
                    // trace that pins the band — |v_b| ≳ 2σ — is the CUSUM's
                    // raw signal, even before the accumulator crosses threshold.
                    KfChart(
                        title = "Bias rate v_b (mm/s)",
                        series = listOf(
                            ChartSeries(
                                "v_b",
                                COLOR_VB,
                                samples.map { s -> s.vB?.let { it * 1000f } },
                                sigma = samples.map { s -> s.sigmaVB?.let { it * 1000f } },
                            ),
                        ),
                        centerAtZero = false,
                        windowSeconds = windowSec,
                    )
                    Text(
                        "Cascade v_b (2/3-state) is noisier than the 4-state's v_b state. " +
                            "Swap models to compare the same trajectory under both.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
            }
            item {
                KfChart(
                    title = "Uncertainty (m)",
                    series = buildList {
                        add(ChartSeries("σ(h)", COLOR_SIGMA_H, samples.map { it.sigmaH }))
                        if (modelHasBias) {
                            add(ChartSeries("σ(b)", COLOR_SIGMA_B, samples.map { it.sigmaB }))
                        }
                    },
                    windowSeconds = windowSec,
                )
            }
            item {
                KfChart(
                    title = "Innovation (m)",
                    series = buildList {
                        add(ChartSeries("Δgps", COLOR_DGPS, samples.map { it.deltaGps }))
                        if (modelHasBias) {
                            add(ChartSeries("Δbaro", COLOR_DBARO, samples.map { it.deltaBaro }))
                        }
                    },
                    centerAtZero = true,
                    windowSeconds = windowSec,
                )
            }
            // NIS² GPS is valid for EVERY model — it is the normalized
            // squared GPS innovation, a pure GPS statistic that has nothing
            // to do with whether the model fuses baro. It is one of the
            // primary calibration checks the tab exists to show, so it must
            // NOT be hidden for no-bias models. Only the baro series is
            // gated. Same shape as the Innovation chart above.
            item {
                // NIS² sits below Innovation: same quantity, normalized.
                // Reference line at 3.84 = χ²(1, 0.95). Robust methods push
                // S up on rejected samples, so NIS² runs below the line by
                // design; under Gaussian, a well-specified model yields ~5%
                // of samples above the line and a running mean near 1.0.
                KfChart(
                    title = "Innovation consistency (NIS²)",
                    series = buildList {
                        add(ChartSeries("gps", COLOR_DGPS, samples.map { it.nisGps }))
                        if (modelHasBias) {
                            add(ChartSeries("baro", COLOR_DBARO, samples.map { it.nisBaro }))
                        }
                    },
                    referenceLines = listOf(3.84f),
                    yClampMax = 10f,
                    windowSeconds = windowSec,
                )
                Text(
                    "Dashed line: χ²(1, 0.95) = 3.84. Valid under Gaussian. " +
                        "Robust methods inflate S on rejected samples, so NIS² sits below the line by design.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
            // CUSUM is intrinsically a baro detector — it tests baro
            // innovations, so hiding it for no-baro models is honest, not
            // a bug.
            if (modelHasBias) {
                item {
                    // CUSUM on the normalized baro innovation. Two accumulators
                    // (positive and negative drift) share a threshold line. A
                    // crossing is the detector firing — aircon step in v_b.
                    // Dashed reference line at 8 (the threshold).
                    KfChart(
                        title = "Aircon CUSUM",
                        series = listOf(
                            ChartSeries("g⁺", COLOR_CUSUM_POS, samples.map { it.cusumPos }),
                            ChartSeries("g⁻", COLOR_CUSUM_NEG, samples.map { it.cusumNeg }),
                        ),
                        referenceLines = listOf(8f),
                        windowSeconds = windowSec,
                    )
                    Text(
                        "Dashed line: CUSUM threshold h = 8. Crossing = aircon-like step in v_b. " +
                            "Tests model consistency, not |v_b| — weather (any magnitude) stays below.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
            }

            item { HorizontalDivider() }
            item {
                FilledTonalButton(onClick = vm::resetFilter) {
                    Text("Reset filter")
                }
            }
        }

        if (showCalibrate) {
            BaroCalibrationDialog(
                currentBaro = state.baroInput ?: 0f,
                gpsOrthoAlt = state.gpsInput,
                kfAlt       = state.h,
                onCalibrate = { ref ->
                    vm.calibrateBaro(ref)
                    showCalibrate = false
                },
                onDismiss   = { showCalibrate = false },
            )
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
 *
 * [referenceLines] draws dashed horizontal lines at the given y-values; used
 * by the NIS² chart for the χ²(1, 0.95) threshold and by the CUSUM chart for
 * the detection threshold. Values above the reference line are the tail of
 * interest (α-sized, in the calibrated-model case).
 *
 * [yClampMax], when set, clips the visible y range to [min, yClampMax] and
 * clamps plotted values to that range. Without it, a single NIS² spike of
 * 100 would compress the ~1 values near zero, making the chart unreadable.
 * The clamp is a display-only operation; the underlying samples are not
 * truncated.
 *
 * Per-series σ bands (see [ChartSeries.sigma]): when present, dashed ±σ
 * lines are drawn in a lighter tint of the series color, before the main
 * line so the latter is on top. The axis range is extended to cover the
 * band, so the trace is never clipped by its own uncertainty. The bands
 * are visual decorations of the primary series and don't get their own
 * legend entries.
 */
@Composable
private fun KfChart(
    title: String,
    series: List<ChartSeries>,
    modifier: Modifier = Modifier,
    centerAtZero: Boolean = false,
    referenceLines: List<Float> = emptyList(),
    yClampMax: Float? = null,
    windowSeconds: Int = 90,
) {
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val refColor = COLOR_REF
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

            // Axis range covers the main series AND any sigma bands; without
            // this the band lines would be clipped at the plot edges when
            // the estimate is tight but the band is wide (post-seed, post-swap).
            val all = series.flatMap { s ->
                val main = s.values.filterNotNull()
                val band = s.sigma?.let { sigmas ->
                    s.values.zip(sigmas).flatMap { (v, sigma) ->
                        if (v != null && sigma != null) listOf(v - sigma, v + sigma) else emptyList()
                    }
                } ?: emptyList()
                main + band
            }
            if (all.size < 2) {
                drawCenteredText(
                    textMeasurer, "no data yet",
                    plotWidth / 2, plotHeight / 2,
                    TextStyle(fontSize = 11.sp, color = labelColor),
                )
                return@Canvas
            }

            val dataLo = all.min()
            val dataHiRaw = all.max()
            val dataHi = if (yClampMax != null) minOf(dataHiRaw, yClampMax) else dataHiRaw
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

            // User-supplied reference lines (dashed). Used for statistical
            // thresholds like χ²(1, 0.95) on the NIS chart and CUSUM h on
            // the detector chart.
            val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
            for (ref in referenceLines) {
                if (ref in yLo..yHi) {
                    val refY = plotHeight * (1f - (ref - yLo) / yRange)
                    drawLine(
                        color = refColor.copy(alpha = 0.55f),
                        start = Offset(0f, refY),
                        end = Offset(plotWidth, refY),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = dash,
                    )
                }
            }

            // Sigma bands (dashed), drawn BEFORE the main series so the
            // series is on top. Two lines per series (±σ), lighter tint of
            // the series color.
            val bandDash = PathEffect.dashPathEffect(floatArrayOf(4f, 4f), 0f)
            for (s in series) {
                val sigmas = s.sigma ?: continue
                val bandColor = s.color.copy(alpha = 0.45f)
                for (sign in intArrayOf(-1, 1)) {
                    var prevX: Float? = null
                    var prevY: Float? = null
                    for (i in 0 until s.values.size) {
                        val v = s.values[i]
                        val sigma = sigmas.getOrNull(i)
                        if (v == null || sigma == null) {
                            prevX = null; prevY = null
                            continue
                        }
                        val bandValue = v + sign * sigma
                        val x = plotWidth * i / (s.values.size - 1)
                        val y = plotHeight * (1f - (bandValue - yLo) / yRange)
                        if (prevX != null && prevY != null) {
                            drawLine(
                                color = bandColor,
                                start = Offset(prevX, prevY),
                                end = Offset(x, y),
                                strokeWidth = 1.dp.toPx(),
                                pathEffect = bandDash,
                            )
                        }
                        prevX = x
                        prevY = y
                    }
                }
            }

            // Main series lines.
            for (s in series) {
                val n = s.values.size
                if (n < 2) continue
                var prevX: Float? = null
                var prevY: Float? = null
                for (i in 0 until n) {
                    val raw = s.values[i]
                    if (raw == null) {
                        prevX = null; prevY = null
                        continue
                    }
                    val value = if (yClampMax != null) minOf(raw, yClampMax) else raw
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

/**
 * Helper for the Seed button's hint text. Handles three cases:
 *
 *  - Filter has never been seeded and no baro reference exists — needs GPS.
 *  - Filter has never been seeded but baro is available on a baro-fusing
 *    model — can seed from a manual altitude plus the current baro reading.
 *  - Filter is already seeded (h in state) — re-seeding is always allowed.
 */
private fun seedHint(state: KfUiState, modelHasBaro: Boolean): String = when {
    state.h != null ->
        "Snap h to a known altitude. The b state absorbs remaining bias (fusion models only)."
    state.gpsInput != null ->
        "Uses the current GPS height as the reference."
    modelHasBaro && state.baroInput != null ->
        "Enable GNSS or seed from a known altitude using the current baro reading."
    else ->
        "Enable GNSS and wait for a fix to seed."
}

private fun fmtM(value: Float?, decimals: Int): String =
    value?.let { "%.${decimals}f".format(it) } ?: "--"

private fun fmtSignedM(value: Float?, decimals: Int): String =
    value?.let { "%+.${decimals}f".format(it) } ?: "--"

private fun fmtNis(value: Float?): String =
    value?.let { "%.2f".format(it) } ?: "--"
