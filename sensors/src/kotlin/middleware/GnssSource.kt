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

    fun start(intervalMs: Long)
    fun updateInterval(intervalMs: Long)
    fun stop()

    suspend fun requestSingleFix(timeoutMs: Long): GnssFix?
}
