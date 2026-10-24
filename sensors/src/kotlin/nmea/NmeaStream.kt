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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
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
    /**
     * Merged field state across the seven parse-set types. Every nullable
     * field means "not yet seen" or "cleared by the last update of its
     * sentence type"; it never represents a contradictory combination
     * because [Position] is pair-or-null and dates/positions are
     * separately tracked (so a time-only GGA can coexist with a not-yet-
     * arrived RMC date).
     *
     * [datetime] is a derived view: only non-null when BOTH a time (from
     * GGA/RMC/GNS) and a date (from RMC) have been seen.
     */
    data class ParsedSnapshot(
        val utcTime: LocalTime? = null,
        val utcDate: LocalDate? = null,
        val position: Position? = null,
        val fixQuality: Int? = null,
        val fixDimension: Int? = null,
        val satellitesUsed: Int? = null,
        val altitudeMslM: Double? = null,
        val geoidSepM: Double? = null,
        val hdop: Double? = null,
        val vdop: Double? = null,
        val pdop: Double? = null,
        val speedKnots: Double? = null,
        val courseDeg: Double? = null,
        val horizontalSigmaM: Double? = null,
        val verticalSigmaM: Double? = null,
    ) {
        val datetime: LocalDateTime?
            get() = if (utcTime != null && utcDate != null)
                LocalDateTime.of(utcDate, utcTime)
            else null
    }

    private val _parsed = MutableStateFlow(ParsedSnapshot())
    val parsed: StateFlow<ParsedSnapshot> = _parsed.asStateFlow()

    private val _recentSentences = MutableStateFlow<List<NmeaSentence>>(emptyList())
    val recentSentences: StateFlow<List<NmeaSentence>> = _recentSentences.asStateFlow()

    private val _sentenceLastSeenMs = MutableStateFlow<Map<String, Long>>(emptyMap())
    val sentenceLastSeenMs: StateFlow<Map<String, Long>> = _sentenceLastSeenMs.asStateFlow()

    private val lock = Any()
    private var acquireCount = 0
    private var listener: OnNmeaMessageListener? = null

    /** Log ring buffer, bounded by [MAX_SENTENCES]. Mutated only from main. */
    private val buffer = ArrayDeque<NmeaSentence>(MAX_SENTENCES + 1)

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
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "NMEA listener not registered — ACCESS_FINE_LOCATION missing")
            return
        }
        val lm = ctx.getSystemService(LocationManager::class.java) ?: run {
            Log.w(TAG, "NMEA listener not registered — LocationManager unavailable")
            return
        }
        val l = OnNmeaMessageListener { sentence, _ ->
            onSentence(sentence, System.currentTimeMillis())
        }
        try {
            lm.addNmeaListener(ctx.mainExecutor, l)
            listener = l
            Log.i(TAG, "NMEA listener registered")
        } catch (e: SecurityException) {
            Log.w(TAG, "NMEA listener registration denied", e)
        } catch (e: Exception) {
            Log.w(TAG, "NMEA listener registration failed", e)
        }
    }

    private fun unregister() {
        val l = listener ?: return
        listener = null
        try {
            ctx.getSystemService(LocationManager::class.java)?.removeNmeaListener(l)
            Log.i(TAG, "NMEA listener unregistered")
        } catch (e: Exception) {
            Log.w(TAG, "NMEA listener removal failed", e)
        }
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
     */
    private fun onSentence(raw: String, wallMs: Long) {
        val sentence = NmeaParser.parse(raw, wallMs) ?: return
        buffer.addLast(sentence)
        while (buffer.size > MAX_SENTENCES) buffer.removeFirst()
        _recentSentences.value = buffer.toList()

        if (sentence !is NmeaSentence.Corrupt) {
            val nowElapsed = SystemClock.elapsedRealtime()
            _sentenceLastSeenMs.value = _sentenceLastSeenMs.value + (sentence.type to nowElapsed)
            _parsed.value = merge(_parsed.value, sentence)
        }
    }

    private companion object {
        const val TAG = "NmeaStream"
        const val MAX_SENTENCES = 500

        /**
         * Fold one valid sentence into the accumulated snapshot.
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
         */
        fun merge(current: ParsedSnapshot, sentence: NmeaSentence): ParsedSnapshot =
            when (sentence) {
                is NmeaSentence.Corrupt -> current
                is NmeaSentence.Other -> current
                is NmeaSentence.Gga -> current.copy(
                    utcTime = sentence.utc ?: current.utcTime,
                    position = sentence.position ?: current.position,
                    fixQuality = sentence.fixQuality,
                    satellitesUsed = sentence.satellitesUsed,
                    hdop = sentence.hdop ?: current.hdop,
                    altitudeMslM = sentence.altitudeMslM ?: current.altitudeMslM,
                    geoidSepM = sentence.geoidSepM ?: current.geoidSepM,
                )
                is NmeaSentence.Rmc -> current.copy(
                    utcTime = sentence.utc ?: current.utcTime,
                    utcDate = sentence.date ?: current.utcDate,
                    position = sentence.position ?: current.position,
                    speedKnots = sentence.speedKnots ?: current.speedKnots,
                    courseDeg = sentence.courseDeg ?: current.courseDeg,
                )
                is NmeaSentence.Gsa -> current.copy(
                    fixDimension = sentence.fixDimension,
                    pdop = sentence.pdop,
                    vdop = sentence.vdop,
                    hdop = sentence.hdop ?: current.hdop,
                )
                is NmeaSentence.Gns -> current.copy(
                    utcTime = sentence.utc ?: current.utcTime,
                    position = sentence.position ?: current.position,
                    satellitesUsed = sentence.satellitesUsed ?: current.satellitesUsed,
                )
                is NmeaSentence.Vtg -> current.copy(
                    speedKnots = sentence.speedKnots ?: current.speedKnots,
                    courseDeg = sentence.courseDeg ?: current.courseDeg,
                )
                is NmeaSentence.Gst -> current.copy(
                    horizontalSigmaM = sentence.horizontalSigmaM ?: current.horizontalSigmaM,
                    verticalSigmaM = sentence.altSigmaM ?: current.verticalSigmaM,
                )
            }
    }
}
