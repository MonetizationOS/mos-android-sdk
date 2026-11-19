package com.monetizationos.sdk

import com.monetizationos.sdk.decision.Decision
import com.monetizationos.sdk.decision.Experience
import com.monetizationos.sdk.decision.Offer
import com.monetizationos.sdk.decision.OfferSlot
import com.monetizationos.sdk.decision.offerSlots
import com.monetizationos.sdk.decision.parseDecision
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class ExperienceTest {
    private val expiry = 1_893_456_000_000L
    private val token = offerToken(expiry)

    private fun json(text: String): JsonElement = MosJson.parseToJsonElement(text)

    private fun slotsJson(): String = """[
        {"type":"single","offer":{"id":"offer_1","presentation":{"title":"Monthly","storeProductId":"com.x.monthly"},"redemptionSchema":"custom:purchase","token":"$token"}},
        {"type":"single","offer":null}
    ]"""

    @Test
    fun `json facet object decodes as native without mos-offer-slots`() {
        val experience = Experience.decode(json("""{"headline":"Subscribe","cta":{"label":"Go"},"mos-offer-slots":${slotsJson()}}"""))

        val native = assertIs<Experience.Native>(experience)
        assertEquals(json("""{"headline":"Subscribe","cta":{"label":"Go"}}"""), native.content)
        assertNull(native.schema)
        assertEquals(2, native.offers.size)
        assertEquals("offer_1", native.offers[0].offer?.id)
        assertEquals(OfferSlot("single", null), native.offers[1])
    }

    @Test
    fun `json facet empty object is no experience, an object without slots has no offers`() {
        assertNull(Experience.decode(JsonObject(emptyMap())))
        val native = assertIs<Experience.Native>(Experience.decode(json("""{"headline":"Hi"}""")))
        assertEquals(emptyList(), native.offers)
    }

    @Test
    fun `web content html and text items concatenate in order with text escaped`() {
        val experience = Experience.decode(
            json(
                """[
                {"type":"html","content":"<style>@scope (#mos-1) { p { color: red } }</style><div id=\"mos-1\">"},
                {"type":"text","content":"Tom & Jerry <b>\"quoted\"</b> it's"},
                {"type":"html","content":"</div>"}
            ]""",
            ),
        )

        val web = assertIs<Experience.Web>(experience)
        assertEquals(
            "<style>@scope (#mos-1) { p { color: red } }</style><div id=\"mos-1\">" +
                "Tom &amp; Jerry &lt;b&gt;&quot;quoted&quot;&lt;/b&gt; it&#39;s</div>",
            web.html,
        )
        assertEquals(emptyList(), web.offers)
    }

    @Test
    fun `web content single element decodes as native with schema`() {
        val experience = Experience.decode(
            json("""[{"type":"element","schema":"paywall-card","props":{"headline":"Hi","mos-offer-slots":${slotsJson()}}}]"""),
        )

        val native = assertIs<Experience.Native>(experience)
        assertEquals("paywall-card", native.schema)
        assertEquals(json("""{"headline":"Hi"}"""), native.content)
        assertEquals(listOf("offer_1", null), native.offers.map { it.offer?.id })
    }

    @Test
    fun `web content empty, mixed, unknown and malformed arrays are not experiences`() {
        val element = """{"type":"element","schema":"s","props":{}}"""
        val html = """{"type":"html","content":"<p>x</p>"}"""
        for (
        value in listOf(
            "[]",
            "[$element,$html]",
            "[$html,$element]",
            "[$element,$element]",
            """[{"type":"element","props":{}}]""",
            """[{"type":"element","schema":"s","props":[]}]""",
            """[{"type":"element","schema":"s"}]""",
            """[{"type":"markdown","content":"x"}]""",
            """[$html,{"type":"video","content":"x"}]""",
            """[{"type":"html","content":1}]""",
            """[{"type":"html"}]""",
            """[{"content":"x"}]""",
            """["<p>x</p>"]""",
            """[$html,null]""",
        )
        ) {
            assertNull(Experience.decode(json(value)), value)
        }
    }

    @Test
    fun `anything else is not an experience`() {
        for (value in listOf(JsonPrimitive("<p>html</p>"), JsonPrimitive(1), JsonPrimitive(true), JsonNull, null)) {
            assertNull(Experience.decode(value), value.toString())
        }
    }

    @Test
    fun `offer fields, storeProductId and expiresAt`() {
        val offer = offerSlots(json(slotsJson()))[0].offer
        assertEquals(
            Offer(
                id = "offer_1",
                presentation = json("""{"title":"Monthly","storeProductId":"com.x.monthly"}""").jsonObject,
                redemptionSchema = "custom:purchase",
                token = token,
                expiresAt = Instant.ofEpochMilli(expiry),
                storeProductId = "com.x.monthly",
            ),
            offer,
        )
    }

    @Test
    fun `offer without expiry, non-string storeProductId, missing presentation, undecodable token`() {
        val slots = offerSlots(
            json(
                """[
                {"type":"single","offer":{"id":"a","presentation":{"storeProductId":42},"token":"${offerToken(null)}"}},
                {"type":"single","offer":{"id":"b","token":"not.a.jwt"}}
            ]""",
            ),
        )

        val a = slots[0].offer!!
        assertNull(a.expiresAt)
        assertNull(a.storeProductId)
        assertNull(a.redemptionSchema)
        val b = slots[1].offer!!
        assertEquals(JsonObject(emptyMap()), b.presentation)
        assertNull(b.expiresAt, "a token that is not a JWT has no expiry, but the offer is kept")
    }

    @Test
    fun `offer slots skip malformed slots and null offers without id or token`() {
        val slots = offerSlots(
            json(
                """[
                {"type":"single","offer":null},
                {"type":"single"},
                {"type":"single","offer":{"token":"$token"}},
                {"type":"single","offer":{"id":"x"}},
                {"type":"single","offer":{"id":1,"token":"$token"}},
                {"type":"single","offer":"offer_1"},
                {"offer":{"id":"x","token":"$token"}},
                {"type":2,"offer":null},
                "single",
                null,
                {"type":"carousel","offer":{"id":"y","token":"$token"}}
            ]""",
            ),
        )

        assertEquals(listOf("single", "single", "single", "single", "single", "single", "carousel"), slots.map { it.type })
        assertEquals(listOf(null, null, null, null, null, null, "y"), slots.map { it.offer?.id })
        assertEquals(emptyList(), offerSlots(json("""{"type":"single"}""")))
        assertEquals(emptyList(), offerSlots(null))
    }

    @Test
    fun `decision experience reads surface or component properties`() {
        val web = JsonArray(listOf(buildJsonObject { put("type", "html"); put("content", "<p>wall</p>") }))
        val body = MosJson.parseToJsonElement(decisionBody(surfaceProperties = buildJsonObject { put("wall", web) })).jsonObject
        val withComponent = JsonObject(
            body + ("componentBehaviors" to buildJsonObject {
                putJsonObject("inline") { putJsonObject("properties") { putJsonObject("card") { put("headline", "Hi") } } }
            }),
        )
        val decision = parseDecision(withComponent)!!

        assertEquals(Experience.Web("<p>wall</p>"), decision.experience("wall"))
        assertEquals("Hi", (decision.experience("card", component = "inline") as Experience.Native).content["headline"].let { (it as JsonPrimitive).content })
        assertNull(decision.experience("card"))
        assertNull(decision.experience("wall", component = "inline"))
        assertNull(decision.experience("card", component = "missing"))
    }

    @Test
    fun `a decision decodes from JSON, null when it is not one`() {
        val body = json(fixture("decision-success.json")).jsonObject
        assertEquals(parseDecision(body), Decision.fromJson(body))
        assertEquals("11111111-1111-4111-8111-111111111111", Decision.fromJson(body)?.eventId)
        assertNull(Decision.fromJson(json("""{"status":"error"}""").jsonObject))
        assertNull(Decision.fromJson(json("""{"status":"success","identity":{}}""").jsonObject))
    }

    @Serializable
    data class Card(val headline: String, val price: Double? = null)

    @Test
    fun `typed decode of native content ignores unknown keys`() {
        val native = Experience.decode(json("""{"headline":"Hi","price":4.99,"extra":true,"mos-offer-slots":[]}""")) as Experience.Native

        assertEquals(Card("Hi", 4.99), native.decode(Card.serializer()))
        assertEquals(Card("Hi", 4.99), native.decode<Card>())
        val wrong = Experience.decode(json("""{"title":"no headline"}""")) as Experience.Native
        assertFailsWith<SerializationException> { wrong.decode<Card>() }
    }
}
