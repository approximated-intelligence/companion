package de.perigon.companion.core.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private val Context.appDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_prefs")

interface AppPrefs {
    // ---- B2 addressing ----
    fun b2Endpoint(): String?
    fun b2Bucket(): String?
    fun b2KeyId(): String?
    suspend fun setB2Config(endpoint: String?, keyId: String, bucket: String?)

    // ---- NaCl public key ----
    fun naclPkHex(): String?
    suspend fun setNaclPkHex(hex: String)

    // ---- GitHub repo identity ----
    fun githubOwner(): String?
    fun githubRepo(): String?
    suspend fun setGithubRepo(owner: String, repo: String)

    // ---- Site URL ----
    fun siteUrl(): String?
    suspend fun setSiteUrl(url: String)

    // ---- Tile source ----
    fun tileSourceUri(): String?
    fun tileSourceLabel(): String?
    suspend fun setTileSource(uri: String, label: String)
    suspend fun clearTileSource()

    // ---- Journey ----
    fun journeyStartDate(): String?
    fun journeyTitle(): String?
    fun journeyTag(): String?
    suspend fun setJourneyStartDate(date: String)
    suspend fun setJourneyTitle(title: String)
    suspend fun setJourneyTag(tag: String)

    // ---- Recording ----
    fun isBackgroundGpsEnabled(): Boolean
    suspend fun setBackgroundGpsEnabled(enabled: Boolean)

    // ---- GPX export folder ----
    fun gpxExportFolderUri(): String?
    fun gpxExportFolderLabel(): String?
    suspend fun setGpxExportFolder(uri: String, label: String)
    suspend fun clearGpxExportFolder()

    // ---- PostMedia folder ----
    fun postMediaFolderUri(): String?
    fun postMediaFolderLabel(): String?
    suspend fun setPostMediaFolder(uri: String, label: String)
    suspend fun clearPostMediaFolder()

    // ---- Autosave ----
    fun autosaveDebounceMs(): Long
    suspend fun setAutosaveDebounceMs(ms: Long)
    fun autosaveMaxIntervalMs(): Long
    suspend fun setAutosaveMaxIntervalMs(ms: Long)

    // ---- Observe ----
    fun observeAll(): Flow<Preferences>
    fun observeBackgroundGps(): Flow<Boolean> = observeAll().map { it[booleanPreferencesKey("background_gps")] ?: false }

    // ---- Bulk ----
    suspend fun clearAll()

    // ---- Convenience ----
    data class B2Config(val endpoint: String, val bucket: String, val keyId: String)

    fun b2Config(): B2Config? {
        val endpoint = b2Endpoint() ?: return null
        val bucket = b2Bucket() ?: return null
        val keyId = b2KeyId() ?: return null
        return B2Config(endpoint, bucket, keyId)
    }

    data class GitHubRepo(val owner: String, val repo: String)

    fun githubRepo2(): GitHubRepo? {
        val owner = githubOwner() ?: return null
        val repo = githubRepo() ?: return null
        return GitHubRepo(owner, repo)
    }

    companion object {
        const val DEFAULT_AUTOSAVE_DEBOUNCE_MS = 3_000L
        val AUTOSAVE_OPTIONS_MS = listOf(1_000L, 2_000L, 3_000L, 5_000L, 10_000L)
        const val DEFAULT_AUTOSAVE_MAX_INTERVAL_MS = 60_000L
        val AUTOSAVE_MAX_INTERVAL_OPTIONS_MS = listOf(15_000L, 30_000L, 60_000L, 120_000L)
    }
}

@Singleton
class AppPrefsImpl @Inject constructor(
    @param:ApplicationContext private val ctx: Context,
) : AppPrefs {

    private val ds: DataStore<Preferences> = ctx.appDataStore

    /**
     * Cached snapshot of current preferences. Updated on every write.
     * Synchronous reads pull from this cache — no runBlocking on hot paths.
     * Initialized once at construction via runBlocking (cold start only).
     */
    @Volatile
    private var cache: Preferences = runBlocking { ds.data.first() }

    init {
        migrateLegacyIfNeeded()
    }

    private fun getString(key: Preferences.Key<String>): String? = cache[key]
    private fun getLong(key: Preferences.Key<Long>, default: Long): Long = cache[key] ?: default
    private fun getBoolean(key: Preferences.Key<Boolean>, default: Boolean): Boolean = cache[key] ?: default

    private suspend fun edit(block: MutablePreferences.() -> Unit) {
        cache = ds.edit(block)
    }

    // ---- B2 addressing ----
    override fun b2Endpoint(): String? = getString(Keys.B2_ENDPOINT)
    override fun b2Bucket(): String? = getString(Keys.B2_BUCKET)
    override fun b2KeyId(): String? = getString(Keys.B2_KEY_ID)

    override suspend fun setB2Config(endpoint: String?, keyId: String, bucket: String?) {
        edit {
            if (endpoint != null) set(Keys.B2_ENDPOINT, endpoint) else remove(Keys.B2_ENDPOINT)
            set(Keys.B2_KEY_ID, keyId)
            if (bucket != null) set(Keys.B2_BUCKET, bucket) else remove(Keys.B2_BUCKET)
        }
    }

    // ---- NaCl ----
    override fun naclPkHex(): String? = getString(Keys.NACL_PK_HEX)
    override suspend fun setNaclPkHex(hex: String) { edit { set(Keys.NACL_PK_HEX, hex) } }

    // ---- GitHub repo identity ----
    override fun githubOwner(): String? = getString(Keys.GITHUB_OWNER)
    override fun githubRepo(): String? = getString(Keys.GITHUB_REPO)

    override suspend fun setGithubRepo(owner: String, repo: String) {
        edit {
            set(Keys.GITHUB_OWNER, owner)
            set(Keys.GITHUB_REPO, repo)
        }
    }

    // ---- Site URL ----
    override fun siteUrl(): String? = getString(Keys.SITE_URL)
    override suspend fun setSiteUrl(url: String) { edit { set(Keys.SITE_URL, url) } }

    // ---- Tile source ----
    override fun tileSourceUri(): String? = getString(Keys.TILE_URI)
    override fun tileSourceLabel(): String? = getString(Keys.TILE_LABEL)

    override suspend fun setTileSource(uri: String, label: String) {
        edit {
            set(Keys.TILE_URI, uri)
            set(Keys.TILE_LABEL, label)
        }
    }

    override suspend fun clearTileSource() {
        edit {
            remove(Keys.TILE_URI)
            remove(Keys.TILE_LABEL)
        }
    }

    // ---- Journey ----
    override fun journeyStartDate(): String? = getString(Keys.JOURNEY_START)
    override fun journeyTitle(): String? = getString(Keys.JOURNEY_TITLE)
    override fun journeyTag(): String? = getString(Keys.JOURNEY_TAG)

    override suspend fun setJourneyStartDate(date: String) { edit { set(Keys.JOURNEY_START, date) } }
    override suspend fun setJourneyTitle(title: String) { edit { set(Keys.JOURNEY_TITLE, title) } }
    override suspend fun setJourneyTag(tag: String) { edit { set(Keys.JOURNEY_TAG, tag) } }

    // ---- Recording ----
    override fun isBackgroundGpsEnabled(): Boolean = getBoolean(Keys.BACKGROUND_GPS, false)
    override suspend fun setBackgroundGpsEnabled(enabled: Boolean) { edit { set(Keys.BACKGROUND_GPS, enabled) } }

    // ---- GPX export folder ----
    override fun gpxExportFolderUri(): String? = getString(Keys.GPX_EXPORT_URI)
    override fun gpxExportFolderLabel(): String? = getString(Keys.GPX_EXPORT_LABEL)

    override suspend fun setGpxExportFolder(uri: String, label: String) {
        edit {
            set(Keys.GPX_EXPORT_URI, uri)
            set(Keys.GPX_EXPORT_LABEL, label)
        }
    }

    override suspend fun clearGpxExportFolder() {
        edit {
            remove(Keys.GPX_EXPORT_URI)
            remove(Keys.GPX_EXPORT_LABEL)
        }
    }

    // ---- PostMedia folder ----
    override fun postMediaFolderUri(): String? = getString(Keys.POSTMEDIA_URI)
    override fun postMediaFolderLabel(): String? = getString(Keys.POSTMEDIA_LABEL)

    override suspend fun setPostMediaFolder(uri: String, label: String) {
        edit {
            set(Keys.POSTMEDIA_URI, uri)
            set(Keys.POSTMEDIA_LABEL, label)
        }
    }

    override suspend fun clearPostMediaFolder() {
        edit {
            remove(Keys.POSTMEDIA_URI)
            remove(Keys.POSTMEDIA_LABEL)
        }
    }

    // ---- Autosave ----
    override fun autosaveDebounceMs(): Long = getLong(Keys.AUTOSAVE_DEBOUNCE_MS, AppPrefs.DEFAULT_AUTOSAVE_DEBOUNCE_MS)
    override suspend fun setAutosaveDebounceMs(ms: Long) { edit { set(Keys.AUTOSAVE_DEBOUNCE_MS, ms) } }

    override fun autosaveMaxIntervalMs(): Long = getLong(Keys.AUTOSAVE_MAX_INTERVAL_MS, AppPrefs.DEFAULT_AUTOSAVE_MAX_INTERVAL_MS)
    override suspend fun setAutosaveMaxIntervalMs(ms: Long) { edit { set(Keys.AUTOSAVE_MAX_INTERVAL_MS, ms) } }

    // ---- Observe ----
    override fun observeAll(): Flow<Preferences> = ds.data

    // ---- Bulk ----
    override suspend fun clearAll() {
        edit { clear() }
    }

    // ---- Legacy migration ----

    private fun migrateLegacyIfNeeded() {
        val legacyFile = File(ctx.applicationInfo.dataDir, "shared_prefs/app_prefs.xml")
        if (!legacyFile.exists()) return

        val legacy = ctx.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val legacyAll = legacy.all
        if (legacyAll.isEmpty()) {
            legacyFile.delete()
            return
        }

        runBlocking {
            edit {
                legacyAll.forEach { (key, value) ->
                    when (value) {
                        is String -> set(stringPreferencesKey(key), value)
                        is Boolean -> set(booleanPreferencesKey(key), value)
                        is Long -> set(longPreferencesKey(key), value)
                        is Int -> set(intPreferencesKey(key), value)
                        is Float -> set(floatPreferencesKey(key), value)
                    }
                }
            }
        }

        legacy.edit().clear().apply()
        legacyFile.delete()

        // Also clean up the old backup_creds legacy file if it exists
        val backupCredsFile = File(ctx.applicationInfo.dataDir, "shared_prefs/backup_creds.xml")
        if (backupCredsFile.exists()) {
            ctx.getSharedPreferences("backup_creds", Context.MODE_PRIVATE).edit().clear().apply()
            backupCredsFile.delete()
        }
    }

    private object Keys {
        val B2_ENDPOINT = stringPreferencesKey("b2_endpoint")
        val B2_BUCKET = stringPreferencesKey("b2_bucket")
        val B2_KEY_ID = stringPreferencesKey("b2_key_id")
        val NACL_PK_HEX = stringPreferencesKey("nacl_pk_hex")
        val GITHUB_OWNER = stringPreferencesKey("github_owner")
        val GITHUB_REPO = stringPreferencesKey("github_repo")
        val SITE_URL = stringPreferencesKey("site_url")
        val TILE_URI = stringPreferencesKey("tile_source_uri")
        val TILE_LABEL = stringPreferencesKey("tile_source_label")
        val JOURNEY_START = stringPreferencesKey("journey_start_date")
        val JOURNEY_TITLE = stringPreferencesKey("journey_title")
        val JOURNEY_TAG = stringPreferencesKey("journey_tag")
        val BACKGROUND_GPS = booleanPreferencesKey("background_gps")
        val GPX_EXPORT_URI = stringPreferencesKey("gpx_export_folder_uri")
        val GPX_EXPORT_LABEL = stringPreferencesKey("gpx_export_folder_label")
        val POSTMEDIA_URI = stringPreferencesKey("postmedia_folder_uri")
        val POSTMEDIA_LABEL = stringPreferencesKey("postmedia_folder_label")
        val AUTOSAVE_DEBOUNCE_MS = longPreferencesKey("autosave_debounce_ms")
        val AUTOSAVE_MAX_INTERVAL_MS = longPreferencesKey("autosave_max_interval_ms")
    }
}
