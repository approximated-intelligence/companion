package de.perigon.companion.sensors.middleware

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import de.perigon.companion.sensors.nmea.NmeaCycleTracker
import de.perigon.companion.sensors.nmea.NmeaStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import javax.inject.Inject
import javax.inject.Singleton

/**
 * NMEA-primary GNSS source.
 *
 * ARCHITECTURE — NMEA is the only data source; Location is only the
 * engine warm-up switch:
 *
 *  - `addNmeaListener` is passive. The GNSS chip produces NMEA only
 *    while *something* requests location updates. This source registers
 *    a **no-op** `LocationListener` to satisfy that requirement.
 *  - The `Location` objects it delivers are **discarded**. Every field
 *    in `GnssFix` comes from the [NmeaCycleTracker]'s consolidated
 *    cycle snapshot.
 *  - Fixes are emitted on **cycle completion**, triggered by the
 *    terminator sentence determined during the tracker's warm-up cycle.
 *  - Fields the chipset does not emit are `null`, not a Location
 *    substitute. Consumers handle nulls per their own policy.
 *
 * TIMING — GNSS-provided UTC seconds exclusively:
 *
 *  - The tracker hands us a `CycleSnapshot` per NMEA cycle. Each
 *    snapshot carries the cycle's authoritative `utcSecond`.
 *  - The delivery interval check compares the snapshot's `utcSecond`
 *    against the last emitted `utcSecond`. Wall clock and
 *    `elapsedRealtime` never participate in the timing decision.
 *  - `SystemClock.elapsedRealtimeNanos()` is stamped on the emitted
 *    `GnssFix` only as a monotonic dedupe key for consumers that
 *    buffer fixes (the recorder's `TrackPointBuffer`); it is never
 *    used to decide *whether* to emit.
 *  - A snapshot whose `utcSecond` is null (impossible in practice —
 *    warm-up will not complete without at least one time-bearing
 *    sentence) is dropped with a diagnostic-style log entry.
 *
 * INTERVAL — throttle, not engine rate. The GNSS chip runs at its own
 * cadence. `intervalMs` decides how often we forward a cycle snapshot;
 * it does not slow the chip. Only SINGLE_FIX / ALARM / PASSIVE modes
 * change engine activity.
 */
@Singleton
class NmeaGnssSource @Inject constructor(
    @param:ApplicationContext private val ctx: Context,
    private val nmeaStream: NmeaStream,
    private val cycleTracker: NmeaCycleTracker,
) : GnssSource {

    override val type: GnssSourceType = GnssSourceType.NMEA

    private val _fixes = MutableSharedFlow<GnssFix>(extraBufferCapacity = 8)
    override val fixes: SharedFlow<GnssFix> = _fixes.asSharedFlow()

    private val _state = MutableStateFlow<GnssSourceState>(GnssSourceState.Idle)
    override val state: StateFlow<GnssSourceState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var nmeaLease: Closeable? = null
    private var trackerLease: Closeable? = null
    private var warmupListener: LocationListener? = null
    private var fixJob: Job? = null

    private var currentIntervalMs: Long = 1_000L

    /**
     * UTC second of the last emitted cycle. The interval throttle
     * compares a new snapshot's `utcSecond` against this. Reset on
     * [start]; carried across [updateInterval] so a config change does
     * not trigger an immediate emission.
     */
    private var lastEmittedUtcSecond: Long? = null

    override fun start(intervalMs: Long) {
        if (fixJob != null) { updateInterval(intervalMs); return }
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
        lastEmittedUtcSecond = null

        nmeaLease = nmeaStream.acquire()
        trackerLease = cycleTracker.acquire()
        warmUpEngine(lm)

        fixJob = scope.launch {
            cycleTracker.cycleSnapshots.collect { snapshot ->
                tryEmit(snapshot)
            }
        }
        _state.value = GnssSourceState.Running
    }

    override fun updateInterval(intervalMs: Long) {
        // Delivery throttle only. Engine runs at chipset rate.
        currentIntervalMs = intervalMs
    }

    override fun stop() {
        fixJob?.cancel(); fixJob = null
        coolDownEngine()
        trackerLease?.close(); trackerLease = null
        nmeaLease?.close(); nmeaLease = null
        _state.value = GnssSourceState.Idle
    }

    override suspend fun requestSingleFix(timeoutMs: Long): GnssFix? =
        withTimeoutOrNull(timeoutMs) {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return@withTimeoutOrNull null
            // Acquire temporary leases if the source isn't already
            // running. Both are ref-counted, so concurrent continuous
            // sessions are undisturbed.
            val tempNmea = if (nmeaLease == null) nmeaStream.acquire() else null
            val tempTracker = if (trackerLease == null) cycleTracker.acquire() else null
            val fixDeferred = CompletableDeferred<GnssFix?>()

            val collector = scope.launch {
                cycleTracker.cycleSnapshots.collect { snapshot ->
                    buildFixFromSnapshot(snapshot)?.let {
                        if (fixDeferred.isActive) fixDeferred.complete(it)
                    }
                }
            }
            try {
                // Warm the engine. The returned Location is discarded —
                // we wait for a full cycle to arrive via the tracker.
                ctx.getSystemService(LocationManager::class.java)
                    ?.getCurrentLocation(LocationManager.GPS_PROVIDER, null, ctx.mainExecutor) { _ ->
                        /* discard */
                    }
                fixDeferred.await()
            } finally {
                collector.cancel()
                tempTracker?.close()
                tempNmea?.close()
            }
        }

    /**
     * Register a no-op `LocationListener`. Its only purpose is to keep
     * the GNSS engine running so NMEA sentences flow. The Location
     * objects it receives are discarded — NMEA mode uses only NMEA data.
     *
     * The 1 s interval is unconditional: the engine runs at chipset
     * rate, and this listener is the "someone is requesting updates"
     * signal the platform needs. Delivery throttling happens in
     * [tryEmit], not here.
     */
    private fun warmUpEngine(lm: LocationManager) {
        if (warmupListener != null) return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            _state.value = GnssSourceState.Error("Location permission required")
            return
        }
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) { /* discarded */ }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, listener)
            warmupListener = listener
        } catch (e: SecurityException) {
            Log.w(TAG, "Warm-up registration denied", e)
        } catch (e: Exception) {
            Log.w(TAG, "Warm-up registration failed", e)
        }
    }

    private fun coolDownEngine() {
        val l = warmupListener ?: return
        try {
            ctx.getSystemService(LocationManager::class.java)?.removeUpdates(l)
        } catch (e: Exception) {
            Log.w(TAG, "Warm-up removal failed", e)
        }
        warmupListener = null
    }

    /**
     * Delivery throttle, based purely on the snapshot's authoritative
     * GNSS UTC second.
     *
     *  1. Snapshot without a UTC second → drop. Warm-up cannot complete
     *     without a time-bearing sentence, so this only occurs if the
     *     first cycle itself was empty — meaningless.
     *  2. Backward jump → drop with a warning. [NmeaCycleTracker] should
     *     not produce these (it does its own stale filtering and mid-
     *     night rollover correction), but defence-in-depth: if one
     *     escapes, we prefer silence to a duplicate-key emission.
     *  3. Interval not yet elapsed → drop. `(currentIntervalMs + 999)/1000`
     *     rounds up to whole seconds, conservative — never emit faster
     *     than the user asked for.
     */
    private fun tryEmit(snapshot: NmeaCycleTracker.CycleSnapshot) {
        val utcSecond = snapshot.utcSecond
        if (utcSecond == null) {
            Log.w(TAG, "CycleSnapshot without utcSecond — dropped (empty warm-up?)")
            return
        }

        val prev = lastEmittedUtcSecond
        if (prev != null) {
            if (utcSecond < prev) {
                Log.w(TAG, "CycleSnapshot utcSecond=$utcSecond < lastEmitted=$prev — dropped")
                return
            }
            val intervalSeconds = (currentIntervalMs + 999L) / 1000L
            if (utcSecond - prev < intervalSeconds) return
        }

        val fix = buildFixFromSnapshot(snapshot) ?: return
        lastEmittedUtcSecond = utcSecond
        _fixes.tryEmit(fix)
    }

    private fun buildFixFromSnapshot(
        snapshot: NmeaCycleTracker.CycleSnapshot,
    ): GnssFix? {
        val position = snapshot.position ?: return null
        val timestamp = snapshot.timestamp ?: return null

        // Satellites and SBAS state are not per-cycle: they are
        // maintained by NmeaStream across cycles (TTL-pruned satellite
        // map, GSA-derived active PRNs). Reading from `parsed` here is
        // the intended hand-off — the tracker owns per-cycle scalar
        // consolidation, the stream owns cross-cycle satellite state.
        val parsed = nmeaStream.parsed.value

        // Horizontal accuracy: GST residual-derived σ if the chipset
        // emitted it, otherwise HDOP × UERE (standard standalone-GPS
        // rule of thumb). When neither is present the fix stays
        // accuracy-less — the recorder's max-inaccuracy gate rejects
        // it, but the fix is still emitted and visible as "provided"
        // on the live card.
        val horizSigma: Double? = snapshot.horizontalSigmaM
            ?: snapshot.hdop?.times(HDOP_UERE_M)

        val vertSigma: Double? = snapshot.verticalSigmaM
            ?: snapshot.vdop?.times(HDOP_UERE_M)

        return GnssFix(
            source = GnssSourceType.NMEA,
            timestamp = timestamp,
            // Monotonic stamp for consumers that dedupe. NOT used for
            // any decision in this class.
            monotonicNanos = SystemClock.elapsedRealtimeNanos(),
            position = position,
            altitudeMslM = snapshot.altitudeMslM,
            altitudeWgs84M = snapshot.altitudeWgs84M,
            speedMps = snapshot.speedMps,
            courseDeg = snapshot.courseDeg,
            magVarDeg = snapshot.magVarDeg,
            fixQuality = snapshot.fixQuality,
            fixDimension = snapshot.fixDimension,
            satellitesUsed = snapshot.satellitesUsed,
            satellites = parsed.satellites,
            dop = Dop(snapshot.hdop, snapshot.vdop, snapshot.pdop),
            sigma = Sigma(horizSigma, vertSigma),
            sbasActive = parsed.sbasActive,
            sourceLocation = null,
        )
    }

    private companion object {
        const val TAG = "NmeaGnssSource"
        const val HDOP_UERE_M = 5.0
    }
}
