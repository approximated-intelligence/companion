package de.perigon.companion.util.network

import de.perigon.companion.util.toHex
import java.security.MessageDigest
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Pure AWS Signature V4 signer. No I/O, no state.
 * Consumed by S3BackendImpl (backup + media upload).
 *
 * Canonicalization contract (matches how S3BackendImpl builds requests):
 *  - the PATH part of [sign]'s bucketPath arrives RAW and is AWS-URI-encoded
 *    here (unreserved chars + '/' pass through, everything else %XX). For the
 *    key charset this app produces the encoding is the identity, so canonical
 *    strings are unchanged; keys with exotic chars now sign correctly instead
 *    of mismatching what the HTTP layer sends.
 *  - the QUERY part arrives ALREADY percent-encoded exactly as sent on the
 *    wire (uploadId is encodeURLParameter'd by the caller). It is therefore
 *    only split and SORTED here, never re-encoded.
 */
object SigV4 {

    const val EMPTY_HASH = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    data class Credentials(
        val keyId: String,
        val secretKey: String,
        val region: String,
    )

    data class Timestamp(val dateTime: String, val date: String)

    fun now(): Timestamp {
        val t = ZonedDateTime.now(ZoneOffset.UTC)
        return Timestamp(
            dateTime = t.format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")),
            date     = t.format(DateTimeFormatter.ofPattern("yyyyMMdd")),
        )
    }

    /**
     * Compute SigV4 authorization headers.
     *
     * @param method       HTTP method (GET, PUT, POST, HEAD, DELETE)
     * @param host         Host header value (no scheme)
     * @param bucketPath   "/{bucket}/{key}" or "/{bucket}?query" — path raw,
     *                     query pre-encoded (see canonicalization contract)
     * @param payloadHash  SHA-256 hex of request body (use [EMPTY_HASH] for empty)
     * @param ts           Timestamp from [now]
     * @param creds        AWS credentials + region
     * @param extraHeaders Additional headers to sign (lowercase key → value)
     * @return Map of headers to set on the request
     */
    fun sign(
        method:        String,
        host:          String,
        bucketPath:    String,
        payloadHash:   String,
        ts:            Timestamp,
        creds:         Credentials,
        extraHeaders:  Map<String, String> = emptyMap(),
    ): Map<String, String> {
        val (rawPath, rawQuery) = if ("?" in bucketPath)
            bucketPath.substringBefore("?") to bucketPath.substringAfter("?")
        else bucketPath to ""

        val path  = awsEncodePath(rawPath)
        val query = canonicalQuery(rawQuery)

        val hdrs = (sortedMapOf(
            "host"                 to host,
            "x-amz-content-sha256" to payloadHash,
            "x-amz-date"           to ts.dateTime,
        ) + extraHeaders.mapKeys { it.key.lowercase() }).toSortedMap()

        val signedHeaders    = hdrs.keys.joinToString(";")
        val canonicalHeaders = hdrs.entries.joinToString("\n") { "${it.key}:${it.value}" } + "\n"
        val canonicalRequest = "$method\n$path\n$query\n$canonicalHeaders\n$signedHeaders\n$payloadHash"

        val credScope = "${ts.date}/${creds.region}/s3/aws4_request"
        val sts       = "AWS4-HMAC-SHA256\n${ts.dateTime}\n$credScope\n${sha256hex(canonicalRequest.toByteArray())}"

        val sigKey = hmac(
            hmac(hmac(hmac("AWS4${creds.secretKey}".toByteArray(), ts.date), creds.region), "s3"),
            "aws4_request",
        )
        val sig = hmac(sigKey, sts).toHex()

        return mapOf(
            "Authorization"        to "AWS4-HMAC-SHA256 Credential=${creds.keyId}/$credScope, SignedHeaders=$signedHeaders, Signature=$sig",
            "x-amz-date"           to ts.dateTime,
            "x-amz-content-sha256" to payloadHash,
        ) + extraHeaders
    }

    fun sha256hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).toHex()

    private fun hmac(key: ByteArray, data: String): ByteArray =
        Mac.getInstance("HmacSHA256")
            .apply { init(SecretKeySpec(key, "HmacSHA256")) }
            .doFinal(data.toByteArray())

    // ---- Canonicalization ----

    private const val UNRESERVED =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

    /** AWS URI-encode a raw path: unreserved and '/' pass through, rest %XX (UTF-8). */
    private fun awsEncodePath(raw: String): String = buildString(raw.length) {
        for (byte in raw.toByteArray(Charsets.UTF_8)) {
            val i = byte.toInt() and 0xFF
            val c = i.toChar()
            if (c == '/' || c in UNRESERVED) append(c)
            else append('%').append("%02X".format(i))
        }
    }

    /**
     * Sort an already-wire-encoded query string into canonical param order
     * (by name, then value). Values are preserved byte-for-byte; a param
     * without '=' canonicalizes to "name=" (e.g. "uploads=").
     */
    private fun canonicalQuery(encoded: String): String {
        if (encoded.isEmpty()) return ""
        return encoded.split('&')
            .filter { it.isNotEmpty() }
            .map { param ->
                val eq = param.indexOf('=')
                if (eq < 0) param to "" else param.substring(0, eq) to param.substring(eq + 1)
            }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .joinToString("&") { (name, value) -> "$name=$value" }
    }
}
