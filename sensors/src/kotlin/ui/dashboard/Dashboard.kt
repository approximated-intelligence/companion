package de.perigon.companion.sensors.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.perigon.companion.sensors.gnss.SatelliteObservation
import java.util.Locale

data class GnssUiState(
    val satellites: List<SatelliteObservation> = emptyList(),
    val latitude: Double? = null,
    val longitude: Double? = null,
    /**
     * Chip's ECEF-derived ellipsoidal height. Exact as reported by the
     * source's position solution; independent of any geoid model.
     */
    val altitudeWgs84: Double? = null,
    /**
     * MSL derived from [altitudeWgs84] via XGM2019e. Same semantics on
     * both sources — the app's geoid is authoritative for MSL.
     */
    val altitudeMsl: Double? = null,
    val speedMs: Float? = null,
    val bearing: Float? = null,
    val accuracy: Float? = null,
    val ttffSeconds: Int? = null,
    val lastFixTimeMs: Long? = null,
    // Per-fix reality vs. session setting. Populated on every fix from
    // GnssFix.source and GnssController.sourceType respectively; mismatch
    // is highlighted in GnssContent so a settings/reality divergence is
    // visible without opening any other screen.
    val fixSource: String? = null,
    val setSource: String? = null,
)

/**
 * A cell observed by the telephony callback.
 *
 * [firstSeenMs] and [lastSeenMs] are `SystemClock.elapsedRealtime()` values,
 * not wall clock: they are used only to age cells out of the accumulation
 * buffer and to compute "seen Xs ago" labels, both of which are monotonic
 * durations and immune to clock adjustments.
 *
 * Both are zero for a fresh [toTower] result; [DashboardViewModel]'s
 * accumulation step fills them via `copy`. Anything downstream reading these
 * fields should treat zero as "unknown" rather than "seen at boot".
 */
@Immutable
data class CellSignalMetrics(
    /** Base-class getDbm(); synthesised for LTE, often UNAVAILABLE for NR. */
    val dbm: Int? = null,
    /** Universal 0..4 level. 0 = SIGNAL_STRENGTH_NONE_OR_UNKNOWN, sanitised to null. */
    val level: Int? = null,
    /** LTE: getRsrp(); NR: getSsRsrp(). Plain dBm. */
    val rsrpDbm: Int? = null,
    /** LTE: getRsrq(); NR: getSsRsrq(). Plain dB. */
    val rsrqDb: Int? = null,
    /** LTE: getRssnr(); NR: getSsSinr(). Plain dB. */
    val sinrDb: Int? = null,
    /** LTE-only: getTimingAdvance(). */
    val timingAdvance: Int? = null,
) {
    val hasAny: Boolean
        get() = dbm != null || level != null || rsrpDbm != null
}

@Immutable
data class CellTowerInfo(
    val type: String,
    val mcc: String,
    val mnc: String,
    val cid: String,
    val metrics: CellSignalMetrics = CellSignalMetrics(),
    val firstSeenMs: Long = 0L,
    val lastSeenMs: Long = 0L,
)

@Immutable
data class WifiInfo(
    val ssid: String, val bssid: String, val frequencyMhz: Int, val signalDbm: Int,
)

/**
 * Position + kinematics grid shared by the GNSS tab and the NMEA tab's
 * Location comparison card. Fixed 2×2: heights on top (where the device
 * is), speed and heading on bottom (what the device is doing). Every cell
 * is always present; a source that cannot supply one renders "--" so the
 * shape is identical regardless of source.
 *
 * Not used by the Track live card: that card has its own typography
 * (headline-sized heading, distance in a colored container, per-fix
 * diagnostic rows) and calling this would flatten it.
 */
@Composable
fun PositionReadings(
    wgs84M: Double?,
    wgs84SigmaM: Double?,
    mslM: Double?,
    speedMps: Double?,
    headingDeg: Double?,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ReadingCell(
                label = "Height WGS84",
                value = wgs84M?.let { "%.1f m".format(Locale.US, it) } ?: "--",
                tail = wgs84SigmaM?.let { "± %.1f m".format(Locale.US, it) },
                modifier = Modifier.weight(1f),
            )
            ReadingCell(
                label = "Height MSL",
                value = mslM?.let { "%.1f m".format(Locale.US, it) } ?: "--",
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ReadingCell(
                label = "Speed",
                value = speedMps?.let { "%.1f km/h".format(Locale.US, it * 3.6) } ?: "--",
                modifier = Modifier.weight(1f),
            )
            ReadingCell(
                label = "Heading",
                value = headingDeg?.let { "%.0f°".format(Locale.US, it) } ?: "--",
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ReadingCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    tail: String? = null,
) {
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                value,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            if (tail != null) {
                Text(
                    tail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Immutable
data class RawSensorValues(
    val accelX: Float? = null, val accelY: Float? = null, val accelZ: Float? = null,
    val gyroX: Float? = null, val gyroY: Float? = null, val gyroZ: Float? = null,
    val magX: Float? = null, val magY: Float? = null, val magZ: Float? = null,
    val azimuth: Float? = null, val pitch: Float? = null, val roll: Float? = null,
    // Magnetic → true-north correction, positive = magnetic north east of
    // true north. Null until a GNSS fix is available (declination is a
    // function of position and time, via WMM2025). Computed for the
    // current position, not stored — the value changes as you move.
    val declinationDeg: Float? = null,
    // Magnetic azimuth + declination, normalised to [0, 360). Null when
    // [declinationDeg] is null (no position).
    val trueAzimuthDeg: Float? = null,
    val light: Float? = null, val pressure: Float? = null, val proximity: Float? = null,
    val humidity: Float? = null, val temp: Float? = null,
)
