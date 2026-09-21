package de.perigon.companion.track.domain

import de.perigon.companion.track.data.RecordingMode

data class BackgroundConfig(
    val mode: String,
    val intervalMs: Long,
    val fixTimeoutMs: Long,
    val maxInaccuracyM: Float,
    val keepEphemerisWarm: Boolean,
    val holdWakeLock: Boolean,
    val autoSplitOnDayRollover: Boolean,
    val autoSegmentGapMs: Long,
)

sealed class FixStrategy {
    data class Continuous(val minTimeMs: Long) : FixStrategy()
    data class DelayedSingle(val delayMs: Long) : FixStrategy()
    data class AlarmSingle(val delayMs: Long) : FixStrategy()
    data object Passive : FixStrategy()
}

object Background {

    fun strategyFor(mode: String, intervalMs: Long): FixStrategy = when (mode) {
        RecordingMode.CONTINUOUS -> FixStrategy.Continuous(intervalMs)
        RecordingMode.SINGLE_FIX -> FixStrategy.DelayedSingle(intervalMs)
        RecordingMode.ALARM      -> FixStrategy.AlarmSingle(intervalMs)
        RecordingMode.PASSIVE    -> FixStrategy.Passive
        else                     -> FixStrategy.Continuous(intervalMs)
    }

    fun shouldFlush(
        bufferSize: Int,
        lastFlushElapsedMs: Long,
        intervalMs: Long,
        mode: String,
    ): Boolean {
        if (bufferSize == 0) return false
        if (mode != RecordingMode.PASSIVE && intervalMs >= 120_000L) return true
        return lastFlushElapsedMs >= 120_000L
    }
}

val INTERVAL_OPTIONS_MS: List<Long> = listOf(
    1_000L, 2_000L, 5_000L, 10_000L, 30_000L,
    60_000L, 120_000L, 300_000L, 900_000L,
)

fun formatIntervalLabel(ms: Long): String = when {
    ms < 60_000L -> "${ms / 1000}s"
    else         -> "${ms / 60_000}min"
}

fun formatModeLabel(mode: String): String = when (mode) {
    RecordingMode.CONTINUOUS -> "Continuous"
    RecordingMode.SINGLE_FIX -> "Single fix"
    RecordingMode.ALARM      -> "Alarm"
    RecordingMode.PASSIVE    -> "Passive"
    else                     -> mode
}
