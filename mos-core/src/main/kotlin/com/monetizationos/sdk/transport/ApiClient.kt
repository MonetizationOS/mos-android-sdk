package com.monetizationos.sdk.transport

import com.monetizationos.sdk.Clock
import com.monetizationos.sdk.Failure
import com.monetizationos.sdk.FailureReason
import com.monetizationos.sdk.MOS_VERSION
import com.monetizationos.sdk.MosJson
import com.monetizationos.sdk.logging.Logger
import com.monetizationos.sdk.number
import com.monetizationos.sdk.parseStrictJson
import com.monetizationos.sdk.rethrowIfCancelled
import com.monetizationos.sdk.string
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.net.SocketTimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal sealed interface ApiOutcome {
    /** A 2xx whose body parsed and is not an error envelope. */
    data class Ok(val status: Int, val body: JsonElement) : ApiOutcome
    data class Failed(val failure: Failure) : ApiOutcome
}

/** Any HTTP response, or the failure that stopped one arriving. */
internal sealed interface HttpOutcome {
    data class Response(val response: HttpResponse) : HttpOutcome
    data class Failed(val failure: Failure) : HttpOutcome
}

internal val FAIL_FAST_TIMEOUT: Duration = 2.seconds
internal const val FAIL_FAST_WINDOW_MS: Long = 10L * 60 * 1000

/**
 * Contract headers and response classification; never retries. A timeout enters fail-fast: for [FAIL_FAST_WINDOW_MS] from
 * then, or until a response, calls use min(timeout, 2s), so an app on a dead network stops waiting the full timeout on
 * every screen. Timeouts inside the window do not extend it. A call sent with `failFast = false` (redemptions, endpoints)
 * neither uses nor enters it, but its response still ends it.
 */
internal class ApiClient(
    baseUrl: String,
    private val publicKey: String,
    private val userAgent: String,
    private val transport: HttpTransport,
    private val timeout: Duration,
    private val clock: Clock,
    private val logger: Logger,
) {
    private val baseUrl = baseUrl.trimEnd('/')
    private val tierLock = Any()
    private var failFastSince: Long? = null

    /** A contract call: `Authorization` auth, JSON body, response classified into success or failure. */
    suspend fun post(path: String, body: JsonElement): ApiOutcome = when (val outcome = postRaw(path, body)) {
        is HttpOutcome.Failed -> ApiOutcome.Failed(outcome.failure)
        is HttpOutcome.Response -> classify(outcome.response)
    }

    /** [post] without classifying, for a caller that reads a non-2xx body itself. */
    suspend fun postRaw(path: String, body: JsonElement, failFast: Boolean = true): HttpOutcome {
        val headers = mapOf(
            "Authorization" to "Bearer $publicKey",
            "Content-Type" to "application/json",
            "User-Agent" to userAgent,
            "X-MOS-Mobile-Version" to "android/$MOS_VERSION",
        )
        return send("POST", path, headers, MosJson.encodeToString(JsonElement.serializer(), body), failFast = failFast)
    }

    /** The headers every org endpoint call carries, before the host's own. */
    fun endpointHeaders(): Map<String, String> = mapOf(
        "X-MOS-Key" to "Bearer $publicKey",
        "User-Agent" to userAgent,
        "X-MOS-Mobile-Version" to "android/$MOS_VERSION",
        "Accept" to "application/json",
    )

    /**
     * One request, any status. With [explicitTimeout] the limit is exact and a timeout does not enter the fail-fast tier;
     * without it the tier applies, unless [failFast] is false: then the limit is the full timeout and a timeout does not
     * enter the tier either.
     */
    suspend fun send(
        method: String,
        path: String,
        headers: Map<String, String>,
        body: String?,
        explicitTimeout: Duration? = null,
        failFast: Boolean = true,
    ): HttpOutcome {
        val tiered = failFast && explicitTimeout == null
        val limit = explicitTimeout ?: if (tiered) currentTimeout() else timeout
        val request = HttpRequest(baseUrl + path, headers, body, limit.inWholeMilliseconds, method)
        val response = try {
            // The transport must be cancellable for this to bound the call end to end.
            withTimeoutOrNull(limit) { transport.send(request) } ?: return timedOut(limit, tiered)
        } catch (_: SocketTimeoutException) {
            return timedOut(limit, tiered)
        } catch (e: Exception) {
            rethrowIfCancelled(e)
            return HttpOutcome.Failed(Failure(FailureReason.RequestFailed, message = "Request failed: ${e.javaClass.simpleName}."))
        }
        val recovered = synchronized(tierLock) { (failFastSince != null).also { failFastSince = null } }
        if (recovered) logger.info("transport:recovered", "A response arrived; back to the full request timeout.", "reason" to "response")
        return HttpOutcome.Response(response)
    }

    private fun currentTimeout(): Duration {
        val now = clock.nowMs()
        var expired = false
        val degraded = synchronized(tierLock) {
            val since = failFastSince
            if (since != null && now - since >= FAIL_FAST_WINDOW_MS) {
                failFastSince = null
                expired = true
            }
            failFastSince != null
        }
        if (expired) logger.info("transport:recovered", "Ten minutes in fail-fast; back to the full request timeout.", "reason" to "expired")
        return if (degraded) minOf(timeout, FAIL_FAST_TIMEOUT) else timeout
    }

    private fun timedOut(limit: Duration, entersTier: Boolean): HttpOutcome {
        val now = clock.nowMs()
        // Only the timeout that enters starts the window: a network slower than 2s must get the full timeout back.
        val entering = entersTier && synchronized(tierLock) { (failFastSince == null).also { if (it) failFastSince = now } }
        if (entering) {
            logger.info(
                "transport:degraded", "Request timed out; using the fail-fast timeout for ten minutes or until a response arrives.",
                "timeoutMs" to minOf(timeout, FAIL_FAST_TIMEOUT).inWholeMilliseconds,
            )
        }
        return HttpOutcome.Failed(Failure(FailureReason.Timeout, message = "Request timed out after ${limit.inWholeMilliseconds}ms."))
    }
}

private fun failed(reason: FailureReason, message: String, status: Int? = null, statusCode: Int? = null) =
    ApiOutcome.Failed(Failure(reason, status, statusCode, message))

/** Error envelope first (even on a 200), then HTTP status, then JSON validity. */
internal fun classify(response: HttpResponse): ApiOutcome {
    val status = response.status
    val success = status in 200..299
    val text = response.text
    val json = if (text.isBlank()) null else parseStrictJson(text)
    if (json is JsonObject && json.string("status") == "error") {
        val statusCode = json.number("statusCode")?.toInt() ?: status
        val message = json.string("message") ?: "API error $statusCode."
        // Either code decides: an HTTP 401 or 429 counts whatever the envelope says, as on iOS.
        return when {
            statusCode == 429 || status == 429 -> failed(FailureReason.RateLimited, message, status, statusCode)
            statusCode == 401 || status == 401 -> failed(FailureReason.Unauthorized, message, status, statusCode)
            else -> failed(FailureReason.ApiError, message, status, statusCode)
        }
    }
    return when {
        status == 429 -> failed(FailureReason.RateLimited, "Rate limited (HTTP 429).", status)
        status == 401 -> failed(FailureReason.Unauthorized, "Unauthorized (HTTP 401).", status)
        !success -> failed(FailureReason.HttpError, "HTTP $status.", status)
        json != null -> ApiOutcome.Ok(status, json)
        else -> failed(FailureReason.InvalidJson, "Response body is not JSON (HTTP $status).", status)
    }
}
