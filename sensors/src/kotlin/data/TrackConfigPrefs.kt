package de.perigon.companion.sensors.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import de.perigon.companion.core.di.ApplicationScope
import de.perigon.companion.sensors.domain.KalmanMode
import de.perigon.companion.sensors.domain.KalmanModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

private val Context.trackConfigDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "track_config",
)

private object Keys {
    val MODE                    = stringPreferencesKey("mode")
    val INTERVAL_MS             = longPreferencesKey("interval_ms")
    val HARDWARE_INTERVAL_MS    = longPreferencesKey("hardware_interval_ms")
    val FIX_TIMEOUT_MS          = longPreferencesKey("fix_timeout_ms")
    val MAX_INACCURACY_M        = floatPreferencesKey("max_inaccuracy_m")
    val KEEP_EPHEMERIS_WARM     = booleanPreferencesKey("keep_ephemeris_warm")
    val HOLD_WAKE_LOCK          = booleanPreferencesKey("hold_wake_lock")
    val RECORD_BARO_ALTITUDE    = booleanPreferencesKey("record_baro_altitude")
    val AIRPLANE_MODE           = booleanPreferencesKey("airplane_mode")
    val AUTO_SCHEDULE_ENABLED   = booleanPreferencesKey("auto_schedule_enabled")
    val AUTO_START_SECONDS      = longPreferencesKey("auto_start_seconds")
    val AUTO_STOP_SECONDS       = longPreferencesKey("auto_stop_seconds")
    val AUTO_SPLIT_DAY_ROLLOVER = booleanPreferencesKey("auto_split_day_rollover")
    val AUTO_SEGMENT_GAP_MS     = longPreferencesKey("auto_segment_gap_ms")
    val KALMAN_MODEL            = stringPreferencesKey("kalman_model")
    val KALMAN_MODE             = stringPreferencesKey("kalman_mode")
    val ISKF_INVERSE_HESSIAN    = booleanPreferencesKey("iskf_inverse_hessian")

    // Advanced tuning overrides. Absent key = "use the built-in default";
    // present key = user-tuned value. Never write a default here.
    //
    // Process-noise fields store the variance rate (m²/s or m²/s³) directly,
    // not σ. Same convention across models — see KalmanTuning docs.
    val KALMAN_ACCEL_Q          = floatPreferencesKey("kalman_accel_q")     // q_v, m²/s³
    val KALMAN_BIAS_Q           = floatPreferencesKey("kalman_bias_q")      // q_b, m²/s
    val KALMAN_QH               = floatPreferencesKey("kalman_qh")          // q_h, m²/s
    val KALMAN_DERIVED_VEL      = floatPreferencesKey("kalman_derived_vel_noise")
    val KALMAN_BIAS_RATE_Q      = floatPreferencesKey("kalman_bias_rate_q") // q_vb, m²/s³
    val KALMAN_DERIVED_BIAS_RATE = floatPreferencesKey("kalman_derived_bias_rate_noise")
    val BARO_SIGMA_M            = floatPreferencesKey("baro_sigma_m")
    val KALMAN_GPS_VERTICAL_MIN = floatPreferencesKey("kalman_gps_vertical_min_m")

    // Legacy σ-scale keys from before the unification. Read for migration;
    // removed on any write. Never written again.
    val LEGACY_KALMAN_ACCEL_NOISE = floatPreferencesKey("kalman_accel_noise_mps2")
    val LEGACY_KALMAN_BIAS_NOISE  = floatPreferencesKey("kalman_bias_noise_mps")
    val LEGACY_KALMAN_QB          = floatPreferencesKey("kalman_qb")
}

private fun Preferences.toTrackConfig() = TrackConfigEntity(
    mode                   = this[Keys.MODE]                    ?: TrackConfigEntity.DEFAULT.mode,
    intervalMs             = this[Keys.INTERVAL_MS]             ?: TrackConfigEntity.DEFAULT.intervalMs,
    hardwareIntervalMs     = this[Keys.HARDWARE_INTERVAL_MS]    ?: TrackConfigEntity.DEFAULT.hardwareIntervalMs,
    fixTimeoutMs           = this[Keys.FIX_TIMEOUT_MS]          ?: TrackConfigEntity.DEFAULT.fixTimeoutMs,
    maxInaccuracyM         = this[Keys.MAX_INACCURACY_M]        ?: TrackConfigEntity.DEFAULT.maxInaccuracyM,
    keepEphemerisWarm      = this[Keys.KEEP_EPHEMERIS_WARM]     ?: TrackConfigEntity.DEFAULT.keepEphemerisWarm,
    holdWakeLock           = this[Keys.HOLD_WAKE_LOCK]          ?: TrackConfigEntity.DEFAULT.holdWakeLock,
    recordBaroAltitude     = this[Keys.RECORD_BARO_ALTITUDE]    ?: TrackConfigEntity.DEFAULT.recordBaroAltitude,
    airplaneMode           = this[Keys.AIRPLANE_MODE]           ?: TrackConfigEntity.DEFAULT.airplaneMode,
    autoScheduleEnabled    = this[Keys.AUTO_SCHEDULE_ENABLED]   ?: TrackConfigEntity.DEFAULT.autoScheduleEnabled,
    autoStartSeconds       = this[Keys.AUTO_START_SECONDS]      ?: TrackConfigEntity.DEFAULT.autoStartSeconds,
    autoStopSeconds        = this[Keys.AUTO_STOP_SECONDS]       ?: TrackConfigEntity.DEFAULT.autoStopSeconds,
    autoSplitOnDayRollover = this[Keys.AUTO_SPLIT_DAY_ROLLOVER] ?: TrackConfigEntity.DEFAULT.autoSplitOnDayRollover,
    autoSegmentGapMs       = this[Keys.AUTO_SEGMENT_GAP_MS]     ?: TrackConfigEntity.DEFAULT.autoSegmentGapMs,
    kalmanModel            = parseKalmanModel(this[Keys.KALMAN_MODEL]),
    kalmanMode             = parseKalmanMode(this[Keys.KALMAN_MODE]),
    iskfInverseHessian     = this[Keys.ISKF_INVERSE_HESSIAN]    ?: TrackConfigEntity.DEFAULT.iskfInverseHessian,

    // Process noise: prefer the current key. If absent, migrate from the
    // legacy σ-scale key (square it), or the legacy 2-state qB key (use
    // directly). Migration runs on read; the next write moves the value
    // into the current key and removes the legacy ones.
    kalmanAccelNoiseQ = this[Keys.KALMAN_ACCEL_Q]?.toDouble()
        ?: this[Keys.LEGACY_KALMAN_ACCEL_NOISE]?.toDouble()?.let { it * it },
    kalmanBiasNoiseQ = this[Keys.KALMAN_BIAS_Q]?.toDouble()
        ?: this[Keys.LEGACY_KALMAN_BIAS_NOISE]?.toDouble()?.let { it * it }
        ?: this[Keys.LEGACY_KALMAN_QB]?.toDouble(),
    kalmanQH              = this[Keys.KALMAN_QH]?.toDouble(),
    kalmanDerivedVelNoise = this[Keys.KALMAN_DERIVED_VEL]?.toDouble(),
    kalmanBiasRateQ       = this[Keys.KALMAN_BIAS_RATE_Q]?.toDouble(),
    kalmanDerivedBiasRateNoise = this[Keys.KALMAN_DERIVED_BIAS_RATE]?.toDouble(),
    baroSigmaM            = this[Keys.BARO_SIGMA_M],
    kalmanGpsVerticalMinM = this[Keys.KALMAN_GPS_VERTICAL_MIN],
)

private fun parseKalmanMode(raw: String?): KalmanMode =
    if (raw == null) KalmanMode.GAUSSIAN
    else runCatching { KalmanMode.valueOf(raw) }.getOrDefault(KalmanMode.GAUSSIAN)

private fun parseKalmanModel(raw: String?): KalmanModel =
    if (raw == null) KalmanModel.THREE_STATE
    else runCatching { KalmanModel.valueOf(raw) }.getOrDefault(KalmanModel.THREE_STATE)

/**
 * Sync-read facade over the track-config DataStore.
 *
 * The snapshot is warmed ASYNCHRONOUSLY at construction (previously a
 * runBlocking disk read during Hilt graph construction on the main thread —
 * one of four such seeds serialized into cold start). [get] keeps its
 * synchronous signature for existing callers; it only blocks in the unlikely
 * window before the warm-up lands, and never returns defaults for stored
 * values, so [update]'s read-modify-write cannot clobber persisted config.
 *
 * Process-noise migration: any [update] removes the legacy σ-scale keys
 * and writes the current q-scale keys from the input config. Because
 * [toTrackConfig] already migrated the values on read, the user's tuned
 * values survive across the rename; the legacy keys are deleted on first
 * write so subsequent reads use the fast path.
 */
@Singleton
class TrackConfigPrefs @Inject constructor(
    @param:ApplicationContext private val ctx: Context,
    @ApplicationScope appScope: CoroutineScope,
) {
    private val ds: DataStore<Preferences> = ctx.trackConfigDataStore

    @Volatile
    private var cache: Preferences? = null

    private val seed: Deferred<Preferences> = appScope.async(Dispatchers.IO) {
        ds.data.first().also { snapshot ->
            // An edit() completing before the seed already installed a newer
            // snapshot — never replace it with the older read.
            if (cache == null) cache = snapshot
        }
    }

    private suspend fun edit(block: MutablePreferences.() -> Unit) {
        cache = ds.edit(block)
    }

    private suspend fun awaitPrefs(): Preferences = cache ?: seed.await()

    fun get(): TrackConfigEntity =
        (cache ?: runBlocking { seed.await() }).toTrackConfig()

    fun observe(): Flow<TrackConfigEntity> = ds.data.map { it.toTrackConfig() }

    suspend fun update(transform: (TrackConfigEntity) -> TrackConfigEntity) {
        val updated = transform(awaitPrefs().toTrackConfig())
        edit {
            set(Keys.MODE,                    updated.mode)
            set(Keys.INTERVAL_MS,             updated.intervalMs)
            set(Keys.HARDWARE_INTERVAL_MS,    updated.hardwareIntervalMs)
            set(Keys.FIX_TIMEOUT_MS,          updated.fixTimeoutMs)
            set(Keys.MAX_INACCURACY_M,        updated.maxInaccuracyM)
            set(Keys.KEEP_EPHEMERIS_WARM,     updated.keepEphemerisWarm)
            set(Keys.HOLD_WAKE_LOCK,          updated.holdWakeLock)
            set(Keys.RECORD_BARO_ALTITUDE,    updated.recordBaroAltitude)
            set(Keys.AIRPLANE_MODE,           updated.airplaneMode)
            set(Keys.AUTO_SCHEDULE_ENABLED,   updated.autoScheduleEnabled)
            set(Keys.AUTO_START_SECONDS,      updated.autoStartSeconds)
            set(Keys.AUTO_STOP_SECONDS,       updated.autoStopSeconds)
            set(Keys.AUTO_SPLIT_DAY_ROLLOVER, updated.autoSplitOnDayRollover)
            set(Keys.AUTO_SEGMENT_GAP_MS,     updated.autoSegmentGapMs)
            set(Keys.KALMAN_MODEL,            updated.kalmanModel.name)
            set(Keys.KALMAN_MODE,             updated.kalmanMode.name)
            set(Keys.ISKF_INVERSE_HESSIAN,    updated.iskfInverseHessian)
            // Advanced tuning overrides: null means "clear the override,
            // fall back to the built-in default". Never write a default
            // value here — otherwise the two copies of "0.04 is the default"
            // drift the first time either is tuned.
            updated.kalmanAccelNoiseQ
                ?.let { set(Keys.KALMAN_ACCEL_Q, it.toFloat()) }
                ?: remove(Keys.KALMAN_ACCEL_Q)
            updated.kalmanBiasNoiseQ
                ?.let { set(Keys.KALMAN_BIAS_Q, it.toFloat()) }
                ?: remove(Keys.KALMAN_BIAS_Q)
            updated.kalmanQH
                ?.let { set(Keys.KALMAN_QH, it.toFloat()) }
                ?: remove(Keys.KALMAN_QH)
            updated.kalmanDerivedVelNoise
                ?.let { set(Keys.KALMAN_DERIVED_VEL, it.toFloat()) }
                ?: remove(Keys.KALMAN_DERIVED_VEL)
            updated.kalmanBiasRateQ
                ?.let { set(Keys.KALMAN_BIAS_RATE_Q, it.toFloat()) }
                ?: remove(Keys.KALMAN_BIAS_RATE_Q)
            updated.kalmanDerivedBiasRateNoise
                ?.let { set(Keys.KALMAN_DERIVED_BIAS_RATE, it.toFloat()) }
                ?: remove(Keys.KALMAN_DERIVED_BIAS_RATE)
            updated.baroSigmaM
                ?.let { set(Keys.BARO_SIGMA_M, it) }
                ?: remove(Keys.BARO_SIGMA_M)
            updated.kalmanGpsVerticalMinM
                ?.let { set(Keys.KALMAN_GPS_VERTICAL_MIN, it) }
                ?: remove(Keys.KALMAN_GPS_VERTICAL_MIN)
            // Drop legacy σ-scale keys. They were read for migration above;
            // any subsequent write supersedes them, so they must not linger
            // and shadow a future read.
            remove(Keys.LEGACY_KALMAN_ACCEL_NOISE)
            remove(Keys.LEGACY_KALMAN_BIAS_NOISE)
            remove(Keys.LEGACY_KALMAN_QB)
        }
    }
}
