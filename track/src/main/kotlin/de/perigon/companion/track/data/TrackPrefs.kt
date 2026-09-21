package de.perigon.companion.track.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import de.perigon.companion.core.di.ApplicationScope
import de.perigon.companion.core.prefs.SnapshotStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.trackPrefsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "track_prefs",
)

/**
 * Track settings outside the recorder config: background GPS switch, tile
 * sources, GPX export folder. Method names match the former TrackPrefs, so
 * call sites only changed type.
 */
@Singleton
class TrackPrefs @Inject constructor(
    @ApplicationContext ctx: Context,
    @ApplicationScope scope: CoroutineScope,
) {
    private val store = SnapshotStore(ctx.trackPrefsDataStore, scope)

    private fun str(key: Preferences.Key<String>): String? = store.snapshot()[key]

    // ---- Background GPS ----
    fun isBackgroundGpsEnabled(): Boolean = store.snapshot()[Keys.BACKGROUND_GPS] ?: false
    suspend fun setBackgroundGpsEnabled(enabled: Boolean) = store.edit { set(Keys.BACKGROUND_GPS, enabled) }
    fun observeBackgroundGps(): Flow<Boolean> =
        store.data.map { it[Keys.BACKGROUND_GPS] ?: false }.distinctUntilChanged()

    // ---- Tile source ----
    fun tileSourceUri(): String? = str(Keys.TILE_URI)
    fun tileSourceLabel(): String? = str(Keys.TILE_LABEL)
    fun tileSourceNetworkUrl(): String? = str(Keys.TILE_NETWORK_URL)
    fun tileSourceMode(): String = str(Keys.TILE_MODE) ?: TILE_MODE_LOCAL

    suspend fun setTileSource(uri: String, label: String) = store.edit {
        set(Keys.TILE_URI, uri)
        set(Keys.TILE_LABEL, label)
        set(Keys.TILE_MODE, TILE_MODE_LOCAL)
    }

    suspend fun setTileSourceNetwork(url: String) = store.edit {
        set(Keys.TILE_NETWORK_URL, url)
        set(Keys.TILE_MODE, TILE_MODE_NETWORK)
    }

    suspend fun setTileSourceMode(mode: String) = store.edit { set(Keys.TILE_MODE, mode) }

    suspend fun clearTileSourceLocal() = store.edit {
        remove(Keys.TILE_URI)
        remove(Keys.TILE_LABEL)
        if (this[Keys.TILE_MODE] == TILE_MODE_LOCAL) {
            if (this[Keys.TILE_NETWORK_URL] != null) set(Keys.TILE_MODE, TILE_MODE_NETWORK)
            else remove(Keys.TILE_MODE)
        }
    }

    suspend fun clearTileSourceNetwork() = store.edit {
        remove(Keys.TILE_NETWORK_URL)
        if (this[Keys.TILE_MODE] == TILE_MODE_NETWORK) {
            if (this[Keys.TILE_URI] != null) set(Keys.TILE_MODE, TILE_MODE_LOCAL)
            else remove(Keys.TILE_MODE)
        }
    }

    // ---- GPX export folder (explicit export only; recordings live in the DB) ----
    fun gpxExportFolderUri(): String? = str(Keys.GPX_EXPORT_URI)
    fun gpxExportFolderLabel(): String? = str(Keys.GPX_EXPORT_LABEL)

    suspend fun setGpxExportFolder(uri: String, label: String) = store.edit {
        set(Keys.GPX_EXPORT_URI, uri)
        set(Keys.GPX_EXPORT_LABEL, label)
    }

    suspend fun clearGpxExportFolder() = store.edit {
        remove(Keys.GPX_EXPORT_URI)
        remove(Keys.GPX_EXPORT_LABEL)
    }

    suspend fun clear() = store.edit { this.clear() }

    companion object {
        const val TILE_MODE_LOCAL = "local"
        const val TILE_MODE_NETWORK = "network"
    }

    private object Keys {
        val BACKGROUND_GPS   = booleanPreferencesKey("background_gps")
        val TILE_URI         = stringPreferencesKey("tile_source_uri")
        val TILE_LABEL       = stringPreferencesKey("tile_source_label")
        val TILE_NETWORK_URL = stringPreferencesKey("tile_source_network_url")
        val TILE_MODE        = stringPreferencesKey("tile_source_mode")
        val GPX_EXPORT_URI   = stringPreferencesKey("gpx_export_folder_uri")
        val GPX_EXPORT_LABEL = stringPreferencesKey("gpx_export_folder_label")
    }
}
