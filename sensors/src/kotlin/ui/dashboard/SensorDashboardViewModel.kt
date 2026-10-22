package de.perigon.companion.sensors.ui.dashboard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.telephony.CellIdentityNr
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import de.perigon.companion.core.geo.CoordFormat
import de.perigon.companion.sensors.data.TrackPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SensorDashboardViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val trackPrefs: TrackPrefs,
) : ViewModel() {

    private val _gnssState = MutableStateFlow(GnssUiState())
    val gnssState: StateFlow<GnssUiState> = _gnssState.asStateFlow()

    private val _cellTowers = MutableStateFlow<List<CellTowerInfo>>(emptyList())
    val cellTowers: StateFlow<List<CellTowerInfo>> = _cellTowers.asStateFlow()

    private val _wifiNetworks = MutableStateFlow<List<WifiInfo>>(emptyList())
    val wifiNetworks: StateFlow<List<WifiInfo>> = _wifiNetworks.asStateFlow()

    private val _rawSensors = MutableStateFlow(RawSensorValues())
    val rawSensors: StateFlow<RawSensorValues> = _rawSensors.asStateFlow()

    val coordFormat: StateFlow<CoordFormat> = trackPrefs.observeCoordFormat()
        .stateIn(viewModelScope, SharingStarted.Eagerly, CoordFormat.DD)

    val backgroundGpsEnabled: StateFlow<Boolean> = trackPrefs.observeBackgroundGps()
        .stateIn(viewModelScope, SharingStarted.Eagerly, trackPrefs.isBackgroundGpsEnabled())

    fun setCoordFormat(format: CoordFormat) {
        viewModelScope.launch { trackPrefs.setCoordFormat(format) }
    }

    private var gnssRegistered = false
    private var locationListener: LocationListener? = null
    private var gnssCallback: GnssStatus.Callback? = null

    private var telephonyCallback: TelephonyCallback? = null

    private var wifiCallback: WifiManager.ScanResultsCallback? = null

    private var rawSensorListener: SensorEventListener? = null
    private var rawSensorManager: SensorManager? = null

    private var lastAccel: FloatArray? = null
    private var lastMag: FloatArray? = null

    private val lastUpdateNs = HashMap<Int, Long>(16)

    // ---- Permission helpers ----

    private fun hasFineLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * Cell info requires BOTH permissions on API 30+: READ_PHONE_STATE for
     * the callback to fire, ACCESS_FINE_LOCATION for the cells not to be
     * redacted.
     */
    private fun checkTelephonyPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED && hasFineLocationPermission()

    private fun checkWifiPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.NEARBY_WIFI_DEVICES
        ) == PackageManager.PERMISSION_GRANTED

    // ---- GNSS ----

    fun startGnss() {
        if (gnssRegistered || !hasFineLocationPermission()) return
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                _gnssState.update {
                    it.copy(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        altitude = if (location.hasAltitude()) location.altitude else null,
                        speedMs = if (location.hasSpeed()) location.speed else null,
                        bearing = if (location.hasBearing()) location.bearing else null,
                        accuracy = if (location.hasAccuracy()) location.accuracy else null,
                        lastFixTimeMs = System.currentTimeMillis(),
                    )
                }
            }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        val callback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                val sats = ArrayList<SatelliteInfo>(status.satelliteCount)
                for (i in 0 until status.satelliteCount) {
                    sats.add(
                        SatelliteInfo(
                            svid = status.getSvid(i),
                            constellation = status.getConstellationType(i),
                            azimuth = status.getAzimuthDegrees(i),
                            elevation = status.getElevationDegrees(i),
                            snr = status.getCn0DbHz(i),
                            usedInFix = status.usedInFix(i),
                        )
                    )
                }
                _gnssState.update { it.copy(satellites = sats) }
            }
        }

        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener)
            lm.registerGnssStatusCallback(context.mainExecutor, callback)
            locationListener = listener
            gnssCallback = callback
            gnssRegistered = true
        } catch (e: SecurityException) {}
    }

    fun stopGnss() {
        if (!gnssRegistered) return
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locationListener?.let { lm.removeUpdates(it) }
        gnssCallback?.let { lm.unregisterGnssStatusCallback(it) }
        locationListener = null
        gnssCallback = null
        gnssRegistered = false
    }

    // ---- Raw sensors ----

    fun startRawSensors() {
        if (rawSensorListener != null) return
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rawSensorManager = sm

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val type = event.sensor.type
                val now = event.timestamp
                val last = lastUpdateNs[type] ?: 0L
                if (now - last < SENSOR_MIN_INTERVAL_NS) return
                lastUpdateNs[type] = now

                when (type) {
                    Sensor.TYPE_ACCELEROMETER -> {
                        lastAccel = event.values.clone()
                        _rawSensors.update {
                            it.copy(
                                accelX = event.values[0],
                                accelY = event.values[1],
                                accelZ = event.values[2],
                            )
                        }
                        updateOrientation()
                    }
                    Sensor.TYPE_GYROSCOPE -> _rawSensors.update {
                        it.copy(
                            gyroX = event.values[0],
                            gyroY = event.values[1],
                            gyroZ = event.values[2],
                        )
                    }
                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        lastMag = event.values.clone()
                        _rawSensors.update {
                            it.copy(
                                magX = event.values[0],
                                magY = event.values[1],
                                magZ = event.values[2],
                            )
                        }
                        updateOrientation()
                    }
                    Sensor.TYPE_LIGHT -> _rawSensors.update { it.copy(light = event.values[0]) }
                    Sensor.TYPE_PRESSURE -> _rawSensors.update { it.copy(pressure = event.values[0]) }
                    Sensor.TYPE_PROXIMITY -> _rawSensors.update { it.copy(proximity = event.values[0]) }
                    Sensor.TYPE_RELATIVE_HUMIDITY -> _rawSensors.update { it.copy(humidity = event.values[0]) }
                    Sensor.TYPE_AMBIENT_TEMPERATURE -> _rawSensors.update { it.copy(temp = event.values[0]) }
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        listOf(
            Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE, Sensor.TYPE_MAGNETIC_FIELD,
            Sensor.TYPE_LIGHT, Sensor.TYPE_PRESSURE, Sensor.TYPE_PROXIMITY,
            Sensor.TYPE_RELATIVE_HUMIDITY, Sensor.TYPE_AMBIENT_TEMPERATURE,
        ).forEach { type ->
            sm.getDefaultSensor(type)?.let {
                sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI)
            }
        }
        rawSensorListener = listener
    }

    fun stopRawSensors() {
        val listener = rawSensorListener ?: return
        rawSensorManager?.unregisterListener(listener)
        rawSensorListener = null
        rawSensorManager = null
        lastAccel = null
        lastMag = null
        lastUpdateNs.clear()
    }

    private fun updateOrientation() {
        val a = lastAccel ?: return
        val m = lastMag ?: return
        val r = FloatArray(9)
        if (!SensorManager.getRotationMatrix(r, null, a, m)) return
        val o = FloatArray(3)
        SensorManager.getOrientation(r, o)
        val azimuthDeg = Math.toDegrees(o[0].toDouble()).toFloat()
        _rawSensors.update {
            it.copy(
                azimuth = (azimuthDeg + 360f) % 360f,
                pitch = Math.toDegrees(o[1].toDouble()).toFloat(),
                roll = Math.toDegrees(o[2].toDouble()).toFloat(),
            )
        }
    }

    // ---- Telephony ----

    fun startTelephony() {
        if (telephonyCallback != null || !checkTelephonyPermission()) return

        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val callback = object : TelephonyCallback(), TelephonyCallback.CellInfoListener {
            override fun onCellInfoChanged(cellInfo: MutableList<android.telephony.CellInfo>) {
                _cellTowers.value = cellInfo.mapNotNull { info -> info.toTower() }
            }
        }
        try {
            tm.registerTelephonyCallback(context.mainExecutor, callback)
            telephonyCallback = callback
            // Seed from the current snapshot — registerTelephonyCallback
            // does not guarantee an immediate onCellInfoChanged.
            val initial = try { tm.allCellInfo } catch (e: SecurityException) { null }
            if (initial != null) {
                _cellTowers.value = initial.mapNotNull { info -> info.toTower() }
            }
        } catch (e: SecurityException) {}
    }

    fun stopTelephony() {
        val callback = telephonyCallback ?: return
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        try {
            tm.unregisterTelephonyCallback(callback)
        } catch (e: Exception) {}
        telephonyCallback = null
    }

    // ---- WiFi ----

    /**
     * Push, not poll. `registerScanResultsCallback` (API 30+) fires once per
     * completed platform scan; the poll that used to live here read the same
     * `scanResults` cache every 5 s and returned the same list between scans.
     * The platform throttles `startScan()` to roughly 4 per 2 minutes in the
     * foreground and disables it while idle, so the notification rate is
     * whatever the OS scanner produces — this callback is informed of it
     * without doing any work in between.
     *
     * The callback carries no payload: the API contract is "results changed,
     * go read them". `scanResults` is a synchronous getter (cached in the
     * WiFi service), so the read is cheap; it runs on `context.mainExecutor`
     * for consistency with `startGnss()` and `startTelephony()` above. The
     * previous poll also ran it on main (via `viewModelScope`), so this is
     * not a regression — it just eliminates the idle wakeups.
     *
     * Seed from the current cache on registration: the callback only fires
     * after the NEXT platform scan, which on a throttled or idle device can
     * be many seconds away. Without the seed, opening the WiFi tab on a cold
     * device would show nothing for up to that long.
     */
    fun startWifi() {
        if (wifiCallback != null || !checkWifiPermission()) return

        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        val callback = object : WifiManager.ScanResultsCallback() {
            override fun onScanResultsAvailable() {
                publishWifiResults(wm)
            }
        }

        try {
            wm.registerScanResultsCallback(context.mainExecutor, callback)
            wifiCallback = callback
            publishWifiResults(wm)
        } catch (e: SecurityException) {
            // permission revoked between check and here
        }
    }

    private fun publishWifiResults(wm: WifiManager) {
        val results = try {
            wm.scanResults.map {
                WifiInfo(
                    ssid = scanResultSsid(it),
                    bssid = it.BSSID ?: "",
                    frequencyMhz = it.frequency,
                    signalDbm = it.level,
                )
            }.sortedByDescending { it.signalDbm }
        } catch (e: SecurityException) {
            return
        }
        _wifiNetworks.value = results
    }

    fun stopWifi() {
        val callback = wifiCallback ?: return
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        try {
            wm.unregisterScanResultsCallback(callback)
        } catch (e: Exception) {}
        wifiCallback = null
    }

    @Suppress("DEPRECATION")
    private fun scanResultSsid(sr: android.net.wifi.ScanResult): String =
        sr.SSID ?: "Hidden"

    // ---- Lifecycle ----

    override fun onCleared() {
        stopGnss()
        stopTelephony()
        stopWifi()
        stopRawSensors()
    }

    private companion object {
        const val SENSOR_MIN_INTERVAL_NS = 100_000_000L
    }
}

/**
 * Map a platform [android.telephony.CellInfo] to our display model, or null
 * if it's a type we don't render.
 *
 * On API 30+ without `ACCESS_FINE_LOCATION` the platform redacts cell
 * identity: MCC/MNC come back null and CID/NCI as [Int.MAX_VALUE] /
 * [Long.MAX_VALUE]. Those cells are still real (signal strength is
 * meaningful) so we render them rather than drop them, with `?` for the
 * unavailable identity fields.
 *
 * `CellInfoNr.getCellIdentity()` is typed as base `CellIdentity` at the
 * compile SDK in use, so the NR branch needs an explicit cast to
 * `CellIdentityNr` to reach `mccString` / `mncString` / `nci`. A safe cast
 * is used so an unexpected runtime type is skipped rather than crashing.
 */
private fun android.telephony.CellInfo.toTower(): CellTowerInfo? = when (this) {
    is CellInfoGsm -> CellTowerInfo(
        type = "GSM",
        mcc = cellIdentity.mccString ?: "?",
        mnc = cellIdentity.mncString ?: "?",
        cid = cellIdentity.cid.takeUnless { it == Int.MAX_VALUE }?.toString() ?: "?",
        signalDbm = cellSignalStrength.dbm,
    )
    is CellInfoLte -> CellTowerInfo(
        type = "LTE",
        mcc = cellIdentity.mccString ?: "?",
        mnc = cellIdentity.mncString ?: "?",
        cid = cellIdentity.ci.takeUnless { it == Int.MAX_VALUE }?.toString() ?: "?",
        signalDbm = cellSignalStrength.dbm,
    )
    is CellInfoNr -> {
        val nr = cellIdentity as? CellIdentityNr ?: return null
        CellTowerInfo(
            type = "NR",
            mcc = nr.mccString ?: "?",
            mnc = nr.mncString ?: "?",
            cid = nr.nci.takeUnless { it == Long.MAX_VALUE }?.toString() ?: "?",
            signalDbm = cellSignalStrength.dbm,
        )
    }
    is CellInfoWcdma -> CellTowerInfo(
        type = "WCDMA",
        mcc = cellIdentity.mccString ?: "?",
        mnc = cellIdentity.mncString ?: "?",
        cid = cellIdentity.cid.takeUnless { it == Int.MAX_VALUE }?.toString() ?: "?",
        signalDbm = cellSignalStrength.dbm,
    )
    else -> null
}
