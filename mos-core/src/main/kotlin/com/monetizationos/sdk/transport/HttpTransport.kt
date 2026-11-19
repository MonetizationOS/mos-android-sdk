package com.monetizationos.sdk.transport

/**
 * `body` is null when nothing is sent (GET, DELETE, or a bodiless call). [toString] redacts the body, which can carry a
 * `userJwt`, and the values of the identity and credential headers, so a transport that logs requests leaks no token.
 */
public data class HttpRequest(
    val url: String,
    val headers: Map<String, String>,
    val body: String?,
    val timeoutMs: Long,
    val method: String = "POST",
) {
    override fun toString(): String {
        val shownHeaders = headers.mapValues { (name, value) -> if (name.lowercase() in REDACTED_HEADERS) REDACTED else value }
        val shownBody = body?.let { "<${it.encodeToByteArray().size} bytes redacted>" }
        return "HttpRequest(url=$url, headers=$shownHeaders, body=$shownBody, timeoutMs=$timeoutMs, method=$method)"
    }
}

private const val REDACTED = "<redacted>"

// Lowercased; the same set as the iOS SDK's HTTPRequest.
private val REDACTED_HEADERS = setOf(
    "authorization", "proxy-authorization", "cookie", "x-mos-key", "x-mos-user-jwt", "x-mos-anonymous-identifier",
)

/** Any HTTP response. [headers] as received; repeated headers joined with `, `. */
public class HttpResponse(
    public val status: Int,
    public val body: ByteArray,
    public val headers: Map<String, String> = emptyMap(),
) {
    public constructor(status: Int, body: String, headers: Map<String, String> = emptyMap()) :
        this(status, body.encodeToByteArray(), headers)

    /** The body as UTF-8, malformed bytes replaced. */
    public val text: String get() = body.decodeToString()

    override fun toString(): String = "HttpResponse(status=$status, bytes=${body.size}, headers=$headers)"
}

/**
 * One request, no retries. Throw on a network failure; a timeout may be a `java.net.SocketTimeoutException`.
 * Honour cancellation promptly: the SDK cancels the call when its end-to-end timeout elapses.
 */
public interface HttpTransport {
    public suspend fun send(request: HttpRequest): HttpResponse
}
