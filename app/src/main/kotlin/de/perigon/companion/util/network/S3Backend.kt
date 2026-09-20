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
 * Retry policy is PER OPERATION, not client-wide:
 *  - retried: putObject, uploadPart, headObject, listParts, listObjects,
 *    getRange — all idempotent, replay is indistinguishable from one call.
 *  - NOT retried: createMultipart (a replay orphans an upload id),
 *    completeMultipart and abortMultipart (a replay races the first
 *    attempt's effect). These are only classified.
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

    /** Name used in user-facing messages. B2 and MinIO both land here. */
    private val service: String = "backup storage"

    private fun creds() = SigV4.Credentials(keyId, appKey, region)

    private fun bucketPath(key: String, query: String = ""): String {
        val p = "/$bucket/${key.trimStart('/')}"
        return if (query.isNotEmpty()) "$p?$query" else p
    }

    override suspend fun putObject(key: String, data: ByteArray, contentType: String?) {
        retrying(service, "PUT $key") {
            val ts      = SigV4.now()
            val hash    = SigV4.sha256hex(data)
            val extra   = if (contentType != null) mapOf("content-type" to contentType) else emptyMap()
            val headers = SigV4.sign("PUT", host, bucketPath(key), hash, ts, creds(), extra)
            val resp    = http.put("$endpoint/$bucket/$key") {
                headers.forEach { (k, v) -> header(k, v) }
                setBody(data)
            }
            if (!resp.status.isSuccess()) throw classify(resp, service, "PUT $key")
        }
    }

    /**
     * DeleteObject. S3 semantics: deleting a nonexistent key is a success
     * (204), which matches MediaBackend's "gone" contract directly.
     */
    override suspend fun deleteMedia(key: String): Boolean = retrying(service, "DELETE $key") {
        val ts      = SigV4.now()
        val headers = SigV4.sign("DELETE", host, bucketPath(key), SigV4.EMPTY_HASH, ts, creds())
        val resp    = http.delete("$endpoint/$bucket/$key") {
            headers.forEach { (k, v) -> header(k, v) }
        }
        if (resp.status.isSuccess() || resp.status == HttpStatusCode.NotFound) return@retrying true
        throw classify(resp, service, "DELETE $key")
    }

    /**
     * HeadObject. Returns true if the object exists, false if it provably
     * does not (404). Any other status THROWS — a 5xx or auth failure must
     * never be interpreted as "absent": callers count packs / check
     * existence with this, and folding transport errors into `false` used
     * to silently truncate restores.
     */
    override suspend fun headObject(key: String): Boolean = retrying(service, "HEAD $key") {
        val ts      = SigV4.now()
        val headers = SigV4.sign("HEAD", host, bucketPath(key), SigV4.EMPTY_HASH, ts, creds())
        val resp    = http.request("$endpoint/$bucket/$key") {
            method = HttpMethod.Head
            headers.forEach { (k, v) -> header(k, v) }
        }
        if (resp.status == HttpStatusCode.NotFound) return@retrying false
        if (!resp.status.isSuccess()) throw classify(resp, service, "HEAD $key")
        true
    }

    /**
     * NOT retried: a replayed CreateMultipartUpload leaves the first upload
     * id orphaned, consuming storage until aborted.
     */
    override suspend fun createMultipart(key: String): String = classifying(service, "CreateMultipart $key") {
        val ts      = SigV4.now()
        val headers = SigV4.sign("POST", host, bucketPath(key, "uploads="), SigV4.EMPTY_HASH, ts, creds())
        val resp    = http.post("$endpoint/$bucket/$key?uploads=") {
            headers.forEach { (k, v) -> header(k, v) }
        }
        if (!resp.status.isSuccess()) throw classify(resp, service, "CreateMultipart $key")
        val body = resp.bodyAsText()
        Regex("<UploadId>(.+?)</UploadId>").find(body)?.groupValues?.get(1)
            ?: error("No UploadId in response: $body")
    }

    override suspend fun uploadPart(key: String, uploadId: String, partNumber: Int, data: ByteArray): String =
        retrying(service, "UploadPart $partNumber of $key") {
            val ts      = SigV4.now()
            val hash    = SigV4.sha256hex(data)
            val qp      = "partNumber=$partNumber&uploadId=${uploadId.encodeURLParameter()}"
            val headers = SigV4.sign("PUT", host, bucketPath(key, qp), hash, ts, creds())
            val resp    = http.put("$endpoint/$bucket/$key?$qp") {
                headers.forEach { (k, v) -> header(k, v) }
                setBody(data)
            }
            if (!resp.status.isSuccess()) throw classify(resp, service, "UploadPart $partNumber")
            resp.headers["ETag"]?.trim('"') ?: error("No ETag in UploadPart response")
        }

    /**
     * NOT retried: if the first attempt reached the server, the upload is
     * already complete and a replay fails against a nonexistent upload id —
     * turning a success into a reported failure.
     */
    override suspend fun completeMultipart(key: String, uploadId: String, parts: List<S3Backend.Part>) {
        classifying(service, "CompleteMultipart $key") {
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
            if (!resp.status.isSuccess()) throw classify(resp, service, "CompleteMultipart $key")
        }
    }

    /** Best-effort cleanup; never retried and never throws. */
    override suspend fun abortMultipart(key: String, uploadId: String) {
        try {
            val ts      = SigV4.now()
            val qp      = "uploadId=${uploadId.encodeURLParameter()}"
            val headers = SigV4.sign("DELETE", host, bucketPath(key, qp), SigV4.EMPTY_HASH, ts, creds())
            http.delete("$endpoint/$bucket/$key?$qp") {
                headers.forEach { (k, v) -> header(k, v) }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("S3Backend", "abortMultipart $key failed", e)
        }
    }

    override suspend fun listParts(key: String, uploadId: String): List<S3Backend.Part> =
        retrying(service, "ListParts $key") {
            val ts      = SigV4.now()
            val qp      = "uploadId=${uploadId.encodeURLParameter()}"
            val headers = SigV4.sign("GET", host, bucketPath(key, qp), SigV4.EMPTY_HASH, ts, creds())
            val resp    = http.get("$endpoint/$bucket/$key?$qp") {
                headers.forEach { (k, v) -> header(k, v) }
            }
            if (!resp.status.isSuccess()) throw classify(resp, service, "ListParts $key")
            val body = resp.bodyAsText()
            // Parse number and etag out of the SAME <Part> element — zipping two
            // independent findAll streams can silently misalign if the response
            // ever contains extra tags of either kind.
            Regex("<Part>(.*?)</Part>", RegexOption.DOT_MATCHES_ALL)
                .findAll(body)
                .mapNotNull { part ->
                    val block  = part.groupValues[1]
                    val number = Regex("<PartNumber>(\\d+)</PartNumber>").find(block)
                        ?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
                    val etag   = Regex("<ETag>\"?([^<\"]+)\"?</ETag>").find(block)
                        ?.groupValues?.get(1) ?: return@mapNotNull null
                    S3Backend.Part(number, etag)
                }
                .toList()
        }

    override suspend fun listObjects(prefix: String, maxKeys: Int): List<String> =
        retrying(service, "ListObjects $prefix") {
            val ts      = SigV4.now()
            val qp      = "list-type=2&max-keys=$maxKeys&prefix=${prefix.encodeURLParameter()}"
            val headers = SigV4.sign("GET", host, bucketPath("", qp), SigV4.EMPTY_HASH, ts, creds())
            val resp    = http.get("$endpoint/$bucket?$qp") {
                headers.forEach { (k, v) -> header(k, v) }
            }
            if (!resp.status.isSuccess()) throw classify(resp, service, "ListObjects $prefix")
            val body = resp.bodyAsText()
            Regex("<Key>([^<]+)</Key>").findAll(body).map { it.groupValues[1] }.toList()
        }

    override suspend fun getRange(key: String, from: Long, to: Long): ByteArray =
        retrying(service, "GET $key [$from-$to]") {
            val resp = rangeRequest(key, from, to)
            if (!resp.status.isSuccess()) throw classify(resp, service, "GET $key [$from-$to]")
            resp.readRawBytes()
        }

    /**
     * Like [getRange] but returns null when the object or range provably
     * does not exist (404 / 416), and throws on anything else. Restore's
     * sequential pack/part walk needs "definitely past the end" to be
     * distinguishable from "network hiccup" — treating the two the same
     * silently truncated restores.
     */
    override suspend fun getRangeOrNull(key: String, from: Long, to: Long): ByteArray? =
        retrying(service, "GET $key [$from-$to]") {
            val resp = rangeRequest(key, from, to)
            if (resp.status == HttpStatusCode.NotFound ||
                resp.status == HttpStatusCode.RequestedRangeNotSatisfiable) return@retrying null
            if (!resp.status.isSuccess()) throw classify(resp, service, "GET $key [$from-$to]")
            resp.readRawBytes()
        }

    private suspend fun rangeRequest(key: String, from: Long, to: Long): HttpResponse {
        val ts      = SigV4.now()
        val headers = SigV4.sign("GET", host, bucketPath(key), SigV4.EMPTY_HASH, ts, creds())
        return http.get("$endpoint/$bucket/$key") {
            headers.forEach { (k, v) -> header(k, v) }
            header("Range", "bytes=$from-$to")
        }
    }
}

/**
 * S3-compatible storage interface. Decouples backup engine and media upload
 * from the HTTP implementation. Extends [MediaBackend] so it can be used
 * as a media upload target by PublishWorker (and delete target by
 * UnpublishWorker).
 */
interface S3Backend : MediaBackend {
    data class Part(val number: Int, val etag: String)

    override suspend fun putObject(key: String, data: ByteArray, contentType: String?)
    override suspend fun deleteMedia(key: String): Boolean
    override suspend fun objectExists(key: String): Boolean = headObject(key)
    suspend fun headObject(key: String): Boolean
    suspend fun createMultipart(key: String): String
    suspend fun uploadPart(key: String, uploadId: String, partNumber: Int, data: ByteArray): String
    suspend fun completeMultipart(key: String, uploadId: String, parts: List<Part>)
    suspend fun abortMultipart(key: String, uploadId: String)
    suspend fun listParts(key: String, uploadId: String): List<Part>
    suspend fun listObjects(prefix: String = "", maxKeys: Int = 1000): List<String>
    suspend fun getRange(key: String, from: Long, to: Long): ByteArray
    suspend fun getRangeOrNull(key: String, from: Long, to: Long): ByteArray?
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
