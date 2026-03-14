package de.perigon.companion.backup.domain

import de.perigon.companion.backup.network.b2.B2Backend
import de.perigon.companion.util.toHex
import java.io.File
import java.io.FileOutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest

data class RestoreFilePlan(
    val packKey: String,
    val startPart: Int,
    val startPartOffset: Long,
    val expectedSha256: String,
    val fileSize: Long,
)

sealed class RestoreResult {
    data class Success(val tempFile: File, val sha256: String) : RestoreResult()
    data class DecryptionFailed(val pack: String, val part: Int) : RestoreResult()
    data class HeaderMismatch(val pack: String, val part: Int, val offset: Int) : RestoreResult()
    data class HashMismatch(val expected: String, val actual: String) : RestoreResult()
}

/**
 * Streaming file restore: downloads parts, decrypts, verifies PAX header,
 * streams to temp file via DigestOutputStream, verifies SHA-256.
 * Never holds the full file in memory.
 */
class RestoreFileUseCase(
    private val b2: B2Backend,
    private val encryptor: BackupPartEncryptor,
    private val publicKey: ByteArray,
    private val secretKey: ByteArray,
    private val sodium: com.goterl.lazysodium.LazySodiumAndroid,
    private val cacheDir: File,
) {

    suspend fun restore(plan: RestoreFilePlan): RestoreResult {
        val digest = MessageDigest.getInstance("SHA-256")
        var remaining = plan.fileSize
        var partNum = plan.startPart
        var skipInPart = plan.startPartOffset
        var foundHeader = false

        val tempFile = File.createTempFile("restore_", ".tmp", cacheDir)
        try {
            DigestOutputStream(FileOutputStream(tempFile), digest).use { out ->
                while (remaining > 0) {
                    val wireData = b2.getRange(
                        plan.packKey,
                        (partNum - 1).toLong() * BackupPackEngine.PART_WIRE_SIZE,
                        partNum.toLong() * BackupPackEngine.PART_WIRE_SIZE - 1,
                    )

                    val plaintext = BackupPartEncryptor.unseal(wireData, publicKey, secretKey, sodium)
                        ?: return RestoreResult.DecryptionFailed(plan.packKey, partNum)

                    var offset = skipInPart.toInt()
                    skipInPart = 0

                    if (!foundHeader) {
                        if (offset + BackupPaxWriter.BLOCK <= plaintext.size) {
                            val headerBlock = plaintext.copyOfRange(offset, offset + BackupPaxWriter.BLOCK)
                            val entry = BackupPaxWriter.parseEntry(headerBlock)
                            if (entry != null && entry.sha256 == plan.expectedSha256) {
                                foundHeader = true
                                offset += BackupPaxWriter.BLOCK
                            } else {
                                return RestoreResult.HeaderMismatch(plan.packKey, partNum, offset)
                            }
                        }
                    }

                    val available = plaintext.size - offset
                    val toCopy = minOf(available.toLong(), remaining).toInt()
                    out.write(plaintext, offset, toCopy)
                    remaining -= toCopy
                    partNum++
                }
            }

            val actualHash = digest.digest().toHex()
            if (actualHash != plan.expectedSha256) {
                tempFile.delete()
                return RestoreResult.HashMismatch(plan.expectedSha256, actualHash)
            }

            return RestoreResult.Success(tempFile, actualHash)
        } catch (e: Exception) {
            tempFile.delete()
            throw e
        }
    }
}
