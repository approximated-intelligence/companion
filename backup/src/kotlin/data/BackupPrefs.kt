package de.perigon.companion.backup.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
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

private val Context.backupPrefsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "backup_prefs",
)

data class B2Config(val endpoint: String, val bucket: String, val keyId: String, val region: String)

/** Non-secret backup configuration: B2 addressing and the NaCl public keys. */
@Singleton
class BackupPrefs @Inject constructor(
    @ApplicationContext ctx: Context,
    @ApplicationScope scope: CoroutineScope,
) {
    private val store = SnapshotStore(ctx.backupPrefsDataStore, scope)

    private fun get(key: Preferences.Key<String>): String? = store.snapshot()[key]

    fun b2Endpoint(): String? = get(Keys.B2_ENDPOINT)

    fun b2Config(): B2Config? {
        val endpoint = get(Keys.B2_ENDPOINT) ?: return null
        val bucket   = get(Keys.B2_BUCKET)   ?: return null
        val keyId    = get(Keys.B2_KEY_ID)   ?: return null
        val region   = get(Keys.B2_REGION)   ?: return null
        return B2Config(endpoint, bucket, keyId, region)
    }

    suspend fun setB2Config(endpoint: String?, keyId: String, bucket: String?, region: String?) = store.edit {
        if (endpoint != null) set(Keys.B2_ENDPOINT, endpoint) else remove(Keys.B2_ENDPOINT)
        set(Keys.B2_KEY_ID, keyId)
        if (bucket != null) set(Keys.B2_BUCKET, bucket) else remove(Keys.B2_BUCKET)
        if (region != null) set(Keys.B2_REGION, region) else remove(Keys.B2_REGION)
    }

    fun naclPkHex(): String? = get(Keys.NACL_PK_HEX)
    suspend fun setNaclPkHex(hex: String) = store.edit { set(Keys.NACL_PK_HEX, hex) }

    fun phonePkHex(): String? = get(Keys.PHONE_PK_HEX)
    suspend fun setPhonePkHex(hex: String) = store.edit { set(Keys.PHONE_PK_HEX, hex) }

    /** Ticks on every change; settings UI re-reads on it. */
    fun changes(): Flow<Unit> = store.data.map { }

    suspend fun clear() = store.edit { this.clear() }

    private object Keys {
        val B2_ENDPOINT  = stringPreferencesKey("b2_endpoint")
        val B2_BUCKET    = stringPreferencesKey("b2_bucket")
        val B2_KEY_ID    = stringPreferencesKey("b2_rw_key_id")
        val B2_REGION    = stringPreferencesKey("b2_region")
        val NACL_PK_HEX  = stringPreferencesKey("nacl_pk_hex")
        val PHONE_PK_HEX = stringPreferencesKey("phone_nacl_pk_hex")
    }
}

/** Device-bound backup secrets. */
@Singleton
class BackupSecrets @Inject constructor(
    @ApplicationContext ctx: Context,
    @ApplicationScope scope: CoroutineScope,
) {
    private val store = SecretStore(ctx, "backup_secrets", scope)

    fun b2RwAppKey(): String? = store.get(B2_APP_KEY)
    fun setB2AppKey(appKey: String) = store.put(B2_APP_KEY, appKey)

    fun phoneSecretKey(): String? = store.get(PHONE_SECRET_KEY)
    fun setPhoneSecretKey(hex: String) = store.put(PHONE_SECRET_KEY, hex)

    /**
     * Ticks on every put/clear. Settings UIs that read secrets must merge this
     * with their non-secret flow: a secret-only change is otherwise invisible.
     */
    fun changes(): Flow<Unit> = store.changes

    fun clear() = store.clear()

    private companion object {
        const val B2_APP_KEY = "b2_rw_app_key"
        const val PHONE_SECRET_KEY = "phone_secret_key"
    }
}
