package de.perigon.companion.backup.network.b2

import de.perigon.companion.util.toHex
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import java.security.MessageDigest
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * S3-compatible (SigV4) client for B2 Cloud Storage.
 *
 * Hand-rolled rather than using an SDK because we need direct control over
 * multipart upload lifecycle: streaming parts from the secretstream cipher
 * into B2 without buffering full objects, resuming interrupted uploads by
 * part number, and abort/complete semantics that map 1:1 to our pack state
 * machine. No S3 SDK exposes this at the right granularity.
 */
class B2BackendImpl(
    private val http:     HttpClient,
    private val endpoint: String,
    private val bucket:   String,
    private val keyId:    String,
    private val appKey:   String,
) : B2Backend {

    private val region: String = endpoint
        .removePrefix("https://").removePrefix("http://")
        .split(".").getOrElse(1) { "us-west-004" }

    override suspend fun headObject(key: String): Boolean {
        val (dt, d) = nowDateTime()
        val headers = sigV4("HEAD", key, EMPTY_HASH, dt, d)
        val resp = http.request("$endpoint/$bucket/$key") {
            method = HttpMethod.Head
            headers.forEach { (k, v) -> header(k, v) }
        }
        check(resp.status.isSuccess()) { "HEAD $key: ${resp.status}" }
        return true
    }

    override suspend fun createMultipart(key: String): String {
        val (dt, d) = nowDateTime()
        val headers = sigV4("POST", "$key?uploads=", EMPTY_HASH, dt, d)
        val resp = http.post("$endpoint/$bucket/$key?uploads=") {
            headers.forEach { (k, v) -> header(k, v) }
        }
        check(resp.status.isSuccess()) { "CreateMultipart failed: ${resp.status}" }
        val body = resp.bodyAsText()
        return Regex("<UploadId>(.+?)</UploadId>").find(body)?.groupValues?.get(1)
            ?: error("No UploadId in response: $body")
    }

    override suspend fun uploadPart(key: String, uploadId: String, partNumber: Int, data: ByteArray): String {
        val (dt, d) = nowDateTime()
        val hash = sha256hex(data)
        val qp = "partNumber=$partNumber&uploadId=${uploadId.encodeURLParameter()}"
        val headers = sigV4("PUT", "$key?$qp", hash, dt, d)
        val resp = http.put("$endpoint/$bucket/$key?$qp") {
            headers.forEach { (k, v) -> header(k, v) }
            setBody(data)
        }
        check(resp.status.isSuccess()) { "UploadPart $partNumber failed: ${resp.status}" }
        return resp.headers["ETag"]?.trim('"') ?: error("No ETag in UploadPart response")
    }

    override suspend fun completeMultipart(key: String, uploadId: String, parts: List<B2Backend.Part>) {
        val body = buildString {
            append("<CompleteMultipartUpload>")
            parts.sortedBy { it.number }.forEach { p ->
                append("<Part><PartNumber>${p.number}</PartNumber><ETag>${p.etag}</ETag></Part>")
            }
            append("</CompleteMultipartUpload>")
        }.toByteArray()
        val (dt, d) = nowDateTime()
        val qp = "uploadId=${uploadId.encodeURLParameter()}"
        val headers = sigV4("POST", "$key?$qp", sha256hex(body), dt, d)
        val resp = http.post("$endpoint/$bucket/$key?$qp") {
            headers.forEach { (k, v) -> header(k, v) }
            setBody(body)
        }
        check(resp.status.isSuccess()) { "CompleteMultipart failed: ${resp.status} - ${resp.bodyAsText()}" }
    }

    override suspend fun abortMultipart(key: String, uploadId: String) {
        val (dt, d) = nowDateTime()
        val qp = "uploadId=${uploadId.encodeURLParameter()}"
        val headers = sigV4("DELETE", "$key?$qp", EMPTY_HASH, dt, d)
        http.delete("$endpoint/$bucket/$key?$qp") {
            headers.forEach { (k, v) -> header(k, v) }
        }
    }

    override suspend fun listParts(key: String, uploadId: String): List<B2Backend.Part> {
        val (dt, d) = nowDateTime()
        val qp = "uploadId=${uploadId.encodeURLParameter()}"
        val headers = sigV4("GET", "$key?$qp", EMPTY_HASH, dt, d)
        val resp = http.get("$endpoint/$bucket/$key?$qp") {
            headers.forEach { (k, v) -> header(k, v) }
        }
        val body = resp.bodyAsText()
        val numbers = Regex("<PartNumber>(\\d+)</PartNumber>").findAll(body).map { it.groupValues[1].toInt() }
        val etags   = Regex("<ETag>\"?([^<\"]+)\"?</ETag>").findAll(body).map { it.groupValues[1] }
        return numbers.zip(etags).map { (n, e) -> B2Backend.Part(n, e) }.toList()
    }

    override suspend fun listObjects(prefix: String, maxKeys: Int): List<String> {
        val (dt, d) = nowDateTime()
        val qp = "list-type=2&max-keys=$maxKeys&prefix=${prefix.encodeURLParameter()}"
        val headers = sigV4("GET", "?$qp", EMPTY_HASH, dt, d)
        val resp = http.get("$endpoint/$bucket?$qp") {
            headers.forEach { (k, v) -> header(k, v) }
        }
        val body = resp.bodyAsText()
        return Regex("<Key>([^<]+)</Key>").findAll(body).map { it.groupValues[1] }.toList()
    }

    override suspend fun getRange(key: String, from: Long, to: Long): ByteArray {
        val (dt, d) = nowDateTime()
        val headers = sigV4("GET", key, EMPTY_HASH, dt, d, mapOf("range" to "bytes=$from-$to"))
        val resp = http.get("$endpoint/$bucket/$key") {
            headers.forEach { (k, v) -> header(k, v) }
        }
        return resp.readRawBytes()
    }

    private fun sigV4(
        method:            String,
        resourceAndQuery:  String,
        payloadHash:       String,
        dateTime:          String,
        date:              String,
        extra:             Map<String, String> = emptyMap(),
    ): Map<String, String> {
        val host = endpoint.removePrefix("https://").removePrefix("http://")
        val (path, query) = if ("?" in resourceAndQuery)
            resourceAndQuery.substringBefore("?") to resourceAndQuery.substringAfter("?")
        else resourceAndQuery to ""

        val hdrs = sortedMapOf(
            "host"                 to host,
            "x-amz-content-sha256" to payloadHash,
            "x-amz-date"           to dateTime,
        ) + extra.mapKeys { it.key.lowercase() }

        val signedHeaders    = hdrs.keys.joinToString(";")
        val canonicalHeaders = hdrs.entries.joinToString("\n") { "${it.key}:${it.value}" } + "\n"
        val canonicalRequest = "$method\n/$bucket/${path.trimStart('/')}\n$query\n$canonicalHeaders\n$signedHeaders\n$payloadHash"

        val credScope = "$date/$region/s3/aws4_request"
        val sts = "AWS4-HMAC-SHA256\n$dateTime\n$credScope\n${sha256hex(canonicalRequest.toByteArray())}"

        fun hmac(key: ByteArray, data: String) =
            Mac.getInstance("HmacSHA256")
                .apply { init(SecretKeySpec(key, "HmacSHA256")) }
                .doFinal(data.toByteArray())

        val sigKey = hmac(hmac(hmac(hmac("AWS4$appKey".toByteArray(), date), region), "s3"), "aws4_request")
        val sig    = hmac(sigKey, sts).toHex()

        return mapOf(
            "Authorization"        to "AWS4-HMAC-SHA256 Credential=$keyId/$credScope, SignedHeaders=$signedHeaders, Signature=$sig",
            "x-amz-date"           to dateTime,
            "x-amz-content-sha256" to payloadHash,
        ) + extra
    }

    private fun nowDateTime(): Pair<String, String> {
        val now = ZonedDateTime.now(ZoneOffset.UTC)
        return now.format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")) to
               now.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
    }

    private fun sha256hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).toHex()

    companion object {
        private const val EMPTY_HASH = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    }
}

// --- Merged from B2Backend.kt ---

/**
 * B2 storage interface - decouples BackupPackEngine from the HTTP implementation.
 * Real impl: B2BackendImpl (Ktor CIO).
 * Test impl: FakeB2 (in-memory, no network).
 */
interface B2Backend {
    data class Part(val number: Int, val etag: String)

    suspend fun headObject(key: String): Boolean
    suspend fun createMultipart(key: String): String
    suspend fun uploadPart(key: String, uploadId: String, partNumber: Int, data: ByteArray): String
    suspend fun completeMultipart(key: String, uploadId: String, parts: List<Part>)
    suspend fun abortMultipart(key: String, uploadId: String)
    suspend fun listParts(key: String, uploadId: String): List<Part>
    suspend fun listObjects(prefix: String = "", maxKeys: Int = 1000): List<String>
    suspend fun getRange(key: String, from: Long, to: Long): ByteArray
}


// --- Merged from B2BackendFactory.kt ---

/**
 * Factory for creating B2Backend instances. Injected into ViewModels
 * so they never directly construct B2BackendImpl.
 */
interface B2BackendFactory {
    fun create(endpoint: String, bucket: String, keyId: String, appKey: String): B2Backend
}


// --- Merged from B2BackendFactoryImpl.kt ---

@Singleton
class B2BackendFactoryImpl @Inject constructor(
    private val http: HttpClient,
) : B2BackendFactory {
    override fun create(
        endpoint: String,
        bucket: String,
        keyId: String,
        appKey: String,
    ): B2Backend = B2BackendImpl(http, endpoint, bucket, keyId, appKey)
}

