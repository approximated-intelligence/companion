package de.perigon.companion.sensors.middleware

import android.util.Log
import de.perigon.companion.core.di.ApplicationScope
import de.perigon.companion.sensors.data.TrackPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ref-counted GNSS facade. Consumers [acquire] a [GnssSession]; the
 * controller runs the active source at the minimum interval among all
 * sessions. Source swap is triggered by the user setting via
 * [sourceType]; consumers' [fixes] subscription survives the swap.
 *
 * [state] is a proper merged flow (via `flatMapLatest`), not a computed
 * getter: `get() = if (…) a.state else b.state` returns a different
 * StateFlow after each toggle, so collectors on the old flow never see
 * the swap.
 *
 * `flatMapLatest` is still annotated `@ExperimentalCoroutinesApi` at the
 * time of writing — stable enough that the whole coroutine ecosystem
 * depends on it, but the opt-in is required to silence the warning.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class GnssController @Inject constructor(
    private val prefs: TrackPrefs,
    private val locationSource: LocationGnssSource,
    private val nmeaSource: NmeaGnssSource,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _fixes = MutableSharedFlow<GnssFix>(replay = 0, extraBufferCapacity = 8)
    val fixes: SharedFlow<GnssFix> = _fixes.asSharedFlow()

    val sourceType: StateFlow<GnssSourceType> = run {
        val initial = prefs.gnssSourceType()
        MutableStateFlow(initial).also { flow ->
            scope.launch {
                prefs.observeGnssSourceType().distinctUntilChanged().collect {
                    flow.value = it
                }
            }
        }
    }

    val state: StateFlow<GnssSourceState> = sourceType
        .flatMapLatest { sourceFor(it).state }
        .stateIn(scope, SharingStarted.Eagerly, GnssSourceState.Idle)

    private val lock = Any()
    private val sessions = mutableMapOf<Long, Long>()
    private val nextId = AtomicLong(0L)
    private var activeSource: GnssSource? = null
    private var activeSourceType: GnssSourceType? = null

    init {
        scope.launch {
            sourceType.collectLatest { type ->
                sourceFor(type).fixes.collect { _fixes.emit(it) }
            }
        }
        scope.launch {
            sourceType.drop(1).collect { synchronized(lock) { rebindLocked() } }
        }
    }

    fun acquire(intervalMs: Long): GnssSession {
        val id = synchronized(lock) {
            val id = nextId.incrementAndGet()
            sessions[id] = intervalMs
            // A new session means a UI surface just opened that displays
            // position. Force warm-up on the already-running source so the
            // fresh session's first fix arrives at TTFF, not one slow
            // interval later. Without this, returning to the Track tab
            // while the dashboard's session keeps the source alive at
            // hardwareIntervalMs leaves the card waiting a full interval
            // for its first fresh fix — see GnssSource.restartWarmUp.
            // No-op during an active warm-up and null-safe on the first
            // acquire, where rebindLocked() below calls start() which
            // handles warm-up itself.
            activeSource?.restartWarmUp()
            rebindLocked()
            id
        }
        var closed = false
        return object : GnssSession {
            override fun updateInterval(intervalMs: Long) {
                synchronized(lock) {
                    if (closed) return
                    sessions[id] = intervalMs
                    rebindLocked()
                }
            }
            override fun close() {
                synchronized(lock) {
                    if (closed) return
                    closed = true
                    sessions.remove(id)
                    rebindLocked()
                }
            }
        }
    }

    suspend fun requestSingleFix(timeoutMs: Long): GnssFix? =
        sourceFor(sourceType.value).requestSingleFix(timeoutMs)

    private fun sourceFor(type: GnssSourceType): GnssSource = when (type) {
        GnssSourceType.LOCATION -> locationSource
        GnssSourceType.NMEA -> nmeaSource
    }

    private fun rebindLocked() {
        val type = sourceType.value
        val minInterval = sessions.values.minOrNull()

        if (minInterval == null) {
            activeSource?.stop()
            activeSource = null
            activeSourceType = null
            return
        }

        if (activeSource == null || activeSourceType != type) {
            activeSource?.stop()
            activeSource = sourceFor(type)
            activeSourceType = type
            activeSource!!.start(minInterval)
            Log.i(TAG, "Active source: $type @ ${minInterval}ms")
        } else {
            activeSource!!.updateInterval(minInterval)
        }
    }

    private companion object { const val TAG = "GnssController" }
}

interface GnssSession : Closeable {
    fun updateInterval(intervalMs: Long)
}
