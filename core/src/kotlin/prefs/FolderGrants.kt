package de.perigon.companion.core.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import de.perigon.companion.core.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.folderGrantsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "folder_grants",
)

/** Keys owned by core. Features define their own (e.g. "track", "audio"). */
object FolderKeys {
    const val DCIM = "dcim"
}

/**
 * Every persisted SAF tree the app works in, by requirement key: the one
 * place AccessRequirement.Folder, the setup screen and the features read.
 * Stores only URI + label; the grant itself lives in the platform.
 */
@Singleton
class FolderGrants @Inject constructor(
    @ApplicationContext ctx: Context,
    @ApplicationScope scope: CoroutineScope,
) {
    private val store = SnapshotStore(ctx.folderGrantsDataStore, scope)

    fun uri(key: String): String? = store.snapshot()[uriKey(key)]

    fun label(key: String): String? = store.snapshot()[labelKey(key)]

    fun observeUri(key: String): Flow<String?> =
        store.data.map { it[uriKey(key)] }.distinctUntilChanged()

    suspend fun set(key: String, uri: String, label: String) = store.edit {
        this[uriKey(key)] = uri
        this[labelKey(key)] = label
    }

    suspend fun clear(key: String) = store.edit {
        remove(uriKey(key))
        remove(labelKey(key))
    }

    private fun uriKey(key: String) = stringPreferencesKey("${key}_uri")
    private fun labelKey(key: String) = stringPreferencesKey("${key}_label")
}
