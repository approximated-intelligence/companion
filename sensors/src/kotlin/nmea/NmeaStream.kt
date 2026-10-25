package de.perigon.companion.sensors.nmea

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import de.perigon.companion.sensors.gnss.Constellation
import de.perigon.companion.sensors.gnss.FixDimension
import de.perigon.companion.sensors.gnss.FixQuality
import de.perigon.companion.sensors.gnss.Position
import de.perigon.companion.sensors.gnss.SatelliteObservation
import de.perigon.companion.sensors.gnss.UTCDate
import de.perigon.companion.sensors.gnss.UTCDateTime
import de.perigon.companion.sensors.gnss.UTCTime
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide NMEA source. Single owner of `LocationManager.addNmeaListener`;
 * reference-counted via [acquire] so several consumers can share one
 * registration.
 *
 * Exposes three flows:
 *
 *  - [parsed]         merged snapshot across the seven parse-set types.
 *                     Only valid (checksum-OK) sentences update it.
 *  - [recentSentences] bounded ring buffer of raw sentences for the NMEA
 *                     tab's log. Includes corrupt sentences (that is the
 *                     diagnostic value of the log). Excludes lines that
 *                     are not structurally sentences at all.
 *  - [sentenceLastSeenMs] monotonic timestamp per sentence-type for the
 *                     status chips. Only valid sentences count — a
 *                     corrupt sentence arrived but didn't deliver data,
 *                     so it must not turn a chip green.
 *
 * Plus [sentenceArrivals], the live event stream consumed by
 * [NmeaCycleTracker]. Emitted AFTER [parsed] is updated, so a collector
 * reading `parsed.value` sees the sentence it just observed.
 *
 * Thread safety: acquires/releases serialize on [lock]; parse callbacks
 * arrive on the main executor and mutate the flows directly (they are
 * thread-confined to main by construction).
 *
 * Orthogonal to the recorder. The NMEA tab acquires independently; a
 * future recorder NMEA mode would acquire independently too. The two
 * cancel each other out only when *both* release.
 */
@Singleton
class NmeaStream @Inject constructor(
    @param:ApplicationContext private val ctx: Context,
) {
    sealed interface State {
        data object Idle : State
        data object Listening : State
        data class Error(val message: String) : State
    }

    /**
     * Merged field state across the eight parse-set types (GGA, RMC, GSA,
     * GNS, VTG, GST, GSV, GLL). Every nullable
     * field means "not yet seen" or "cleared by the last update of its
     * sentence type"; it never represents a contradictory combination
     * because [Position] is pair-or-null and dates/positions are
     * separately tracked (so a time-only GGA can coexist with a not-yet-
     * arrived RMC date).
     *
     * [timestamp] is a derived view: only non-null when BOTH a time (from
     * GGA/RMC/GNS/GLL) and a date (from RMC) have been seen.
     */
    data class ParsedSnapshot(
        val utcDate: UTCDate? = null,
        val utcTime: UTCTime? = null,
        val position: Position? = null,
        val fixQuality: FixQuality? = null,
        val fixDimension: FixDimension? = null,
        val satellitesUsed: Int? = null,
        val altitudeMslM: Double? = null,
        val geoidSepM: Double? = null,
        val altitudeWgs84M: Double? = null,
        val hdop: Double? = null,
        val vdop: Double? = null,
        val pdop: Double? = null,
        val speedMps: Double? = null,
        val courseDeg: Double? = null,
        val magVarDeg: Double? = null,
        val horizontalSigmaM: Double? = null,
        val verticalSigmaM: Double? = null,
        val diffAgeS: Double? = null,
        val diffStationId: Int? = null,
        val rmcModeIndicator: String? = null,
        val vtgModeIndicator: String? = null,
        val gnsModeIndicators: String? = null,
        val gllModeIndicator: String? = null,
        val gsaSystemId: Int? = null,
        val lastGsvSignalId: Int? = null,
        val satellites: List<SatelliteObservation> = emptyList(),
        val sbasActive: Boolean = false,
        val sentenceElapsedMs: Map<String, Long> = emptyMap(),
    ) {
        val timestamp: UTCDateTime?
            get() = if (utcDate != null && utcTime != null) UTCDateTime(utcDate, utcTime) else null

        fun ageMsOf(type: String, nowElapsed: Long = SystemClock.elapsedRealtime()): Long? =
            sentenceElapsedMs[type]?.let { nowElapsed - it }
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _parsed = MutableStateFlow(ParsedSnapshot())
    val parsed: StateFlow<ParsedSnapshot> = _parsed.asStateFlow()

    private val _sentenceArrivals = MutableSharedFlow<NmeaSentence>(extraBufferCapacity = 64)
    val sentenceArrivals: SharedFlow<NmeaSentence> = _sentenceArrivals.asSharedFlow()

    private val _recentSentences = MutableStateFlow<List<NmeaSentence>>(emptyList())
    val recentSentences: StateFlow<List<NmeaSentence>> = _recentSentences.asStateFlow()

    private val _sentenceLastSeenMs = MutableStateFlow<Map<String, Long>>(emptyMap())
    val sentenceLastSeenMs: StateFlow<Map<String, Long>> = _sentenceLastSeenMs.asStateFlow()

    private val lock = Any()
    private var acquireCount = 0
    private var listener: OnNmeaMessageListener? = null
    private val buffer = ArrayDeque<NmeaSentence>(MAX_SENTENCES + 1)
    
    // Unique ID generator for Compose keys
    private val nextSentenceId = AtomicLong(0L)

    private val satellites = mutableMapOf<SatKey, SeenSatellite>()
    private val activePrns = mutableMapOf<Constellation, Set<Int>>()

    private data class SatKey(val constellation: Constellation, val signalId: Int?, val prn: Int)
    private data class SeenSatellite(val sat: SatelliteObservation, val lastSeenMs: Long)

    /**
     * Increment the acquire count; register the platform listener on the
     * 0→1 transition. Returned [Closeable] releases one acquire; closing
     * more times than acquired is a no-op.
     */
    fun acquire(): Closeable {
        synchronized(lock) {
            acquireCount++
            if (acquireCount == 1) register()
        }
        var closed = false
        return Closeable {
            synchronized(lock) {
                if (closed) return@Closeable
                closed = true
                acquireCount--
                if (acquireCount <= 0) {
                    acquireCount = 0
                    unregister()
                }
            }
        }
    }

    private fun register() {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            _state.value = State.Error("Location permission required for NMEA")
            return
        }
        val lm = ctx.getSystemService(LocationManager::class.java)
        if (lm == null) {
            _state.value = State.Error("LocationManager unavailable")
            return
        }
        val l = OnNmeaMessageListener { sentence, _ ->
            onSentence(sentence)
        }
        try {
            lm.addNmeaListener(ctx.mainExecutor, l)
            listener = l
            _state.value = State.Listening
            Log.i(TAG, "NMEA listener registered")
        } catch (e: Exception) {
            _state.value = State.Error("NMEA listener registration failed: ${e.message}")
            Log.w(TAG, "NMEA listener registration failed", e)
        }
    }

    private fun unregister() {
        val l = listener ?: run { _state.value = State.Idle; return }
        listener = null
        try {
            ctx.getSystemService(LocationManager::class.java)?.removeNmeaListener(l)
        } catch (e: Exception) {
            Log.w(TAG, "NMEA listener removal failed", e)
        }
        _state.value = State.Idle
    }

    /**
     * One sentence arrived. Runs on the main executor.
     *
     *  - Log gets every structurally-valid sentence (including corrupt
     *    ones), because "corrupt GGA arrived" is exactly what the log is
     *    for.
     *  - Chips get only valid sentences: a corrupt sentence did not
     *    deliver data, so it must not turn the chip green.
     *  - Parsed snapshot gets only valid sentences whose fields we extract
     *    (the typed variants + anything the merge handles).
     *  - [sentenceArrivals] is emitted AFTER state is updated, so the
     *    cycle tracker sees the current sentence's timestamp in [parsed].
     */
    private fun onSentence(raw: String) {
        val id = nextSentenceId.incrementAndGet()
        val sentence = NmeaParser.parse(raw, id) ?: return

        buffer.addLast(sentence)
        while (buffer.size > MAX_SENTENCES) buffer.removeFirst()
        _recentSentences.value = buffer.toList()

        if (sentence !is NmeaSentence.Corrupt) {
            val nowElapsed = SystemClock.elapsedRealtime()
            val updatedLastSeen = _sentenceLastSeenMs.value + (sentence.type to nowElapsed)
            _sentenceLastSeenMs.value = updatedLastSeen

            when (sentence) {
                is NmeaSentence.Gsv -> {
                    for (sat in sentence.satellites) {
                        val const = sat.constellation(sentence.constellation)
                        val obs = SatelliteObservation(
                            prn = sat.prn,
                            constellation = const,
                            signalId = sentence.signalId,
                            elevationDeg = sat.elevationDeg,
                            azimuthDeg = sat.azimuthDeg,
                            snrDbHz = sat.snrDbHz,
                            usedInFix = false,
                        )
                        satellites[SatKey(const, sentence.signalId, sat.prn)] =
                            SeenSatellite(obs, nowElapsed)
                    }
                    publishSatellites(updatedLastSeen)
                }
                is NmeaSentence.Gsa -> {
                    sentence.constellation?.let { activePrns[it] = sentence.activePrns.toSet() }
                    _parsed.value = mergeScalars(_parsed.value, sentence)
                        .copy(sentenceElapsedMs = updatedLastSeen)
                    publishSatellites(updatedLastSeen)
                }
                else -> _parsed.value = mergeScalars(_parsed.value, sentence)
                    .copy(sentenceElapsedMs = updatedLastSeen)
            }
        }
        
        _sentenceArrivals.tryEmit(sentence)
    }

    private fun publishSatellites(lastSeen: Map<String, Long>) {
        val now = SystemClock.elapsedRealtime()
        val cutoff = now - SATELLITE_TTL_MS
        satellites.entries.removeAll { it.value.lastSeenMs < cutoff }

        val list = satellites.entries.map { (key, seen) ->
            val active = activePrns[key.constellation]?.contains(key.prn) == true
            seen.sat.copy(usedInFix = active)
        }.sortedWith(compareBy({ it.constellation.ordinal }, { it.prn }))

        val ggaSaysSbas = _parsed.value.fixQuality == FixQuality.WAAS
        val gsaSaysSbas = list.any { it.usedInFix && it.isSbas }

        _parsed.value = _parsed.value.copy(
            satellites = list,
            sbasActive = ggaSaysSbas || gsaSaysSbas,
            sentenceElapsedMs = lastSeen,
        )
    }

    private companion object {
        const val TAG = "NmeaStream"
        const val MAX_SENTENCES = 500
        const val SATELLITE_TTL_MS = 5_000L
        const val MIDNIGHT_THRESHOLD_SECONDS = 12 * 3600

        /**
         * Fold one valid sentence's scalar fields into the accumulated
         * snapshot. GSV, Corrupt and Other return [current] unchanged:
         * GSV's per-satellite data is accumulated separately in
         * [onSentence] / [publishSatellites] (keyed by constellation +
         * signalId + prn), and Corrupt/Other carry no fields to extract.
         *
         * Per-type: only the fields the type carries are written. Nulls in
         * the incoming sentence do NOT clear previous values — a GGA
         * without a fix still carries the last good altitude if the previous
         * GGA had one. Per-type clearing would require distinguishing "empty
         * because no fix" from "empty because the chipset omits it", which
         * NMEA does not signal.
         *
         * Exceptions where null DOES overwrite:
         *  - GSA's fixDimension (2/3 change every fix cycle; stale 2D must
         *    not persist when the fix drops to 3D)
         *  - GSA's pdop / vdop (DOPs are per-fix snapshots)
         *
         * GGA/GNS/GLL arrival with a wrapped-around time before RMC's
         * authoritative date is handled by [mergeDate].
         */
        fun mergeScalars(current: ParsedSnapshot, s: NmeaSentence): ParsedSnapshot = when (s) {
            is NmeaSentence.Corrupt, is NmeaSentence.Other, is NmeaSentence.Gsv -> current
            is NmeaSentence.Gga -> current.copy(
                utcDate = mergeDate(current.utcDate, current.utcTime, s.utcTime),
                utcTime = s.utcTime ?: current.utcTime,
                position = s.position ?: current.position,
                fixQuality = s.fixQuality,
                satellitesUsed = s.satellitesUsed,
                hdop = s.hdop ?: current.hdop,
                altitudeMslM = s.altitudeMslM ?: current.altitudeMslM,
                geoidSepM = s.geoidSepM ?: current.geoidSepM,
                altitudeWgs84M = s.altitudeWgs84M ?: current.altitudeWgs84M,
                diffAgeS = s.diffAgeS,
                diffStationId = s.diffStationId,
            )
            is NmeaSentence.Rmc -> current.copy(
                utcDate = s.utcDate ?: current.utcDate,
                utcTime = s.utcTime ?: current.utcTime,
                position = s.position ?: current.position,
                speedMps = s.speedMps ?: current.speedMps,
                courseDeg = s.courseDeg ?: current.courseDeg,
                magVarDeg = s.magVarDeg ?: current.magVarDeg,
                rmcModeIndicator = s.modeIndicator,
            )
            is NmeaSentence.Gsa -> current.copy(
                fixDimension = s.fixDimension,
                pdop = s.pdop,
                vdop = s.vdop,
                hdop = s.hdop ?: current.hdop,
                gsaSystemId = s.systemId,
            )
            is NmeaSentence.Gns -> current.copy(
                utcDate = mergeDate(current.utcDate, current.utcTime, s.utcTime),
                utcTime = s.utcTime ?: current.utcTime,
                position = s.position ?: current.position,
                gnsModeIndicators = s.modeIndicators,
                satellitesUsed = s.satellitesUsed ?: current.satellitesUsed,
            )
            is NmeaSentence.Vtg -> current.copy(
                speedMps = s.speedMps ?: current.speedMps,
                courseDeg = s.courseDeg ?: current.courseDeg,
                vtgModeIndicator = s.modeIndicator,
            )
            is NmeaSentence.Gst -> current.copy(
                horizontalSigmaM = s.horizontalSigmaM ?: current.horizontalSigmaM,
                verticalSigmaM = s.altSigmaM ?: current.verticalSigmaM,
            )
            is NmeaSentence.Gll -> current.copy(
                utcDate = mergeDate(current.utcDate, current.utcTime, s.utcTime),
                utcTime = s.utcTime ?: current.utcTime,
                position = s.position ?: current.position,
                gllModeIndicator = s.modeIndicator,
            )
        }

        fun mergeDate(currentDate: UTCDate?, currentTime: UTCTime?, newTime: UTCTime?): UTCDate? {
            if (currentDate == null) return null
            if (currentTime == null || newTime == null) return currentDate
            val diff = newTime.value.toSecondOfDay() - currentTime.value.toSecondOfDay()
            return if (diff < -MIDNIGHT_THRESHOLD_SECONDS) {
                UTCDate.fromLocalDate(currentDate.value.plusDays(1))
            } else {
                currentDate
            }
        }
    }
}
