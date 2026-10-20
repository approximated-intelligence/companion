package de.perigon.companion.core.prefs

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val TAG = "SecretStore"
private const val GCM_TAG_BITS = 128
private const val GCM_IV_BYTES = 12

/**
 * Device-bound secrets: AES-GCM with an AndroidKeyStore key, ciphertext in a
 * private SharedPreferences file. Each module owns one instance ([prefsName])
 * and wraps it in typed accessors; the Keystore key may be shared.
 *
 * Excluded from backup by name in data_extraction_rules — values cannot be
 * decrypted on another device anyway.
 */
class SecretStore(
    context: Context,
    prefsName: String,
    scope: CoroutineScope,
    private val keyAlias: String = "companion_cred_key",
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    @Volatile private var keyReady = false

    init {
        // Keystore work off the main thread; get/put stay correct if they win the race.
        scope.launch(Dispatchers.IO) { ensureKey() }
    }

    fun get(key: String): String? {
        val encoded = prefs.getString(key, null) ?: return null
        ensureKey()
        return try {
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE, secretKey(),
                GCMParameterSpec(GCM_TAG_BITS, combined.copyOfRange(0, GCM_IV_BYTES)),
            )
            String(cipher.doFinal(combined.copyOfRange(GCM_IV_BYTES, combined.size)), Charsets.UTF_8)
        } catch (e: Exception) {
            // Stored but undecryptable: Keystore key invalidated or data corrupt.
            // Callers see "not configured"; this is the only trace of the cause.
            Log.e(TAG, "decrypt failed for '$key' — re-enter this credential", e)
            null
        }
    }

    fun put(key: String, value: String?) {
        prefs.edit {
            if (value == null) remove(key) else putString(key, encrypt(value))
        }
    }

    fun clear() {
        prefs.edit { clear() }
    }

    private fun encrypt(plaintext: String): String {
        ensureKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val body = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + body, Base64.NO_WRAP)
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (ks.getEntry(keyAlias, null) as KeyStore.SecretKeyEntry).secretKey
    }

    private fun ensureKey() {
        if (keyReady) return
        synchronized(this) {
            if (keyReady) return
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!ks.containsAlias(keyAlias)) {
                val spec = KeyGenParameterSpec.Builder(
                    keyAlias,
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
            keyReady = true
        }
    }
}
