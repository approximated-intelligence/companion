// app/src/main/kotlin/de/perigon/companion/backup/domain/BackupPartEncryptor.kt

package de.perigon.companion.backup.domain

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.interfaces.Box
import java.security.SecureRandom

/**
 * Encrypts/decrypts backup parts using crypto_box (authenticated).
 *
 * Wire format per part: [24-byte nonce][ciphertext]
 * Ciphertext = crypto_box(plaintext, nonce, recipient_pk, sender_sk)
 *
 * The sender's public key is not in the wire format — it's the
 * bucket prefix (directory name) and supplied out-of-band at decrypt time.
 *
 * Plaintext is PAX data + random tail padding to fixed size, so all
 * parts are identical wire size regardless of content.
 */
object BackupPartEncryptor {

    const val NONCE_BYTES = 24
    val MAC_BYTES = Box.MACBYTES  // 16
    val OVERHEAD = NONCE_BYTES + MAC_BYTES  // 40

    private val secureRandom = SecureRandom()

    /**
     * Encrypt a part. Returns [nonce || ciphertext] of size plaintext.size + OVERHEAD.
     *
     * @param plaintext  fixed-size plaintext (PAX data + random tail pad)
     * @param recipientPk  server/restore public key (32 bytes)
     * @param senderSk  phone's secret key (32 bytes)
     */
    fun seal(
        plaintext: ByteArray,
        recipientPk: ByteArray,
        senderSk: ByteArray,
        sodium: LazySodiumAndroid,
    ): ByteArray {
        val nonce = ByteArray(NONCE_BYTES)
        secureRandom.nextBytes(nonce)

        val ciphertext = ByteArray(plaintext.size + MAC_BYTES)
        val ok = sodium.cryptoBoxEasy(
            ciphertext,
            plaintext,
            plaintext.size.toLong(),
            nonce,
            recipientPk,
            senderSk,
        )
        check(ok) { "crypto_box_easy failed" }

        val wire = ByteArray(NONCE_BYTES + ciphertext.size)
        nonce.copyInto(wire, 0)
        ciphertext.copyInto(wire, NONCE_BYTES)
        return wire
    }

    /**
     * Decrypt a part. Returns plaintext or null on failure.
     *
     * @param wire  [nonce || ciphertext] as produced by seal()
     * @param senderPk  phone's public key (32 bytes) — from bucket prefix
     * @param recipientSk  server/restore secret key (32 bytes)
     */
    fun unseal(
        wire: ByteArray,
        senderPk: ByteArray,
        recipientSk: ByteArray,
        sodium: LazySodiumAndroid,
    ): ByteArray? {
        if (wire.size <= OVERHEAD) return null

        val nonce = wire.copyOfRange(0, NONCE_BYTES)
        val ciphertext = wire.copyOfRange(NONCE_BYTES, wire.size)
        val plaintext = ByteArray(ciphertext.size - MAC_BYTES)

        val ok = sodium.cryptoBoxOpenEasy(
            plaintext,
            ciphertext,
            ciphertext.size.toLong(),
            nonce,
            senderPk,
            recipientSk,
        )
        return if (ok) plaintext else null
    }
}
