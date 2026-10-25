package de.perigon.companion.sensors.ui.dashboard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import de.perigon.companion.sensors.nmea.NmeaCycleTracker
import de.perigon.companion.sensors.nmea.NmeaSentence
import de.perigon.companion.sensors.nmea.NmeaStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import javax.inject.Inject

/**
 * Backs the NMEA tab.
 *
 * Three responsibilities:
 *
 *  1. Hold a [NmeaStream] lease while the tab is visible. The stream is
 *     passive: it observes whatever the GNSS engine emits. If nothing is
 *     warming the engine, the stream is silent.
 *
 *  2. Hold a [NmeaCycleTracker] lease so the "Last Cycle" view has data
 *     to render. The tracker consolidates the raw sentence stream into
 *     per-cycle snapshots — it uses the same passive subscription model
 *     as the stream, so this lease is cheap.
 *
 *  3. Warm the engine via a `requestLocationUpdates` call. The GNSS chip
 *     produces NMEA only while *something* is requesting fixes; the
 *     listener here is that "something" when the tab is open and no
 *     recorder is running. The user-facing toggle controls this — on by
 *     default so the tab has data to show, off when another consumer is
 *     keeping the engine warm and the user wants zero cost from this tab.
 *
 * The interval passed to `requestLocationUpdates` is a delivery throttle,
 * not an engine throttle — the GNSS chip runs at its own (~1 Hz) cadence
 * regardless. This is why the toggle only makes sense as on/off:
 * adjusting an interval would not change battery cost.
 *
 * Failures surface as state, not exceptions: [streamState] carries the
 * stream lifecycle, [engineError] carries location-request failures. Both
 * drive the banner in the UI.
 */
@HiltViewModel
class NmeaViewModel @Inject constructor(
    @param:ApplicationContext private val ctx: Context,
    private val stream: NmeaStream,
    private val cycleTracker: NmeaCycleTracker,
) : ViewModel() {

    val parsed: StateFlow<NmeaStream.ParsedSnapshot> = stream.parsed
    val recent: StateFlow<List<NmeaSentence>> = stream.recentSentences
    val lastSeen: StateFlow<Map<String, Long>> = stream.sentenceLastSeenMs
    val streamState: StateFlow<NmeaStream.State> = stream.state

    /** Raw sentences of the last complete cycle, updated by the tracker. */
    val lastCycleSentences: StateFlow<List<NmeaSentence>> = cycleTracker.lastCycleSentences

    /** Most recent platform fix, for the comparison card. */
    private val _latestLocation = MutableStateFlow<Location?>(null)
    val latestLocation: StateFlow<Location?> = _latestLocation.asStateFlow()

    /** Location-request failure message, or null when running / idle. */
    private val _engineError = MutableStateFlow<String?>(null)
    val engineError: StateFlow<String?> = _engineError.asStateFlow()

    /**
     * Whether this tab requests GPS updates to keep the engine warm. On by
     * default: without it, the tab would show no data unless the recorder
     * or another app happened to be requesting fixes.
     */
    private val _requestGpsEnabled = MutableStateFlow(true)
    val requestGpsEnabled: StateFlow<Boolean> = _requestGpsEnabled.asStateFlow()

    private var streamLease: Closeable? = null
    private var trackerLease: Closeable? = null
    private var locationListener: LocationListener? = null

    /**
     * Acquire the stream + tracker, and start the location request (if
     * enabled). Idempotent — safe to call from both the outer
     * LifecycleResumeEffect and the inner RequireAccess LaunchedEffect.
     */
    fun start() {
        if (streamLease == null) streamLease = stream.acquire()
        if (trackerLease == null) trackerLease = cycleTracker.acquire()
        if (_requestGpsEnabled.value) startLocationRequest()
    }

    /** Release both leases and stop the location request. Idempotent. */
    fun stop() {
        streamLease?.close()
        streamLease = null
        trackerLease?.close()
        trackerLease = null
        stopLocationRequest()
    }

    /**
     * Toggle the engine-warming request. On when the tab needs its own
     * fix stream; off when another consumer (recorder, GNSS tab) is
     * already keeping the engine warm and the user prefers this tab to
     * add no cost.
     */
    fun setRequestGpsEnabled(enabled: Boolean) {
        if (_requestGpsEnabled.value == enabled) return
        _requestGpsEnabled.value = enabled
        if (enabled) startLocationRequest() else stopLocationRequest()
    }

    private fun startLocationRequest() {
        if (locationListener != null) return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            _engineError.value = "Location permission required"
            return
        }
        val lm = ctx.getSystemService(LocationManager::class.java)
        if (lm == null) {
            _engineError.value = "LocationManager unavailable"
            return
        }
        // Seed the comparison card from whatever the platform already has.
        lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let {
            _latestLocation.value = it
        }
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                _latestLocation.value = location
            }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, listener)
            locationListener = listener
            _engineError.value = null
        } catch (e: SecurityException) {
            _engineError.value = "Location permission denied: ${e.message}"
            Log.w(TAG, "Location request denied", e)
        }
    }

    private fun stopLocationRequest() {
        val l = locationListener ?: return
        try {
            ctx.getSystemService(LocationManager::class.java)?.removeUpdates(l)
        } catch (e: Exception) {
            Log.w(TAG, "Location request removal failed", e)
        }
        locationListener = null
    }

    override fun onCleared() {
        stop()
    }

    private companion object {
        const val TAG = "NmeaViewModel"
    }
}
