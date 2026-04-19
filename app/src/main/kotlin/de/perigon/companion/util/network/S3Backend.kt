package de.perigon.companion.util.network

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * S3-compatible (SigV4) client for B2 Cloud Storage and MinIO.
 *
 * Hand-rolled rather than using an SDK because we need direct control over
 * multipart upload lifecycle: streaming parts from the secretstream cipher
 * into B2 without buffering full objects, resuming interrupted uploads by
 * part number, and abort/complete semantics that map 1:1 to our pack state
 * machine. No S3 SDK exposes this at the right granularity.
 *
 * Region is explicit at construction — callers must pass the correct value:
 *   B2:    derived from endpoint subdomain (e.g. "us-west-004")
 *   MinIO: always "us-east-1"
 */
class S3BackendImpl(
    private val http:     HttpClient,
    private val endpoint: String,
    private val bucket:   String,
    private val keyId:    String,
    private val appKey:   String,
    private val region:   String,
) : S3Backend {

    private val host: String = endpoint.removePrefix("https://").removePrefix("http://")

    private fun creds() = SigV4.Credentials(keyId, appKey, region)

    private fun bucketPath(key: String, query: String = ""): String {
        val p = "/$bucket/${key.trimStart('/')}"
        return if (query.isNotEmpty()) "$p?$query" else p
    }

    override suspend fun putObject(key: String, data: ByteArray, contentType: String?) {
        val ts      = SigV4.now()
        val hash    = SigV4.sha256hex(data)
        val extra   = if (contentType != null) mapOf("content-type" to contentType) else emptyMap()
        val headers = SigV4.sign("PUT", host, bucketPath(key), hash, ts, creds(), extra)
        android.util.Log.d("S3Sign", "putObject auth: " + headers["Authorization"]?.take(120))
        val resp    = http.put("$endpoint/$bucket/$key") {
            headers.forEach { (k, v) -> header(k, v) }
            setBody(data)
        }
        check(resp.status.isSuccess()) { "PUT $key failed: ${resp.status} - ${resp.bodyAsText()}" }
    }

    override suspend fun headObject(key: String): Boolean {
        val ts      = SigV4.now()
        val headers = SigV4.sign("HEAD", host, bucketPath(key), SigV4.EMPTY_HASH, ts, creds())
        val resp    = http.request("$endpoint/$bucket/$key") {
            method = HttpMethod.Head
            headers.forEach { (k, v) -> header(k, v) }
        }
        return resp.status.isSuccess()
    }

    override suspend fun createMultipart(key: String): String {
        val ts      = SigV4.now()
        val headers = SigV4.sign("POST", host, bucketPath(key, "uploads="), SigV4.EMPTY_HASH, ts, creds())
        val resp    = http.post("$endpoint/$bucket/$key?uploads=") {
            headers.forEach { (k, v) -> header(k, v) }
        }
        check(resp.status.isSuccess()) { "CreateMultipart failed: ${resp.status}" }
        val body = resp.bodyAsText()
        return Regex("<UploadId>(.+?)</UploadId>").find(body)?.groupValues?.get(1)
            ?: error("No UploadId in response: $body")
    }

    override suspend fun uploadPart(key: String, uploadId: String, partNumber: Int, data: ByteArray): String {
        val ts      = SigV4.now()
        val hash    = SigV4.sha256hex(data)
        val qp      = "partNumber=$partNumber&uploadId=${uploadId.encodeURLParameter()}"
        val headers = SigV4.sign("PUT", host, bucketPath(key, qp), hash, ts, creds())
        val resp    = http.put("$endpoint/$bucket/$key?$qp") {
            headers.forEach { (k, v) -> header(k, v) }
            setBody(data)
        }
        check(resp.status.isSuccess()) { "UploadPart $partNumber failed: ${resp.status}" }
        return resp.headers["ETag"]?.trim('"') ?: error("No ETag in UploadPart response")
    }

    override suspend fun completeMultipart(key: String, uploadId: String, parts: List<S3Backend.Part>) {
        val body = buildString {
            append("<CompleteMultipartUpload>")
            parts.sortedBy { it.number }.forEach { p ->
                append("<Part><PartNumber>${p.number}</PartNumber><ETag>${p.etag}</ETag></Part>")
            }
            append("</CompleteMultipartUpload>")
        }.toByteArray()
        val ts      = SigV4.now()
        val qp      = "uploadId=${uploadId.encodeURLParameter()}"
        val headers = SigV4.sign("POST", host, bucketPath(key, qp), SigV4.sha256hex(body), ts, creds())
        val resp    = http.post("$endpoint/$bucket/$key?$qp") {
            headers.forEach { (k, v) -> header(k, v) }
            setBody(body)
        }
        check(resp.status.isSuccess()) { "CompleteMultipart failed: ${resp.status} - ${resp.bodyAsText()}" }
    }

    override suspend fun abortMultipart(key: String, uploadId: String) {
        val ts      = SigV4.now()
        val qp      = "uploadId=${uploadId.encodeURLParameter()}"
        val headers = SigV4.sign("DELETE", host, bucketPath(key, qp), SigV4.EMPTY_HASH, ts, creds())
        http.delete("$endpoint/$bucket/$key?$qp") {
            headers.forEach { (k, v) -> header(k, v) }
        }
    }

    override suspend fun listParts(key: String, uploadId: String): List<S3Backend.Part> {
        val ts      = SigV4.now()
        val qp      = "uploadId=${uploadId.encodeURLParameter()}"
        val headers = SigV4.sign("GET", host, bucketPath(key, qp), SigV4.EMPTY_HASH, ts, creds())
        val resp    = http.get("$endpoint/$bucket/$key?$qp") {
            headers.forEach { (k, v) -> header(k, v) }
        }
        val body    = resp.bodyAsText()
        val numbers = Regex("<PartNumber>(\\d+)</PartNumber>").findAll(body).map { it.groupValues[1].toInt() }
        val etags   = Regex("<ETag>\"?([^<\"]+)\"?</ETag>").findAll(body).map { it.groupValues[1] }
        return numbers.zip(etags).map { (n, e) -> S3Backend.Part(n, e) }.toList()
    }

    override suspend fun listObjects(prefix: String, maxKeys: Int): List<String> {
        val ts      = SigV4.now()
        val qp      = "list-type=2&max-keys=$maxKeys&prefix=${prefix.encodeURLParameter()}"
        val headers = SigV4.sign("GET", host, bucketPath("", qp), SigV4.EMPTY_HASH, ts, creds())
        val resp    = http.get("$endpoint/$bucket?$qp") {
            headers.forEach { (k, v) -> header(k, v) }
        }
        val body = resp.bodyAsText()
        return Regex("<Key>([^<]+)</Key>").findAll(body).map { it.groupValues[1] }.toList()
    }

    override suspend fun getRange(key: String, from: Long, to: Long): ByteArray {
        val ts      = SigV4.now()
        val headers = SigV4.sign("GET", host, bucketPath(key), SigV4.EMPTY_HASH, ts, creds())
        android.util.Log.d("S3Sign", "getRange auth: " + headers["Authorization"]?.take(120))
        val resp    = http.get("$endpoint/$bucket/$key") {
            headers.forEach { (k, v) -> header(k, v) }
            header("Range", "bytes=$from-$to")
        }
        check(resp.status.isSuccess()) { "getRange $key [$from-$to] failed: ${resp.status} - ${resp.bodyAsText()}" }
        return resp.readRawBytes()
    }
}

/**
 * S3-compatible storage interface. Decouples backup engine and media upload
 * from the HTTP implementation. Extends [MediaBackend] so it can be used
 * as a media upload target by PublishWorker.
 */
interface S3Backend : MediaBackend {
    data class Part(val number: Int, val etag: String)

    override suspend fun putObject(key: String, data: ByteArray, contentType: String?)
    suspend fun headObject(key: String): Boolean
    suspend fun createMultipart(key: String): String
    suspend fun uploadPart(key: String, uploadId: String, partNumber: Int, data: ByteArray): String
    suspend fun completeMultipart(key: String, uploadId: String, parts: List<Part>)
    suspend fun abortMultipart(key: String, uploadId: String)
    suspend fun listParts(key: String, uploadId: String): List<Part>
    suspend fun listObjects(prefix: String = "", maxKeys: Int = 1000): List<String>
    suspend fun getRange(key: String, from: Long, to: Long): ByteArray
}

interface S3BackendFactory {
    fun create(endpoint: String, bucket: String, keyId: String, appKey: String, region: String): S3Backend
}

@Singleton
class S3BackendFactoryImpl @Inject constructor(
    private val http: HttpClient,
) : S3BackendFactory {
    override fun create(
        endpoint: String,
        bucket:   String,
        keyId:    String,
        appKey:   String,
        region:   String,
    ): S3Backend = S3BackendImpl(http, endpoint, bucket, keyId, appKey, region)
}
