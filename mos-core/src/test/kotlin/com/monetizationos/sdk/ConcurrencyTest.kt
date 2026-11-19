package com.monetizationos.sdk

import com.monetizationos.sdk.identity.anonIdentityKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Coalescing and the stale-response guard. Each test parks requests on a gate to hold them in flight. */
@OptIn(ExperimentalCoroutinesApi::class)
class ConcurrencyTest {
    private fun props(value: String) = JsonObject(mapOf("plan" to JsonPrimitive(value)))

    private fun Harness.staleReasons(): List<String> =
        logs.events.filter { it.code == "identity:stale-response" }.map { (it.context["reason"] as JsonPrimitive).content }

    @Test
    fun `coalescing N concurrent identical decides make one transport call and all get the result`() = runTest {
        val h = Harness(anonymousId = ANON)
        val mos = h.mos()
        val gate = CompletableDeferred<Unit>()
        h.transport.handler = { gate.await(); ok(decisionBody(eventId = "evt-shared")) }

        val calls = (1..5).map { async { mos.decide(article(), props("x")) } }
        runCurrent()
        assertEquals(1, h.transport.requests.size)
        gate.complete(Unit)
        val results = calls.awaitAll()

        assertTrue(results.all { it.source == DecisionSource.Network && it.decision?.eventId == "evt-shared" })
        assertEquals(1, h.transport.requests.size)
        assertEquals(4, h.logs.codes().count { it == "decision:coalesced" })
        assertEquals(1, h.cacheStore.load().size)

        // The entry is gone once the request completes: a later identical call sends normally.
        mos.decide(article(), props("x"))
        assertEquals(2, h.transport.requests.size)
    }

    @Test
    fun `coalescing compares canonical JSON, so different properties or surfaces make separate calls`() = runTest {
        val h = Harness(anonymousId = ANON)
        val mos = h.mos()
        val gate = CompletableDeferred<Unit>()
        h.transport.handler = { gate.await(); ok(decisionBody()) }

        val reordered = listOf(
            JsonObject(linkedMapOf("a" to JsonPrimitive(1), "b" to JsonPrimitive(2))),
            JsonObject(linkedMapOf("b" to JsonPrimitive(2), "a" to JsonPrimitive(1))),
        )
        val calls = listOf(props("x"), props("y"), props("x")).map { async { mos.decide(article(), it) } } +
            reordered.map { async { mos.decide(article(), it) } } +
            async { mos.decide(article("article/2"), props("x")) } +
            async { mos.decide(article(), props("x"), surface = "other") }
        runCurrent()
        gate.complete(Unit)
        calls.awaitAll()

        val sent = h.transport.requests.map { it.json()["properties"]!!.jsonObject }
        assertEquals(5, sent.size, "x, y, {a,b}, another resource and another surface")
        assertEquals(2, h.logs.codes().count { it == "decision:coalesced" })
    }

    @Test
    fun `a coalesced caller applies its own policy fallback`() = runTest {
        val h = Harness(anonymousId = ANON)
        val mos = h.mos()
        mos.decide(article())
        val gate = CompletableDeferred<Unit>()
        h.transport.handler = { gate.await(); ok(fixture("error-531.json"), 531) }

        val networkOnly = async { mos.decide(article(), policy = CachePolicy.NetworkOnly) }
        val networkFirst = async { mos.decide(article()) }
        runCurrent()
        gate.complete(Unit)

        assertEquals(DecisionSource.Unavailable, networkOnly.await().source)
        val served = networkFirst.await()
        assertIs<DecisionSource.Cache>(served.source)
        assertEquals(FailureReason.ApiError, served.failure?.reason)
        assertEquals(2, h.transport.requests.size)
    }

    @Test
    fun `redeem double-tap makes one call`() = runTest {
        val h = Harness(anonymousId = ANON)
        val mos = h.mos()
        val gate = CompletableDeferred<Unit>()
        h.transport.handler = { gate.await(); ok(fixture("redemption-navigate.json")) }
        val token = offerToken(null)

        val taps = (1..2).map { async { mos.redeem(token) } }
        runCurrent()
        gate.complete(Unit)
        val results = taps.awaitAll()

        assertEquals(1, h.transport.to(OFFER_REDEMPTIONS_PATH).size)
        assertTrue(results.all { it is RedemptionResult.Redeemed })
        assertEquals(1, h.logs.codes().count { it == "redeem:coalesced" })

        // A different offer is its own call.
        mos.redeem(offerToken(NOW))
        assertEquals(2, h.transport.to(OFFER_REDEMPTIONS_PATH).size)
    }

    @Test
    fun `coalescing never spans a reset`() = runTest {
        val h = Harness()
        val mos = h.mos()
        val gate = CompletableDeferred<Unit>()
        h.transport.handler = { gate.await(); ok(decisionBody()) }

        val before = async { mos.decide(article()) }
        runCurrent()
        mos.reset()
        val after = async { mos.decide(article()) }
        runCurrent()
        gate.complete(Unit)
        before.await()
        after.await()

        assertEquals(2, h.transport.requests.size)
        assertEquals(h.transport.requests[0].body, h.transport.requests[1].body, "identical mint bodies")
        assertTrue("decision:coalesced" !in h.logs.codes())
    }

    @Test
    fun `stale response when the identity switched mid-flight is returned but nothing is cached or persisted`() = runTest {
        val h = Harness()
        val mos = h.mos()
        val gate = CompletableDeferred<Unit>()
        h.transport.handler = { gate.await(); ok(decisionBody(identifier = ANON)) }

        val minting = async { mos.decide(article()) }
        runCurrent()
        mos.identify(userJwt("user_42"))
        gate.complete(Unit)
        val result = minting.await()

        assertEquals(DecisionSource.Network, result.source)
        assertEquals(ANON, result.decision?.identity?.identifier)
        assertNull(h.identityStore.anonymousIdentifier())
        assertTrue(h.cacheStore.load().isEmpty())
        assertEquals(listOf("identity-changed"), h.staleReasons())
    }

    @Test
    fun `stale response for an anonymous identity does not clear the link or fill the purged cache`() = runTest {
        val h = Harness(anonymousId = ANON)
        val mos = h.mos()
        mos.decide(article())
        val gate = CompletableDeferred<Unit>()
        h.transport.handler = { gate.await(); ok(decisionBody()) }

        val inFlight = async { mos.decide(article("article/2")) }
        runCurrent()
        mos.identify(userJwt("user_42"))
        gate.complete(Unit)

        assertEquals(DecisionSource.Network, inFlight.await().source)
        assertTrue(h.cacheStore.load().isEmpty())
        assertEquals(ANON, h.identityStore.pendingPreviousAnonymousIdentifier())
        assertEquals(listOf("identity-changed"), h.staleReasons())
    }

    @Test
    fun `stale response second concurrent mint is returned but not persisted or cached`() = runTest {
        val h = Harness()
        val mos = h.mos()
        val gates = mapOf("article/1" to CompletableDeferred<Unit>(), "article/2" to CompletableDeferred())
        h.transport.handler = { request ->
            val resourceId = (request.json()["resource"]!!.jsonObject["id"] as JsonPrimitive).content
            gates.getValue(resourceId).await()
            ok(decisionBody(identifier = if (resourceId == "article/1") "mos-anon-v1-first" else "mos-anon-v1-second"))
        }

        val first = async { mos.decide(article("article/1")) }
        val second = async { mos.decide(article("article/2")) }
        runCurrent()
        assertEquals(2, h.transport.requests.size, "different resources are not coalesced")
        gates.getValue("article/1").complete(Unit)
        first.await()
        gates.getValue("article/2").complete(Unit)
        val late = second.await()

        assertEquals(DecisionSource.Network, late.source)
        assertEquals("mos-anon-v1-second", late.decision?.identity?.identifier)
        assertEquals("mos-anon-v1-first", h.identityStore.anonymousIdentifier())
        assertEquals(listOf(anonIdentityKey("mos-anon-v1-first") to "article/1"), h.cacheStore.load().map { it.identityKey to it.resourceId })
        assertEquals(listOf("anonymous-already-stored"), h.staleReasons())
    }

    @Test
    fun `stale response after a reset mid-flight`() = runTest {
        val h = Harness(anonymousId = ANON)
        val mos = h.mos()
        val gate = CompletableDeferred<Unit>()
        h.transport.handler = { gate.await(); ok(decisionBody()) }

        val inFlight = async { mos.decide(article()) }
        runCurrent()
        mos.reset()
        gate.complete(Unit)

        assertEquals(DecisionSource.Network, inFlight.await().source)
        assertNull(mos.anonymousIdentifier(), "reset is not undone by a late response")
        assertTrue(h.cacheStore.load().isEmpty())
        assertNull(h.identityStore.lastIdentityKey())
        assertEquals(listOf("reset"), h.staleReasons())
    }

    @Test
    fun `cancelling the first caller neither cancels the shared request nor makes the joiner send again`() = runTest {
        val h = Harness(anonymousId = ANON)
        val mos = h.mos()
        val gate = CompletableDeferred<Unit>()
        h.transport.handler = { gate.await(); ok(decisionBody(eventId = "evt-shared")) }

        val first = async { mos.decide(article()) }
        runCurrent()
        val joiner = async { mos.decide(article()) }
        runCurrent()
        first.cancel()
        runCurrent()
        gate.complete(Unit)

        assertEquals("evt-shared", joiner.await().decision?.eventId)
        assertTrue(first.isCancelled)
        assertEquals(1, h.transport.requests.size)
        assertEquals(1, h.cacheStore.load().size, "the response is still applied")
    }

    @Test
    fun `a cancelled redemption still completes for the other tap`() = runTest {
        val h = Harness(anonymousId = ANON)
        val mos = h.mos()
        val gate = CompletableDeferred<Unit>()
        h.transport.handler = { gate.await(); ok(fixture("redemption-navigate.json")) }
        val token = offerToken(null)

        val first = async { mos.redeem(token) }
        runCurrent()
        val second = async { mos.redeem(token) }
        runCurrent()
        first.cancel()
        gate.complete(Unit)

        assertIs<RedemptionResult.Redeemed>(second.await())
        assertEquals(1, h.transport.to(OFFER_REDEMPTIONS_PATH).size)
    }

    @Test
    fun `pending link is cleared by an accepted response even when the identity switched mid-flight`() = runTest {
        val h = Harness(anonymousId = ANON)
        val mos = h.mos()
        mos.decide(article())
        mos.identify(userJwt("user_42"))
        val gate = CompletableDeferred<Unit>()
        h.transport.handler = { gate.await(); ok(decisionBody(identifier = "user_42", isAuthenticated = true)) }

        val inFlight = async { mos.decide(article()) }
        runCurrent()
        mos.identify(userJwt("user_43"))
        gate.complete(Unit)
        val result = inFlight.await()

        assertTrue(result.linkedAnonymousIdentifier)
        assertNull(h.identityStore.pendingPreviousAnonymousIdentifier())
        assertEquals(1, h.logs.codes().count { it == "identity:previous-anon-sent" })
        assertEquals(listOf("identity-changed"), h.staleReasons(), "the rest of the response is still not applied")
        assertTrue(h.cacheStore.load().isEmpty())
        h.transport.handler = { ok(decisionBody(identifier = "user_43", isAuthenticated = true)) }
        mos.decide(article())
        assertEquals(PLATFORM_ONLY, h.transport.requests.last().json()["properties"], "never sent again")
    }

    @Test
    fun `pending link is not cleared and not linked after a reset mid-flight`() = runTest {
        val h = Harness(anonymousId = ANON)
        val mos = h.mos()
        mos.decide(article())
        mos.identify(userJwt("user_42"))
        val gate = CompletableDeferred<Unit>()
        h.transport.handler = { gate.await(); ok(decisionBody(identifier = "user_42", isAuthenticated = true)) }

        val inFlight = async { mos.decide(article()) }
        runCurrent()
        mos.reset()
        gate.complete(Unit)

        assertEquals(false, inFlight.await().linkedAnonymousIdentifier)
        assertTrue("identity:previous-anon-sent" !in h.logs.codes())
        assertEquals(listOf("reset"), h.staleReasons())
    }

    @Test
    fun `only the response that clears the pending link is linked`() = runTest {
        val h = Harness(anonymousId = ANON)
        val mos = h.mos()
        mos.decide(article())
        mos.identify(userJwt("user_42"))
        val gates = mapOf("article/1" to CompletableDeferred<Unit>(), "article/2" to CompletableDeferred())
        h.transport.handler = { request ->
            gates.getValue((request.json()["resource"]!!.jsonObject["id"] as JsonPrimitive).content).await()
            ok(decisionBody(identifier = "user_42", isAuthenticated = true))
        }

        val first = async { mos.decide(article("article/1")) }
        val second = async { mos.decide(article("article/2")) }
        runCurrent()
        assertEquals(2, h.transport.requests.drop(1).count { "previousAnonymousIdentifier" in it.json()["properties"]!!.jsonObject }, "both carried the link")
        gates.getValue("article/1").complete(Unit)
        assertTrue(first.await().linkedAnonymousIdentifier)
        gates.getValue("article/2").complete(Unit)
        assertEquals(false, second.await().linkedAnonymousIdentifier)
        assertEquals(1, h.logs.codes().count { it == "identity:previous-anon-sent" })
    }
}
