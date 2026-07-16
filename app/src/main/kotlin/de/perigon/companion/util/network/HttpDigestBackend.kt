package de.perigon.companion.util.network

import android.util.Base64
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * Plain HTTP media backend with Basic or Digest auth.
 * Implements [MediaBackend] (PUT + DELETE).
 *
 * Digest flow per request:
 *   1. Request → expect 401 with WWW-Authenticate: Digest
 *   2. Parse challenge, compute response
 *   3. Retry with Authorization header
 *   Caches the last challenge (with its own nonce counter) to avoid extra
 *   round-trips. Challenge and counter travel together in one immutable
 *   session object behind a single volatile reference, so concurrent
 *   calls can never pair a counter with the wrong nonce.
 */
class HttpMediaBackend(
    private val http:     HttpClient,
    private val endpoint: String,
    private val user:     String,
    private val password: String,
    private val authType: String = "digest",
) : MediaBackend {

    private class DigestSession(val challenge: DigestAuth.Challenge) {
        private val counter = AtomicInteger(0)
        fun nextNc(): Int = counter.incrementAndGet()
    }

    @Volatile
    private var session: DigestSession? = null

    override suspend fun putObject(key: String, data: ByteArray, contentType: String?) {
        val resp = requestWithAuth(HttpMethod.Put, key, data, contentType)
        check(resp.status.isSuccess()) { "PUT $key failed: ${resp.status} - ${resp.bodyAsText()}" }
    }

    override suspend fun deleteMedia(key: String): Boolean {
        val resp = requestWithAuth(HttpMethod.Delete, key, data = null, contentType = null)
        // Already gone counts as deleted.
        return resp.status.isSuccess() || resp.status == HttpStatusCode.NotFound
    }

    // ---- Auth orchestration ----

    private suspend fun requestWithAuth(
        method: HttpMethod,
        key: String,
        data: ByteArray?,
        contentType: String?,
    ): HttpResponse = when (authType) {
        "basic" -> requestBasic(method, key, data, contentType)
        else    -> requestDigest(method, key, data, contentType)
    }

    private suspend fun requestBasic(
        method: HttpMethod,
        key: String,
        data: ByteArray?,
        contentType: String?,
    ): HttpResponse {
        val credentials = Base64.encodeToString(
            "$user:$password".toByteArray(Charsets.UTF_8), Base64.NO_WRAP,
        )
        return doRequest(method, key, data, contentType, "Basic $credentials")
    }

    private suspend fun requestDigest(
        method: HttpMethod,
        key: String,
        data: ByteArray?,
        contentType: String?,
    ): HttpResponse {
        val uri = "/${key.trimStart('/')}"
        val creds = DigestAuth.Credentials(user, password)

        // Try with cached session first to avoid a round-trip
        val cached = session
        if (cached != null) {
            val authHeader = DigestAuth.authorize(method.value, uri, cached.challenge, creds, nc = cached.nextNc())
            val resp = doRequest(method, key, data, contentType, authHeader)
            if (resp.status != HttpStatusCode.Unauthorized) return resp
            // Nonce expired — fall through to fresh challenge
        }

        val initialResp = doRequest(method, key, data, contentType, authHeader = null)
        if (initialResp.status != HttpStatusCode.Unauthorized) return initialResp

        val wwwAuth = initialResp.headers["WWW-Authenticate"]
            ?: error("${method.value} $key: 401 without WWW-Authenticate header")
        val fresh = DigestSession(DigestAuth.parseChallenge(wwwAuth))
        session = fresh

        val authHeader = DigestAuth.authorize(method.value, uri, fresh.challenge, creds, nc = fresh.nextNc())
        return doRequest(method, key, data, contentType, authHeader)
    }

    private suspend fun doRequest(
        method: HttpMethod,
        key: String,
        data: ByteArray?,
        contentTypeStr: String?,
        authHeader: String?,
    ): HttpResponse {
        val url = "${endpoint.trimEnd('/')}/${key.trimStart('/')}"
        return http.request(url) {
            this.method = method
            if (authHeader != null) header(HttpHeaders.Authorization, authHeader)
            if (data != null) setBody(data)
            // Content-Type is an unsafe header in Ktor — setting it via
            // header() throws UnsafeHeaderException. contentType() attaches
            // it to the outgoing content instead.
            if (contentTypeStr != null) contentType(ContentType.parse(contentTypeStr))
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
