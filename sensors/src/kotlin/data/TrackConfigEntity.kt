package de.perigon.companion.sensors.data

import de.perigon.companion.sensors.domain.KalmanMode
import de.perigon.companion.sensors.domain.KalmanModel
import de.perigon.companion.sensors.domain.KalmanTuning

object RecordingMode {
    const val CONTINUOUS = "CONTINUOUS"
    const val SINGLE_FIX = "SINGLE_FIX"
    const val ALARM      = "ALARM"
    const val PASSIVE    = "PASSIVE"
    val ALL = listOf(CONTINUOUS, SINGLE_FIX, ALARM, PASSIVE)

    val DEPRECATED = setOf(SINGLE_FIX, ALARM)
}

data class TrackConfigEntity(
    val mode: String                     = RecordingMode.CONTINUOUS,
    val intervalMs: Long                 = DEFAULT_INTERVAL_MS,
    val hardwareIntervalMs: Long         = DEFAULT_HARDWARE_INTERVAL_MS,
    val fixTimeoutMs: Long               = DEFAULT_FIX_TIMEOUT_MS,
    val maxInaccuracyM: Float            = DEFAULT_MAX_INACCURACY_M,
    val keepEphemerisWarm: Boolean       = true,
    val holdWakeLock: Boolean            = false,
    val recordBaroAltitude: Boolean      = false,
    val airplaneMode: Boolean            = false,
    val autoScheduleEnabled: Boolean     = false,
    val autoStartSeconds: Long           = 25200L,
    val autoStopSeconds: Long            = 75600L,
    val autoSplitOnDayRollover: Boolean  = true,
    val autoSegmentGapMs: Long           = DEFAULT_AUTO_SEGMENT_GAP_MS,
    // Model axis (state dimension / dynamics). Independent of kalmanMode.
    val kalmanModel: KalmanModel         = KalmanModel.THREE_STATE,
    // Method axis (update rule). Independent of kalmanModel.
    val kalmanMode: KalmanMode           = KalmanMode.GAUSSIAN,
    val iskfInverseHessian: Boolean      = false,
    // Advanced altitude-filter tuning overrides. Null = use the built-in
    // default from KalmanTuning's companion. Non-null only when the user has
    // touched a slider in the Track or KF tab's "Advanced tuning" expander.
    //
    // PROCESS NOISE FIELDS ARE THE PROCESS NOISE ITSELF (variance rate),
    // not σ. Same convention across models — see KalmanTuning docs. The
    // `biasNoiseQ` field is shared by 2-state and 3-state (both model b
    // as a random walk); the other fields are model-specific.
    val kalmanAccelNoiseQ: Double?       = null,   // 3-state only, q_v, m²/s³
    val kalmanBiasNoiseQ: Double?        = null,   // both models, q_b, m²/s
    val kalmanQH: Double?                = null,   // 2-state only, q_h, m²/s
    val kalmanDerivedVelNoise: Double?   = null,   // 2-state only, q_v (cascade)
    val baroSigmaM: Float?               = null,   // both, R_baro = σ²
    val kalmanGpsVerticalMinM: Float?    = null,   // both, σ floor on GPS
) {
    val autoStartTime: java.time.LocalTime get() = java.time.LocalTime.ofSecondOfDay(autoStartSeconds)
    val autoStopTime: java.time.LocalTime  get() = java.time.LocalTime.ofSecondOfDay(autoStopSeconds)

    companion object {
        const val DEFAULT_INTERVAL_MS           = 10_000L
        const val DEFAULT_HARDWARE_INTERVAL_MS  = 1_000L
        const val DEFAULT_FIX_TIMEOUT_MS        = 30_000L
        const val DEFAULT_MAX_INACCURACY_M      = 50f
        const val DEFAULT_AUTO_SEGMENT_GAP_MS   = 30 * 60 * 1000L  // 30 min

        // Two interval scales with the same shape (1/2/5/10/15/30/60…), kept
        // side by side because they are read together by the recording UI.
        // The logging rate is filtered against the hardware rate at runtime
        // by isValidLoggingRate() — 15 s logging with 10 s hardware is
        // hidden, not silently rounded.
        val HARDWARE_INTERVAL_OPTIONS_MS        = listOf(
            1_000L, 2_000L, 5_000L, 10_000L, 15_000L,
            30_000L, 60_000L, 120_000L, 300_000L,
        )
        val INTERVAL_OPTIONS_MS                 = listOf(
            1_000L, 2_000L, 5_000L, 10_000L, 15_000L, 30_000L,
            60_000L, 120_000L, 300_000L, 600_000L, 900_000L,
            1_800_000L, 3_600_000L,
        )

        val FIX_TIMEOUT_OPTIONS_MS              = listOf(15_000L, 30_000L, 45_000L, 60_000L)
        val MAX_INACCURACY_OPTIONS_M            = listOf(10f, 25f, 50f, 100f, 200f)
        val AUTO_SEGMENT_GAP_OPTIONS_MS         = listOf(
            5 * 60_000L, 15 * 60_000L, 30 * 60_000L, 60 * 60_000L, 120 * 60_000L
        )
        val DEFAULT = TrackConfigEntity()
    }
}

/** Compose the current tuning overrides for the filter. */
fun TrackConfigEntity.toKalmanTuning(): KalmanTuning = KalmanTuning(
    accelNoiseQ     = kalmanAccelNoiseQ,
    biasNoiseQ      = kalmanBiasNoiseQ,
    qH              = kalmanQH,
    derivedVelNoise = kalmanDerivedVelNoise,
    baroSigmaM      = baroSigmaM,
    gpsVerticalMinM = kalmanGpsVerticalMinM,
)

fun isValidLoggingRate(loggingMs: Long, hardwareMs: Long): Boolean =
    hardwareMs > 0L && loggingMs >= hardwareMs && loggingMs % hardwareMs == 0L

fun smallestValidLoggingRate(hardwareMs: Long, options: List<Long>): Long =
    options.firstOrNull { isValidLoggingRate(it, hardwareMs) } ?: hardwareMs
