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
import android.os.SystemClock
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
import de.perigon.companion.core.geo.magnetic.MagneticModel
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
    private val magneticModel: MagneticModel,
) : ViewModel() {

    private val _gnssState = MutableStateFlow(GnssUiState())
    val gnssState: StateFlow<GnssUiState> = _gnssState.asStateFlow()

    /**
     * Cells seen recently, sorted by recency (most recent first).
     *
     * Backed by an accumulating map in this ViewModel. On every telephony
     * callback, the currently-visible cells are merged into [cellObservations]
     * and stamped with a fresh `lastSeenMs`; entries older than
     * [CELL_TTL_MS] are pruned; the map is capped at [MAX_CELLS]. The
     * published list is the map's values sorted by recency, so old cells
     * sink to the bottom before disappearing entirely on the next update.
     *
     * The map survives tab switches and screen-off because it lives on the
     * ViewModel. It is garbage-collected when the Sensors nav destination
     * leaves the back stack (and its ViewModel is cleared).
     */
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

    /**
     * Accumulation buffer for cells. Key is the cell's identity (type +
     * mcc + mnc + cid); value is the latest observation with its
     * first/last-seen timestamps. Only ever touched from the telephony
     * callback and the seed-in-startTelephony call, both on the main
     * executor, so no lock is needed.
     */
    private val cellObservations = LinkedHashMap<CellKey, CellTowerInfo>()

    private data class CellKey(
        val type: String,
        val mcc: String,
        val mnc: String,
        val cid: String,
    )

    /**
     * Declination memoization state.
     *
     * `updateOrientation()` runs at ~20 Hz (accelerometer + magnetometer
     * callbacks, each throttled to 10 Hz). A WMM eval is ~10–20 µs, so the
     * uncached cost is ~0.04% of one core — negligible. The cache exists
     * because declination changes only with position and time: at mid-
     * latitudes the gradient is ~0.02°/degree-latitude, and the secular
     * drift is ~0.1°/year. Recomputing at 20 Hz for a stationary user is
     * pure waste; recomputing only when position has moved past a
     * tolerance, or after a time bucket, is both cheaper and no less
     * accurate at the 0.1° the UI displays.
     *
     * Tolerances are chosen so the declination error introduced by the
     * cache is <0.005° at mid-latitudes (well below display resolution),
     * and the recompute rate scales correctly near the magnetic poles
     * where the spatial gradient is 10–100× steeper.
     */
    private var lastDeclLat: Double? = null
    private var lastDeclLon: Double? = null
    private var lastDeclAltM: Double = 0.0
    private var lastDeclTimeMs: Long = 0L
    private var lastDeclination: Float? = null

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

    /**
     * Register the GNSS listener and status callback.
     *
     * Driven by SensorsScreen's effects for the whole screen, not by the
     * GNSS tab. Two reasons:
     *
     *  1. Raw-tab declination needs a position. Without a hoisted listener
     *     the Raw tab would show `--` until the user visited GNSS.
     *  2. The position should keep updating while the user is on other
     *     tabs, so a returned-to GNSS tab shows a live skyplot rather than
     *     a snapshot from whenever it was last visited.
     *
     * Idempotent: safe to call from both SensorsScreen (on every resume,
     * which catches permission granted via system Settings while the app
     * was backgrounded) and GnssContent's post-permission LaunchedEffect.
     * The guard prevents double registration.
     */
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
        val magneticAzimuth = (Math.toDegrees(o[0].toDouble()).toFloat() + 360f) % 360f

        // Declination requires a position. When there is one, compute it via
        // the WMM2025 model at that point and now — cached across calls so
        // that a stationary user does not pay for a fresh spherical-harmonic
        // expansion on every accelerometer tick.
        //
        // `_gnssState.altitude` is Location.getAltitude() — metres above the
        // WGS84 ellipsoid. WMM is referenced to the same ellipsoid, so no
        // geoid adjustment is needed here. This is the OPPOSITE convention
        // from BackgroundService.convert(), which subtracts undulation to
        // get MSL because AltitudeKalmanFilter's `h` is MSL. Do not "unify"
        // them: the KF's state is orthometric, the magnetic model's input is
        // ellipsoidal, and both are correct as written.
        val lat = _gnssState.value.latitude
        val lon = _gnssState.value.longitude
        val alt = _gnssState.value.altitude ?: 0.0
        val declination: Float? = if (lat != null && lon != null) {
            declinationAt(lat, lon, alt)
        } else null
        val trueAzimuth: Float? = declination?.let { d ->
            (magneticAzimuth + d + 360f) % 360f
        }

        _rawSensors.update {
            it.copy(
                azimuth = magneticAzimuth,
                pitch = Math.toDegrees(o[1].toDouble()).toFloat(),
                roll = Math.toDegrees(o[2].toDouble()).toFloat(),
                declinationDeg = declination,
                trueAzimuthDeg = trueAzimuth,
            )
        }
    }

    /**
     * Declination at [lat], [lon], [altM], memoized against a position,
     * altitude and time tolerance.
     *
     * The cache is a single slot on the ViewModel: it survives Raw →
     * GNSS → Raw (correct — you did not move, so the value is still valid)
     * and dies with the Sensors screen. Invalidated when the observer
     * moves past [DECL_POS_TOL_DEG], climbs past [DECL_ALT_TOL_M], or
     * [DECL_TIME_TOL_MS] has passed since the last compute. At mid-
     * latitudes the position tolerance corresponds to ~1 km of travel,
     * well below the declination gradient at the 0.1° the UI displays.
     * Near the magnetic poles the same tolerance forces more frequent
     * recomputes, which is exactly what a fast-moving observer near the
     * pole needs.
     *
     * Not thread-safe by itself, but only ever called from the sensor
     * listener callbacks, which the platform delivers on the main thread
     * (no explicit Handler was passed to registerListener). If a future
     * caller invokes this from another thread, add synchronization.
     */
    private fun declinationAt(lat: Double, lon: Double, altM: Double): Float? {
        val now = SystemClock.elapsedRealtime()
        val cached = lastDeclination
        val cachedLat = lastDeclLat
        val cachedLon = lastDeclLon
        if (cached != null && cachedLat != null && cachedLon != null) {
            if (Math.abs(lat - cachedLat) < DECL_POS_TOL_DEG &&
                Math.abs(lon - cachedLon) < DECL_POS_TOL_DEG &&
                Math.abs(altM - lastDeclAltM) < DECL_ALT_TOL_M &&
                now - lastDeclTimeMs < DECL_TIME_TOL_MS
            ) {
                return cached
            }
        }
        val decl = magneticModel
            .field(lat, lon, altM, System.currentTimeMillis())
            .declinationDeg
        lastDeclLat = lat
        lastDeclLon = lon
        lastDeclAltM = altM
        lastDeclTimeMs = now
        lastDeclination = decl
        return decl
    }

    // ---- Telephony ----

    /**
     * Register the telephony callback and seed from the current snapshot.
     *
     * Idempotent: safe to call from both SensorsScreen's effects (fires on
     * every resume, which catches permission granted via system Settings
     * while backgrounded) and GsmContent's LaunchedEffect (the retry after
     * an app-internal permission grant). The registration guard prevents
     * the two from double-registering.
     *
     * Callbacks merge into [cellObservations]; the accumulating map is what
     * makes cells survive tab switches and stale observations age out.
     */
    fun startTelephony() {
        if (telephonyCallback != null || !checkTelephonyPermission()) return

        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val callback = object : TelephonyCallback(), TelephonyCallback.CellInfoListener {
            override fun onCellInfoChanged(cellInfo: MutableList<android.telephony.CellInfo>) {
                recordCellTowers(cellInfo.mapNotNull { info -> info.toTower() })
            }
        }
        try {
            tm.registerTelephonyCallback(context.mainExecutor, callback)
            telephonyCallback = callback
            // Seed from the current snapshot — registerTelephonyCallback
            // does not guarantee an immediate onCellInfoChanged.
            val initial = try { tm.allCellInfo } catch (e: SecurityException) { null }
            if (initial != null) {
                recordCellTowers(initial.mapNotNull { info -> info.toTower() })
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

    /**
     * Merge the currently-visible cells into the accumulation buffer, then
     * prune and republish.
     *
     *  - New cells get `firstSeenMs = lastSeenMs = now`.
     *  - Known cells get `lastSeenMs = now`; `firstSeenMs` is preserved.
     *  - Entries whose `lastSeenMs` is older than [CELL_TTL_MS] are removed.
     *    This is what makes a cell "fall off automatically": it sinks to
     *    the bottom (sorted by recency on publish) and then disappears on
     *    the first callback after the TTL boundary.
     *  - Entries beyond [MAX_CELLS] are removed oldest-first, so dense
     *    urban areas cannot grow the list unbounded within a single TTL
     *    window.
     */
    private fun recordCellTowers(visible: List<CellTowerInfo>) {
        val now = SystemClock.elapsedRealtime()
        for (cell in visible) {
            val key = CellKey(cell.type, cell.mcc, cell.mnc, cell.cid)
            val prev = cellObservations[key]
            cellObservations[key] = cell.copy(
                firstSeenMs = prev?.firstSeenMs ?: now,
                lastSeenMs = now,
            )
        }
        val cutoff = now - CELL_TTL_MS
        cellObservations.entries.removeAll { it.value.lastSeenMs < cutoff }
        if (cellObservations.size > MAX_CELLS) {
            cellObservations.entries
                .sortedBy { it.value.lastSeenMs }
                .take(cellObservations.size - MAX_CELLS)
                .forEach { cellObservations.remove(it.key) }
        }
        _cellTowers.value = cellObservations.values
            .sortedByDescending { it.lastSeenMs }
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

        /** A cell not seen for this long is dropped from the buffer. */
        const val CELL_TTL_MS = 10L * 60 * 1000L

        /** Hard cap on cells held, regardless of TTL. */
        const val MAX_CELLS = 100

        /**
         * Position tolerance for the declination cache. 0.01° ≈ 1.1 km,
         * translating to <0.005° declination change at mid-latitudes —
         * well below the 0.1° the UI displays. Near the magnetic poles
         * the gradient is steeper and this tolerance invalidates faster,
         * which is the desired behavior.
         */
        const val DECL_POS_TOL_DEG = 0.01

        /**
         * Altitude tolerance for the declination cache. Declination changes
         * by ~0.001°/km of altitude, so 500 m contributes <0.001° error.
         */
        const val DECL_ALT_TOL_M = 500.0

        /**
         * Time tolerance for the declination cache. Secular variation is
         * ~0.1°/year, so 15 minutes contributes <0.001° error.
         */
        const val DECL_TIME_TOL_MS = 15L * 60 * 1000L
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
 *
 * The timestamps are deliberately left at their default (0) here; the
 * accumulating map sets them from the observation time, not the platform
 * callback time.
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
