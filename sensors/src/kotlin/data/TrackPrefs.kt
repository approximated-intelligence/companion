package de.perigon.companion.sensors.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import de.perigon.companion.core.di.ApplicationScope
import de.perigon.companion.core.geo.CoordFormat
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
 * Recorder settings: background GPS switch, deprecated-mode visibility, GPX
 * export folder, GNSS coordinate display format, KF sandbox wake-lock
 * preference. Tile-source config moved to media.data.RenderPrefs.
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

    // ---- Deprecated recording modes ----
    // SINGLE_FIX and ALARM are only shown in the mode selector when this is
    // on; the currently selected mode is always shown so the user can switch
    // away even after toggling off.
    fun isShowDeprecatedModesEnabled(): Boolean =
        store.snapshot()[Keys.SHOW_DEPRECATED_MODES] ?: false
    suspend fun setShowDeprecatedModes(enabled: Boolean) =
        store.edit { set(Keys.SHOW_DEPRECATED_MODES, enabled) }
    fun observeShowDeprecatedModes(): Flow<Boolean> =
        store.data.map { it[Keys.SHOW_DEPRECATED_MODES] ?: false }.distinctUntilChanged()

    // ---- KF sandbox wake-lock ----
    // Persists the sandbox's wake-lock toggle across tab changes and app
    // restarts. Unlike the other KF tab toggles (which are session-local
    // in KfUiState and reset on tab change), this one has to survive
    // leaving the tab: the whole point is overnight convergence with the
    // screen off, and requiring the user to re-enable it each time they
    // return would defeat the purpose. The *active* lock is still session-
    // scoped — acquired when the sandbox FGS starts, released when it
    // stops — so the battery cost is not paid outside the tab.
    fun isKfWakeLockEnabled(): Boolean = store.snapshot()[Keys.KF_WAKELOCK] ?: false
    suspend fun setKfWakeLockEnabled(enabled: Boolean) =
        store.edit { set(Keys.KF_WAKELOCK, enabled) }

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

    // ---- GNSS coordinate display format ----
    fun coordFormat(): CoordFormat =
        parseCoordFormat(store.snapshot()[Keys.COORD_FORMAT])

    fun observeCoordFormat(): Flow<CoordFormat> =
        store.data
            .map { parseCoordFormat(it[Keys.COORD_FORMAT]) }
            .distinctUntilChanged()

    suspend fun setCoordFormat(format: CoordFormat) =
        store.edit { set(Keys.COORD_FORMAT, format.name) }

    private fun parseCoordFormat(raw: String?): CoordFormat {
        if (raw == null) return CoordFormat.DD
        return runCatching { CoordFormat.valueOf(raw) }.getOrDefault(CoordFormat.DD)
    }

    suspend fun clear() = store.edit { this.clear() }

    private object Keys {
        val BACKGROUND_GPS        = booleanPreferencesKey("background_gps")
        val SHOW_DEPRECATED_MODES = booleanPreferencesKey("show_deprecated_modes")
        val KF_WAKELOCK           = booleanPreferencesKey("kf_wakelock")
        val GPX_EXPORT_URI        = stringPreferencesKey("gpx_export_folder_uri")
        val GPX_EXPORT_LABEL      = stringPreferencesKey("gpx_export_folder_label")
        val COORD_FORMAT          = stringPreferencesKey("coord_format")
    }
}
