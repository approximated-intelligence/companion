package de.perigon.companion.util.network

import android.util.Log
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException
import javax.net.ssl.SSLException

private const val TAG = "NetworkRetry"

/**
 * Why a network operation failed, in the terms a CALLER can act on.
 *
 * The split that matters is "the request never got a verdict" (Transport,
 * Server, RateLimited — worth trying again later) versus "the server said no
 * and will say no again" (Auth, NotFound, Client — tell the user now).
 * Workers map the former to Result.retry() and the latter to failure().
 */
class NetworkFailure(
    val kind: Kind,
    val userMessage: String,
    val detail: String? = null,
    cause: Throwable? = null,
) : Exception(userMessage, cause) {

    enum class Kind {
        /** No response at all: DNS, refused, reset, timeout, TLS. */
        TRANSPORT,
        /** 5xx — the server is broken or overloaded right now. */
        SERVER,
        /** 429 / 403 with a rate-limit signature. */
        RATE_LIMITED,
        /** 401, or 403 that is genuinely a permission problem. */
        AUTH,
        /** 404 where the caller expected something to exist. */
        NOT_FOUND,
        /** Other 4xx: malformed request, 409 conflict, 422 unprocessable. */
        CLIENT,
        ;

        /** Whether trying the same operation again later could succeed. */
        val isTransient: Boolean get() = this == TRANSPORT || this == SERVER || this == RATE_LIMITED
    }

    val isTransient: Boolean get() = kind.isTransient
}

/** True for exceptions that mean "the request never reached a verdict". */
fun Throwable.isTransportFailure(): Boolean = when (this) {
    is SocketTimeoutException,
    is ConnectTimeoutException,
    is HttpRequestTimeoutException,
    is SSLException,
    is IOException -> true
    else -> false
}

/**
 * Classify a failed response. [service] names the system for the message
 * ("GitHub", "B2", "media server"); [action] is what was being attempted.
 */
suspend fun classify(
    response: HttpResponse,
    service: String,
    action: String,
): NetworkFailure {
    val status = response.status
    val body = runCatching { response.bodyAsText().take(400) }.getOrDefault("")

    // GitHub signals a secondary rate limit as 403 with a retry hint; a plain
    // 403 is a genuine permission problem. Distinguishing them matters: one
    // is worth retrying, the other never is.
    val rateLimited = status == HttpStatusCode.TooManyRequests ||
        (status == HttpStatusCode.Forbidden &&
            (response.headers["Retry-After"] != null ||
                response.headers["x-ratelimit-remaining"] == "0"))

    val kind = when {
        rateLimited -> NetworkFailure.Kind.RATE_LIMITED
        status.value >= 500 -> NetworkFailure.Kind.SERVER
        status == HttpStatusCode.Unauthorized -> NetworkFailure.Kind.AUTH
        status == HttpStatusCode.Forbidden -> NetworkFailure.Kind.AUTH
        status == HttpStatusCode.NotFound -> NetworkFailure.Kind.NOT_FOUND
        status.value >= 400 -> NetworkFailure.Kind.CLIENT
        else -> NetworkFailure.Kind.SERVER
    }

    val message = when (kind) {
        NetworkFailure.Kind.AUTH -> when (service) {
            "GitHub" -> "GitHub rejected the token — rescan the GitHub QR in Settings"
            else -> "$service rejected the credentials — rescan the QR in Settings"
        }
        NetworkFailure.Kind.RATE_LIMITED -> "$service is rate-limiting this device — it will retry later"
        NetworkFailure.Kind.SERVER -> "$service is unavailable right now (${status.value})"
        NetworkFailure.Kind.NOT_FOUND -> "$action: not found on $service"
        NetworkFailure.Kind.CLIENT -> "$service rejected the request (${status.value})"
        NetworkFailure.Kind.TRANSPORT -> "Cannot reach $service"
    }

    return NetworkFailure(kind, message, detail = "$action → ${status.value} $body")
}

/** Wrap a transport-level throwable. */
fun transportFailure(service: String, action: String, cause: Throwable) = NetworkFailure(
    kind = NetworkFailure.Kind.TRANSPORT,
    userMessage = "Cannot reach $service — check the connection",
    detail = "$action → ${cause.javaClass.simpleName}: ${cause.message}",
    cause = cause,
)

private const val MAX_ATTEMPTS = 3
private val BACKOFF_MS = longArrayOf(1_000, 5_000, 20_000)

/**
 * Run an IDEMPOTENT network operation, retrying transient failures.
 *
 * Deliberately NOT a client-wide plugin: whether replaying a request is safe
 * is a property of the operation, not of the transport. Re-sending an
 * 8 MB PUT of the same object is harmless; re-sending CreateMultipartUpload
 * leaves an orphaned upload behind, and re-sending CompleteMultipartUpload or
 * a ref update races whatever the first attempt actually did. So call sites
 * opt in one by one.
 *
 * [block] must either return normally or throw NetworkFailure — use
 * [classify]/[transportFailure] inside it.
 */
suspend fun <T> retrying(
    service: String,
    action: String,
    maxAttempts: Int = MAX_ATTEMPTS,
    block: suspend () -> T,
): T {
    var lastFailure: NetworkFailure? = null
    repeat(maxAttempts) { attempt ->
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: NetworkFailure) {
            lastFailure = e
            if (!e.isTransient || attempt == maxAttempts - 1) throw e
            val waitMs = BACKOFF_MS[attempt.coerceAtMost(BACKOFF_MS.lastIndex)]
            Log.i(TAG, "$service $action failed (${e.kind}), attempt ${attempt + 1}/$maxAttempts, retrying in ${waitMs}ms")
            delay(waitMs)
        } catch (e: Exception) {
            // Anything not already classified: transport if it looks like it,
            // otherwise a genuine bug that must not be retried into oblivion.
            if (!e.isTransportFailure()) throw e
            val failure = transportFailure(service, action, e)
            lastFailure = failure
            if (attempt == maxAttempts - 1) throw failure
            val waitMs = BACKOFF_MS[attempt.coerceAtMost(BACKOFF_MS.lastIndex)]
            Log.i(TAG, "$service $action transport failure, attempt ${attempt + 1}/$maxAttempts, retrying in ${waitMs}ms")
            delay(waitMs)
        }
    }
    throw lastFailure ?: NetworkFailure(
        NetworkFailure.Kind.TRANSPORT, "Cannot reach $service", "$action: exhausted retries")
}

/**
 * Run a single network call, classifying failures but NOT retrying — for
 * operations whose replay is unsafe.
 */
suspend fun <T> classifying(
    service: String,
    action: String,
    block: suspend () -> T,
): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: NetworkFailure) {
    throw e
} catch (e: Exception) {
    if (e.isTransportFailure()) throw transportFailure(service, action, e) else throw e
}
