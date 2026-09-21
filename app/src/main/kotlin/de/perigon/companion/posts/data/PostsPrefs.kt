package de.perigon.companion.posts.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import de.perigon.companion.core.di.ApplicationScope
import de.perigon.companion.core.prefs.SecretStore
import de.perigon.companion.core.prefs.SnapshotStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.postsPrefsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "posts_prefs",
)

data class S3MediaConfig(val endpoint: String, val staticUrl: String, val bucket: String, val keyId: String, val region: String)

data class HttpMediaConfig(val endpoint: String, val staticUrl: String, val user: String, val authType: String)

/**
 * Non-secret posts configuration. Method names match the former AppPrefs,
 * so call sites only changed type.
 */
@Singleton
class PostsPrefs @Inject constructor(
    @ApplicationContext ctx: Context,
    @ApplicationScope scope: CoroutineScope,
) {
    private val store = SnapshotStore(ctx.postsPrefsDataStore, scope)

    private fun str(key: Preferences.Key<String>): String? = store.snapshot()[key]
    private fun long(key: Preferences.Key<Long>, default: Long): Long = store.snapshot()[key] ?: default
    private fun bool(key: Preferences.Key<Boolean>, default: Boolean): Boolean = store.snapshot()[key] ?: default

    // ---- GitHub / site ----
    fun githubOwner(): String? = str(Keys.GITHUB_OWNER)
    fun githubRepo(): String? = str(Keys.GITHUB_REPO)
    suspend fun setGithubRepo(owner: String, repo: String) = store.edit {
        set(Keys.GITHUB_OWNER, owner)
        set(Keys.GITHUB_REPO, repo)
    }

    fun siteUrl(): String? = str(Keys.SITE_URL)
    suspend fun setSiteUrl(url: String) = store.edit { set(Keys.SITE_URL, url) }

    // ---- S3 media ----
    fun isS3MediaEnabled(): Boolean = bool(Keys.S3_ENABLED, false)

    suspend fun setS3MediaEnabled(enabled: Boolean) = store.edit {
        set(Keys.S3_ENABLED, enabled)
        if (enabled) set(Keys.HTTP_ENABLED, false)
    }

    suspend fun setS3Config(endpoint: String, staticUrl: String, bucket: String, keyId: String, region: String) = store.edit {
        set(Keys.S3_ENDPOINT, endpoint)
        set(Keys.S3_STATIC_URL, staticUrl)
        set(Keys.S3_BUCKET, bucket)
        set(Keys.S3_KEY_ID, keyId)
        set(Keys.S3_REGION, region)
        set(Keys.S3_ENABLED, true)
        set(Keys.HTTP_ENABLED, false)
    }

    /** Configured regardless of the enabled flag. */
    fun s3MediaConfigRaw(): S3MediaConfig? {
        val endpoint  = str(Keys.S3_ENDPOINT)   ?: return null
        val staticUrl = str(Keys.S3_STATIC_URL) ?: return null
        val bucket    = str(Keys.S3_BUCKET)     ?: return null
        val keyId     = str(Keys.S3_KEY_ID)     ?: return null
        val region    = str(Keys.S3_REGION)     ?: return null
        return S3MediaConfig(endpoint, staticUrl, bucket, keyId, region)
    }

    fun s3MediaConfig(): S3MediaConfig? = if (isS3MediaEnabled()) s3MediaConfigRaw() else null

    // ---- HTTP media ----
    fun httpMediaAuthType(): String = str(Keys.HTTP_AUTH_TYPE) ?: "digest"
    fun isHttpMediaEnabled(): Boolean = bool(Keys.HTTP_ENABLED, false)

    suspend fun setHttpMediaEnabled(enabled: Boolean) = store.edit {
        set(Keys.HTTP_ENABLED, enabled)
        if (enabled) set(Keys.S3_ENABLED, false)
    }

    suspend fun setHttpMediaConfig(endpoint: String, staticUrl: String, user: String) = store.edit {
        set(Keys.HTTP_ENDPOINT, endpoint)
        set(Keys.HTTP_STATIC_URL, staticUrl)
        set(Keys.HTTP_USER, user)
        set(Keys.HTTP_ENABLED, true)
        set(Keys.S3_ENABLED, false)
    }

    suspend fun setHttpMediaAuthType(type: String) = store.edit { set(Keys.HTTP_AUTH_TYPE, type) }

    fun httpMediaConfigRaw(): HttpMediaConfig? {
        val endpoint  = str(Keys.HTTP_ENDPOINT)   ?: return null
        val staticUrl = str(Keys.HTTP_STATIC_URL) ?: return null
        val user      = str(Keys.HTTP_USER)       ?: return null
        return HttpMediaConfig(endpoint, staticUrl, user, httpMediaAuthType())
    }

    fun httpMediaConfig(): HttpMediaConfig? = if (isHttpMediaEnabled()) httpMediaConfigRaw() else null

    // ---- Journey ----
    fun journeyStartDate(): String? = str(Keys.JOURNEY_START)
    fun journeyTitle(): String? = str(Keys.JOURNEY_TITLE)
    fun journeyTag(): String? = str(Keys.JOURNEY_TAG)
    suspend fun setJourneyStartDate(date: String) = store.edit { set(Keys.JOURNEY_START, date) }
    suspend fun setJourneyTitle(title: String) = store.edit { set(Keys.JOURNEY_TITLE, title) }
    suspend fun setJourneyTag(tag: String) = store.edit { set(Keys.JOURNEY_TAG, tag) }

    // ---- Editor ----
    fun autosaveDebounceMs(): Long = long(Keys.AUTOSAVE_DEBOUNCE_MS, DEFAULT_AUTOSAVE_DEBOUNCE_MS)
    fun autosaveMaxIntervalMs(): Long = long(Keys.AUTOSAVE_MAX_INTERVAL_MS, DEFAULT_AUTOSAVE_MAX_INTERVAL_MS)
    suspend fun setAutosaveDebounceMs(ms: Long) = store.edit { set(Keys.AUTOSAVE_DEBOUNCE_MS, ms) }
    suspend fun setAutosaveMaxIntervalMs(ms: Long) = store.edit { set(Keys.AUTOSAVE_MAX_INTERVAL_MS, ms) }

    fun mediaPickerMode(): String = str(Keys.MEDIA_PICKER_MODE) ?: MEDIA_PICKER_SYSTEM
    suspend fun setMediaPickerMode(mode: String) = store.edit { set(Keys.MEDIA_PICKER_MODE, mode) }

    /** Ticks on every change; settings UI re-reads on it. */
    fun changes(): Flow<Unit> = store.data.map { }

    suspend fun clear() = store.edit { this.clear() }

    companion object {
        const val DEFAULT_AUTOSAVE_DEBOUNCE_MS = 3_000L
        val AUTOSAVE_OPTIONS_MS = listOf(1_000L, 2_000L, 3_000L, 5_000L, 10_000L)
        const val DEFAULT_AUTOSAVE_MAX_INTERVAL_MS = 60_000L
        val AUTOSAVE_MAX_INTERVAL_OPTIONS_MS = listOf(15_000L, 30_000L, 60_000L, 120_000L)

        const val MEDIA_PICKER_SYSTEM  = "system"
        const val MEDIA_PICKER_BUILTIN = "builtin"
    }

    private object Keys {
        val GITHUB_OWNER             = stringPreferencesKey("github_owner")
        val GITHUB_REPO              = stringPreferencesKey("github_repo")
        val SITE_URL                 = stringPreferencesKey("site_url")
        val S3_ENDPOINT              = stringPreferencesKey("s3_endpoint")
        val S3_STATIC_URL            = stringPreferencesKey("s3_static_url")
        val S3_BUCKET                = stringPreferencesKey("s3_bucket")
        val S3_KEY_ID                = stringPreferencesKey("s3_key_id")
        val S3_REGION                = stringPreferencesKey("s3_region")
        val S3_ENABLED               = booleanPreferencesKey("s3_media_enabled")
        val HTTP_ENDPOINT            = stringPreferencesKey("http_media_endpoint")
        val HTTP_STATIC_URL          = stringPreferencesKey("http_media_static_url")
        val HTTP_USER                = stringPreferencesKey("http_media_user")
        val HTTP_ENABLED             = booleanPreferencesKey("http_media_enabled")
        val HTTP_AUTH_TYPE           = stringPreferencesKey("http_media_auth_type")
        val JOURNEY_START            = stringPreferencesKey("journey_start_date")
        val JOURNEY_TITLE            = stringPreferencesKey("journey_title")
        val JOURNEY_TAG              = stringPreferencesKey("journey_tag")
        val AUTOSAVE_DEBOUNCE_MS     = longPreferencesKey("autosave_debounce_ms")
        val AUTOSAVE_MAX_INTERVAL_MS = longPreferencesKey("autosave_max_interval_ms")
        val MEDIA_PICKER_MODE        = stringPreferencesKey("media_picker_mode")
    }
}

/** Device-bound posts secrets. */
@Singleton
class PostsSecrets @Inject constructor(
    @ApplicationContext ctx: Context,
    @ApplicationScope scope: CoroutineScope,
) {
    private val store = SecretStore(ctx, "posts_secrets", scope)

    fun githubToken(): String? = store.get(GITHUB_TOKEN)
    fun setGithubToken(token: String) = store.put(GITHUB_TOKEN, token)

    fun s3SecretKey(): String? = store.get(S3_SECRET)
    fun setS3SecretKey(key: String) = store.put(S3_SECRET, key)

    fun httpMediaPassword(): String? = store.get(HTTP_PASSWORD)
    fun setHttpMediaPassword(password: String) = store.put(HTTP_PASSWORD, password)

    fun clear() = store.clear()

    private companion object {
        const val GITHUB_TOKEN = "github_token"
        const val S3_SECRET = "s3_secret_key"
        const val HTTP_PASSWORD = "http_media_password"
    }
}
