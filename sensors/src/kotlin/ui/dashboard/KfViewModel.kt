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
import de.perigon.companion.sensors.data.GeoidGrid
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
    // Tab-local: whether the baro measurement is fed to the filter. Defaults
    // ON — fusing GPS + baro is the filter's intended use, and the tab is a
    // sandbox with none of the recorder's reasons for shipping the feature
    // off (battery, per-point DB writes, an extra GPX column). The listener
    // stays registered regardless so the raw reading is always available;
    // this toggle only gates filter.updateBaro().
    val baroEnabled: Boolean = true,
    // Tab-local. Only applied in ISKF mode; the toggle is only shown there.
    val iskfInverseHessian: Boolean = false,
    // Tab-local: subtract geoid undulation from GPS altitude before feeding.
    // On by default because the recorder always applies it.
    val geoidEnabled: Boolean = true,
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
    val geoidUndulationM: Float? = null,
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
 * TRUE SANDBOX. Own filter instance, own listeners, own toggles. Nothing here
 * reads or writes TrackConfigPrefs, and no state is shared with the recording
 * pipeline. Changing a chip in this tab changes only this filter's update
 * rule; the recorder's filter is untouched until the same setting is changed
 * on the Track tab. The point of the tab is to answer "what would the filter
 * do with these inputs?" without altering — or being altered by — the session
 * you are trying to inspect.
 *
 * Defaults are the filter's INTENDED configuration (Gaussian mode, GPS + baro
 * fused, geoid applied), which is what the tab exists to show. They
 * deliberately do not mirror the recorder's shipped defaults: the recorder
 * ships baro off because enabling it has downstream costs a user should opt
 * into (battery, per-point DB writes, GPX size), none of which apply to the
 * sandbox.
 *
 * The raw baro reading is shown whether or not it is fused: the question
 * "how far is the sensor from the filter's altitude?" is exactly what the
 * tab exists to answer, and it is independent of the fusion toggle.
 *
 * Toggling geoid mid-session is not reset by design: watching the filter
 * absorb the step between ellipsoidal and orthometric altitude is more
 * informative than a blank chart.
 */
@HiltViewModel
class KfViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val geoidGrid: GeoidGrid,
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

    companion object {
        const val HISTORY_SIZE = 180
        const val HISTORY_INTERVAL_MS = 500L
        private const val BARO_SIGMA_M = 1.0f
        private const val GPS_VERTICAL_DEFAULT_M = 15f
        private const val GPS_VERTICAL_FALLBACK_FACTOR = 3f
    }

    fun start() {
        // Hardware detection, independent of the fusion toggle: the switch
        // must be disabled on phones without a pressure sensor.
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        _state.update { it.copy(hasBarometer = sm.getDefaultSensor(Sensor.TYPE_PRESSURE) != null) }

        // Always start both listeners. The baro listener is what feeds the
        // raw pressure readout; whether its measurement is FUSED is the
        // separate baroEnabled toggle. Starting/stopping the listener on a
        // toggle would give the tab two different "baro is off" states that
        // look the same in the UI but read differently in diagnostics.
        startBaro()
        if (_state.value.gnssEnabled) startGnss()
    }

    fun stop() {
        stopGnss()
        stopBaro()
    }

    // ---- Toggles (all tab-local, none writes to config) ----

    fun setMode(mode: KalmanMode) {
        if (_state.value.mode == mode) return
        filter.setMode(mode)
        _state.update { it.copy(mode = mode) }
    }

    fun setIskfInverseHessian(enabled: Boolean) {
        if (_state.value.iskfInverseHessian == enabled) return
        filter.setIskfInverseHessian(enabled)
        _state.update { it.copy(iskfInverseHessian = enabled) }
    }

    fun setBaroEnabled(enabled: Boolean) {
        if (_state.value.baroEnabled == enabled) return
        _state.update { it.copy(baroEnabled = enabled) }
    }

    fun setGeoidEnabled(enabled: Boolean) {
        if (_state.value.geoidEnabled == enabled) return
        _state.update { it.copy(geoidEnabled = enabled) }
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
                geoidUndulationM = null,
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
        val pressure = sm.getDefaultSensor(Sensor.TYPE_PRESSURE) ?: return

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type != Sensor.TYPE_PRESSURE) return
                val p = event.values[0]
                val z = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, p)
                // Fusion is the toggle; the reading is always published so
                // the numeric and chart output show "what baro says" even
                // while it is not being fed to the filter. That is the
                // question the tab exists to answer.
                if (_state.value.baroEnabled) {
                    filter.updateBaro(z, BARO_SIGMA_M)
                }
                publish(
                    baroPressureHpa = p,
                    baroInput = z,
                )
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
                // Geoid is read unconditionally so the numeric grid can show
                // the undulation even when the toggle is off.
                val geoidM = geoidGrid.undulation(location.latitude, location.longitude)
                val fedAlt = if (_state.value.geoidEnabled)
                    (location.altitude - geoidM).toFloat()
                else
                    location.altitude.toFloat()
                filter.updateGps(fedAlt, vAcc)
                publish(
                    gpsInput = fedAlt,
                    gpsSigmaM = vAcc,
                    geoidUndulationM = geoidM,
                )
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
        geoidUndulationM: Float? = _state.value.geoidUndulationM,
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
                geoidUndulationM = geoidUndulationM,
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
        stop()
    }
}
