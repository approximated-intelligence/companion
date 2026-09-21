package de.perigon.companion.core.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Synchronous-read facade over a Preferences DataStore — the one copy of the
 * pattern previously repeated in AppPrefsImpl, TrackConfigPrefs,
 * AudioConfigPrefs and BackupSchedulePrefsImpl.
 *
 * The snapshot is warmed off-main at construction. [snapshot] only blocks in
 * the short window before that lands, and never returns defaults in place of
 * stored values, so read-modify-write through [current] cannot clobber
 * persisted data. Every [edit] installs the newer snapshot.
 */
class SnapshotStore(
    private val ds: DataStore<Preferences>,
    scope: CoroutineScope,
) {
    @Volatile
    private var cache: Preferences? = null

    private val seed: Deferred<Preferences> = scope.async(Dispatchers.IO) {
        ds.data.first().also { snapshot -> if (cache == null) cache = snapshot }
    }

    val data: Flow<Preferences> get() = ds.data

    fun snapshot(): Preferences = cache ?: runBlocking { seed.await() }

    suspend fun current(): Preferences = cache ?: seed.await()

    suspend fun edit(block: MutablePreferences.() -> Unit) {
        cache = ds.edit { prefs -> prefs.block() }
    }
}
