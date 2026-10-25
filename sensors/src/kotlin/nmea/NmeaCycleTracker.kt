package de.perigon.companion.sensors.nmea

import android.os.SystemClock
import de.perigon.companion.sensors.gnss.FixDimension
import de.perigon.companion.sensors.gnss.FixQuality
import de.perigon.companion.sensors.gnss.Position
import de.perigon.companion.sensors.gnss.UTCDateTime
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.Closeable
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NmeaCycleTracker @Inject constructor(
    private val nmeaStream: NmeaStream,
) {
    data class Diagnostic(
        val severity: Severity,
        val message: String,
        val elapsedRealtimeMs: Long = SystemClock.elapsedRealtime(),
    ) {
        enum class Severity { INFO, WARNING, ERROR }
    }

    sealed interface State {
        data object Idle : State
        data object WarmingUp : State
        data class Running(
            val order: List<String>,
            val terminator: String,
        ) : State
    }

    data class CycleSnapshot(
        val utcSecond: Long?,
        val timestamp: UTCDateTime?,
        val position: Position?,
        val altitudeMslM: Double?,
        val altitudeWgs84M: Double?,
        val geoidSepM: Double?,
        val speedMps: Double?,
        val courseDeg: Double?,
        val magVarDeg: Double?,
        val fixQuality: FixQuality?,
        val fixDimension: FixDimension?,
        val satellitesUsed: Int?,
        val hdop: Double?,
        val vdop: Double?,
        val pdop: Double?,
        val horizontalSigmaM: Double?,
        val verticalSigmaM: Double?,
        val diffAgeS: Double?,
        val diffStationId: Int?,
        val rmcModeIndicator: String?,
        val vtgModeIndicator: String?,
        val gllModeIndicator: String?,
        val gnsModeIndicators: String?,
        val gsaSystemId: Int?,
    )

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _diagnostics = MutableStateFlow<List<Diagnostic>>(emptyList())
    val diagnostics: StateFlow<List<Diagnostic>> = _diagnostics.asStateFlow()

    private val _cycleSnapshots = MutableSharedFlow<CycleSnapshot>(extraBufferCapacity = 8)
    val cycleSnapshots: SharedFlow<CycleSnapshot> = _cycleSnapshots.asSharedFlow()

    private val _lastCycleSentences = MutableStateFlow<List<NmeaSentence>>(emptyList())
    val lastCycleSentences: StateFlow<List<NmeaSentence>> = _lastCycleSentences.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var sentenceJob: Job? = null
    private val lock = Any()
    private var acquireCount = 0

    private var warmupOrder: MutableList<String> = mutableListOf()
    private var warmupDiscriminators: MutableSet<String> = mutableSetOf()
    private var warmupAccumulator: CycleAccumulator = CycleAccumulator()

    private var runningOrder: List<String> = emptyList()
    private var runningTerminator: String = ""
    private var runningSeenTypes: MutableSet<String> = mutableSetOf()
    private var runningAccumulator: CycleAccumulator = CycleAccumulator()
    private var lastEmittedCycleUtcSecond: Long? = null
    private val announcedEnrichmentTypes = mutableSetOf<String>()

    fun acquire(): Closeable {
        synchronized(lock) {
            acquireCount++
            if (acquireCount == 1) start()
        }
        var closed = false
        return Closeable {
            synchronized(lock) {
                if (closed) return@Closeable
                closed = true
                acquireCount--
                if (acquireCount <= 0) {
                    acquireCount = 0
                    stop()
                }
            }
        }
    }

    private fun start() {
        resetWarmup()
        resetRunning()
        _state.value = State.WarmingUp
        _diagnostics.value = emptyList()
        announcedEnrichmentTypes.clear()

        sentenceJob = scope.launch {
            nmeaStream.sentenceArrivals.collect { sentence -> onSentence(sentence) }
        }
    }

    private fun stop() {
        sentenceJob?.cancel()
        sentenceJob = null
        resetWarmup()
        resetRunning()
        _state.value = State.Idle
    }

    private fun resetWarmup() {
        warmupOrder.clear()
        warmupDiscriminators.clear()
        warmupAccumulator = CycleAccumulator()
    }

    private fun resetRunning() {
        runningOrder = emptyList()
        runningTerminator = ""
        runningSeenTypes.clear()
        runningAccumulator = CycleAccumulator()
        lastEmittedCycleUtcSecond = null
        _lastCycleSentences.value = emptyList()
    }

    private fun onSentence(sentence: NmeaSentence) {
        if (sentence is NmeaSentence.Corrupt || sentence is NmeaSentence.Other) return
        when (_state.value) {
            State.Idle -> Unit
            State.WarmingUp -> handleWarmup(sentence)
            is State.Running -> handleRunning(sentence)
        }
    }

    private fun handleWarmup(sentence: NmeaSentence) {
        val disc = discriminator(sentence)
        val utcSecond = currentUtcSecond()
        val isRepeat = disc in warmupDiscriminators

        if (isRepeat) {
            finalizeWarmup()
            startNewRunningCycle(sentence)
            return
        }

        if (utcSecond != null && warmupAccumulator.utcSecond != null &&
            utcSecond > warmupAccumulator.utcSecond!!) {
            report(Diagnostic.Severity.WARNING, "Cycle 1: time advanced without repeat...")
            finalizeWarmup()
            startNewRunningCycle(sentence)
            return
        }

        warmupDiscriminators += disc
        if (sentence.type !in warmupOrder) warmupOrder += sentence.type
        
        warmupAccumulator.accumulate(sentence, nmeaStream.parsed.value.timestamp)
    }

    private fun finalizeWarmup() {
        if (warmupOrder.isEmpty()) {
            report(Diagnostic.Severity.ERROR, "Cycle 1: no sentences accumulated")
            return
        }
        val order = warmupOrder.toList()
        val terminator = order.last()

        runningOrder = order
        runningTerminator = terminator
        _state.value = State.Running(order, terminator)

        report(Diagnostic.Severity.INFO, "Warmup complete. Order: $order. Terminator: '$terminator'.")

        val snapshot = warmupAccumulator.snapshot()
        if (snapshot != null) {
            lastEmittedCycleUtcSecond = snapshot.utcSecond
            _cycleSnapshots.tryEmit(snapshot)
            _lastCycleSentences.value = warmupAccumulator.rawSentences.toList()
        }
    }

    private fun startNewRunningCycle(sentence: NmeaSentence) {
        runningSeenTypes.clear()
        runningAccumulator = CycleAccumulator()
        runningSeenTypes += sentence.type
        runningAccumulator.accumulate(sentence, nmeaStream.parsed.value.timestamp)
    }

    private fun handleRunning(sentence: NmeaSentence) {
        val utcSecond = currentUtcSecond()
        val disc = discriminator(sentence)

        if (utcSecond != null && runningAccumulator.utcSecond != null &&
            utcSecond > runningAccumulator.utcSecond!!) {
            report(Diagnostic.Severity.ERROR, "Premature cycle end...")
            val snapshot = runningAccumulator.snapshot()
            if (snapshot != null) {
                lastEmittedCycleUtcSecond = snapshot.utcSecond
                _cycleSnapshots.tryEmit(snapshot)
                _lastCycleSentences.value = runningAccumulator.rawSentences.toList()
            }
            startNewRunningCycle(sentence)
            return
        }

        if (utcSecond != null && lastEmittedCycleUtcSecond != null &&
            utcSecond < lastEmittedCycleUtcSecond!!) {
            report(Diagnostic.Severity.WARNING, "Stale sentence '$disc'...")
            return
        }

        if (sentence.type !in runningSeenTypes) {
            val expectedIdx = runningOrder.indexOf(sentence.type)
            if (expectedIdx < 0) {
                if (announcedEnrichmentTypes.add(sentence.type)) {
                    report(Diagnostic.Severity.INFO, "Enrichment: '$disc' not in warm-up order...")
                }
            } else {
                for (i in 0 until expectedIdx) {
                    val expectedBefore = runningOrder[i]
                    if (expectedBefore !in runningSeenTypes) {
                        report(Diagnostic.Severity.WARNING, "Cycle skipped '$expectedBefore' before '$disc'...")
                        break
                    }
                }
                val maxSeenIdx = runningSeenTypes
                    .mapNotNull { runningOrder.indexOf(it).takeIf { idx -> idx >= 0 } }
                    .maxOrNull() ?: -1
                if (expectedIdx < maxSeenIdx) {
                    report(Diagnostic.Severity.ERROR, "Out of order: '$disc' arrived after '${runningOrder[maxSeenIdx]}'...")
                }
            }
        }

        runningAccumulator.accumulate(sentence, nmeaStream.parsed.value.timestamp)
        runningSeenTypes += sentence.type

        if (sentence.type == runningTerminator) {
            val snapshot = runningAccumulator.snapshot()
            if (snapshot != null) {
                lastEmittedCycleUtcSecond = snapshot.utcSecond
                _cycleSnapshots.tryEmit(snapshot)
                _lastCycleSentences.value = runningAccumulator.rawSentences.toList()
            }
            runningSeenTypes.clear()
            runningAccumulator = CycleAccumulator()
        }
    }

    private fun currentUtcSecond(): Long? = nmeaStream.parsed.value.timestamp?.toInstant()?.epochSecond

    private fun discriminator(sentence: NmeaSentence): String = when (sentence) {
        is NmeaSentence.Gga -> "GGA"
        is NmeaSentence.Rmc -> "RMC"
        is NmeaSentence.Vtg -> "VTG"
        is NmeaSentence.Gst -> "GST"
        is NmeaSentence.Gns -> "GNS"
        is NmeaSentence.Gll -> "GLL"
        is NmeaSentence.Gsa -> "GSA:" + (sentence.systemId ?: sentence.constellation?.name ?: "")
        is NmeaSentence.Gsv -> "GSV:" + sentence.constellation.name + ":" + (sentence.messageNumber ?: 0)
        is NmeaSentence.Corrupt, is NmeaSentence.Other -> ""
    }

    private fun report(severity: Diagnostic.Severity, message: String) {
        _diagnostics.update { it + Diagnostic(severity, message) }
    }

    private class CycleAccumulator {
        var utcSecond: Long? = null
        var timestamp: UTCDateTime? = null
        var position: Position? = null
        var positionSource: SourceRank? = null
        var altitudeMslM: Double? = null
        var altitudeWgs84M: Double? = null
        var geoidSepM: Double? = null
        var speedMps: Double? = null
        var speedSource: SourceRank? = null
        var courseDeg: Double? = null
        var courseSource: SourceRank? = null
        var magVarDeg: Double? = null
        var fixQuality: FixQuality? = null
        var fixDimension: FixDimension? = null
        var satellitesUsed: Int? = null
        var hdop: Double? = null
        var hdopSource: SourceRank? = null
        var vdop: Double? = null
        var pdop: Double? = null
        var horizontalSigmaM: Double? = null
        var verticalSigmaM: Double? = null
        var diffAgeS: Double? = null
        var diffStationId: Int? = null
        var rmcModeIndicator: String? = null
        var vtgModeIndicator: String? = null
        var gllModeIndicator: String? = null
        var gnsModeIndicators: String? = null
        var gsaSystemId: Int? = null

        val rawSentences = mutableListOf<NmeaSentence>()

        enum class SourceRank { LOW, MID, HIGH }

        fun accumulate(s: NmeaSentence, parsedTs: UTCDateTime?) {
            rawSentences.add(s)
            if (timestamp == null && parsedTs != null) {
                timestamp = parsedTs
                utcSecond = parsedTs.toInstant().epochSecond
            }
            when (s) {
                is NmeaSentence.Gga -> {
                    takePosition(s.position, SourceRank.HIGH)
                    s.altitudeMslM?.let { altitudeMslM = it }
                    s.geoidSepM?.let { geoidSepM = it }
                    s.altitudeWgs84M?.let { altitudeWgs84M = it }
                    s.fixQuality?.let { fixQuality = it }
                    s.satellitesUsed?.let { satellitesUsed = it }
                    s.hdop?.let { takeHdop(it, SourceRank.MID) }
                    s.diffAgeS?.let { diffAgeS = it }
                    s.diffStationId?.let { diffStationId = it }
                }
                is NmeaSentence.Rmc -> {
                    takePosition(s.position, SourceRank.MID)
                    takeSpeed(s.speedMps, SourceRank.HIGH)
                    takeCourse(s.courseDeg, SourceRank.HIGH)
                    s.magVarDeg?.let { magVarDeg = it }
                    s.modeIndicator?.let { rmcModeIndicator = it }
                }
                is NmeaSentence.Gns -> {
                    takePosition(s.position, SourceRank.LOW)
                    s.satellitesUsed?.let { if (satellitesUsed == null) satellitesUsed = it }
                    s.modeIndicators?.let { gnsModeIndicators = it }
                }
                is NmeaSentence.Gll -> {
                    takePosition(s.position, SourceRank.LOW)
                    s.modeIndicator?.let { gllModeIndicator = it }
                }
                is NmeaSentence.Vtg -> {
                    takeSpeed(s.speedMps, SourceRank.MID)
                    takeCourse(s.courseDeg, SourceRank.MID)
                    s.modeIndicator?.let { vtgModeIndicator = it }
                }
                is NmeaSentence.Gsa -> {
                    s.fixDimension?.let { fixDimension = it }
                    s.hdop?.let { takeHdop(it, SourceRank.HIGH) }
                    s.vdop?.let { vdop = it }
                    s.pdop?.let { pdop = it }
                    s.systemId?.let { gsaSystemId = it }
                }
                is NmeaSentence.Gst -> {
                    s.horizontalSigmaM?.let { horizontalSigmaM = it }
                    s.altSigmaM?.let { verticalSigmaM = it }
                }
                is NmeaSentence.Gsv, is NmeaSentence.Corrupt, is NmeaSentence.Other -> Unit
            }
        }

        private fun takePosition(p: Position?, rank: SourceRank) {
            if (p == null) return
            val cur = positionSource
            if (cur == null || rank.ordinal > cur.ordinal) {
                position = p
                positionSource = rank
            }
        }

        private fun takeSpeed(v: Double?, rank: SourceRank) {
            if (v == null) return
            val cur = speedSource
            if (cur == null || rank.ordinal > cur.ordinal) {
                speedMps = v
                speedSource = rank
            }
        }

        private fun takeCourse(v: Double?, rank: SourceRank) {
            if (v == null) return
            val cur = courseSource
            if (cur == null || rank.ordinal > cur.ordinal) {
                courseDeg = v
                courseSource = rank
            }
        }

        private fun takeHdop(v: Double, rank: SourceRank) {
            val cur = hdopSource
            if (cur == null || rank.ordinal > cur.ordinal) {
                hdop = v
                hdopSource = rank
            }
        }

        fun snapshot(): CycleSnapshot? {
            val ts = timestamp ?: return null
            return CycleSnapshot(
                utcSecond = utcSecond,
                timestamp = ts,
                position = position,
                altitudeMslM = altitudeMslM,
                altitudeWgs84M = altitudeWgs84M,
                geoidSepM = geoidSepM,
                speedMps = speedMps,
                courseDeg = courseDeg,
                magVarDeg = magVarDeg,
                fixQuality = fixQuality,
                fixDimension = fixDimension,
                satellitesUsed = satellitesUsed,
                hdop = hdop,
                vdop = vdop,
                pdop = pdop,
                horizontalSigmaM = horizontalSigmaM,
                verticalSigmaM = verticalSigmaM,
                diffAgeS = diffAgeS,
                diffStationId = diffStationId,
                rmcModeIndicator = rmcModeIndicator,
                vtgModeIndicator = vtgModeIndicator,
                gllModeIndicator = gllModeIndicator,
                gnsModeIndicators = gnsModeIndicators,
                gsaSystemId = gsaSystemId,
            )
        }
    }
}
