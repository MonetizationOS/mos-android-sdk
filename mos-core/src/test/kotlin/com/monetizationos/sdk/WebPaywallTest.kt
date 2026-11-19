package com.monetizationos.sdk

import com.monetizationos.sdk.logging.LogLevel
import com.monetizationos.sdk.webpaywall.PAGE_SCRIPT
import com.monetizationos.sdk.webpaywall.WebPaywall
import com.monetizationos.sdk.webpaywall.WebPaywallNavigation
import com.monetizationos.sdk.webpaywall.WebPaywallNavigation.Blocked
import com.monetizationos.sdk.webpaywall.WebPaywallNavigation.Close
import com.monetizationos.sdk.webpaywall.WebPaywallNavigation.OpenUrl
import com.monetizationos.sdk.webpaywall.WebPaywallNavigation.Redeem
import com.monetizationos.sdk.webpaywall.parseProductIds
import com.monetizationos.sdk.webpaywall.priceFillScript
import com.monetizationos.sdk.webpaywall.scriptJsonLiteral
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebPaywallTest {
    private fun paywall(h: Harness = Harness(), provider: PriceProvider? = null) = WebPaywall(h.mos(), provider)

    private fun navigate(url: String, isMainFrame: Boolean = true, isInitialLoad: Boolean = false): WebPaywallNavigation =
        paywall().navigation(url, isMainFrame, isInitialLoad)

    @Test
    fun `navigation classification`() {
        val t = offerToken(null)
        assertEquals(WebPaywallNavigation.Allow, navigate("about:blank", isInitialLoad = true), "initial load")
        assertEquals(Redeem(t), navigate("mos://redeem?token=$t"))
        assertEquals(Redeem("a b+c/é"), navigate("mos://redeem?token=a%20b+c%2F%C3%A9"), "percent-decoded, + kept")
        assertEquals(Redeem("t1"), navigate("MOS://Redeem?x=1&token=t1&token=t2#frag"), "first token; scheme and host case-insensitive")
        assertEquals(Close, navigate("mos://close"))
        assertEquals(WebPaywallNavigation.Allow, navigate("about:blank#plans"), "an in-page anchor")
        assertEquals(OpenUrl("https://example.test/terms?a=1"), navigate("https://example.test/terms?a=1"))
        assertEquals(OpenUrl("http://example.test/"), navigate("http://example.test/"))

        val blocked = mapOf(
            "mos://redeem?token=" to "mos",
            "mos://redeem" to "mos",
            "mos://redeem?tok=x" to "mos",
            "mos://redeem?token=%zz" to "mos",
            "mos://redeem?token=%2" to "mos",
            "mos://buy?token=x" to "mos",
            "mos:close" to "mos",
            "file:///etc/hosts" to "file",
            "content://com.x/secret" to "content",
            "data:text/html,<p>x</p>" to "data",
            "javascript:alert(1)" to "javascript",
            "JavaScript:alert(1)" to "javascript",
            "intent://scan#Intent;scheme=x;end" to "intent",
            "about:blank" to "about",
            "about:blank?x#plans" to "about",
            "about:srcdoc#plans" to "about",
            "tel:123" to "tel",
            "no-scheme" to "",
        )
        for ((url, scheme) in blocked) assertEquals(Blocked(scheme), navigate(url), url)

        // Sub-frame navigations are blocked whatever they are.
        assertEquals(Blocked("https"), navigate("https://example.test/", isMainFrame = false))
        assertEquals(Blocked("mos"), navigate("mos://redeem?token=$t", isMainFrame = false))
        assertEquals(Blocked("mos"), navigate("mos://close", isMainFrame = false))
        assertEquals(Blocked("about"), navigate("about:blank#plans", isMainFrame = false))
    }

    @Test
    fun `navigation logs blocked schemes and redeem without the token`() {
        val h = Harness()
        val paywall = paywall(h)
        val t = offerToken(NOW)

        paywall.loaded()
        paywall.navigation("mos://redeem?token=$t", isMainFrame = true)
        paywall.navigation("javascript:alert(1)", isMainFrame = true)
        paywall.navigation("https://example.test/", isMainFrame = true)

        assertEquals(listOf("webpaywall:loaded", "webpaywall:redeem", "webpaywall:navigation-blocked"), h.logs.codes())
        val blocked = h.logs.events.last()
        assertEquals(LogLevel.Warn, blocked.level)
        assertEquals(JsonPrimitive("javascript"), blocked.context["scheme"])
        assertTrue(h.logs.events.none { t in it.toString() })
        assertFalse(t in Redeem(t).toString(), "the token never reaches a log via toString")
    }

    @Test
    fun `document shape`() {
        val html = "<style>@scope (#mos-1) {}</style><div id=\"mos-1\"><span data-mos-product=\"p1\">£4.99</span></div>"
        val document = paywall().document(html, baseCss = "body{margin:0}")

        assertTrue(document.startsWith("<!doctype html>\n"))
        val order = listOf(
            "<meta charset=\"utf-8\">",
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">",
            "color-scheme:light dark",
            "<style>body{margin:0}</style>",
            "<style>html:not(.mos-prices-ready) [data-mos-product]{visibility:hidden!important}</style>",
            "<script>$PAGE_SCRIPT</script>",
            "</head>",
            "<body>\n$html\n</body>",
        )
        val positions = order.map { document.indexOf(it) }
        assertTrue(positions.all { it >= 0 }, positions.toString())
        assertEquals(positions.sorted(), positions, "in spec order")
        assertTrue("[data-mos-redeem]" in document && "mos://redeem?token=" in document && "encodeURIComponent" in document)

        assertTrue("<style></style>" in paywall().document("<p>x</p>"), "no base CSS, an empty style element")
        val hostile = paywall().document("<p>x</p>", baseCss = "a{}</style><script>alert(1)</script>")
        assertFalse("</style><script>alert" in hostile, "baseCss cannot close its style element")
    }

    @Test
    fun `price script embeds prices as an escaped JSON literal`() {
        val nasty = "</script><script>alert(\"x\\\\y\")</script>' <!--"
        val script = priceFillScript(
            mapOf(
                nasty to StorePrice(displayPrice = "£4.99 \"q\" \\ </SCRIPT>", displayPeriod = "month", introductoryDisplayPrice = nasty),
                "plain" to StorePrice("$1"),
            ),
        )

        assertFalse("</script" in script.lowercase(), script)
        assertFalse("<!--" in script)
        assertFalse(' ' in script)
        val literal = script.substring(script.lastIndexOf(")(") + 2, script.length - 1)
        val decoded = MosJson.parseToJsonElement(literal)
        assertEquals(
            buildJsonArray {
                addJsonArray {
                    add(nasty)
                    addJsonObject {
                        put("display-price", "£4.99 \"q\" \\ </SCRIPT>")
                        put("display-period", "month")
                        put("intro-display-price", nasty)
                    }
                }
                addJsonArray {
                    add("plain")
                    addJsonObject { put("display-price", "$1") }
                }
            },
            decoded,
            "the literal round-trips to exactly the provider's values",
        )
        assertEquals(scriptJsonLiteral(decoded), literal)
        assertTrue(script.endsWith(")"))
        assertTrue("data-mos-price-missing" in script && "'display-price'" in script)
    }

    @Test
    fun `price script cannot change prototypes`() {
        val script = priceFillScript(mapOf("__proto__" to StorePrice("£1"), "constructor" to StorePrice("£2")))
        // Ids arrive as array entries and land in a prototype-less map, never as object-literal keys.
        assertTrue("prices = Object.create(null)" in script)
        assertTrue("""[["__proto__",{"display-price":"£1"}],["constructor",{"display-price":"£2"}]]""" in script, script)
        assertFalse(Regex("""\{"__proto__"\s*:""").containsMatchIn(script))
    }

    @Test
    fun `reveal timeout`() {
        // Hidden until the class is added; the page adds it itself after 2 s, the fill adds it when done.
        assertTrue("setTimeout(function () { document.documentElement.classList.add('mos-prices-ready'); }, 2000)" in PAGE_SCRIPT)
        assertTrue("classList.add('mos-prices-ready')" in priceFillScript(emptyMap()))
    }

    @Test
    fun `price fill asks the provider for collected products and logs counts`() = runTest {
        val h = Harness()
        val asked = mutableListOf<Set<String>>()
        val paywall = paywall(h) { ids ->
            asked += ids
            mapOf("p1" to StorePrice("£4.99"), "unasked" to StorePrice("£1"))
        }

        val script = paywall.priceScript("""["p1","p2","p1",""]""")
        paywall.pricesFilled("""{"filled":1,"missing":2}""")

        assertEquals(listOf(setOf("p1", "p2")), asked)
        assertTrue("\"p1\"" in script && "unasked" !in script)
        val logged = h.logs.events.single { it.code == "webpaywall:prices-filled" }
        assertEquals(JsonObject(mapOf("filled" to JsonPrimitive(1), "missing" to JsonPrimitive(2))), logged.context)
    }

    @Test
    fun `no provider, no products or a throwing provider mark everything missing, reveal and log filled 0`() = runTest {
        val h = Harness()
        val markAll = priceFillScript(emptyMap())
        assertTrue("data-mos-price-missing" in markAll && "mos-prices-ready" in markAll)
        assertEquals(markAll, paywall(h).priceScript("""["p1"]"""), "no provider")
        var calls = 0
        val counting = paywall(h) { calls++; mapOf("p1" to StorePrice("£1")) }
        assertEquals(markAll, counting.priceScript("[]"), "no product ids")
        assertEquals(markAll, counting.priceScript(null))
        assertEquals(markAll, counting.priceScript("null"))
        assertEquals(0, calls)
        val throwing = paywall(h) { error("store down") }
        assertEquals(markAll, throwing.priceScript("""["p1"]"""), "a throwing provider")

        // The page reports what the script did: nothing filled, every priced element missing.
        paywall(h).pricesFilled("""{"filled":0,"missing":3}""")
        val logged = h.logs.events.single { it.code == "webpaywall:prices-filled" }
        assertEquals(JsonObject(mapOf("filled" to JsonPrimitive(0), "missing" to JsonPrimitive(3))), logged.context)
        paywall(h).pricesFilled("null")
        assertEquals(1, h.logs.codes().count { it == "webpaywall:prices-filled" })
    }

    @Test
    fun `collected product ids parse leniently`() {
        assertEquals(setOf("a", "b"), parseProductIds("""["a",1,null,"b","a"]"""))
        assertEquals(emptySet(), parseProductIds("""{"a":1}"""))
        assertEquals(emptySet(), parseProductIds("garbage"))
    }
}
