package de.perigon.companion.sensors.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * Shared by the Track live card and the KF sandbox's Seed button. Both seed
 * the altitude Kalman filter once via `resetWithAltitude(ref, baro)`. No
 * offset is stored — the filter's bias state absorbs any remaining weather
 * drift.
 *
 * [currentBaro] is the raw ISA barometric altitude (what
 * `SensorManager.getAltitude(1013.25, P)` returns). [gpsOrthoAlt] is the
 * current GPS altitude (orthometric if geoid is enabled). [kfAlt] is the
 * filter's current h, useful context but not a valid seed reference on its
 * own (it's what we're calibrating).
 *
 * Either button sets the filter's h to the reference altitude and snaps b
 * to `baro - ref`, keeping velocity and bias-rate covariances in the 3/4-
 * state models. The calibration is a one-shot; nothing persists.
 */
@Composable
fun BaroCalibrationDialog(
    currentBaro: Float,
    gpsOrthoAlt: Float?,
    kfAlt: Float?,
    onCalibrate: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    var manualInput by remember { mutableStateOf("") }
    val manualValue = manualInput.toFloatOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Initial barometric calibration") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Raw ISA baro: %.1f m".format(currentBaro))
                if (kfAlt != null) {
                    Text("KF altitude: %.1f m".format(kfAlt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    "This seeds the Kalman filter once so it converges immediately " +
                        "instead of over several minutes. It does not store a persistent " +
                        "offset — the filter's bias state absorbs any remaining weather drift.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (gpsOrthoAlt != null) {
                    OutlinedButton(
                        onClick = { onCalibrate(gpsOrthoAlt) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Use GPS height (%.1f m)".format(gpsOrthoAlt))
                    }
                }
                OutlinedTextField(
                    value = manualInput,
                    onValueChange = { manualInput = it.filter { c -> c.isDigit() || c == '.' || c == '-' } },
                    label = { Text("Manual altitude (m)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { manualValue?.let(onCalibrate) },
                enabled = manualValue != null,
            ) { Text("Seed") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
