package de.perigon.companion.sensors.ui.dashboard

import androidx.compose.runtime.Immutable

data class SatelliteInfo(
    val svid: Int,
    val constellation: Int,
    val azimuth: Float,
    val elevation: Float,
    val snr: Float,
    val usedInFix: Boolean
)

data class GnssUiState(
    val satellites: List<SatelliteInfo> = emptyList(),
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitude: Double? = null,
    val speedMs: Float? = null,
    val bearing: Float? = null,
    val accuracy: Float? = null,
    val ttffSeconds: Int? = null,
    val lastFixTimeMs: Long? = null,
)

/**
 * A cell observed by the telephony callback.
 *
 * [firstSeenMs] and [lastSeenMs] are `SystemClock.elapsedRealtime()` values,
 * not wall clock: they are used only to age cells out of the accumulation
 * buffer and to compute "seen Xs ago" labels, both of which are monotonic
 * durations and immune to clock adjustments.
 *
 * Both are zero for a fresh [toTower] result; [SensorDashboardViewModel]'s
 * accumulation step fills them via `copy`. Anything downstream reading these
 * fields should treat zero as "unknown" rather than "seen at boot".
 */
@Immutable
data class CellTowerInfo(
    val type: String,
    val mcc: String,
    val mnc: String,
    val cid: String,
    val signalDbm: Int,
    val firstSeenMs: Long = 0L,
    val lastSeenMs: Long = 0L,
)

@Immutable
data class WifiInfo(
    val ssid: String,
    val bssid: String,
    val frequencyMhz: Int,
    val signalDbm: Int
)

@Immutable
data class RawSensorValues(
    val accelX: Float? = null, val accelY: Float? = null, val accelZ: Float? = null,
    val gyroX: Float? = null, val gyroY: Float? = null, val gyroZ: Float? = null,
    val magX: Float? = null, val magY: Float? = null, val magZ: Float? = null,
    val azimuth: Float? = null, val pitch: Float? = null, val roll: Float? = null,
    val light: Float? = null, val pressure: Float? = null, val proximity: Float? = null,
    val humidity: Float? = null, val temp: Float? = null,
)
