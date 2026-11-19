package com.monetizationos.sdk

import kotlinx.serialization.json.JsonElement
import kotlin.time.Duration

public enum class EndpointMethod(public val value: String) {
    Get("GET"),
    Post("POST"),
    Put("PUT"),
    Delete("DELETE"),
    Patch("PATCH"),
}

/** Any HTTP response is [Response], whatever its status. [Failed] only when no response arrived. */
public sealed interface EndpointResult {
    @ConsistentCopyVisibility
    public data class Response internal constructor(val response: EndpointResponse) : EndpointResult

    @ConsistentCopyVisibility
    public data class Failed internal constructor(val failure: Failure) : EndpointResult
}

/**
 * What an org endpoint returned. Header names are lowercased. Equality compares the body by content.
 * [json] is the parsed body when it is valid JSON, else null; [text] is the body as UTF-8, malformed bytes replaced.
 */
public class EndpointResponse(public val status: Int, headers: Map<String, String>, public val body: ByteArray) {
    public val headers: Map<String, String> = lowercaseHeaders(headers)
    public val json: JsonElement? = if (body.isEmpty()) null else parseStrictJson(body.decodeToString())
    public val text: String get() = body.decodeToString()
    public val isSuccess: Boolean get() = status in 200..299

    override fun equals(other: Any?): Boolean =
        other is EndpointResponse && status == other.status && headers == other.headers && body.contentEquals(other.body)

    override fun hashCode(): Int = (status * 31 + headers.hashCode()) * 31 + body.contentHashCode()

    override fun toString(): String = "EndpointResponse(status=$status, bytes=${body.size}, headers=${headers.keys})"
}

/** An app's endpoints under one namespace. Holds nothing but the namespace; every call goes through [MOS.callEndpoint]. */
public class AppClient internal constructor(private val mos: MOS, public val namespace: String) {
    public suspend fun call(
        path: String,
        method: EndpointMethod = EndpointMethod.Post,
        body: JsonElement? = null,
        query: Map<String, String>? = null,
        headers: Map<String, String>? = null,
        timeout: Duration? = null,
    ): EndpointResult = mos.callEndpoint(path, method, body, query, headers, namespace, timeout)

    public suspend fun call(
        pathSegments: List<String>,
        method: EndpointMethod = EndpointMethod.Post,
        body: JsonElement? = null,
        query: Map<String, String>? = null,
        headers: Map<String, String>? = null,
        timeout: Duration? = null,
    ): EndpointResult = mos.callEndpoint(pathSegments, method, body, query, headers, namespace, timeout)

    override fun toString(): String = "AppClient(namespace=$namespace)"
}

/** Names the SDK sets or reserves on endpoint calls, identity headers included; a host header with one of these names is dropped. */
internal val SDK_OWNED_ENDPOINT_HEADERS: Set<String> = setOf(
    "x-mos-key", "user-agent", "x-mos-mobile-version", "content-type", "content-length",
    "x-mos-user-jwt", "x-mos-anonymous-identifier",
)

private fun lowercaseHeaders(headers: Map<String, String>): Map<String, String> {
    val merged = LinkedHashMap<String, String>()
    for ((name, value) in headers) {
        val key = name.lowercase()
        merged[key] = merged[key]?.let { "$it, $value" } ?: value
    }
    return merged
}

/** RFC 3986 unreserved characters kept, everything else percent-encoded as UTF-8. */
internal fun percentEncode(value: String): String = buildString {
    for (byte in value.encodeToByteArray()) {
        val c = byte.toInt() and 0xff
        if (c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code || c.toChar() in "-._~") {
            append(c.toChar())
        } else {
            append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 0x0f])
        }
    }
}

/** Empty, `.` and `..` would be normalised away by servers and proxies, changing which endpoint is called. */
internal fun isRefusedSegment(segment: String): Boolean = segment.isEmpty() || segment == "." || segment == ".."

/** Segments percent-encoded and joined; null when there is none or one is refused. */
internal fun encodeEndpointPath(segments: List<String>): String? {
    if (segments.isEmpty() || segments.any(::isRefusedSegment)) return null
    return segments.joinToString("/") { percentEncode(it) }
}

internal fun encodeQuery(query: Map<String, String>?): String =
    query.orEmpty().entries.sortedBy { it.key }
        .joinToString("&") { (key, value) -> "${percentEncode(key)}=${percentEncode(value)}" }
