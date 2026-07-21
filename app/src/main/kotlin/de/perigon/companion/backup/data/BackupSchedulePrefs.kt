package de.perigon.companion.backup.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import de.perigon.companion.core.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

private val Context.backupScheduleDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "backup_schedule",
)

private object Keys {
    val AUTO_ENABLED      = booleanPreferencesKey("auto_enabled")
    val INTERVAL_HOURS    = longPreferencesKey("interval_hours")
    val NUM_PARTS_PER_PACK = longPreferencesKey("num_parts_per_pack")
}

interface BackupSchedulePrefs {
    fun autoEnabled(): Boolean
    suspend fun setAutoEnabled(value: Boolean)
    fun intervalHours(): Int
    suspend fun setIntervalHours(value: Int)
    fun numPartsPerPack(): Int
    suspend fun setNumPartsPerPack(value: Int)

    companion object {
        val INTERVAL_OPTIONS  = listOf(1, 4, 12, 24, 48)
        val NUM_PARTS_OPTIONS = listOf(8, 16, 32, 64, 128)
        const val DEFAULT_NUM_PARTS     = 64
        const val DEFAULT_INTERVAL_HOURS = 12
    }
}

/**
 * Sync-read facade; snapshot warmed asynchronously at construction
 * (previously a runBlocking disk read during Hilt graph construction on the
 * main thread). Same pattern as TrackConfigPrefs / AudioConfigPrefs.
 */
@Singleton
class BackupSchedulePrefsImpl @Inject constructor(
    @param:ApplicationContext private val ctx: Context,
    @ApplicationScope appScope: CoroutineScope,
) : BackupSchedulePrefs {

    private val ds: DataStore<Preferences> = ctx.backupScheduleDataStore

    @Volatile
    private var cache: Preferences? = null

    private val seed: Deferred<Preferences> = appScope.async(Dispatchers.IO) {
        ds.data.first().also { snapshot ->
            if (cache == null) cache = snapshot
        }
    }

    private fun snapshot(): Preferences = cache ?: runBlocking { seed.await() }

    private suspend fun edit(block: MutablePreferences.() -> Unit) {
        cache = ds.edit(block)
    }

    override fun autoEnabled(): Boolean =
        snapshot()[Keys.AUTO_ENABLED] ?: false

    override suspend fun setAutoEnabled(value: Boolean) {
        edit { set(Keys.AUTO_ENABLED, value) }
    }

    override fun intervalHours(): Int =
        snapshot()[Keys.INTERVAL_HOURS]?.toInt() ?: BackupSchedulePrefs.DEFAULT_INTERVAL_HOURS

    override suspend fun setIntervalHours(value: Int) {
        edit { set(Keys.INTERVAL_HOURS, value.toLong()) }
    }

    override fun numPartsPerPack(): Int =
        snapshot()[Keys.NUM_PARTS_PER_PACK]?.toInt() ?: BackupSchedulePrefs.DEFAULT_NUM_PARTS

    override suspend fun setNumPartsPerPack(value: Int) {
        edit { set(Keys.NUM_PARTS_PER_PACK, value.toLong()) }
    }
}
