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
    fun b2Region(): String?
    suspend fun setB2Config(endpoint: String?, keyId: String, bucket: String?, region: String?)

    // ---- NaCl public key ----
    fun naclPkHex(): String?
    suspend fun setNaclPkHex(hex: String)

    // ---- Phone NaCl keypair ----
    fun phonePkHex(): String?
    suspend fun setPhonePkHex(hex: String)

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
    fun tileSourceNetworkUrl(): String?
    fun tileSourceMode(): String
    suspend fun setTileSource(uri: String, label: String)
    suspend fun setTileSourceNetwork(url: String)
    suspend fun setTileSourceMode(mode: String)
    suspend fun clearTileSourceLocal()
    suspend fun clearTileSourceNetwork()
    suspend fun clearTileSource()

    // ---- DCIM SAF grant ----
    fun dcimTreeUri(): String?
    fun dcimTreeLabel(): String?
    suspend fun setDcimTree(uri: String, label: String)
    suspend fun clearDcimTree()

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

    // ---- Autosave ----
    fun autosaveDebounceMs(): Long
    suspend fun setAutosaveDebounceMs(ms: Long)
    fun autosaveMaxIntervalMs(): Long
    suspend fun setAutosaveMaxIntervalMs(ms: Long)

    // ---- S3 media ----
    fun s3Endpoint(): String?
    fun s3StaticUrl(): String?
    fun s3Bucket(): String?
    fun s3KeyId(): String?
    fun s3Region(): String?
    fun isS3MediaEnabled(): Boolean
    suspend fun setS3MediaEnabled(enabled: Boolean)
    suspend fun setS3Config(endpoint: String, staticUrl: String, bucket: String, keyId: String, region: String)
    suspend fun clearS3Config()

    // ---- HTTP media ----
    fun httpMediaEndpoint(): String?
    fun httpMediaStaticUrl(): String?
    fun httpMediaUser(): String?
    fun httpMediaAuthType(): String
    fun isHttpMediaEnabled(): Boolean
    suspend fun setHttpMediaEnabled(enabled: Boolean)
    suspend fun setHttpMediaConfig(endpoint: String, staticUrl: String, user: String)
    suspend fun setHttpMediaEndpoint(endpoint: String)
    suspend fun setHttpMediaStaticUrl(staticUrl: String)
    suspend fun setHttpMediaUser(user: String)
    suspend fun setHttpMediaAuthType(type: String)
    suspend fun clearHttpMediaConfig()

    // ---- Media picker mode ----
    fun mediaPickerMode(): String
    suspend fun setMediaPickerMode(mode: String)

    // ---- Observe ----
    fun observeAll(): Flow<Preferences>
    fun observeBackgroundGps(): Flow<Boolean> = observeAll().map { it[booleanPreferencesKey("background_gps")] ?: false }
    fun observeDcimTreeUri(): Flow<String?> = observeAll().map { it[stringPreferencesKey("dcim_tree_uri")] }

    // ---- Bulk ----
    suspend fun clearAll()

    // ---- Convenience ----
    data class B2Config(val endpoint: String, val bucket: String, val keyId: String, val region: String)

    fun b2Config(): B2Config? {
        val endpoint = b2Endpoint() ?: return null
        val bucket   = b2Bucket()   ?: return null
        val keyId    = b2KeyId()    ?: return null
        val region   = b2Region()   ?: return null
        return B2Config(endpoint, bucket, keyId, region)
    }

    data class S3MediaConfig(val endpoint: String, val staticUrl: String, val bucket: String, val keyId: String, val region: String)

    fun s3MediaConfig(): S3MediaConfig? {
        if (!isS3MediaEnabled()) return null
        val endpoint  = s3Endpoint()  ?: return null
        val staticUrl = s3StaticUrl() ?: return null
        val bucket    = s3Bucket()    ?: return null
        val keyId     = s3KeyId()     ?: return null
        val region    = s3Region()    ?: return null
        return S3MediaConfig(endpoint, staticUrl, bucket, keyId, region)
    }

    fun s3MediaConfigRaw(): S3MediaConfig? {
        val endpoint  = s3Endpoint()  ?: return null
        val staticUrl = s3StaticUrl() ?: return null
        val bucket    = s3Bucket()    ?: return null
        val keyId     = s3KeyId()     ?: return null
        val region    = s3Region()    ?: return null
        return S3MediaConfig(endpoint, staticUrl, bucket, keyId, region)
    }

    data class HttpMediaConfig(val endpoint: String, val staticUrl: String, val user: String, val authType: String)

    fun httpMediaConfig(): HttpMediaConfig? {
        if (!isHttpMediaEnabled()) return null
        val endpoint  = httpMediaEndpoint()  ?: return null
        val staticUrl = httpMediaStaticUrl() ?: return null
        val user      = httpMediaUser()      ?: return null
        return HttpMediaConfig(endpoint, staticUrl, user, httpMediaAuthType())
    }

    fun httpMediaConfigRaw(): HttpMediaConfig? {
        val endpoint  = httpMediaEndpoint()  ?: return null
        val staticUrl = httpMediaStaticUrl() ?: return null
        val user      = httpMediaUser()      ?: return null
        return HttpMediaConfig(endpoint, staticUrl, user, httpMediaAuthType())
    }

    data class GitHubRepo(val owner: String, val repo: String)

    fun githubRepo2(): GitHubRepo? {
        val owner = githubOwner() ?: return null
        val repo  = githubRepo()  ?: return null
        return GitHubRepo(owner, repo)
    }

    companion object {
        const val TILE_MODE_LOCAL   = "local"
        const val TILE_MODE_NETWORK = "network"

        const val DEFAULT_AUTOSAVE_DEBOUNCE_MS = 3_000L
        val AUTOSAVE_OPTIONS_MS = listOf(1_000L, 2_000L, 3_000L, 5_000L, 10_000L)
        const val DEFAULT_AUTOSAVE_MAX_INTERVAL_MS = 60_000L
        val AUTOSAVE_MAX_INTERVAL_OPTIONS_MS = listOf(15_000L, 30_000L, 60_000L, 120_000L)

        const val MEDIA_PICKER_SYSTEM  = "system"
        const val MEDIA_PICKER_BUILTIN = "builtin"
    }
}

@Singleton
class AppPrefsImpl @Inject constructor(
    @param:ApplicationContext private val ctx: Context,
) : AppPrefs {

    private val ds: DataStore<Preferences> = ctx.appDataStore

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
    override fun b2Bucket(): String?   = getString(Keys.B2_BUCKET)
    override fun b2KeyId(): String?    = getString(Keys.B2_RW_KEY_ID)
    override fun b2Region(): String?   = getString(Keys.B2_REGION)

    override suspend fun setB2Config(endpoint: String?, keyId: String, bucket: String?, region: String?) {
        edit {
            if (endpoint != null) set(Keys.B2_ENDPOINT, endpoint) else remove(Keys.B2_ENDPOINT)
            set(Keys.B2_RW_KEY_ID, keyId)
            if (bucket != null) set(Keys.B2_BUCKET, bucket) else remove(Keys.B2_BUCKET)
            if (region != null) set(Keys.B2_REGION, region) else remove(Keys.B2_REGION)
        }
    }

    // ---- NaCl ----
    override fun naclPkHex(): String? = getString(Keys.NACL_PK_HEX)
    override suspend fun setNaclPkHex(hex: String) { edit { set(Keys.NACL_PK_HEX, hex) } }

    // ---- Phone NaCl ----
    override fun phonePkHex(): String? = getString(Keys.PHONE_PK_HEX)
    override suspend fun setPhonePkHex(hex: String) { edit { set(Keys.PHONE_PK_HEX, hex) } }

    // ---- GitHub repo identity ----
    override fun githubOwner(): String? = getString(Keys.GITHUB_OWNER)
    override fun githubRepo(): String?  = getString(Keys.GITHUB_REPO)

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
    override fun tileSourceUri(): String?        = getString(Keys.TILE_URI)
    override fun tileSourceLabel(): String?      = getString(Keys.TILE_LABEL)
    override fun tileSourceNetworkUrl(): String? = getString(Keys.TILE_NETWORK_URL)
    override fun tileSourceMode(): String        = getString(Keys.TILE_MODE) ?: AppPrefs.TILE_MODE_LOCAL

    override suspend fun setTileSource(uri: String, label: String) {
        edit {
            set(Keys.TILE_URI, uri)
            set(Keys.TILE_LABEL, label)
            set(Keys.TILE_MODE, AppPrefs.TILE_MODE_LOCAL)
        }
    }

    override suspend fun setTileSourceNetwork(url: String) {
        edit {
            set(Keys.TILE_NETWORK_URL, url)
            set(Keys.TILE_MODE, AppPrefs.TILE_MODE_NETWORK)
        }
    }

    override suspend fun setTileSourceMode(mode: String) {
        edit { set(Keys.TILE_MODE, mode) }
    }

    override suspend fun clearTileSourceLocal() {
        edit {
            remove(Keys.TILE_URI)
            remove(Keys.TILE_LABEL)
            if (get(Keys.TILE_MODE) == AppPrefs.TILE_MODE_LOCAL) {
                if (get(Keys.TILE_NETWORK_URL) != null) set(Keys.TILE_MODE, AppPrefs.TILE_MODE_NETWORK)
                else remove(Keys.TILE_MODE)
            }
        }
    }

    override suspend fun clearTileSourceNetwork() {
        edit {
            remove(Keys.TILE_NETWORK_URL)
            if (get(Keys.TILE_MODE) == AppPrefs.TILE_MODE_NETWORK) {
                if (get(Keys.TILE_URI) != null) set(Keys.TILE_MODE, AppPrefs.TILE_MODE_LOCAL)
                else remove(Keys.TILE_MODE)
            }
        }
    }

    override suspend fun clearTileSource() {
        edit {
            remove(Keys.TILE_URI)
            remove(Keys.TILE_LABEL)
            remove(Keys.TILE_NETWORK_URL)
            remove(Keys.TILE_MODE)
        }
    }

    // ---- DCIM SAF grant ----
    override fun dcimTreeUri(): String?   = getString(Keys.DCIM_TREE_URI)
    override fun dcimTreeLabel(): String? = getString(Keys.DCIM_TREE_LABEL)

    override suspend fun setDcimTree(uri: String, label: String) {
        edit {
            set(Keys.DCIM_TREE_URI, uri)
            set(Keys.DCIM_TREE_LABEL, label)
        }
    }

    override suspend fun clearDcimTree() {
        edit {
            remove(Keys.DCIM_TREE_URI)
            remove(Keys.DCIM_TREE_LABEL)
        }
    }

    // ---- Journey ----
    override fun journeyStartDate(): String? = getString(Keys.JOURNEY_START)
    override fun journeyTitle(): String?     = getString(Keys.JOURNEY_TITLE)
    override fun journeyTag(): String?       = getString(Keys.JOURNEY_TAG)

    override suspend fun setJourneyStartDate(date: String)  { edit { set(Keys.JOURNEY_START, date) } }
    override suspend fun setJourneyTitle(title: String)     { edit { set(Keys.JOURNEY_TITLE, title) } }
    override suspend fun setJourneyTag(tag: String)         { edit { set(Keys.JOURNEY_TAG, tag) } }

    // ---- Recording ----
    override fun isBackgroundGpsEnabled(): Boolean = getBoolean(Keys.BACKGROUND_GPS, false)
    override suspend fun setBackgroundGpsEnabled(enabled: Boolean) { edit { set(Keys.BACKGROUND_GPS, enabled) } }

    // ---- GPX export folder ----
    override fun gpxExportFolderUri(): String?   = getString(Keys.GPX_EXPORT_URI)
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

    // ---- Autosave ----
    override fun autosaveDebounceMs(): Long = getLong(Keys.AUTOSAVE_DEBOUNCE_MS, AppPrefs.DEFAULT_AUTOSAVE_DEBOUNCE_MS)
    override suspend fun setAutosaveDebounceMs(ms: Long) { edit { set(Keys.AUTOSAVE_DEBOUNCE_MS, ms) } }

    override fun autosaveMaxIntervalMs(): Long = getLong(Keys.AUTOSAVE_MAX_INTERVAL_MS, AppPrefs.DEFAULT_AUTOSAVE_MAX_INTERVAL_MS)
    override suspend fun setAutosaveMaxIntervalMs(ms: Long) { edit { set(Keys.AUTOSAVE_MAX_INTERVAL_MS, ms) } }

    // ---- S3 media ----
    override fun s3Endpoint(): String?  = getString(Keys.S3_ENDPOINT)
    override fun s3StaticUrl(): String? = getString(Keys.S3_STATIC_URL)
    override fun s3Bucket(): String?    = getString(Keys.S3_BUCKET)
    override fun s3KeyId(): String?     = getString(Keys.S3_KEY_ID)
    override fun s3Region(): String?    = getString(Keys.S3_REGION)
    override fun isS3MediaEnabled(): Boolean = getBoolean(Keys.S3_MEDIA_ENABLED, false)

    override suspend fun setS3MediaEnabled(enabled: Boolean) {
        edit {
            set(Keys.S3_MEDIA_ENABLED, enabled)
            if (enabled) set(Keys.HTTP_MEDIA_ENABLED, false)
        }
    }

    override suspend fun setS3Config(endpoint: String, staticUrl: String, bucket: String, keyId: String, region: String) {
        edit {
            set(Keys.S3_ENDPOINT,    endpoint)
            set(Keys.S3_STATIC_URL,  staticUrl)
            set(Keys.S3_BUCKET,      bucket)
            set(Keys.S3_KEY_ID,      keyId)
            set(Keys.S3_REGION,      region)
            set(Keys.S3_MEDIA_ENABLED, true)
            set(Keys.HTTP_MEDIA_ENABLED, false)
        }
    }

    override suspend fun clearS3Config() {
        edit {
            remove(Keys.S3_ENDPOINT)
            remove(Keys.S3_STATIC_URL)
            remove(Keys.S3_BUCKET)
            remove(Keys.S3_KEY_ID)
            remove(Keys.S3_REGION)
            remove(Keys.S3_MEDIA_ENABLED)
        }
    }

    // ---- HTTP media ----
    override fun httpMediaEndpoint(): String?  = getString(Keys.HTTP_MEDIA_ENDPOINT)
    override fun httpMediaStaticUrl(): String? = getString(Keys.HTTP_MEDIA_STATIC_URL)
    override fun httpMediaUser(): String?      = getString(Keys.HTTP_MEDIA_USER)
    override fun httpMediaAuthType(): String   = getString(Keys.HTTP_MEDIA_AUTH_TYPE) ?: "digest"
    override fun isHttpMediaEnabled(): Boolean = getBoolean(Keys.HTTP_MEDIA_ENABLED, false)

    override suspend fun setHttpMediaEnabled(enabled: Boolean) {
        edit {
            set(Keys.HTTP_MEDIA_ENABLED, enabled)
            if (enabled) set(Keys.S3_MEDIA_ENABLED, false)
        }
    }

    override suspend fun setHttpMediaConfig(endpoint: String, staticUrl: String, user: String) {
        edit {
            set(Keys.HTTP_MEDIA_ENDPOINT,   endpoint)
            set(Keys.HTTP_MEDIA_STATIC_URL, staticUrl)
            set(Keys.HTTP_MEDIA_USER,       user)
            set(Keys.HTTP_MEDIA_ENABLED,    true)
            set(Keys.S3_MEDIA_ENABLED,      false)
        }
    }

    override suspend fun setHttpMediaEndpoint(endpoint: String) { edit { set(Keys.HTTP_MEDIA_ENDPOINT, endpoint) } }
    override suspend fun setHttpMediaStaticUrl(staticUrl: String) { edit { set(Keys.HTTP_MEDIA_STATIC_URL, staticUrl) } }
    override suspend fun setHttpMediaUser(user: String) { edit { set(Keys.HTTP_MEDIA_USER, user) } }
    override suspend fun setHttpMediaAuthType(type: String) { edit { set(Keys.HTTP_MEDIA_AUTH_TYPE, type) } }

    override suspend fun clearHttpMediaConfig() {
        edit {
            remove(Keys.HTTP_MEDIA_ENDPOINT)
            remove(Keys.HTTP_MEDIA_STATIC_URL)
            remove(Keys.HTTP_MEDIA_USER)
            remove(Keys.HTTP_MEDIA_ENABLED)
            remove(Keys.HTTP_MEDIA_AUTH_TYPE)
        }
    }

    // ---- Media picker mode ----
    override fun mediaPickerMode(): String = getString(Keys.MEDIA_PICKER_MODE) ?: AppPrefs.MEDIA_PICKER_SYSTEM
    override suspend fun setMediaPickerMode(mode: String) { edit { set(Keys.MEDIA_PICKER_MODE, mode) } }

    // ---- Observe ----
    override fun observeAll(): Flow<Preferences> = ds.data

    // ---- Bulk ----
    override suspend fun clearAll() { edit { clear() } }

    // ---- Legacy migration ----

    private fun migrateLegacyIfNeeded() {
        val legacyFile = File(ctx.applicationInfo.dataDir, "shared_prefs/app_prefs.xml")
        if (!legacyFile.exists()) return

        val legacy    = ctx.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val legacyAll = legacy.all
        if (legacyAll.isEmpty()) {
            legacyFile.delete()
            return
        }

        runBlocking {
            edit {
                legacyAll.forEach { (key, value) ->
                    when (value) {
                        is String  -> set(stringPreferencesKey(key), value)
                        is Boolean -> set(booleanPreferencesKey(key), value)
                        is Long    -> set(longPreferencesKey(key), value)
                        is Int     -> set(intPreferencesKey(key), value)
                        is Float   -> set(floatPreferencesKey(key), value)
                    }
                }
            }
        }

        legacy.edit().clear().apply()
        legacyFile.delete()

        val backupCredsFile = File(ctx.applicationInfo.dataDir, "shared_prefs/backup_creds.xml")
        if (backupCredsFile.exists()) {
            ctx.getSharedPreferences("backup_creds", Context.MODE_PRIVATE).edit().clear().apply()
            backupCredsFile.delete()
        }
    }

    private object Keys {
        val B2_ENDPOINT           = stringPreferencesKey("b2_endpoint")
        val B2_BUCKET             = stringPreferencesKey("b2_bucket")
        val B2_RW_KEY_ID          = stringPreferencesKey("b2_rw_key_id")
        val B2_REGION             = stringPreferencesKey("b2_region")
        val NACL_PK_HEX           = stringPreferencesKey("nacl_pk_hex")
        val PHONE_PK_HEX          = stringPreferencesKey("phone_nacl_pk_hex")
        val GITHUB_OWNER          = stringPreferencesKey("github_owner")
        val GITHUB_REPO           = stringPreferencesKey("github_repo")
        val SITE_URL              = stringPreferencesKey("site_url")
        val TILE_URI              = stringPreferencesKey("tile_source_uri")
        val TILE_LABEL            = stringPreferencesKey("tile_source_label")
        val TILE_NETWORK_URL      = stringPreferencesKey("tile_source_network_url")
        val TILE_MODE             = stringPreferencesKey("tile_source_mode")
        val DCIM_TREE_URI         = stringPreferencesKey("dcim_tree_uri")
        val DCIM_TREE_LABEL       = stringPreferencesKey("dcim_tree_label")
        val JOURNEY_START         = stringPreferencesKey("journey_start_date")
        val JOURNEY_TITLE         = stringPreferencesKey("journey_title")
        val JOURNEY_TAG           = stringPreferencesKey("journey_tag")
        val BACKGROUND_GPS        = booleanPreferencesKey("background_gps")
        val GPX_EXPORT_URI        = stringPreferencesKey("gpx_export_folder_uri")
        val GPX_EXPORT_LABEL      = stringPreferencesKey("gpx_export_folder_label")
        val AUTOSAVE_DEBOUNCE_MS  = longPreferencesKey("autosave_debounce_ms")
        val AUTOSAVE_MAX_INTERVAL_MS = longPreferencesKey("autosave_max_interval_ms")
        val S3_ENDPOINT           = stringPreferencesKey("s3_endpoint")
        val S3_STATIC_URL         = stringPreferencesKey("s3_static_url")
        val S3_BUCKET             = stringPreferencesKey("s3_bucket")
        val S3_KEY_ID             = stringPreferencesKey("s3_key_id")
        val S3_REGION             = stringPreferencesKey("s3_region")
        val S3_MEDIA_ENABLED      = booleanPreferencesKey("s3_media_enabled")
        val HTTP_MEDIA_ENDPOINT   = stringPreferencesKey("http_media_endpoint")
        val HTTP_MEDIA_STATIC_URL = stringPreferencesKey("http_media_static_url")
        val HTTP_MEDIA_USER       = stringPreferencesKey("http_media_user")
        val HTTP_MEDIA_ENABLED    = booleanPreferencesKey("http_media_enabled")
        val HTTP_MEDIA_AUTH_TYPE  = stringPreferencesKey("http_media_auth_type")
        val MEDIA_PICKER_MODE     = stringPreferencesKey("media_picker_mode")
    }
}
