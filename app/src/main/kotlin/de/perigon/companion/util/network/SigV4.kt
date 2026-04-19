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
     * @param bucketPath   "/{bucket}/{key}" or "/{bucket}?query"
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
        val (path, query) = if ("?" in bucketPath)
            bucketPath.substringBefore("?") to bucketPath.substringAfter("?")
        else bucketPath to ""

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
}
