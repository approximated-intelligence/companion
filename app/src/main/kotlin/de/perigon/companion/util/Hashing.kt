package de.perigon.companion.util

import java.io.InputStream
import java.security.MessageDigest

private const val IO_BUF = 8192

/** Full SHA-256 hex digest of an input stream. Caller must close the stream. */
fun sha256Hex(stream: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buf = ByteArray(IO_BUF)
    var n: Int
    while (stream.read(buf).also { n = it } != -1) {
        digest.update(buf, 0, n)
    }
    return digest.digest().toHex()
}

/** Full SHA-256 hex digest of a byte array. */
fun sha256Hex(data: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(data).toHex()

/**
 * Compute the Git blob SHA-1 for the given content bytes.
 * Git blob format: "blob <size>\0<content>" hashed with SHA-1.
 * This produces the same hash GitHub uses for file SHAs.
 */
fun gitBlobSha1(content: ByteArray): String {
    val header = "blob ${content.size}\u0000".toByteArray(Charsets.US_ASCII)
    val digest = MessageDigest.getInstance("SHA-1")
    digest.update(header)
    digest.update(content)
    return digest.digest().toHex()
}

/** Convenience: Git blob SHA-1 from a String. */
fun gitBlobSha1(content: String): String =
    gitBlobSha1(content.toByteArray(Charsets.UTF_8))

// --- Merged from HexExt.kt ---

fun String.fromHex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

