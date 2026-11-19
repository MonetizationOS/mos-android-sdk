package com.monetizationos.sdk

import com.monetizationos.sdk.transport.HttpRequest
import com.monetizationos.sdk.transport.HttpResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalCoroutinesApi::class)
class RedemptionEndpointTest {
    @Test
    fun `redeem three outcomes and action mapping`() = runTest {
        val h = Harness(anonymousId = ANON)
        val mos = h.mos()
        val token = offerToken(NOW + 60_000)

        h.transport.handler = { ok(fixture("redemption-navigate.json")) }
        val navigate = assertIs<RedemptionResult.Redeemed>(mos.redeem(token, JsonObject(mapOf("source" to JsonPrimitive("paywall")))))
        assertEquals(
            Action.Navigate("https://example.com/post-checkout", buildJsonObject { put("plan", "monthly") }),
            navigate.action,
        )
        assertEquals("66666666-6666-4666-8666-666666666666", navigate.eventId)
        assertEquals(200, navigate.statusCode)
        val request = h.transport.requests.single()
        assertEquals("https://api.test/api/v1/offer-redemptions", request.url)
        assertEquals(
            buildJsonObject {
                putJsonObject("identity") { put("anonymousIdentifier", ANON) }
                put("offerToken", token)
                putJsonObject("properties") { put("source", "paywall") }
            },
            request.json(),
        )

        h.transport.handler = { ok(fixture("redemption-custom.json")) }
        val custom = assertIs<Action.Custom>(assertIs<RedemptionResult.Redeemed>(mos.redeem(token)).action)
        assertEquals("custom:purchase", custom.type)
        assertEquals(JsonPrimitive("com.example.monthly"), custom.properties["productId"])

        h.transport.handler = { ok(fixture("redemption-none.json")) }
        assertIs<Action.NoAction>(assertIs<RedemptionResult.Redeemed>(mos.redeem(token)).action)

        h.transport.handler = { ok(fixture("redemption-failure.json"), 409) }
        assertEquals(
            RedemptionResult.Declined("Already redeemed", 409, "99999999-9999-4999-8999-999999999999"),
            mos.redeem(token),
        )

        h.transport.handler = { ok(fixture("error-400.json"), 400) }
        val failed = assertIs<RedemptionResult.Failed>(mos.redeem(token))
        assertEquals(Failure(FailureReason.ApiError, 400, 400, "resource.meta must be an object with string values"), failed.failure)

        assertTrue(listOf("redeem:success", "redeem:declined", "redeem:error").all { it in h.logs.codes() })
    }

    @Test
    fun `a redeemed offer clears the decision cache, a declined or failed one does not`() = runTest {
        val h = Harness(anonymousId = ANON)
        val paywalled = decisionBody(hasAccess = false, consumedInRequest = false, consumedUnitsInRequest = 0, consumedUnits = 3.0, totalUnits = 3.0)
        var redemption = ok(fixture("redemption-failure.json"), 409)
        h.transport.handler = { request -> if (request.url.endsWith(OFFER_REDEMPTIONS_PATH)) redemption else ok(paywalled) }
        val mos = h.mos()
        val cacheFirst = CachePolicy.CacheFirst(5.minutes)
        fun decisions() = h.transport.to(SURFACE_DECISIONS_PATH).size

        mos.decide(article())
        val ledger = h.meterStore.load()
        assertIs<RedemptionResult.Declined>(mos.redeem(offerToken(null)))
        redemption = ok("nope", 500)
        assertIs<RedemptionResult.Failed>(mos.redeem(offerToken(null)))
        assertIs<DecisionSource.Cache>(mos.decide(article(), policy = cacheFirst).source)
        assertEquals(1, decisions())

        for (name in listOf("redemption-none.json", "redemption-navigate.json", "redemption-custom.json", "redemption-custom-bare.json")) {
            mos.decide(article())
            redemption = ok(fixture(name))
            assertIs<RedemptionResult.Redeemed>(mos.redeem(offerToken(null)), name)
            val before = decisions()
            assertEquals(DecisionSource.Network, mos.decide(article(), policy = cacheFirst).source, name)
            assertEquals(before + 1, decisions(), name)
        }
        assertEquals(4, h.logs.events.count { it.code == "cache:cleared" && it.context["reason"] == JsonPrimitive("entitlements-changed") })
        assertEquals(ledger, h.meterStore.load(), "the meter counts are untouched")
    }

    @Test
    fun `a decision in flight while an offer is redeemed is returned but not cached`() = runTest {
        val h = Harness(anonymousId = ANON)
        val gate = CompletableDeferred<Unit>()
        val sent = AtomicInteger()
        h.transport.handler = { request ->
            if (request.url.endsWith(OFFER_REDEMPTIONS_PATH)) {
                ok(fixture("redemption-none.json"))
            } else {
                val eventId = "evt-${sent.incrementAndGet()}"
                gate.await()
                ok(decisionBody(eventId = eventId))
            }
        }
        val mos = h.mos()

        val inFlight = async { mos.decide(article()) }
        runCurrent()
        assertIs<RedemptionResult.Redeemed>(mos.redeem(offerToken(null)))
        val joined = async { mos.decide(article()) }
        runCurrent()
        gate.complete(Unit)

        assertEquals(DecisionSource.Network, inFlight.await().source)
        assertEquals(DecisionSource.Network, joined.await().source)
        assertEquals(2, h.transport.to(SURFACE_DECISIONS_PATH).size, "a decision after the redemption does not join one sent before it")
        assertEquals(listOf("evt-2"), h.cacheStore.load().map { it.decision["eventId"]?.jsonPrimitive?.content }, "only the decision sent after it is cached")
    }

    @Test
    fun `a bare custom action is custom`() {
        val properties = buildJsonObject { put("productId", "com.example.annual") }
        assertEquals(Action.Custom("custom", properties), parseAction(buildJsonObject { put("type", "custom"); put("properties", properties) }))
        assertIs<Action.NoAction>(parseAction(buildJsonObject { put("type", "customer") }))
    }

    @Test
    fun `unknown or incomplete actions degrade to none`() {
        assertIs<Action.NoAction>(parseAction(null))
        assertIs<Action.NoAction>(parseAction(buildJsonObject { put("type", "navigate") }))
        assertIs<Action.NoAction>(parseAction(buildJsonObject { put("type", "teleport") }))
    }

    @Test
    fun `redeem is never retried`() = runTest {
        for (handler in listOf<suspend (HttpRequest) -> HttpResponse>(
            { throw IOException("offline") },
            { ok(fixture("error-531.json"), 531) },
            { ok(fixture("error-429.json"), 429) },
            { ok("nope", 200) },
        )) {
            val h = Harness(anonymousId = ANON)
            h.transport.handler = handler
            assertIs<RedemptionResult.Failed>(h.mos().redeem(offerToken(null)))
            assertEquals(1, h.transport.requests.size)
        }
    }

    @Test
    fun `redeem sends the bare JWT when signed in`() = runTest {
        val h = Harness(anonymousId = ANON)
        val mos = h.mos()
        val token = userJwt("user_42")
        mos.identify(token)
        h.transport.handler = { ok(fixture("redemption-none.json")) }
        mos.redeem(offerToken(null))
        assertEquals(buildJsonObject { put("userJwt", token) }, h.transport.requests.single().json()["identity"])
    }

    @Test
    fun `env parsing`() {
        assertEquals("dev_f23d9bc1", environmentFromKey("pk_dev_f23d9bc1_abc_def"))
        assertEquals("live_x", environmentFromKey("pk_live_x_secret"))
        assertNull(environmentFromKey("pk_live_x"))
        assertNull(environmentFromKey("pk_live_x_"))
        assertNull(environmentFromKey("pk__x_secret"))
        assertNull(environmentFromKey("sk_live_x_secret"))
    }
}
