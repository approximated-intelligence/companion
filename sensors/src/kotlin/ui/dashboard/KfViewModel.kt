package de.perigon.companion.sensors.ui.dashboard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import de.perigon.companion.sensors.domain.AltitudeKalmanFilter
import de.perigon.companion.sensors.domain.KalmanMode
import de.perigon.companion.sensors.domain.UpdateSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class KfSample(
    val h: Float?,
    val v: Float?,
    val b: Float?,
    val sigmaH: Float?,
    val sigmaV: Float?,
    val sigmaB: Float?,
    val deltaGps: Float?,
    val deltaBaro: Float?,
    val baroInput: Float?,
    val gpsInput: Float?,
)

data class KfUiState(
    val mode: KalmanMode = KalmanMode.GAUSSIAN,
    val gnssEnabled: Boolean = true,
    val isInitialized: Boolean = false,
    val hadGpsSeed: Boolean = false,
    val h: Float? = null,
    val v: Float? = null,
    val b: Float? = null,
    val sigmaH: Float? = null,
    val sigmaV: Float? = null,
    val sigmaB: Float? = null,
    val deltaGps: Float? = null,
    val deltaBaro: Float? = null,
    val baroInput: Float? = null,
    val gpsInput: Float? = null,
    val baroPressureHpa: Float? = null,
    val gpsSigmaM: Float? = null,
    val hasBarometer: Boolean = false,
    val huberWeight: Float? = null,
    val vbEstimatedR: Float? = null,
    val iskfProcessOutlierNorm: Float? = null,
    val iskfMeasOutlier: Float? = null,
    val lastUpdateSource: UpdateSource? = null,
    val history: List<KfSample> = emptyList(),
)

/**
 * Runs a standalone AltitudeKalmanFilter for the KF debug tab.
 *
 * Owns its own baro listener and (optional) GPS listener. These are
 * independent from BackgroundService's feeds and from the GNSS tab's
 * listener — Android coalesces GPS requests at the provider level, so
 * there is no battery cost to having more than one.
 *
 * GNSS toggle: when off, we stop feeding GPS but keep feeding baro. The
 * filter continues from its converged state, and the tab shows how h and
 * b respond to baro-only input. This is the useful "watch it drift"
 * case. The toggle cannot help before the filter has been seeded by GPS:
 * the filter refuses baro updates until initialized, by design. Seeding
 * from baro would lock the bias and give a physically wrong h on
 * re-fusion. The UI shows that hint when applicable.
 */
@HiltViewModel
class KfViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val filter = AltitudeKalmanFilter(
        initialMode = KalmanMode.GAUSSIAN,
        initialIskfInverseHessian = false,
    )

    private val _state = MutableStateFlow(KfUiState())
    val state: StateFlow<KfUiState> = _state.asStateFlow()

    private var locationListener: LocationListener? = null
    private var pressureListener: SensorEventListener? = null
    private var sensorManager: SensorManager? = null
    private var gnssActive = false
    private var baroActive = false

    private var lastHistoryMs = 0L
    private val history = ArrayDeque<KfSample>(HISTORY_SIZE + 1)

    private companion object {
        const val HISTORY_SIZE = 180
        const val HISTORY_INTERVAL_MS = 500L
        const val BARO_SIGMA_M = 1.0f
        const val GPS_VERTICAL_DEFAULT_M = 15f
        const val GPS_VERTICAL_FALLBACK_FACTOR = 3f
    }

    fun start() {
        startBaro()
        if (_state.value.gnssEnabled) startGnss()
    }

    fun stop() {
        stopGnss()
        stopBaro()
    }

    fun setMode(mode: KalmanMode) {
        filter.setMode(mode)
        _state.update { it.copy(mode = mode) }
    }

    fun setGnssEnabled(enabled: Boolean) {
        if (_state.value.gnssEnabled == enabled) return
        _state.update { it.copy(gnssEnabled = enabled) }
        if (enabled) startGnss() else stopGnss()
    }

    fun resetFilter() {
        filter.reset()
        history.clear()
        lastHistoryMs = 0L
        _state.update {
            it.copy(
                isInitialized = false,
                hadGpsSeed = false,
                h = null, v = null, b = null,
                sigmaH = null, sigmaV = null, sigmaB = null,
                deltaGps = null, deltaBaro = null,
                baroInput = null, gpsInput = null,
                baroPressureHpa = null,
                gpsSigmaM = null,
                huberWeight = null,
                vbEstimatedR = null,
                iskfProcessOutlierNorm = null,
                iskfMeasOutlier = null,
                lastUpdateSource = null,
                history = emptyList(),
            )
        }
    }

    // ---- Baro ----

    private fun startBaro() {
        if (baroActive) return
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val pressure = sm.getDefaultSensor(Sensor.TYPE_PRESSURE)
        _state.update { it.copy(hasBarometer = pressure != null) }
        if (pressure == null) return

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type != Sensor.TYPE_PRESSURE) return
                val p = event.values[0]
                val z = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, p)
                filter.updateBaro(z, BARO_SIGMA_M)
                publish(baroPressureHpa = p, baroInput = z)
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sm.registerListener(listener, pressure, SensorManager.SENSOR_DELAY_NORMAL)
        sensorManager = sm
        pressureListener = listener
        baroActive = true
    }

    private fun stopBaro() {
        val listener = pressureListener ?: return
        sensorManager?.unregisterListener(listener)
        pressureListener = null
        sensorManager = null
        baroActive = false
    }

    // ---- GNSS ----

    private fun startGnss() {
        if (gnssActive) return
        if (ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (!location.hasAltitude()) return
                val vAcc = when {
                    location.hasVerticalAccuracy() -> location.verticalAccuracyMeters
                    location.hasAccuracy() -> location.accuracy * GPS_VERTICAL_FALLBACK_FACTOR
                    else -> GPS_VERTICAL_DEFAULT_M
                }
                filter.updateGps(location.altitude.toFloat(), vAcc)
                publish(gpsInput = location.altitude.toFloat(), gpsSigmaM = vAcc)
            }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener)
            locationListener = listener
            gnssActive = true
        } catch (e: SecurityException) {
            // permission revoked between check and here
        }
    }

    private fun stopGnss() {
        val listener = locationListener ?: return
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            lm.removeUpdates(listener)
        } catch (e: SecurityException) {
            // ignore
        }
        locationListener = null
        gnssActive = false
    }

    // ---- Publish ----

    private fun publish(
        baroPressureHpa: Float? = _state.value.baroPressureHpa,
        baroInput: Float? = _state.value.baroInput,
        gpsInput: Float? = _state.value.gpsInput,
        gpsSigmaM: Float? = _state.value.gpsSigmaM,
    ) {
        val h = filter.altitude()
        val v = filter.verticalSpeed()
        val b = filter.bias()
        val sh = filter.altitudeSigma()
        val sv = filter.velocitySigma()
        val sb = filter.biasSigma()
        val dg = filter.lastGpsInnovationM()
        val db = filter.lastBaroInnovationM()
        val initialized = filter.isInitialized()

        val now = SystemClock.elapsedRealtime()
        val push = initialized && (now - lastHistoryMs) >= HISTORY_INTERVAL_MS
        if (push) {
            lastHistoryMs = now
            if (history.size >= HISTORY_SIZE) history.removeFirst()
            history.addLast(
                KfSample(
                    h = h, v = v, b = b,
                    sigmaH = sh, sigmaV = sv, sigmaB = sb,
                    deltaGps = dg, deltaBaro = db,
                    baroInput = baroInput, gpsInput = gpsInput,
                )
            )
        }

        _state.update {
            it.copy(
                isInitialized = initialized,
                hadGpsSeed = it.hadGpsSeed || (dg != null || (initialized && filter.lastUpdateSource() == UpdateSource.GPS)),
                h = h, v = v, b = b,
                sigmaH = sh, sigmaV = sv, sigmaB = sb,
                deltaGps = dg, deltaBaro = db,
                baroInput = baroInput,
                gpsInput = gpsInput,
                baroPressureHpa = baroPressureHpa,
                gpsSigmaM = gpsSigmaM,
                huberWeight = filter.lastHuberWeightValue(),
                vbEstimatedR = filter.lastVbEstimatedRValue(),
                iskfProcessOutlierNorm = filter.lastIskfProcessOutlierNormValue(),
                iskfMeasOutlier = filter.lastIskfMeasOutlierValue(),
                lastUpdateSource = filter.lastUpdateSource(),
                history = if (push) history.toList() else it.history,
            )
        }
    }

    override fun onCleared() {
        stopGnss()
        stopBaro()
    }
}
