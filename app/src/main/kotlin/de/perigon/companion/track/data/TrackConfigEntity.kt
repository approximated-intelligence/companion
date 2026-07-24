package de.perigon.companion.track.data

object RecordingMode {
    const val CONTINUOUS = "CONTINUOUS"
    const val SINGLE_FIX = "SINGLE_FIX"
    const val ALARM      = "ALARM"
    const val PASSIVE    = "PASSIVE"
    val ALL = listOf(CONTINUOUS, SINGLE_FIX, ALARM, PASSIVE)
}

data class TrackConfigEntity(
    val mode: String                     = RecordingMode.CONTINUOUS,
    val intervalMs: Long                 = DEFAULT_INTERVAL_MS,
    val fixTimeoutMs: Long               = DEFAULT_FIX_TIMEOUT_MS,
    val maxInaccuracyM: Float            = DEFAULT_MAX_INACCURACY_M,
    val keepEphemerisWarm: Boolean       = true,
    val holdWakeLock: Boolean            = false,
    val autoScheduleEnabled: Boolean     = false,
    val autoStartSeconds: Long           = 25200L,
    val autoStopSeconds: Long            = 75600L,
    val autoSplitOnDayRollover: Boolean  = true,
    val autoSegmentGapMs: Long           = DEFAULT_AUTO_SEGMENT_GAP_MS,
) {
    val autoStartTime: java.time.LocalTime get() = java.time.LocalTime.ofSecondOfDay(autoStartSeconds)
    val autoStopTime: java.time.LocalTime  get() = java.time.LocalTime.ofSecondOfDay(autoStopSeconds)

    companion object {
        const val DEFAULT_INTERVAL_MS           = 10_000L
        const val DEFAULT_FIX_TIMEOUT_MS        = 30_000L
        const val DEFAULT_MAX_INACCURACY_M      = 50f
        const val DEFAULT_AUTO_SEGMENT_GAP_MS   = 30 * 60 * 1000L // 30 min
        val FIX_TIMEOUT_OPTIONS_MS              = listOf(15_000L, 30_000L, 45_000L, 60_000L)
        val MAX_INACCURACY_OPTIONS_M            = listOf(10f, 25f, 50f, 100f, 200f)
        val AUTO_SEGMENT_GAP_OPTIONS_MS         = listOf(
            5 * 60_000L, 15 * 60_000L, 30 * 60_000L, 60 * 60_000L, 120 * 60_000L
        )
        val DEFAULT = TrackConfigEntity()
    }
}
