package de.perigon.companion.core.prefs

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KeyStoreCredentialStore @Inject constructor(
    @param:ApplicationContext private val ctx: Context,
) : CredentialStore {

    companion object {
        private const val KEYSTORE_ALIAS = "companion_cred_key"
        private const val PREFS_NAME = "encrypted_creds"
        private const val LEGACY_PREFS_NAME = "backup_creds"
        private const val GCM_TAG_LENGTH = 128
        private const val GCM_IV_LENGTH = 12

        private const val KEY_B2_RW_APP_KEY = "b2_rw_app_key"
        private const val KEY_GITHUB_TOKEN = "github_token"
        private const val KEY_PHONE_SECRET_KEY = "phone_secret_key"
        private const val KEY_S3_SECRET_KEY = "s3_secret_key"
        private const val KEY_HTTP_MEDIA_PASSWORD = "http_media_password"

        private val SECRET_KEYS = listOf(KEY_B2_RW_APP_KEY, KEY_GITHUB_TOKEN)
    }

    private val prefs: SharedPreferences =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        ensureKey()
        migrateLegacyIfNeeded()
    }

    override fun b2RwAppKey(): String? = decrypt(KEY_B2_RW_APP_KEY)
    override fun setB2AppKey(appKey: String) {
        prefs.edit().putEncrypted(KEY_B2_RW_APP_KEY, appKey).apply()
    }

    override fun githubToken(): String? = decrypt(KEY_GITHUB_TOKEN)
    override fun setGithubToken(token: String) {
        prefs.edit().putEncrypted(KEY_GITHUB_TOKEN, token).apply()
    }

    override fun phoneSecretKey(): String? = decrypt(KEY_PHONE_SECRET_KEY)
    override fun setPhoneSecretKey(hex: String) {
        prefs.edit().putEncrypted(KEY_PHONE_SECRET_KEY, hex).apply()
    }

    override fun s3SecretKey(): String? = decrypt(KEY_S3_SECRET_KEY)
    override fun setS3SecretKey(key: String) {
        prefs.edit().putEncrypted(KEY_S3_SECRET_KEY, key).apply()
    }

    override fun httpMediaPassword(): String? = decrypt(KEY_HTTP_MEDIA_PASSWORD)
    override fun setHttpMediaPassword(password: String) {
        prefs.edit().putEncrypted(KEY_HTTP_MEDIA_PASSWORD, password).apply()
    }
    override fun clearHttpMediaPassword() {
        prefs.edit().remove(KEY_HTTP_MEDIA_PASSWORD).apply()
    }

    override fun clearAll() {
        prefs.edit().clear().apply()
    }

    // ---- KeyStore ----

    private fun ensureKey() {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (ks.containsAlias(KEYSTORE_ALIAS)) return

        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()

        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply { init(spec) }
            .generateKey()
    }

    private fun getKey(): KeyStore.Entry {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return ks.getEntry(KEYSTORE_ALIAS, null)
    }

    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, (getKey() as KeyStore.SecretKeyEntry).secretKey)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + ciphertext.size)
        iv.copyInto(combined, 0)
        ciphertext.copyInto(combined, iv.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(key: String): String? {
        val encoded = prefs.getString(key, null) ?: return null
        return try {
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
            val ciphertext = combined.copyOfRange(GCM_IV_LENGTH, combined.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, (getKey() as KeyStore.SecretKeyEntry).secretKey, spec)
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun SharedPreferences.Editor.putEncrypted(
        key: String,
        value: String?,
    ): SharedPreferences.Editor {
        if (value == null) remove(key) else putString(key, encrypt(value))
        return this
    }

    // ---- Migration ----

    private fun migrateLegacyIfNeeded() {
        val legacyFile = java.io.File(
            ctx.applicationInfo.dataDir, "shared_prefs/$LEGACY_PREFS_NAME.xml"
        )
        if (!legacyFile.exists()) return

        val legacy = ctx.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()

        SECRET_KEYS.forEach { key ->
            val value = legacy.getString(key, null)
            if (value != null && prefs.getString(key, null) == null) {
                editor.putEncrypted(key, value)
            }
        }

        editor.apply()
    }
}

// --- CredentialStore interface ---

interface CredentialStore {
    fun b2RwAppKey(): String?
    fun setB2AppKey(appKey: String)

    fun githubToken(): String?
    fun setGithubToken(token: String)

    fun phoneSecretKey(): String?
    fun setPhoneSecretKey(hex: String)

    fun s3SecretKey(): String?
    fun setS3SecretKey(key: String)

    fun httpMediaPassword(): String?
    fun setHttpMediaPassword(password: String)
    fun clearHttpMediaPassword()

    fun clearAll()
}
