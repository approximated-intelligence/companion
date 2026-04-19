package de.perigon.companion.util.network

import android.util.Base64
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*

/**
 * Plain HTTP PUT media backend with Basic or Digest auth.
 * Implements [MediaBackend].
 *
 * Digest flow per request:
 *   1. PUT → expect 401 with WWW-Authenticate: Digest
 *   2. Parse challenge, compute response
 *   3. Retry PUT with Authorization header
 *   Caches the last challenge/nonce to avoid extra round-trips.
 *
 * Basic flow: single PUT with Authorization: Basic header.
 */
class HttpMediaBackend(
    private val http:     HttpClient,
    private val endpoint: String,
    private val user:     String,
    private val password: String,
    private val authType: String = "digest",
) : MediaBackend {

    @Volatile
    private var cachedChallenge: DigestAuth.Challenge? = null
    @Volatile
    private var nc: Int = 0

    override suspend fun putObject(key: String, data: ByteArray, contentType: String?) {
        when (authType) {
            "basic"  -> putBasic(key, data, contentType)
            else     -> putDigest(key, data, contentType)
        }
    }

    private suspend fun putBasic(key: String, data: ByteArray, contentType: String?) {
        val url = "${endpoint.trimEnd('/')}/${key.trimStart('/')}"
        val credentials = Base64.encodeToString(
            "$user:$password".toByteArray(Charsets.UTF_8), Base64.NO_WRAP,
        )
        val resp = doPut(url, data, contentType, "Basic $credentials")
        check(resp.status.isSuccess()) { "PUT $key failed: ${resp.status} - ${resp.bodyAsText()}" }
    }

    private suspend fun putDigest(key: String, data: ByteArray, contentType: String?) {
        val url = "${endpoint.trimEnd('/')}/${key.trimStart('/')}"
        val uri = "/${key.trimStart('/')}"
        val creds = DigestAuth.Credentials(user, password)

        // Try with cached challenge first to avoid round-trip
        val challenge = cachedChallenge
        if (challenge != null) {
            val authHeader = DigestAuth.authorize("PUT", uri, challenge, creds, nc = ++nc)
            val resp = doPut(url, data, contentType, authHeader)
            if (resp.status != HttpStatusCode.Unauthorized) {
                check(resp.status.isSuccess()) { "PUT $key failed: ${resp.status} - ${resp.bodyAsText()}" }
                return
            }
            // Nonce expired — fall through to fresh challenge
        }

        // Initial request to get challenge
        val initialResp = doPut(url, data, contentType, authHeader = null)
        if (initialResp.status.isSuccess()) return // Server didn't require auth

        check(initialResp.status == HttpStatusCode.Unauthorized) {
            "PUT $key failed: expected 401, got ${initialResp.status} - ${initialResp.bodyAsText()}"
        }

        val wwwAuth = initialResp.headers["WWW-Authenticate"]
            ?: error("PUT $key: 401 without WWW-Authenticate header")
        val freshChallenge = DigestAuth.parseChallenge(wwwAuth)
        cachedChallenge = freshChallenge
        nc = 1

        val authHeader = DigestAuth.authorize("PUT", uri, freshChallenge, creds, nc = nc)
        val retryResp = doPut(url, data, contentType, authHeader)
        check(retryResp.status.isSuccess()) {
            "PUT $key failed after digest auth: ${retryResp.status} - ${retryResp.bodyAsText()}"
        }
    }

    private suspend fun doPut(
        url: String,
        data: ByteArray,
        contentType: String?,
        authHeader: String?,
    ): HttpResponse {
        return http.put(url) {
            if (authHeader != null) header(HttpHeaders.Authorization, authHeader)
            if (contentType != null) header(HttpHeaders.ContentType, contentType)
            setBody(data)
        }
    }
}

interface HttpMediaBackendFactory {
    fun create(endpoint: String, user: String, password: String, authType: String = "digest"): MediaBackend
}

@javax.inject.Singleton
class HttpMediaBackendFactoryImpl @javax.inject.Inject constructor(
    private val http: HttpClient,
) : HttpMediaBackendFactory {
    override fun create(endpoint: String, user: String, password: String, authType: String): MediaBackend =
        HttpMediaBackend(http, endpoint, user, password, authType)
}
