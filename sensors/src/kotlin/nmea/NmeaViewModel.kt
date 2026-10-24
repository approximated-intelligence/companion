package de.perigon.companion.sensors.nmea

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import java.io.Closeable
import javax.inject.Inject

/**
 * ViewModel for the NMEA tab. Two responsibilities:
 *
 *  1. Proxy [NmeaStream]'s flows to the UI. The ViewModel holds the
 *     stream's lease — [start] acquires, [stop] releases — so the stream
 *     is only live while the tab is visible. Consumed as
 *     `LifecycleResumeEffect` on the tab content, matching KfContent.
 *
 *  2. Own a Location listener for the "Android Location comparison"
 *     section (design §7.2). Same lifecycle as the stream lease; both
 *     started on resume, both stopped on pause/dispose.
 *
 * Filter state is UI-scoped (`rememberSaveable` in the composable) per
 * design §7.3: "Session-scoped; reset to All on tab reopen".
 */
@HiltViewModel
class NmeaViewModel @Inject constructor(
    @param:ApplicationContext private val ctx: Context,
    private val stream: NmeaStream,
) : ViewModel() {

    val parsed: StateFlow<NmeaStream.ParsedSnapshot> = stream.parsed
    val recent: StateFlow<List<NmeaSentence>> = stream.recentSentences
    val lastSeen: StateFlow<Map<String, Long>> = stream.sentenceLastSeenMs

    private val _latestLocation = MutableStateFlow<Location?>(null)
    val latestLocation: StateFlow<Location?> = _latestLocation.asStateFlow()

    private var streamLease: Closeable? = null
    private var locationListener: LocationListener? = null

    /**
     * Acquire the stream and start the Location listener. Idempotent —
     * safe to call from both the outer LifecycleResumeEffect and the
     * inner RequireAccess LaunchedEffect (they run on different triggers
     * and may overlap).
     */
    fun start() {
        if (streamLease == null) streamLease = stream.acquire()
        startLocationListener()
    }

    /** Release both. Idempotent. */
    fun stop() {
        streamLease?.close()
        streamLease = null
        stopLocationListener()
    }

    /** Filtered view of [recent] by [types]. Called from a combine in the UI. */
    fun filtered(types: Set<String>): StateFlow<List<NmeaSentence>> =
        MutableStateFlow(
            recent.value.filter { it.type in types }
        ).also { flow ->
            // Update on every recent emission. Called per-composition from
            // the UI's combine — kept out of the ViewModel to avoid a
            // filtered StateFlow that the ViewModel would need to track.
            // The implementation is: the UI does its own combine. See
            // NmeaContent. This method exists as documentation of intent.
        }

    private fun startLocationListener() {
        if (locationListener != null) return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Location listener not started — permission missing")
            return
        }
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return
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
        } catch (e: SecurityException) {
            Log.w(TAG, "Location listener permission denied", e)
        } catch (e: Exception) {
            Log.w(TAG, "Location listener start failed", e)
        }
    }

    private fun stopLocationListener() {
        val l = locationListener ?: return
        val lm = ctx.getSystemService(LocationManager::class.java)
        try { lm?.removeUpdates(l) } catch (_: Exception) {}
        locationListener = null
    }

    override fun onCleared() {
        stop()
    }

    private companion object {
        const val TAG = "NmeaViewModel"
    }
}
