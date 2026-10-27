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
import de.perigon.companion.core.geo.geoid.GeoidGrid
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
 * INTERVAL — throttle AND engine-warming hint. `intervalMs` bounds the
 * rate at which [tryEmit] forwards a cycle snapshot, and is passed to
 * `requestLocationUpdates` in the warm-up listener. Chipsets that
 * honour `minTime` slow the engine to the caller's rate; chipsets that
 * produce at native rate ignore it. Both are correct — the emitted
 * rate stays bounded by [tryEmit] either way, so the two sources
 * (Location and NMEA) now agree on what the setting means.
 */
@Singleton
class NmeaGnssSource @Inject constructor(
    @param:ApplicationContext private val ctx: Context,
    private val nmeaStream: NmeaStream,
    private val cycleTracker: NmeaCycleTracker,
    private val geoidGrid: GeoidGrid,
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
    private var sessionStartMs: Long = 0L
    private var sessionTtffSeconds: Int? = null

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
        sessionStartMs = SystemClock.elapsedRealtime()
        sessionTtffSeconds = null

        nmeaLease = nmeaStream.acquire()
        trackerLease = cycleTracker.acquire()
        warmUpEngine(lm)

        fixJob = scope.launch {
            cycleTracker.cycleSnapshots.collect { snapshot ->
                if (sessionTtffSeconds == null && snapshot.position != null && sessionStartMs > 0L) {
                    sessionTtffSeconds =
                        ((SystemClock.elapsedRealtime() - sessionStartMs) / 1000L).toInt()
                }
                tryEmit(snapshot)
            }
        }
        _state.value = GnssSourceState.Running
    }

    override fun updateInterval(intervalMs: Long) {
        if (intervalMs == currentIntervalMs) return
        currentIntervalMs = intervalMs
        // Re-register so the platform sees the new minTime. On chipsets
        // that honour it, the engine slows to the caller's rate; on
        // chipsets that produce at native rate, this is a no-op and
        // [tryEmit]'s throttle remains the bound on emission. Matches
        // [LocationGnssSource.updateInterval]'s pattern.
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return
        val l = warmupListener ?: return

        // warmUpEngine() checked the grant when it registered, but location
        // can be revoked while a session is running. Returning silently
        // would leave the source looking healthy while the warm-up
        // registration — the only thing keeping the engine producing NMEA —
        // stops having any effect. Report it the way start() and
        // warmUpEngine() already do; GnssController re-exposes this flow.
        //
        // The registration is deliberately NOT dropped: it is inert while
        // the grant is gone and delivers again if it comes back, and that is
        // this class's only recovery path — start() early-returns on a live
        // fixJob, so a torn-down warm-up would need a full session
        // stop/start. tryEmit() clears the error on the next completed
        // cycle, so it lives exactly as long as the silence does.
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            _state.value = GnssSourceState.Error("Location permission required")
            return
        }

        try {
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                currentIntervalMs.coerceAtLeast(1_000L),
                0f,
                l,
            )
        } catch (e: SecurityException) {
            // TOCTOU: revoked between the check and the call.
            _state.value = GnssSourceState.Error("Location permission required")
        } catch (e: Exception) {
            Log.w(TAG, "Warm-up interval update failed", e)
        }
    }

    override fun stop() {
        fixJob?.cancel(); fixJob = null
        coolDownEngine()
        trackerLease?.close(); trackerLease = null
        nmeaLease?.close(); nmeaLease = null
        sessionStartMs = 0L
        sessionTtffSeconds = null
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
     * Active warm-up. Its only job is to trigger the GNSS engine so that
     * `addNmeaListener` inside [NmeaStream] has something to receive. The
     * Location objects delivered here are discarded; this class never
     * reads a Location field.
     *
     * GPS_PROVIDER: PASSIVE_PROVIDER does not start the engine — it only
     * receives whatever some other consumer is already producing. When
     * NMEA is the only source asking for a fix, passive means "no
     * sentences ever". An active request is the only warm-up that works
     * universally.
     *
     * Interval: [currentIntervalMs], the minimum across all sessions
     * supplied by GnssController. This is the same value LocationGnssSource
     * passes to its own requestLocationUpdates, so the two sources agree
     * on what the interval means. Chipsets that honour `minTime` slow
     * the engine to this rate (a 300 s recording wakes the chip every
     * 300 s, not every 1 s); chipsets that produce at native rate
     * ignore it. Both are correct — [tryEmit]'s throttle is the bound
     * on the emitted rate either way.
     *
     * Floor at 1 s so a pathological 0 or negative value (should not
     * occur; sessions carry a validated interval) cannot flood the chip.
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
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                currentIntervalMs.coerceAtLeast(1_000L),
                0f,
                listener,
            )
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
        // A cycle got through, so the grant is demonstrably back — clear an
        // error set by updateInterval() rather than letting it outlive the
        // condition it reported.
        if (_state.value is GnssSourceState.Error) _state.value = GnssSourceState.Running
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

        // Sigma derivation lives in deriveNmeaSigma (middleware/NmeaSigma.kt)
        // so the NMEA debug tab can call the same function from its own
        // ParsedSnapshot without duplicating the GST-vs-DOP rule. When
        // neither GST nor DOP is present the fix stays accuracy-less — the
        // recorder's max-inaccuracy gate rejects it, but the fix is still
        // emitted and visible as "provided" on the live card.

        // XGM2019e-derived MSL, not the chip's. Same semantics as the
        // Location source (which already corrects via GeoidGrid), so
        // consumers stay source-agnostic. Depends on the chip having
        // emitted geoidSep (GGA field 11). Compliant receivers emit it,
        // but some cheap chipsets and multiplexer rewrites omit the
        // field — the null path is a real case, not defensive padding.
        // When WGS84 or geoidSep is absent, MSL is absent too: null is
        // the honest answer, and no silent fallback to the chip's own
        // geoid (which is the exact value this field exists to replace).
        val altMslAppM: Double? = snapshot.altitudeWgs84M?.let { wgs84 ->
            wgs84 - geoidGrid.undulation(position.lat, position.lon)
        }

        return GnssFix(
            source = GnssSourceType.NMEA,
            timestamp = timestamp,
            // Monotonic stamp for consumers that dedupe. NOT used for
            // any decision in this class.
            monotonicNanos = SystemClock.elapsedRealtimeNanos(),
            position = position,
            altitudeMslM = altMslAppM,
            altitudeWgs84M = snapshot.altitudeWgs84M,
            speedMps = snapshot.speedMps,
            courseDeg = snapshot.courseDeg,
            magVarDeg = snapshot.magVarDeg,
            fixQuality = snapshot.fixQuality,
            fixDimension = snapshot.fixDimension,
            satellitesUsed = snapshot.satellitesUsed,
            satellites = parsed.satellites,
            dop = Dop(snapshot.hdop, snapshot.vdop, snapshot.pdop),
            sigma = deriveNmeaSigma(
                gstHorizontalM = snapshot.horizontalSigmaM,
                gstVerticalM = snapshot.verticalSigmaM,
                hdop = snapshot.hdop,
                vdop = snapshot.vdop,
            ),
            sbasActive = parsed.sbasActive,
            sourceLocation = null,
            ttffSeconds = sessionTtffSeconds,
        )
    }

    private companion object {
        const val TAG = "NmeaGnssSource"
    }
}
