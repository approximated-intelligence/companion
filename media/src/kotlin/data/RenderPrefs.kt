package de.perigon.companion.media.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import de.perigon.companion.core.di.ApplicationScope
import de.perigon.companion.core.prefs.SnapshotStore
import kotlinx.coroutines.CoroutineScope
import javax.inject.Inject
import javax.inject.Singleton

private val Context.renderPrefsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "render_prefs",
)

/** Renderer configuration (tile source). Owned by media, not by the recorder. */
@Singleton
class RenderPrefs @Inject constructor(
    @ApplicationContext ctx: Context,
    @ApplicationScope scope: CoroutineScope,
) {
    private val store = SnapshotStore(ctx.renderPrefsDataStore, scope)

    private fun str(key: Preferences.Key<String>): String? = store.snapshot()[key]

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

    companion object {
        const val TILE_MODE_LOCAL = "local"
        const val TILE_MODE_NETWORK = "network"

        /** Suggested network tile template (Bing aerial, quadkey form). */
        const val DEFAULT_NETWORK_TILE_URL =
            "https://ecn.t0.tiles.virtualearth.net/tiles/a{q}.jpeg?g=14226"
    }

    private object Keys {
        val TILE_URI         = stringPreferencesKey("tile_source_uri")
        val TILE_LABEL       = stringPreferencesKey("tile_source_label")
        val TILE_NETWORK_URL = stringPreferencesKey("tile_source_network_url")
        val TILE_MODE        = stringPreferencesKey("tile_source_mode")
    }
}
