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
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import de.perigon.companion.core.geo.geoid.GeoidGrid
import de.perigon.companion.sensors.nmea.NmeaCycleTracker
import de.perigon.companion.sensors.nmea.NmeaSentence
import de.perigon.companion.sensors.nmea.NmeaStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.Closeable
import javax.inject.Inject

/**
 * Backs the NMEA tab.
 *
 * Holds two leases while the tab is visible:
 *   1. [NmeaStream] — passive observer of the platform's NMEA callback.
 *   2. [NmeaCycleTracker] — consolidates the raw sentence stream into
 *      per-cycle snapshots for the "Last Cycle" view.
 *
 * Registers a PASSIVE_PROVIDER Location listener, purely so the
 * Location-comparison card has data. That listener is a consumer of the
 * LocationManager, not a warmer — it does not trigger the GNSS engine.
 *
 * Engine warming is NOT this ViewModel's job. SensorsScreen's
 * LifecycleResumeEffect(Unit) acquires a DashboardViewModel GnssSession
 * for the whole screen lifetime, which drives the active source (Location
 * or NMEA per user setting). Both sources register an ACTIVE
 * requestLocationUpdates(GPS_PROVIDER) — the Location source for its own
 * fix stream, the NMEA source purely to keep the engine producing (its
 * Location payloads are discarded; it builds fixes from NMEA sentences
 * via addNmeaListener). Neither uses PASSIVE_PROVIDER for warm-up:
 * passive receives nothing when no other consumer is requesting.
 *
 * Consequence: while the Sensors screen is resumed, NMEA sentences reach
 * this tab regardless of whether the recorder is running or which source
 * is selected. The blank-tab case is "Sensors screen paused AND nothing
 * else on the device is requesting fixes."
 *
 * No "Request GPS updates" toggle. There is nothing to toggle: warming is
 * a screen-level or source-level decision, and the tab cannot affect it
 * either way without duplicating the source's job.
 */
@HiltViewModel
class NmeaViewModel @Inject constructor(
    @param:ApplicationContext private val ctx: Context,
    private val stream: NmeaStream,
    private val cycleTracker: NmeaCycleTracker,
    private val geoidGrid: GeoidGrid,
) : ViewModel() {

    val parsed: StateFlow<NmeaStream.ParsedSnapshot> = stream.parsed
    val recent: StateFlow<List<NmeaSentence>> = stream.recentSentences
    val lastSeen: StateFlow<Map<String, Long>> = stream.sentenceLastSeenMs
    val streamState: StateFlow<NmeaStream.State> = stream.state

    /** Raw sentences of the last complete cycle, updated by the tracker. */
    val lastCycleSentences: StateFlow<List<NmeaSentence>> = cycleTracker.lastCycleSentences

    /**
     * XGM2019e undulation at the last parsed position. Recomputed only
     * when position crosses a 0.00005° boundary — a bucket narrow enough
     * that the memoized undulation differs from the exact-value
     * computation by at most ~1 cm on the steepest walkable terrain, well
     * below the display precision of the card's heights.
     *
     * Quantization is on the position, not the output:
     * distinctUntilChanged drops emissions where consecutive sentences
     * report positions within the same bucket, so the undulation
     * computation runs at most once per bucket rather than once per
     * sentence (which would be 8–10× per second from the raw stream).
     */
    private val _appUndulation = MutableStateFlow<Float?>(null)
    val appUndulation: StateFlow<Float?> = _appUndulation.asStateFlow()

    /**
     * Most recent platform Location, for the comparison card. Fed by a
     * PASSIVE_PROVIDER listener that does not warm the engine. Null until
     * something else on the device starts requesting fixes.
     */
    private val _latestLocation = MutableStateFlow<Location?>(null)
    val latestLocation: StateFlow<Location?> = _latestLocation.asStateFlow()

    private var streamLease: Closeable? = null
    private var trackerLease: Closeable? = null
    private var locationListener: LocationListener? = null

    init {
        viewModelScope.launch {
            stream.parsed
                .map { it.position }
                .distinctUntilChanged { a, b ->
                    a == b || (
                        a != null && b != null &&
                        (a.lat * 20000).toLong() == (b.lat * 20000).toLong() &&
                        (a.lon * 20000).toLong() == (b.lon * 20000).toLong()
                    )
                }
                .collect { pos ->
                    _appUndulation.value = pos?.let {
                        geoidGrid.undulation(it.lat, it.lon)
                    }
                }
        }
    }

    /** Undulation lookup for the Location-comparison card's MSL row. */
    fun undulationAt(lat: Double, lon: Double): Float = geoidGrid.undulation(lat, lon)

    /**
     * Acquire the stream + tracker, and start the passive Location
     * listener. Idempotent — safe to call from both the outer
     * LifecycleResumeEffect and the inner RequireAccess LaunchedEffect.
     */
    fun start() {
        if (streamLease == null) streamLease = stream.acquire()
        if (trackerLease == null) trackerLease = cycleTracker.acquire()
        startPassiveLocationListener()
    }

    /** Release both leases and stop the passive listener. Idempotent. */
    fun stop() {
        streamLease?.close()
        streamLease = null
        trackerLease?.close()
        trackerLease = null
        stopPassiveLocationListener()
    }

    private fun startPassiveLocationListener() {
        if (locationListener != null) return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return
        lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let {
            _latestLocation.value = it
        }
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                // Prefer altitude-bearing locations. Network-only passive
                // updates arrive without altitude, which would blank the
                // MSL cell in the comparison card every time one lands.
                // Accept an altitude-less location only when nothing
                // better is held — position stays usable, MSL lights up
                // again as soon as a GPS fix arrives.
                val current = _latestLocation.value
                if (location.hasAltitude() || current == null || !current.hasAltitude()) {
                    _latestLocation.value = location
                }
            }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        try {
            lm.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 0L, 0f, listener)
            locationListener = listener
        } catch (e: SecurityException) {
            Log.w(TAG, "Passive listener registration denied", e)
        } catch (e: Exception) {
            Log.w(TAG, "Passive listener registration failed", e)
        }
    }

    private fun stopPassiveLocationListener() {
        val l = locationListener ?: return
        try {
            ctx.getSystemService(LocationManager::class.java)?.removeUpdates(l)
        } catch (e: Exception) {
            Log.w(TAG, "Passive listener removal failed", e)
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
