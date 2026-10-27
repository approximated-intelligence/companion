package de.perigon.companion.sensors.middleware

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

sealed interface GnssSourceState {
    data object Idle : GnssSourceState
    data object Running : GnssSourceState
    data class Error(val message: String) : GnssSourceState
}

/**
 * A GNSS fix producer. Plug-in point for new sources (Bluetooth NMEA,
 * USB serial): implement this, get `@Inject @Singleton` on the
 * constructor, add a [GnssSourceType] value, wire it in
 * [GnssController.sourceFor].
 *
 * Contract:
 *  - [fixes] emits only when the source has a *complete* fix — a cycle
 *    whose position and timestamp are both known. Consumers never
 *    receive a half-built fix.
 *  - [start] / [updateInterval] / [stop] drive the continuous stream
 *    used by [fixes]. [intervalMs] is the *delivery* interval, not an
 *    engine interval — the GNSS chip runs at its own rate regardless.
 *  - [requestSingleFix] warms the engine, waits for the next complete
 *    fix, and returns it (or null on timeout). Independent of any
 *    active continuous session.
 */
interface GnssSource {
    val type: GnssSourceType
    val fixes: Flow<GnssFix>
    val state: StateFlow<GnssSourceState>

    /**
     * Start continuous delivery at the user's requested [intervalMs].
     *
     * CONTRACT: the FIRST fix arrives as soon as the hardware can produce
     * one (TTFF), NOT one [intervalMs] later. The interval applies to
     * fixes after the first. Implementations achieve this by warming the
     * engine at [FIRST_CYCLE_INTERVAL_MS] and switching to [intervalMs]
     * only after the first fix has been delivered. This matters on HALs
     * that treat `minTime` as a polling interval rather than a lower
     * bound: without it, the first fix is delayed by the full interval
     * (60 s at the recorder's default, which reads as a hung screen).
     *
     * Consumers must be agnostic to which source is active and to this
     * warm-up — the first fix arrives on the same timescale regardless of
     * source. Only diagnostics should read [GnssFix.source].
     */
    fun start(intervalMs: Long)
    fun updateInterval(intervalMs: Long)
    fun stop()

    suspend fun requestSingleFix(timeoutMs: Long): GnssFix?

    /**
     * Restart the first-fix warm-up. Called by [GnssController] whenever
     * a new [GnssSession] is acquired: the caller has just opened a UI
     * surface that displays position, so the fresh session's first fix
     * should arrive at TTFF rather than one slow interval later. This is
     * the tab-switch case — the source stays running (another consumer's
     * session keeps it alive at a slow rate), [start] is never called,
     * and without this the card waits a full [hardwareIntervalMs] for
     * its first fresh fix after a return-to-tab.
     *
     * Implementations switch the engine back to
     * [FIRST_CYCLE_INTERVAL_MS] and drop to [targetIntervalMs] after the
     * next fix lands, exactly like [start]. No-op while already warming
     * up.
     */
    fun restartWarmUp()

    companion object {
        /**
         * Warm-up interval for the first fix: 1 s matches the recorder's
         * KF-clamped rate and the KF sandbox's fixed rate, so first-fix
         * latency is consistent across every surface. See [start].
         */
        const val FIRST_CYCLE_INTERVAL_MS = 1_000L
    }
}
