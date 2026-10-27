package de.perigon.companion.sensors.middleware

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import de.perigon.companion.core.geo.geoid.GeoidGrid
import de.perigon.companion.sensors.gnss.Constellation
import de.perigon.companion.sensors.gnss.Position
import de.perigon.companion.sensors.gnss.SatelliteObservation
import de.perigon.companion.sensors.gnss.UTCDateTime
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Location-based GNSS source. Every field comes from Android's Location /
 * GnssStatus APIs — the counterpart to [NmeaGnssSource], with no NMEA
 * involvement.
 *
 * `fixDimension` is null: Android does not report 2D vs 3D. Guessing from
 * "has altitude" conflates two different things and would produce a false
 * 3D on a 2D fix that carries a stale altitude. Report the honest null.
 */
@Singleton
class LocationGnssSource @Inject constructor(
    @param:ApplicationContext private val ctx: Context,
    private val geoidGrid: GeoidGrid,
) : GnssSource {

    override val type: GnssSourceType = GnssSourceType.LOCATION

    private val _fixes = MutableSharedFlow<GnssFix>(extraBufferCapacity = 8)
    override val fixes: SharedFlow<GnssFix> = _fixes.asSharedFlow()

    private val _state = MutableStateFlow<GnssSourceState>(GnssSourceState.Idle)
    override val state: StateFlow<GnssSourceState> = _state.asStateFlow()

    private var locationListener: LocationListener? = null
    private var gnssCallback: GnssStatus.Callback? = null
    private var currentIntervalMs: Long = 1_000L
    private var cachedSatellites: List<SatelliteObservation> = emptyList()
    private var sessionTtffSeconds: Int? = null

    override fun start(intervalMs: Long) {
        if (locationListener != null) { updateInterval(intervalMs); return }
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            _state.value = GnssSourceState.Error("Location permission required")
            return
        }
        val lm = ctx.getSystemService(LocationManager::class.java)
        if (lm == null) {
            _state.value = GnssSourceState.Error("LocationManager unavailable")
            return
        }
        currentIntervalMs = intervalMs
        sessionTtffSeconds = null
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) { emit(buildFix(location)) }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        val cb = object : GnssStatus.Callback() {
            override fun onFirstFix(ttffMillis: Int) {
                sessionTtffSeconds = ttffMillis / 1000
            }
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                cachedSatellites = status.toObservations()
            }
        }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, intervalMs, 0f, listener)
            lm.registerGnssStatusCallback(ctx.mainExecutor, cb)
            locationListener = listener
            gnssCallback = cb
            _state.value = GnssSourceState.Running
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { emit(buildFix(it)) }
        } catch (e: Exception) {
            _state.value = GnssSourceState.Error("Location source failed: ${e.message}")
            Log.w(TAG, "Location source start failed", e)
        }
    }

    override fun updateInterval(intervalMs: Long) {
        if (intervalMs == currentIntervalMs) return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return
        currentIntervalMs = intervalMs
        val listener = locationListener ?: return
        try {
            ctx.getSystemService(LocationManager::class.java)
                ?.requestLocationUpdates(LocationManager.GPS_PROVIDER, intervalMs, 0f, listener)
        } catch (e: Exception) {
            Log.w(TAG, "updateInterval failed", e)
        }
    }

    override fun stop() {
        locationListener?.let {
            try { ctx.getSystemService(LocationManager::class.java)?.removeUpdates(it) }
            catch (_: Exception) {}
        }
        locationListener = null
        gnssCallback?.let {
            try { ctx.getSystemService(LocationManager::class.java)?.unregisterGnssStatusCallback(it) }
            catch (_: Exception) {}
        }
        gnssCallback = null
        cachedSatellites = emptyList()
        sessionTtffSeconds = null
        _state.value = GnssSourceState.Idle
    }

    override suspend fun requestSingleFix(timeoutMs: Long): GnssFix? =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                    cont.resume(null); return@suspendCancellableCoroutine
                }
                try {
                    ctx.getSystemService(LocationManager::class.java)
                        ?.getCurrentLocation(LocationManager.GPS_PROVIDER, null, ctx.mainExecutor) { loc ->
                            if (cont.isActive) cont.resume(loc?.let { buildFix(it) })
                        } ?: run {
                            if (cont.isActive) cont.resume(null)
                        }
                } catch (e: SecurityException) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }

    private fun emit(fix: GnssFix) { _fixes.tryEmit(fix) }

    private fun buildFix(location: Location): GnssFix {
        val position = Position(location.latitude, location.longitude)
        val undulation = geoidGrid.undulation(location.latitude, location.longitude)
        val altWgs84 = if (location.hasAltitude()) location.altitude else null
        val altMsl = altWgs84?.minus(undulation)
        val horiz = if (location.hasAccuracy()) location.accuracy.toDouble() else null
        val vert = if (location.hasVerticalAccuracy())
            location.verticalAccuracyMeters.toDouble() else null

        return GnssFix(
            source = GnssSourceType.LOCATION,
            timestamp = UTCDateTime.fromEpochMillis(location.time),
            monotonicNanos = location.elapsedRealtimeNanos,
            position = position,
            altitudeMslM = altMsl,
            altitudeWgs84M = altWgs84,
            speedMps = if (location.hasSpeed()) location.speed.toDouble() else null,
            courseDeg = if (location.hasBearing()) location.bearing.toDouble() else null,
            magVarDeg = null,
            fixQuality = null,
            fixDimension = null,
            satellitesUsed = cachedSatellites.count { it.usedInFix }
                .takeIf { cachedSatellites.isNotEmpty() },
            satellites = cachedSatellites,
            dop = Dop.NONE,
            sigma = Sigma(horiz, vert),
            sbasActive = cachedSatellites.any { it.usedInFix && it.isSbas },
            sourceLocation = location,
            ttffSeconds = sessionTtffSeconds,
        )
    }

    private companion object { const val TAG = "LocationGnssSource" }
}

internal fun GnssStatus.toObservations(): List<SatelliteObservation> =
    (0 until satelliteCount).map { i ->
        SatelliteObservation(
            prn = getSvid(i),
            constellation = Constellation.fromAndroidConstellation(getConstellationType(i)),
            signalId = null,
            elevationDeg = getElevationDegrees(i).toInt().takeIf { it >= 0 },
            azimuthDeg = getAzimuthDegrees(i).toInt().takeIf { it >= 0 },
            snrDbHz = getCn0DbHz(i).toInt().takeIf { it > 0 },
            usedInFix = usedInFix(i),
        )
    }
