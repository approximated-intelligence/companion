package de.perigon.companion.backup.data

import android.util.Log
import com.goterl.lazysodium.LazySodiumAndroid
import de.perigon.companion.core.network.S3BackendFactory
import de.perigon.companion.core.util.toHex
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "BackupKeys"

/** What offering a NaCl backup key would do. */
sealed interface KeyDecision {
    /** No key stored yet. */
    data object Accept : KeyDecision
    data object Unchanged : KeyDecision
    /** Different key, current bucket holds no packs: accept and reset pack state. */
    data object Change : KeyDecision
    data class Refuse(val reason: String) : KeyDecision
}

/**
 * Backup key lifecycle. A key CHANGE is only allowed when the current bucket
 * holds no packs — otherwise existing backups would become undecryptable.
 * Split into [decide] and [commit] so a bootstrap import can check every
 * part before applying any.
 */
@Singleton
class BackupKeys @Inject constructor(
    private val prefs: BackupPrefs,
    private val secrets: BackupSecrets,
    private val s3Factory: S3BackendFactory,
    private val db: BackupDatabase,
    private val sodium: LazySodiumAndroid,
) {
    suspend fun decide(hex: String): KeyDecision {
        val stored = prefs.naclPkHex() ?: return KeyDecision.Accept
        if (stored == hex) return KeyDecision.Unchanged

        val b2 = prefs.b2Config()
        val appKey = secrets.b2RwAppKey()
        if (b2 == null || appKey == null) {
            return KeyDecision.Refuse("B2 not configured, cannot verify bucket is empty")
        }
        return try {
            val backend = s3Factory.create(b2.endpoint, b2.bucket, b2.keyId, appKey, b2.region)
            val prefix = prefs.phonePkHex() ?: "packs"
            if (backend.listObjects(prefix = "$prefix/", maxKeys = 1).isNotEmpty()) {
                KeyDecision.Refuse("bucket has packs. Run backup_reset.py first.")
            } else {
                KeyDecision.Change
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "bucket check failed during key change", e)
            KeyDecision.Refuse("could not check bucket: ${e.message}")
        }
    }

    suspend fun commit(hex: String, decision: KeyDecision) {
        when (decision) {
            KeyDecision.Accept -> prefs.setNaclPkHex(hex)
            KeyDecision.Unchanged -> Unit
            KeyDecision.Change -> {
                prefs.setNaclPkHex(hex)
                db.resetBackupState()
            }
            is KeyDecision.Refuse -> error("Key change refused - ${decision.reason}")
        }
        ensurePhoneKeypair()
    }

    /** The device's own keypair; its public key names this phone's packs. */
    suspend fun ensurePhoneKeypair() {
        if (secrets.phoneSecretKey() != null && prefs.phonePkHex() != null) return
        val pk = ByteArray(32)
        val sk = ByteArray(32)
        sodium.cryptoBoxKeypair(pk, sk)
        secrets.setPhoneSecretKey(sk.toHex())
        prefs.setPhonePkHex(pk.toHex())
    }
}

/** Pure: short display form of a hex key. */
fun String.fingerprint(): String =
    if (length >= 16) "${take(8)}…${takeLast(8)}" else this
