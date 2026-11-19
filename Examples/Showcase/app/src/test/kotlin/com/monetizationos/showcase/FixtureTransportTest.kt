package com.monetizationos.showcase

import com.monetizationos.sdk.Action
import com.monetizationos.sdk.AppInfo
import com.monetizationos.sdk.DecisionSource
import com.monetizationos.sdk.MOS
import com.monetizationos.sdk.MOSConfig
import com.monetizationos.sdk.RedemptionResult
import com.monetizationos.sdk.Resource
import com.monetizationos.sdk.decision.Experience
import com.monetizationos.sdk.decision.Meterable
import com.monetizationos.sdk.transport.HttpRequest
import com.monetizationos.sdk.transport.HttpResponse
import com.monetizationos.showcase.support.FixtureTransport
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The fixture server against the rules in fixtures/README.md, reading the same files the iOS app reads. */
class FixtureTransportTest {
    private val directory = File(System.getProperty("showcase.fixtures") ?: "../../fixtures")
    private val transport = FixtureTransport({ path -> File(directory, path).takeIf { it.isFile }?.readText() }, latencyMs = 0)

    private fun send(path: String, body: JsonObject? = null, method: String = "POST", headers: Map<String, String> = emptyMap()): HttpResponse =
        runBlocking { transport.send(HttpRequest(FixtureTransport.API_BASE_URL + path, headers, body?.toString(), 5000, method)) }

    private fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun decide(surface: String, resource: String, identity: JsonObject, properties: JsonObject = JsonObject(emptyMap())) =
        send(
            "/api/v1/surface-decisions",
            buildJsonObject {
                put("surfaceSlug", surface)
                put("identity", identity)
                putJsonObject("resource") { put("id", resource) }
                put("properties", properties)
            },
        ).json()

    private fun anon(id: String) = buildJsonObject { put("anonymousIdentifier", id) }

    private fun jwt(sub: String) = buildJsonObject {
        put("userJwt", FixtureTransport.fakeJwt(buildJsonObject { put("sub", sub) }))
    }

    private fun JsonObject.property(feature: String, property: String) =
        this["features"]!!.jsonObject[feature]!!.jsonObject["properties"]!!.jsonObject[property]!!.jsonObject

    private fun JsonObject.slots(key: String) =
        this["surfaceBehavior"]!!.jsonObject["properties"]!!.jsonObject[key]!!.jsonObject["mos-offer-slots"]!!.jsonArray

    private fun redeem(token: String, identity: JsonObject) =
        send("/api/v1/offer-redemptions", buildJsonObject {
            put("identity", identity)
            put("offerToken", token)
        })

    @Test
    fun aDecisionWithoutAnIdentityMintsAnAnonymousOne() {
        val decision = decide("showcase", "article/1", buildJsonObject { put("createAnonymousIdentifier", true) })
        val identity = decision["identity"]!!.jsonObject
        assertTrue(identity["identifier"]!!.jsonPrimitive.content.startsWith("mos-anon-v1-fixture-"))
        assertEquals("anonymous", identity["authType"]!!.jsonPrimitive.content)
        assertEquals(true, decision.property("article", "enabled")["value"]!!.jsonPrimitive.boolean)
        assertFalse("{{eventId}}" in decision.toString())
    }

    @Test
    fun aUniqueResourceMeterCountsEachNewIdOnce() {
        val reader = anon("reader-1")
        fun views(id: String) = decide("showcase-metered", id, reader).property("items", "views")

        val first = views("item/1")
        assertEquals(true, first["consumedInRequest"]!!.jsonPrimitive.boolean)
        assertEquals(2, first["remainingUnits"]!!.jsonPrimitive.int)
        val again = views("item/1")
        assertEquals(true, again["resourceIdUsed"]!!.jsonPrimitive.boolean)
        assertEquals(2, again["remainingUnits"]!!.jsonPrimitive.int)
        views("item/2")
        assertEquals(0, views("item/3")["remainingUnits"]!!.jsonPrimitive.int)
        val locked = views("item/4")
        assertEquals(false, locked["hasAccess"]!!.jsonPrimitive.boolean)
        assertEquals(1, locked["excessUnitsInRequest"]!!.jsonPrimitive.int)
        // Another reader has their own meter.
        assertEquals(2, decide("showcase-metered", "item/4", anon("reader-2")).property("items", "views")["remainingUnits"]!!.jsonPrimitive.int)
    }

    @Test
    fun aCounterUpdateConsumesTheMeter() {
        val reader = anon("reader-1")
        send("/api/v1/counter-updates", buildJsonObject {
            put("identity", reader)
            put("counterId", "offline-views")
            put("resourceId", "offline/1")
        })
        val meter = decide("showcase-metered", "offline/1", reader).property("offline", "views")
        assertEquals(true, meter["resourceIdUsed"]!!.jsonPrimitive.boolean)
        assertEquals(1, meter["consumedUnits"]!!.jsonPrimitive.int)
    }

    @Test
    fun offerSlotsExpandToOffersWithReadableTokens() {
        val slots = decide("showcase", "offers/redeem", anon("reader-1")).slots("offers")
        assertEquals(listOf("offer-monthly", "offer-navigate", "offer-free-week", "offer-expired"), slots.map { it.jsonObject["offer"]!!.jsonObject["id"]!!.jsonPrimitive.content })
        val offer = slots[0].jsonObject["offer"]!!.jsonObject
        assertEquals(setOf("id", "redemptionSchema", "presentation", "token"), offer.keys)
        val payload = FixtureTransport.payloadOfToken(offer["token"]!!.jsonPrimitive.content)!!
        assertEquals("offer-monthly", payload["id"]!!.jsonPrimitive.content)
        assertTrue(payload["expirationMsUtc"]!!.jsonPrimitive.content.toLong() > System.currentTimeMillis())
    }

    @Test
    fun theWebPaywallInlinesItsHtmlWithTokens() {
        val item = decide("showcase", "paywall/web", anon("reader-1"))["surfaceBehavior"]!!.jsonObject["properties"]!!.jsonObject["webPaywall"]!!.jsonArray[0].jsonObject
        assertNull(item["contentFile"])
        val html = item["content"]!!.jsonPrimitive.content
        assertTrue("mos://redeem?token=ey" in html)
        assertFalse("{{" in html)
    }

    @Test
    fun eachOfferAnswersItsRedemptionOutcome() {
        val reader = anon("reader-1")
        val tokens = decide("showcase", "offers/redeem", reader).slots("offers")
            .associate { slot -> slot.jsonObject["offer"]!!.jsonObject.let { it["id"]!!.jsonPrimitive.content to it["token"]!!.jsonPrimitive.content } }

        val monthly = redeem(tokens.getValue("offer-monthly"), reader).json()
        assertEquals("custom:play-billing", monthly["action"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(buildJsonObject { put("productId", "premium"); put("basePlanId", "monthly") }, monthly["action"]!!.jsonObject["properties"])
        assertEquals("navigate", redeem(tokens.getValue("offer-navigate"), reader).json()["action"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("none", redeem(tokens.getValue("offer-free-week"), reader).json()["action"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(409, redeem(tokens.getValue("offer-free-week"), reader).json()["statusCode"]!!.jsonPrimitive.int)
        assertEquals("none", redeem(tokens.getValue("offer-free-week"), anon("reader-2")).json()["action"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(410, redeem(tokens.getValue("offer-expired"), reader).json()["statusCode"]!!.jsonPrimitive.int)
        assertEquals(400, redeem("not-a-token", reader).status)
    }

    @Test
    fun signedInReadersGetTheirOwnFiles() {
        val signedIn = decide("showcase", "feed", jwt("sample-user-1"))
        assertEquals("sample-user-1", signedIn["identity"]!!.jsonObject["identifier"]!!.jsonPrimitive.content)
        assertEquals(false, signedIn.property("ads", "enabled")["value"]!!.jsonPrimitive.boolean)
        assertEquals(true, decide("showcase", "feed", anon("reader-1")).property("ads", "enabled")["value"]!!.jsonPrimitive.boolean)
        assertEquals(false, decide("showcase", "article/2", jwt("user-2")).property("article", "enabled")["value"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun endpointsMatchMethodAndPath() {
        val profile = send("/api/v1/envs/test_showcase/namespaces/showcase/endpoints/profile", method = "GET")
        assertEquals(200, profile.status)
        assertEquals("application/json", profile.headers["Content-Type"])
        assertEquals("free", profile.json()["profile"]!!.jsonObject["plan"]!!.jsonPrimitive.content)
        assertEquals(404, send("/api/v1/envs/test_showcase/namespaces/showcase/endpoints/profile", method = "DELETE").status)

        val note = send("/api/v1/envs/test_showcase/endpoints/notes", buildJsonObject { put("text", "Note 1") })
        assertEquals(201, note.status)
        assertEquals("Note 1", note.json()["received"]!!.jsonObject["text"]!!.jsonPrimitive.content)

        val missing = send("/api/v1/envs/test_showcase/namespaces/showcase/endpoints/nope", method = "GET")
        assertEquals(404, missing.status)
        assertEquals("No fixture endpoint for nope", missing.json()["message"]!!.jsonPrimitive.content)
    }

    @Test
    fun anythingElseIsNotFound() {
        val unknown = decide("showcase", "article/99", anon("reader-1"))
        assertEquals("No fixture for showcase article/99", unknown["message"]!!.jsonPrimitive.content)
        assertEquals(404, send("/api/v1/whatever", JsonObject(emptyMap())).status)
    }

    /** The SDK itself, on the JVM, against the fixtures: what the app does in fixture mode. */
    @Test
    fun theSdkReadsTheFixtures() = runBlocking {
        val mos = MOS.inMemory(MOSConfig(FixtureTransport.PUBLIC_KEY, "showcase", AppInfo("Test", "1"), FixtureTransport.API_BASE_URL, transport = transport))

        val article = mos.decide(Resource("article/1"))
        assertEquals(DecisionSource.Network, article.source)
        assertTrue(article.hasAccess(feature = "article", property = "enabled"))
        assertNotNull(mos.anonymousIdentifier())

        val meter = mos.decide(Resource("item/1"), surface = "showcase-metered").decision?.features?.get("items")?.properties?.get("views") as Meterable
        assertEquals(2.0, meter.remainingUnits)

        val paywall = mos.decide(Resource("paywall/native")).decision?.experience("paywall") as Experience.Native
        assertEquals(JsonPrimitive("Unlock premium articles"), paywall.content["headline"])
        val redemption = mos.redeem(paywall.offers.first().offer!!.token) as RedemptionResult.Redeemed
        val action = redemption.action as Action.Custom
        assertEquals("custom:play-billing", action.type)
        assertEquals(buildJsonObject { put("productId", "premium"); put("basePlanId", "monthly") }, action.properties)

        assertTrue(mos.decide(Resource("paywall/web")).decision?.experience("webPaywall") is Experience.Web)
        assertTrue((mos.identify(FixtureTransport.fakeJwt(buildJsonObject { put("sub", "sample-user-1") }))).linkPending)
        assertTrue(mos.decide(Resource("article/1")).linkedAnonymousIdentifier)
    }
}
