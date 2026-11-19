package com.monetizationos.sdk.decision

import com.monetizationos.sdk.identity.decodeJwtPayload
import com.monetizationos.sdk.number
import com.monetizationos.sdk.string
import com.monetizationos.sdk.webpaywall.percentDecode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What the SDK reads from an offer token's unverified payload. */
internal data class OfferTokenPayload(val expirationMsUtc: Long?)

/** Null when [token] is not three dot-separated parts whose middle decodes to a JSON object. */
internal fun decodeOfferToken(token: String): OfferTokenPayload? =
    decodeJwtPayload(token)?.let { OfferTokenPayload(it.number("expirationMsUtc")?.toLong()) }

/**
 * Walks `surfaceBehavior` and every `componentBehaviors.*` for the string under each `token` key, and for the tokens
 * a web paywall would redeem from each string ([markupOfferTokens]).
 */
internal fun discoverOfferTokens(decision: Decision): List<OfferTokenPayload> {
    val found = mutableListOf<OfferTokenPayload>()
    fun walk(node: JsonElement) {
        when (node) {
            is JsonObject -> {
                node.string("token")?.let(::decodeOfferToken)?.let { found += it }
                node.values.forEach(::walk)
            }
            is JsonArray -> node.forEach(::walk)
            is JsonPrimitive -> if (node.isString) markupOfferTokens(node.content).mapNotNullTo(found, ::decodeOfferToken)
        }
    }
    walk(decision.surfaceBehavior)
    decision.raw["componentBehaviors"]?.let(::walk)
    return found
}

/** The earliest known token expiry, which bounds how long the decision may be cached. */
internal fun earliestOfferExpiry(decision: Decision): Long? =
    discoverOfferTokens(decision).mapNotNull { it.expirationMsUtc }.minOrNull()

private const val REDEEM_URL = "mos://redeem"
private const val REDEEM_ATTRIBUTE = "data-mos-redeem"

/**
 * The tokens a web paywall would redeem from [text], in order: the first `token` query parameter of each
 * `mos://redeem` URL, percent-decoded, and each `data-mos-redeem` attribute value. Character references are decoded
 * first, as the HTML parser does. The iOS SDK applies the same rules (fixtures/offer-tokens-markup.json).
 */
internal fun markupOfferTokens(text: String): List<String> {
    val found = mutableListOf<String>()
    var i = 0
    while (i < text.length) {
        if (text.startsWithAscii(REDEEM_URL, i)) {
            val end = text.valueEnd(i)
            redeemUrlToken(decodeCharacterReferences(text.substring(i, end)))?.let { found += it }
            i = end
            continue
        }
        val attribute = if (text.startsWithAscii(REDEEM_ATTRIBUTE, i)) text.attributeValue(i + REDEEM_ATTRIBUTE.length) else null
        if (attribute != null) {
            decodeCharacterReferences(attribute.first).takeIf { it.isNotEmpty() }?.let { found += it }
            i = attribute.second
            continue
        }
        i++
    }
    return found
}

/**
 * The web paywall's rule for a `mos://` navigation: the host is `redeem`, and the token is the first `token` query
 * parameter, percent-decoded (`+` stays `+`), not empty.
 */
private fun redeemUrlToken(decoded: String): String? {
    val afterHost = decoded.takeWhile { !isDelimiter(it) }.substring(REDEEM_URL.length)
    if (afterHost.isNotEmpty() && afterHost[0] !in "/?#") return null
    val query = afterHost.substringBefore('#').substringAfter('?', "")
    val parameter = query.split('&').firstOrNull { it.substringBefore('=') == "token" } ?: return null
    return percentDecode(parameter.substringAfter('=', ""))?.takeIf { it.isNotEmpty() }
}

/** The value after `=` (whitespace allowed around it): quoted, or unquoted up to a delimiter. */
private fun String.attributeValue(from: Int): Pair<String, Int>? {
    var i = skipWhitespace(from)
    if (i >= length || this[i] != '=') return null
    i = skipWhitespace(i + 1)
    if (i >= length) return null
    val quote = this[i]
    if (quote == '"' || quote == '\'') {
        val close = indexOf(quote, i + 1)
        return if (close < 0) null else substring(i + 1, close) to close + 1
    }
    val end = valueEnd(i)
    return if (end > i) substring(i, end) to end else null
}

/** A URL or an unquoted value ends at HTML whitespace, a quote, `<`, `>`, a backtick or `\`. */
private fun isDelimiter(c: Char): Boolean = isWhitespace(c) || c in "\"'<>`\\"

private fun isWhitespace(c: Char): Boolean = c in "\t\n\u000C\r "

private fun String.valueEnd(from: Int): Int {
    var i = from
    while (i < length && !isDelimiter(this[i])) i++
    return i
}

private fun String.skipWhitespace(from: Int): Int {
    var i = from
    while (i < length && isWhitespace(this[i])) i++
    return i
}

/** ASCII case-insensitive, as URL schemes and HTML attribute names are. */
private fun String.startsWithAscii(marker: String, at: Int): Boolean =
    length - at >= marker.length && marker.indices.all { k ->
        val c = this[at + k]
        (if (c in 'A'..'Z') c + ('a' - 'A') else c) == marker[k]
    }

/** Decodes `&amp;`, `&lt;`, `&gt;`, `&quot;`, `&apos;` and numeric references; anything else stays as written. */
private fun decodeCharacterReferences(text: String): String {
    if ('&' !in text) return text
    val out = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        if (text[i] == '&') {
            var end = i + 1
            while (end < text.length && (text[end] in 'a'..'z' || text[end] in 'A'..'Z' || text[end] in '0'..'9' || text[end] == '#')) end++
            val decoded = if (end < text.length && text[end] == ';') characterReference(text.substring(i + 1, end)) else null
            if (decoded != null) {
                out.appendCodePoint(decoded)
                i = end + 1
                continue
            }
        }
        out.append(text[i])
        i++
    }
    return out.toString()
}

private fun characterReference(name: String): Int? = when (name) {
    "amp" -> '&'.code
    "lt" -> '<'.code
    "gt" -> '>'.code
    "quot" -> '"'.code
    "apos" -> '\''.code
    else -> if (!name.startsWith("#")) {
        null
    } else {
        val hex = name.startsWith("#x") || name.startsWith("#X")
        // Not a surrogate, not beyond U+10FFFF.
        name.substring(if (hex) 2 else 1).toIntOrNull(if (hex) 16 else 10)?.takeIf { it in 1..0x10FFFF && it !in 0xD800..0xDFFF }
    }
}
