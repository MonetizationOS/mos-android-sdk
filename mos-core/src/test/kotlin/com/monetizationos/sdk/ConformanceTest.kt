package com.monetizationos.sdk

import com.monetizationos.sdk.decision.BooleanProperty
import com.monetizationos.sdk.decision.Decision
import com.monetizationos.sdk.decision.Meterable
import com.monetizationos.sdk.decision.NumberProperty
import com.monetizationos.sdk.decision.StringProperty
import com.monetizationos.sdk.decision.UnknownProperty
import com.monetizationos.sdk.decision.decodeOfferToken
import com.monetizationos.sdk.decision.discoverOfferTokens
import com.monetizationos.sdk.decision.markupOfferTokens
import com.monetizationos.sdk.decision.parseDecision
import com.monetizationos.sdk.metering.MeteringEntry
import com.monetizationos.sdk.transport.ApiOutcome
import com.monetizationos.sdk.transport.HttpResponse
import com.monetizationos.sdk.transport.classify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/** Asserts exactly what fixtures/README.md says about each fixture. */
class ConformanceTest {
    private fun decode(name: String): Decision = parseDecision(Json.parseToJsonElement(fixture(name)).jsonObject)!!

    private val expired = 1_577_836_800_000L
    private val future = 1_893_456_000_000L

    /** Decides `article/1` online with [body], then `article/999` offline so the cache serves it. */
    private suspend fun serveFromCache(h: Harness, mos: MOS, body: String): DecisionResult {
        h.transport.handler = { ok(body) }
        mos.decide(article("article/1"))
        h.transport.handler = { throw IOException("offline") }
        return mos.decide(article("article/999"))
    }

    @Test
    fun `every fixture file is covered`() {
        val covered = setOf(
            "README.md", "decision-success.json", "decision-success-paywalled.json", "decision-success-jwt-fallback.json",
            "decision-success-authenticated.json", "decision-success-minimal.json", "error-400.json", "error-401.json",
            "error-402.json", "error-404.json", "error-429.json", "error-531.json", "redemption-navigate.json",
            "redemption-custom.json", "redemption-custom-bare.json", "redemption-none.json", "redemption-failure.json", "counter-update-success.json",
            "offer-tokens.json", "offer-tokens-markup.json", "invalid-json.txt",
        )
        assertEquals(covered, fixturesDir().list()!!.toSet())
    }

    @Test
    fun `decision-success json`() = runTest {
        val decision = decode("decision-success.json")
        assertEquals("11111111-1111-4111-8111-111111111111", decision.eventId)
        assertEquals(false, decision.identity.isAuthenticated)

        val article = decision.features.getValue("article").properties
        val enabled = assertIs<Meterable>(article["enabled"])
        assertEquals("default:feat_article.enabled", enabled.counterId)
        assertTrue(enabled.hasAccess)
        assertEquals(true, enabled.consumedInRequest)
        assertEquals(1.0, enabled.consumedUnitsInRequest)
        assertEquals(true, enabled.uniqueResources)
        assertEquals(2.0, enabled.remainingUnits)
        assertEquals(NumberProperty(3.0, isFallback = false), article["maxArticles"])
        assertEquals(StringProperty("free", isFallback = true), article["tier"])
        assertEquals(BooleanProperty(null, isFallback = false), article["adsFree"])
        assertEquals("gauge", assertIs<UnknownProperty>(article["future"]).type)

        val podcast = assertIs<Meterable>(decision.features.getValue("podcast").properties["enabled"])
        assertEquals(false, podcast.hasAccess)
        assertEquals(
            listOf(podcast.consumedUnits, podcast.remainingUnits, podcast.totalUnits, podcast.consumedUnitsInRequest, podcast.excessUnitsInRequest),
            List(5) { null },
        )
        assertEquals(JsonPrimitive(false), decision.surfaceProperties["entitled"])

        val tokens = discoverOfferTokens(decision)
        assertEquals(3, tokens.size)
        assertEquals(setOf(future, expired, null), tokens.map { it.expirationMsUtc }.toSet())
        val componentOnly = discoverOfferTokens(decision.copy(surfaceBehavior = kotlinx.serialization.json.JsonObject(emptyMap())))
        assertEquals(listOf(expired), componentOnly.map { it.expirationMsUtc }, "the expired token is in componentBehaviors.home")
        assertNull(decodeOfferToken("not.a.jwt"))

        // Via MOS: the anon id is persisted; the 2020 token bounds the cache entry, so it is never served now.
        val h = Harness()
        val mos = h.mos()
        h.transport.handler = { ok(fixture("decision-success.json")) }
        mos.decide(article("article/1"))
        assertEquals("mos-anon-v1-abc", h.identityStore.anonymousIdentifier())
        val entry = h.cacheStore.load().single()
        assertEquals(minOf(NOW + 24.hours.inWholeMilliseconds, expired), entry.expiresAt)
        assertEquals(DecisionSource.Unavailable, mos.decide(article("article/1"), policy = CachePolicy.CacheOnly).source)

        // Before 2020 it is servable: from cache for article/999 it queues exactly one update.
        val early = Harness(anonymousId = ANON)
        early.clock.now = 1_500_000_000_000L
        val served = serveFromCache(early, early.mos(), fixture("decision-success.json"))
        assertIs<DecisionSource.Fallback>(served.source)
        assertEquals(
            listOf(MeteringEntry("anon:$ANON", ANON, "default:feat_article.enabled", "article/999", 1.0, 1_500_000_000_000L)),
            early.queueStore.load(),
        )
    }

    @Test
    fun `decision-success-paywalled json`() = runTest {
        val decision = decode("decision-success-paywalled.json")
        val enabled = assertIs<Meterable>(decision.features.getValue("article").properties["enabled"])
        assertEquals(false, enabled.hasAccess)
        assertEquals(false, enabled.consumedInRequest)
        assertEquals(1.0, enabled.excessUnitsInRequest)
        assertTrue(decision.componentsSkipped)
        assertEquals(listOf<Long?>(future), discoverOfferTokens(decision).map { it.expirationMsUtc })

        val h = Harness(anonymousId = ANON)
        assertIs<DecisionSource.Fallback>(serveFromCache(h, h.mos(), fixture("decision-success-paywalled.json")).source)
        assertTrue(h.queueStore.load().isEmpty())
    }

    @Test
    fun `decision-success-jwt-fallback json`() = runTest {
        val h = Harness()
        val mos = h.mos()
        mos.identify(userJwt("user_42"))
        h.transport.handler = { ok(fixture("decision-success-jwt-fallback.json")) }
        mos.decide(article())
        assertTrue(h.transport.requests.single().json()["identity"]!!.jsonObject.containsKey("userJwt"))
        assertEquals("mos-anon-v1-minted", h.identityStore.anonymousIdentifier())
    }

    @Test
    fun `decision-success-authenticated json`() = runTest {
        val decision = decode("decision-success-authenticated.json")
        assertEquals("user_42", decision.identity.jwtClaims["sub"]!!.jsonPrimitive.content)
        assertTrue(decision.customer.hasProducts)
        assertEquals(2, decision.customer.customerIdentifiers.size)
        val enabled = assertIs<Meterable>(decision.features.getValue("article").properties["enabled"])
        assertEquals(false, enabled.uniqueResources)
        assertEquals(2.0, enabled.consumedUnitsInRequest)

        val h = Harness()
        val mos = h.mos()
        mos.identify(userJwt("user_42"))
        serveFromCache(h, mos, fixture("decision-success-authenticated.json"))
        assertNull(h.identityStore.anonymousIdentifier(), "authenticated identities are never persisted")
        val queued = h.queueStore.load().single()
        assertEquals(2.0, queued.update)
        assertEquals("article/999", queued.resourceId)
    }

    @Test
    fun `decision-success-minimal json`() {
        val decision = decode("decision-success-minimal.json")
        assertTrue(decision.identity.jwtClaims.isEmpty(), "no jwtClaims decodes as an empty object, as on iOS")
        assertTrue(decision.surfaceBehavior.isEmpty())
        assertTrue(decision.features.isEmpty())
        assertTrue(decision.surfaceProperties.isEmpty())
        assertTrue(discoverOfferTokens(decision).isEmpty())
    }

    @Test
    fun `error fixtures`() = runTest {
        val expected = mapOf(
            400 to FailureReason.ApiError, 401 to FailureReason.Unauthorized, 402 to FailureReason.ApiError,
            404 to FailureReason.ApiError, 429 to FailureReason.RateLimited, 531 to FailureReason.ApiError,
        )
        for ((code, reason) in expected) {
            val body = fixture("error-$code.json")
            for (status in listOf(code, 200)) {
                val failure = assertIs<ApiOutcome.Failed>(classify(HttpResponse(status, body))).failure
                assertEquals(reason, failure.reason, "error-$code.json with HTTP $status")
                assertEquals(code, failure.statusCode)

                val h = Harness(anonymousId = ANON)
                h.transport.handler = { ok(body, status) }
                assertEquals(reason, h.mos().decide(article()).failure?.reason)
            }
        }
    }

    @Test
    fun `redemption fixtures`() = runTest {
        val h = Harness(anonymousId = ANON)
        val mos = h.mos()
        val token = offerToken(null)

        h.transport.handler = { ok(fixture("redemption-navigate.json")) }
        val navigate = assertIs<Action.Navigate>(assertIs<RedemptionResult.Redeemed>(mos.redeem(token)).action)
        assertEquals("https://example.com/post-checkout", navigate.url)
        assertEquals(JsonPrimitive("monthly"), navigate.properties["plan"])

        h.transport.handler = { ok(fixture("redemption-custom.json")) }
        val custom = assertIs<Action.Custom>(assertIs<RedemptionResult.Redeemed>(mos.redeem(token)).action)
        assertEquals("custom:purchase", custom.type)
        assertTrue("productId" in custom.properties)

        h.transport.handler = { ok(fixture("redemption-custom-bare.json")) }
        val bare = assertIs<Action.Custom>(assertIs<RedemptionResult.Redeemed>(mos.redeem(token)).action)
        assertEquals("custom", bare.type)
        assertEquals(JsonPrimitive("com.example.annual"), bare.properties["productId"])

        h.transport.handler = { ok(fixture("redemption-none.json")) }
        assertIs<Action.NoAction>(assertIs<RedemptionResult.Redeemed>(mos.redeem(token)).action)

        h.transport.handler = { ok(fixture("redemption-failure.json"), 409) }
        val declined = assertIs<RedemptionResult.Declined>(mos.redeem(token))
        assertEquals("Already redeemed", declined.message)
        assertEquals(409, declined.statusCode)
        assertEquals("99999999-9999-4999-8999-999999999999", declined.eventId)
    }

    @Test
    fun `counter-update-success json`() = runTest {
        val h = Harness(anonymousId = ANON)
        h.queueStore.save(listOf(MeteringEntry("anon:$ANON", ANON, "default:feat_article.enabled", "article/1", 1.0, NOW)))
        h.transport.handler = { ok(fixture("counter-update-success.json")) }
        assertEquals(FlushResult(1, 0, 0, null), h.mos().flush())
    }

    @Test
    fun `offer-tokens json`() {
        val tokens = Json.parseToJsonElement(fixture("offer-tokens.json")).jsonObject.mapValues { it.value.jsonPrimitive.content }
        assertEquals(future, decodeOfferToken(tokens.getValue("future"))?.expirationMsUtc)
        assertEquals(expired, decodeOfferToken(tokens.getValue("expired"))?.expirationMsUtc)
        val noExpiry = decodeOfferToken(tokens.getValue("noExpiry"))
        assertTrue(noExpiry != null && noExpiry.expirationMsUtc == null)
        for (name in listOf("garbage", "twoParts", "badPayload")) assertNull(decodeOfferToken(tokens.getValue(name)), name)
    }

    @Test
    fun `offer-tokens-markup json`() {
        val cases = Json.parseToJsonElement(fixture("offer-tokens-markup.json")).jsonArray.map { it.jsonObject }
        assertTrue(cases.isNotEmpty())
        for (item in cases) {
            val expected = item.getValue("tokens").jsonArray.map { it.jsonPrimitive.content }
            assertEquals(expected, markupOfferTokens(item.getValue("markup").jsonPrimitive.content), item.getValue("case").jsonPrimitive.content)
        }
    }

    @Test
    fun `invalid-json txt`() = runTest {
        for ((status, reason) in listOf(502 to FailureReason.HttpError, 200 to FailureReason.InvalidJson)) {
            val h = Harness(anonymousId = ANON)
            val mos = h.mos()
            mos.decide(article())
            h.transport.requests.clear()
            h.transport.handler = { ok(fixture("invalid-json.txt"), status) }

            val result = mos.decide(article())

            assertEquals(reason, result.failure?.reason)
            assertEquals(status, result.failure?.status)
            assertIs<DecisionSource.Cache>(result.source)
            assertEquals(1, h.transport.requests.size)
        }
    }
}
