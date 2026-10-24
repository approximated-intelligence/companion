package de.perigon.companion.sensors.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.perigon.companion.sensors.domain.KalmanMode
import de.perigon.companion.sensors.domain.KalmanModel

/**
 * Shared model/mode selectors used by both the Track tab and the KF sandbox
 * tab. One source of truth for the chip set, the labels, and the explanatory
 * copy below the chips, so the two tabs cannot drift apart.
 */
@Composable
fun KalmanModelSelector(
    currentModel: KalmanModel,
    onModelSelected: (KalmanModel) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            KalmanModel.entries.forEach { m ->
                FilterChip(
                    selected = currentModel == m,
                    onClick  = { onModelSelected(m) },
                    label    = { Text(m.displayName) },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            currentModel.description(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun KalmanModeSelector(
    currentMode: KalmanMode,
    onModeSelected: (KalmanMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            KalmanMode.entries.forEach { m ->
                FilterChip(
                    selected = currentMode == m,
                    onClick  = { onModeSelected(m) },
                    label    = { Text(m.shortLabel()) },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            currentMode.description(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

fun KalmanModel.description(): String = when (this) {
    KalmanModel.THREE_STATE ->
        "h, v_z, b. v_z is a proper KF state; velocity observable from GPS but weakly, so σ_h grows as t^{3/2} during outages. Better for short outages with well-tracked velocity; worse for long ones."
    KalmanModel.TWO_STATE ->
        "h, b. Baro drives h at full bandwidth; no velocity state steals baro information. v_z is derived by a cascade position-difference filter (leaf; no feedback). σ_h grows as t^{1/2} during outages. Better for long outages; worse for short ones on a moving vehicle."
    KalmanModel.FOUR_STATE ->
        "h, v_z, b, v_b. CV on both h and b; v_b absorbs aircon and weather drift as a rate. Tight q_vb makes aircon a step violation detectable by the CUSUM on baro innovations. Slightly more confident about b during long outages than 3-state (t³ vs t growth on σ_b²)."
    KalmanModel.GPS_TWO_STATE ->
        "h, v_z. GPS-only smoother — no barometer, no bias state. Same geoid-corrected MSL input as the other models. Use when baro is unavailable or untrusted; does not absorb weather drift or aircon. Airplane mode switches to a looser q_v for takeoff ramps."
}

fun KalmanMode.shortLabel(): String = when (this) {
    KalmanMode.GAUSSIAN -> "Gaussian"
    KalmanMode.HUBER    -> "Huber"
    KalmanMode.VBAKF    -> "VBAKF"
    KalmanMode.ISKF     -> "ISKF"
}

fun KalmanMode.description(): String = when (this) {
    KalmanMode.GAUSSIAN ->
        "Plain Kalman filter. No outlier rejection; the reference."
    KalmanMode.HUBER ->
        "Huber M-estimation: downweights measurement outliers, Joseph-form covariance."
    KalmanMode.VBAKF ->
        "Variational Bayesian: estimates the measurement noise covariance online (inverse-gamma prior)."
    KalmanMode.ISKF ->
        "Iteratively Saturated KF (Yang & Boyd 2025): convex robust MAP with separate process and measurement outlier terms."
}
