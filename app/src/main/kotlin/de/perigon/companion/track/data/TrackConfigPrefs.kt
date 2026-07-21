package de.perigon.companion.track.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import de.perigon.companion.core.di.ApplicationScope
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
    val FIX_TIMEOUT_MS          = longPreferencesKey("fix_timeout_ms")
    val MAX_INACCURACY_M        = floatPreferencesKey("max_inaccuracy_m")
    val KEEP_EPHEMERIS_WARM     = booleanPreferencesKey("keep_ephemeris_warm")
    val HOLD_WAKE_LOCK          = booleanPreferencesKey("hold_wake_lock")
    val AUTO_SCHEDULE_ENABLED   = booleanPreferencesKey("auto_schedule_enabled")
    val AUTO_START_SECONDS      = longPreferencesKey("auto_start_seconds")
    val AUTO_STOP_SECONDS       = longPreferencesKey("auto_stop_seconds")
    val AUTO_SPLIT_DAY_ROLLOVER = booleanPreferencesKey("auto_split_day_rollover")
    val AUTO_SEGMENT_GAP_MS     = longPreferencesKey("auto_segment_gap_ms")
}

private fun Preferences.toTrackConfig() = TrackConfigEntity(
    mode                   = this[Keys.MODE]                    ?: TrackConfigEntity.DEFAULT.mode,
    intervalMs             = this[Keys.INTERVAL_MS]             ?: TrackConfigEntity.DEFAULT.intervalMs,
    fixTimeoutMs           = this[Keys.FIX_TIMEOUT_MS]          ?: TrackConfigEntity.DEFAULT.fixTimeoutMs,
    maxInaccuracyM         = this[Keys.MAX_INACCURACY_M]        ?: TrackConfigEntity.DEFAULT.maxInaccuracyM,
    keepEphemerisWarm      = this[Keys.KEEP_EPHEMERIS_WARM]     ?: TrackConfigEntity.DEFAULT.keepEphemerisWarm,
    holdWakeLock           = this[Keys.HOLD_WAKE_LOCK]          ?: TrackConfigEntity.DEFAULT.holdWakeLock,
    autoScheduleEnabled    = this[Keys.AUTO_SCHEDULE_ENABLED]   ?: TrackConfigEntity.DEFAULT.autoScheduleEnabled,
    autoStartSeconds       = this[Keys.AUTO_START_SECONDS]      ?: TrackConfigEntity.DEFAULT.autoStartSeconds,
    autoStopSeconds        = this[Keys.AUTO_STOP_SECONDS]       ?: TrackConfigEntity.DEFAULT.autoStopSeconds,
    autoSplitOnDayRollover = this[Keys.AUTO_SPLIT_DAY_ROLLOVER] ?: TrackConfigEntity.DEFAULT.autoSplitOnDayRollover,
    autoSegmentGapMs       = this[Keys.AUTO_SEGMENT_GAP_MS]     ?: TrackConfigEntity.DEFAULT.autoSegmentGapMs,
)

/**
 * Sync-read facade over the track-config DataStore.
 *
 * The snapshot is warmed ASYNCHRONOUSLY at construction (previously a
 * runBlocking disk read during Hilt graph construction on the main thread —
 * one of four such seeds serialized into cold start). [get] keeps its
 * synchronous signature for existing callers; it only blocks in the unlikely
 * window before the warm-up lands, and never returns defaults for stored
 * values, so [update]'s read-modify-write cannot clobber persisted config.
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
            set(Keys.FIX_TIMEOUT_MS,          updated.fixTimeoutMs)
            set(Keys.MAX_INACCURACY_M,        updated.maxInaccuracyM)
            set(Keys.KEEP_EPHEMERIS_WARM,     updated.keepEphemerisWarm)
            set(Keys.HOLD_WAKE_LOCK,          updated.holdWakeLock)
            set(Keys.AUTO_SCHEDULE_ENABLED,   updated.autoScheduleEnabled)
            set(Keys.AUTO_START_SECONDS,      updated.autoStartSeconds)
            set(Keys.AUTO_STOP_SECONDS,       updated.autoStopSeconds)
            set(Keys.AUTO_SPLIT_DAY_ROLLOVER, updated.autoSplitOnDayRollover)
            set(Keys.AUTO_SEGMENT_GAP_MS,     updated.autoSegmentGapMs)
        }
    }
}
