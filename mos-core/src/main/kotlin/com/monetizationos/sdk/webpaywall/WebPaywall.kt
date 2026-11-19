package com.monetizationos.sdk.webpaywall

import com.monetizationos.sdk.InternalMosApi
import com.monetizationos.sdk.MOS
import com.monetizationos.sdk.MosJson
import com.monetizationos.sdk.PriceProvider
import com.monetizationos.sdk.StorePrice
import com.monetizationos.sdk.number
import com.monetizationos.sdk.parseStrictJson
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray

/** What a web paywall view does with a navigation. */
@InternalMosApi
public sealed interface WebPaywallNavigation {
    /** Let the web view load it (the initial document only). */
    public data object Allow : WebPaywallNavigation

    /** `mos://redeem?token=<t>`: call `mos.redeem(token)`. */
    public class Redeem(public val token: String) : WebPaywallNavigation {
        override fun equals(other: Any?): Boolean = other is Redeem && token == other.token
        override fun hashCode(): Int = token.hashCode()
        override fun toString(): String = "Redeem"
    }

    /** `mos://close`. */
    public data object Close : WebPaywallNavigation

    /** A main-frame `http`/`https` link: hand it to the app and stay put. */
    public data class OpenUrl(val url: String) : WebPaywallNavigation

    /** Anything else. [scheme] is lowercased, empty when the URL has none. */
    public data class Blocked(val scheme: String) : WebPaywallNavigation
}

/**
 * The platform-independent half of mos-android's web paywall view: the document, navigation rules, price fill and
 * logs. The view loads [document], asks [navigation] for every navigation, evaluates [COLLECT_PRODUCT_IDS_SCRIPT]
 * then [priceScript] after load, and reports the result to [pricesFilled].
 */
@InternalMosApi
public class WebPaywall(private val mos: MOS, private val priceProvider: PriceProvider? = null) {
    public fun document(html: String, baseCss: String? = null): String = webPaywallDocument(html, baseCss)

    public fun loaded() {
        mos.logger.info("webpaywall:loaded", "Web paywall loaded.")
    }

    /** Classifies and logs; the view acts on the result. The initial document load is always allowed. */
    public fun navigation(url: String, isMainFrame: Boolean, isInitialLoad: Boolean = false): WebPaywallNavigation {
        val navigation = classifyWebPaywallNavigation(url, isMainFrame, isInitialLoad)
        when (navigation) {
            is WebPaywallNavigation.Redeem -> mos.logger.info("webpaywall:redeem", "Web paywall asked to redeem an offer.")
            is WebPaywallNavigation.Blocked ->
                mos.logger.warn("webpaywall:navigation-blocked", "Web paywall navigation blocked.", "scheme" to navigation.scheme)
            else -> Unit
        }
        return navigation
    }

    /** The web content process died; the view closes, or ([closing] false) loads the page afresh in the next view. */
    public fun processTerminated(closing: Boolean) {
        mos.logger.warn(
            "webpaywall:process-terminated", "Web paywall content process terminated.",
            "action" to if (closing) "close" else "reload",
        )
    }

    /** The script to evaluate once [COLLECT_PRODUCT_IDS_SCRIPT] returned [collectResult]. No prices fill nothing. */
    public suspend fun priceScript(collectResult: String?): String {
        val productIds = parseProductIds(collectResult)
        val provider = priceProvider
        if (provider == null || productIds.isEmpty()) return priceFillScript(emptyMap())
        val prices = try {
            provider.prices(productIds)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyMap()
        }
        return priceFillScript(prices.filterKeys { it in productIds })
    }

    /** Logs the counts the price script returned; ignores anything else. */
    public fun pricesFilled(fillResult: String?) {
        val counts = fillResult?.let(::parseStrictJson) as? JsonObject ?: return
        val filled = counts.number("filled")?.toInt() ?: return
        val missing = counts.number("missing")?.toInt() ?: return
        mos.logger.info("webpaywall:prices-filled", "Web paywall prices filled.", "filled" to filled, "missing" to missing)
    }

    public companion object {
        /** Evaluates to the distinct `data-mos-product` values on the page, as a JSON array. */
        public val COLLECT_PRODUCT_IDS_SCRIPT: String = shared("collect-product-ids.js")
    }
}

internal const val DOCUMENT_URL = "about:blank"

/**
 * A web paywall resource; the iOS SDK ships the same files. The path is absolute because R8 may rename and move this
 * class in a minified app, while resources keep their paths.
 */
private fun shared(name: String): String =
    checkNotNull(WebPaywall::class.java.getResourceAsStream("$RESOURCE_DIR/$name")) { "Missing web paywall resource $name." }
        .use { it.readBytes().decodeToString() }

private const val RESOURCE_DIR = "/com/monetizationos/sdk/webpaywall"

private val TEMPLATE = shared("document.html")

internal val PAGE_SCRIPT = shared("page.js")

private val FILL_PRICES_FUNCTION = shared("fill-prices.js").trim()

// `<\/` is `</` to CSS, but cannot close the style element early. Splitting on the markers rather than
// replacing them means inserted text is never searched.
internal fun webPaywallDocument(html: String, baseCss: String?): String {
    val (head, afterCss) = TEMPLATE.splitOnce("/*base-css*/")
    val (styles, afterScript) = afterCss.splitOnce("/*page-script*/")
    val (beforeHtml, tail) = afterScript.splitOnce("<!--html-->")
    return head + baseCss.orEmpty().replace("</", "<\\/") + styles + PAGE_SCRIPT + beforeHtml + html + tail
}

private fun String.splitOnce(marker: String): Pair<String, String> = substringBefore(marker) to substringAfter(marker)

internal fun classifyWebPaywallNavigation(url: String, isMainFrame: Boolean, isInitialLoad: Boolean): WebPaywallNavigation {
    if (isInitialLoad) return WebPaywallNavigation.Allow
    val scheme = schemeOf(url)
    if (!isMainFrame) return WebPaywallNavigation.Blocked(scheme)
    // An in-page anchor of the loaded document, which has a null-origin about:blank URL.
    if ('#' in url && url.substringBefore('#') == DOCUMENT_URL) return WebPaywallNavigation.Allow
    return when (scheme) {
        "http", "https" -> WebPaywallNavigation.OpenUrl(url)
        "mos" -> mosNavigation(url.substring(scheme.length + 1)) ?: WebPaywallNavigation.Blocked(scheme)
        else -> WebPaywallNavigation.Blocked(scheme)
    }
}

private val schemePattern = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")

private fun schemeOf(url: String): String = schemePattern.find(url.trim())?.groupValues?.get(1)?.lowercase().orEmpty()

/** [rest] is everything after `mos:`. */
private fun mosNavigation(rest: String): WebPaywallNavigation? {
    if (!rest.startsWith("//")) return null
    val afterSlashes = rest.substring(2)
    val host = afterSlashes.takeWhile { it != '/' && it != '?' && it != '#' }.lowercase()
    val query = afterSlashes.substringBefore('#').substringAfter('?', "")
    return when (host) {
        "close" -> WebPaywallNavigation.Close
        "redeem" -> query.split('&')
            .firstOrNull { it.substringBefore('=') == "token" }
            ?.substringAfter('=', "")
            ?.let(::percentDecode)
            ?.takeIf { it.isNotEmpty() }
            ?.let { WebPaywallNavigation.Redeem(it) }
        else -> null
    }
}

/** Strict: `+` stays `+`, and a malformed escape is null rather than a guess. */
internal fun percentDecode(value: String): String? {
    val input = value.encodeToByteArray()
    val out = java.io.ByteArrayOutputStream(input.size)
    var i = 0
    while (i < input.size) {
        val b = input[i].toInt()
        if (b == '%'.code) {
            if (i + 2 >= input.size) return null
            val high = hexDigit(input[i + 1].toInt())
            val low = hexDigit(input[i + 2].toInt())
            if (high < 0 || low < 0) return null
            out.write(high * 16 + low)
            i += 3
        } else {
            out.write(b)
            i++
        }
    }
    return out.toByteArray().decodeToString()
}

private fun hexDigit(c: Int): Int = when (c) {
    in '0'.code..'9'.code -> c - '0'.code
    in 'a'.code..'f'.code -> c - 'a'.code + 10
    in 'A'.code..'F'.code -> c - 'A'.code + 10
    else -> -1
}

internal fun parseProductIds(collectResult: String?): Set<String> {
    val array = collectResult?.let(::parseStrictJson) as? JsonArray ?: return emptySet()
    return array.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.takeIf(String::isNotEmpty) }.toSet()
}

/** The shared fill function, called with `[id, {field: value}]` entries as a script-safe JSON literal. */
internal fun priceFillScript(prices: Map<String, StorePrice>): String {
    val json = buildJsonArray {
        for ((id, price) in prices.toSortedMap()) {
            addJsonArray {
                add(id)
                addJsonObject {
                    put("display-price", price.displayPrice)
                    price.displayPeriod?.let { put("display-period", it) }
                    price.introductoryDisplayPrice?.let { put("intro-display-price", it) }
                }
            }
        }
    }
    return FILL_PRICES_FUNCTION + "(" + scriptJsonLiteral(json) + ")"
}

/**
 * JSON that is also a safe JS literal anywhere, including inside a `<script>` element: `<`, `>` and `&`
 * become `\u` escapes so `</script>` or `<!--` cannot end the element, and U+2028/U+2029 are escaped.
 */
internal fun scriptJsonLiteral(value: JsonElement): String =
    MosJson.encodeToString(JsonElement.serializer(), value)
        .replace("<", "\\u003c")
        .replace(">", "\\u003e")
        .replace("&", "\\u0026")
        .replace(" ", "\\u2028")
        .replace(" ", "\\u2029")
