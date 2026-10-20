package de.perigon.companion.core.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import de.perigon.companion.core.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.corePrefsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "core_prefs",
)

private val SETUP_DONE = booleanPreferencesKey("setup_done")

/** Core's own settings. Feature settings live in their feature modules. */
@Singleton
class CorePrefs @Inject constructor(
    @ApplicationContext ctx: Context,
    @ApplicationScope scope: CoroutineScope,
) {
    private val store = SnapshotStore(ctx.corePrefsDataStore, scope)

    /** The first-start setup screen was completed once. */
    fun setupDone(): Boolean = store.snapshot()[SETUP_DONE] ?: false

    fun observeSetupDone(): Flow<Boolean> =
        store.data.map { it[SETUP_DONE] ?: false }.distinctUntilChanged()

    suspend fun setSetupDone(done: Boolean) = store.edit { this[SETUP_DONE] = done }
}
