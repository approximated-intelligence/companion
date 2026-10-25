package de.perigon.companion.sensors.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.perigon.companion.core.ui.cardinal
import kotlin.math.sqrt

@Composable
fun RawSensorsContent(vm: DashboardViewModel) {
    val raw by vm.rawSensors.collectAsStateWithLifecycle()

    // Raw sensors are only useful while this tab is on screen. Previously
    // they were registered unconditionally in the ViewModel's init block
    // and kept firing (throttled to 10 Hz) for the whole lifetime of the
    // Sensors nav entry — even when only the Track tab was ever shown.
    // GNSS (needed for the declination row below) is hoisted to
    // SensorsScreen and so is live on every tab.
    LifecycleResumeEffect(Unit) {
        vm.startRawSensors()
        onPauseOrDispose { vm.stopRawSensors() }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SensorGroupCard("Acceleration and Gravity") {
                MagnitudeRow(
                    label = "|g|",
                    value = magnitude3(raw.accelX, raw.accelY, raw.accelZ),
                    unit = "m/s²",
                    format = "%.3f",
                )
                Spacer(Modifier.height(8.dp))
                SensorRow3(
                    listOf(
                        Triple("gx", "m/s²", raw.accelX?.let { "%.3f".format(it) }),
                        Triple("gy", "m/s²", raw.accelY?.let { "%.3f".format(it) }),
                        Triple("gz", "m/s²", raw.accelZ?.let { "%.3f".format(it) }),
                    )
                )
            }
        }
        item {
            SensorGroupCard("Rotation") {
                MagnitudeRow(
                    label = "|ω|",
                    value = magnitude3(raw.gyroX, raw.gyroY, raw.gyroZ),
                    unit = "rad/s",
                    format = "%.4f",
                )
                Spacer(Modifier.height(8.dp))
                SensorRow3(
                    listOf(
                        Triple("ωx", "rad/s", raw.gyroX?.let { "%.4f".format(it) }),
                        Triple("ωy", "rad/s", raw.gyroY?.let { "%.4f".format(it) }),
                        Triple("ωz", "rad/s", raw.gyroZ?.let { "%.4f".format(it) }),
                    )
                )
            }
        }
        item {
            SensorGroupCard("Magnetic Field") {
                MagnitudeRow(
                    label = "|B|",
                    value = magnitude3(raw.magX, raw.magY, raw.magZ),
                    unit = "μT",
                    format = "%.2f",
                )
                Spacer(Modifier.height(8.dp))
                SensorRow3(
                    listOf(
                        Triple("Bx", "μT", raw.magX?.let { "%.2f".format(it) }),
                        Triple("By", "μT", raw.magY?.let { "%.2f".format(it) }),
                        Triple("Bz", "μT", raw.magZ?.let { "%.2f".format(it) }),
                    )
                )
            }
        }
        item {
            SensorGroupCard("Orientation") {
                SensorRow3(
                    listOf(
                        Triple("Azimuth (magnetic)", "", raw.azimuth?.let { "${it.toInt()}° ${cardinal(it)}" }),
                        Triple("Pitch",            "", raw.pitch?.let   { "%.0f°".format(it) }),
                        Triple("Roll",             "", raw.roll?.let    { "%.0f°".format(it) }),
                    )
                )
                Spacer(Modifier.height(8.dp))
                SensorRow3(
                    listOf(
                        Triple(
                            "Declination",
                            "",
                            raw.declinationDeg?.let { "%+.1f°".format(it) } ?: "--",
                        ),
                        Triple(
                            "Azimuth (true)",
                            "",
                            raw.trueAzimuthDeg?.let { "${it.toInt()}° ${cardinal(it)}" } ?: "--",
                        ),
                    )
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Declination: WMM2025 at the current GPS position. Positive = magnetic north east of true north.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            SensorGroupCard("Miscellaneous") {
                SensorRow3(
                    listOf(
                        Triple("Temperature", "°C",  raw.temp?.let     { "%.1f".format(it) }),
                        Triple("Pressure",    "hPa", raw.pressure?.let { "%.1f".format(it) }),
                        Triple("Humidity",    "%",   raw.humidity?.let { "%.1f".format(it) }),
                    )
                )
                Spacer(Modifier.height(8.dp))
                SensorRow3(
                    listOf(
                        Triple("Light",     "lux", raw.light?.let     { "%.1f".format(it) }),
                        Triple("Proximity", "cm",  raw.proximity?.let { "%.1f".format(it) }),
                    )
                )
            }
        }
    }
}

@Composable
private fun SensorGroupCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}

@Composable
private fun MagnitudeRow(
    label: String,
    value: Float?,
    unit: String,
    format: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 3.dp),
        )
        Text(
            value?.let { format.format(it) } ?: "--",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            unit,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
    }
}

@Composable
private fun SensorRow3(items: List<Triple<String, String, String?>>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { (label, unit, value) ->
            SensorValueCell(label, unit, value, Modifier.weight(1f))
        }
        repeat(3 - items.size) { Box(Modifier.weight(1f)) }
    }
}

@Composable
private fun SensorValueCell(
    label: String,
    unit: String,
    value: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(
            text = if (unit.isNotEmpty()) "$label ($unit)" else label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value ?: "--",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private fun magnitude3(x: Float?, y: Float?, z: Float?): Float? {
    if (x == null || y == null || z == null) return null
    return sqrt(x * x + y * y + z * z)
}
