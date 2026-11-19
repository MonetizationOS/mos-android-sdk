package com.monetizationos.sdk

import com.monetizationos.sdk.cache.CacheEntry
import com.monetizationos.sdk.decision.Customer
import com.monetizationos.sdk.decision.parseDecision
import com.monetizationos.sdk.identity.IdentityStoreKeys.ANONYMOUS_IDENTIFIER
import com.monetizationos.sdk.identity.InMemoryIdentityStore
import com.monetizationos.sdk.transport.HttpRequest
import com.monetizationos.sdk.transport.HttpResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class CacheTest {
    private val offline: suspend (HttpRequest) -> HttpResponse =
        { throw IOException("offline") }

    @Test
    fun `cache put`() = runTest {
        val h = Harness(anonymousId = ANON)
        val mos = h.mos()

        mos.decide(article("article/1"))

        val entry = h.cacheStore.load().single()
        assertEquals(SURFACE, entry.surface)
        assertEquals("anon:$ANON", entry.identityKey)
        assertEquals("article/1", entry.resourceId)
        assertEquals(NOW, entry.storedAt)
        assertEquals(NOW + 24.hours.inWholeMilliseconds, entry.expiresAt)
        assertEquals(JsonPrimitive("evt-1"), entry.decision["eventId"])
        assertTrue("cache:put" in h.logs.codes())
    }

    @Test
    fun `the cached decision drops identity jwtClaims and the network result keeps them`() = runTest {
        val h = Harness()
        h.transport.handler = { ok(fixture("decision-success-authenticated.json")) }
        val mos = h.mos()
        mos.identify(userJwt("user_42"))

        val result = mos.decide(article("article/1"))

        assertEquals(JsonPrimitive("user_42"), result.decision?.identity?.jwtClaims?.get("sub"))
        val identity = h.cacheStore.load().single().decision["identity"] as JsonObject
        assertNull(identity["jwtClaims"])
        val cached = mos.decide(article("article/1"), policy = CachePolicy.CacheOnly)
        assertEquals(JsonObject(emptyMap()), cached.decision?.identity?.jwtClaims)
        assertEquals(JsonPrimitive(true), identity["isAuthenticated"])
    }

    @Test
    fun `cache put under a minted identity uses the minted anon id`() = runTest {
        val h = Harness()
        h.mos().decide(article())
        assertEquals("anon:$ANON", h.cacheStore.load().single().identityKey)
    }

    @Test
    fun `ttl expiry`() = runTest {
        val h = Harness(anonymousId = ANON)
        val mos = h.mos()
        mos.decide(article())

        h.clock.advance(24.hours - 1.milliseconds)
        assertIs<DecisionSource.Cache>(mos.decide(article(), policy = CachePolicy.CacheOnly).source)

        h.clock.advance(1.milliseconds)
        assertEquals(DecisionSource.Unavailable, mos.decide(article(), policy = CachePolicy.CacheOnly).source)
        assertTrue("cache:evicted" in h.logs.codes())
        assertTrue(h.cacheStore.load().isEmpty(), "expired entries are evicted on read")
    }

    @Test
    fun `a negative ttl expires at once`() = runTest {
        val h = Harness(anonymousId = ANON)
        val mos = h.mos { it.copy(offline = OfflineConfig(ttl = (-1).seconds)) }
        mos.decide(article())
        assertTrue(h.cacheStore.load().single().expiresAt <= NOW)
        assertEquals(DecisionSource.Unavailable, mos.decide(article(), policy = CachePolicy.CacheOnly).source)
    }

    @Test
    fun `a decision needs only status success and a boolean identity isAuthenticated`() = runTest {
        fun parse(body: String) = parseDecision(Json.parseToJsonElement(body).jsonObject)

        val bare = assertNotNull(parse("""{"status":"success","identity":{"isAuthenticated":false}}"""))
        assertEquals(Customer(isCustomer = false, hasProducts = false, customerIdentifiers = emptyList()), bare.customer)
        assertTrue(bare.features.isEmpty())
        assertTrue(bare.surfaceBehavior.isEmpty())
        assertTrue(bare.surfaceProperties.isEmpty())
        assertTrue(bare.componentBehaviors.isEmpty())
        assertFalse(bare.componentsSkipped)
        assertNull(bare.identity.identifier)
        // Optional fields of the wrong type take their defaults too.
        val mistyped = assertNotNull(
            parse(
                """{"status":"success","identity":{"isAuthenticated":true},"customer":[],"features":1,"surfaceBehavior":"x",""" +
                    """"componentsSkipped":"yes","componentBehaviors":null}""",
            ),
        )
        assertTrue(mistyped.identity.isAuthenticated)
        assertTrue(mistyped.features.isEmpty())
        assertFalse(mistyped.componentsSkipped)

        assertNull(parse("""{"status":"success","identity":{}}"""), "isAuthenticated is required")
        assertNull(parse("""{"status":"success","identity":{"isAuthenticated":"false"}}"""))
        assertNull(parse("""{"status":"success","identity":true}"""))
        assertNull(parse("""{"status":"success"}"""))
        assertNull(parse("""{"status":"failure","identity":{"isAuthenticated":false}}"""))

        // End to end: decoded, returned and cached.
        val h = Harness(anonymousId = ANON)
        h.transport.handler = { ok("""{"status":"success","identity":{"isAuthenticated":false,"identifier":"$ANON"}}""") }
        assertEquals(DecisionSource.Network, h.mos().decide(article()).source)
        assertEquals(1, h.cacheStore.load().size)
    }

    @Test
    fun `offer-token expiry shortening`() = runTest {
        val h = Harness(anonymousId = ANON)
        val mos = h.mos()
        val properties = buildJsonObject {
            putJsonObject("soon") { put("token", offerToken(NOW + 1.hours.inWholeMilliseconds)) }
            putJsonObject("later") { put("token", offerToken(NOW + 5.hours.inWholeMilliseconds)) }
            putJsonObject("never") { put("token", offerToken(null)) }
            putJsonObject("junk") { put("token", "not.a.jwt") }
        }
        h.transport.handler = { ok(decisionBody(surfaceProperties = properties)) }

        mos.decide(article())

        assertEquals(NOW + 1.hours.inWholeMilliseconds, h.cacheStore.load().single().expiresAt)
        h.clock.advance(30.minutes)
        assertIs<DecisionSource.Cache>(mos.decide(article(), policy = CachePolicy.CacheOnly).source)
        h.clock.advance(31.minutes)
        assertEquals(DecisionSource.Unavailable, mos.decide(article(), policy = CachePolicy.CacheOnly).source)
    }

    @Test
    fun `tokens without an expiry do not shorten the ttl`() = runTest {
        val h = Harness(anonymousId = ANON)
        val properties = buildJsonObject { putJsonObject("slot") { put("token", offerToken(null)) } }
        h.transport.handler = { ok(decisionBody(surfaceProperties = properties)) }
        h.mos().decide(article())
        assertEquals(NOW + 24.hours.inWholeMilliseconds, h.cacheStore.load().single().expiresAt)
    }

    @Test
    fun `offer tokens a web experience redeems, by href or data-mos-redeem, shorten the entry too`() = runTest {
        val soon = NOW + 10.minutes.inWholeMilliseconds
        fun web(html: String) = decisionBody(
            surfaceProperties = buildJsonObject {
                putJsonArray("paywall") {
                    addJsonObject {
                        put("type", "html")
                        put("content", html)
                    }
                }
            },
        )
        val href = web("""<a href="mos://redeem?token=${offerToken(soon)}">Buy</a>""")
        val attribute = web("""<button data-mos-redeem="${offerToken(soon)}">Buy</button>""")
        for (body in listOf(href, attribute)) {
            val h = Harness(anonymousId = ANON)
            val mos = h.mos()
            h.transport.handler = { ok(body) }
            mos.decide(article())
            assertEquals(soon, h.cacheStore.load().single().expiresAt)
            h.clock.advance(10.minutes)
            assertEquals(DecisionSource.Unavailable, mos.decide(article(), policy = CachePolicy.CacheOnly).source)
        }
    }

    @Test
    fun `max entries`() = runTest {
        val h = Harness(anonymousId = ANON)
        val mos = h.mos { it.copy(offline = OfflineConfig(maxEntries = 2)) }

        for (i in 1..3) {
            mos.decide(article("article/$i"))
            h.clock.advance(1.minutes)
        }

        assertEquals(listOf("article/2", "article/3"), h.cacheStore.load().map { it.resourceId })
        assertTrue("cache:evicted" in h.logs.codes())
    }

    @Test
    fun `per-identity isolation`() = runTest {
        val h = Harness(anonymousId = ANON)
        h.mos().decide(article())

        // Separate identity state over the same cache: nothing is purged, so only the key isolates.
        val signedIn = h.mos { it.copy(identity = it.identity.copy(store = InMemoryIdentityStore())) }
        signedIn.identify(userJwt("user_42"))
        assertEquals(DecisionSource.Unavailable, signedIn.decide(article(), policy = CachePolicy.CacheOnly).source)
        assertEquals(1, h.cacheStore.load().size)

        val anonymous = h.mos()
        assertIs<DecisionSource.Cache>(anonymous.decide(article(), policy = CachePolicy.CacheOnly).source)
    }

    @Test
    fun `entries are scoped by surface`() = runTest {
        val h = Harness(anonymousId = ANON)
        h.mos().decide(article())
        val other = h.mos { it.copy(surface = "other-surface") }
        assertEquals(DecisionSource.Unavailable, other.decide(article(), policy = CachePolicy.CacheOnly).source)
        assertIs<DecisionSource.Cache>(other.decide(article(), policy = CachePolicy.CacheOnly, surface = SURFACE).source)
        assertEquals(1, h.transport.requests.size)
    }

    @Test
    fun `networkFirst serves exact, then fallback, then unavailable, always with the failure`() = runTest {
        val h = Harness(anonymousId = ANON)
        val mos = h.mos()
        val fresh = mos.decide(article("article/1"))
        assertEquals(DecisionSource.Network, fresh.source)
        assertNull(fresh.failure)

        h.transport.handler = offline
        h.clock.advance(5.minutes)
        val exact = mos.decide(article("article/1"))
        assertEquals(DecisionSource.Cache(5.minutes.inWholeMilliseconds), exact.source)
        assertEquals(FailureReason.RequestFailed, exact.failure?.reason)
        assertNotNull(exact.decision)

        val fallback = mos.decide(article("article/2"))
        assertEquals(DecisionSource.Fallback(5.minutes.inWholeMilliseconds, "article/1"), fallback.source)
        assertEquals(FailureReason.RequestFailed, fallback.failure?.reason)

        mos.reset()
        h.identityStore.set(ANONYMOUS_IDENTIFIER, ANON)
        val none = mos.decide(article("article/1"))
        assertEquals(DecisionSource.Unavailable, none.source)
        assertEquals(FailureReason.RequestFailed, none.failure?.reason)
        assertNull(none.decision)
        assertTrue(listOf("decision:cache-hit", "decision:fallback", "decision:unavailable").all { it in h.logs.codes() })
    }

    @Test
    fun `fallbackToLatest false never serves another resource`() = runTest {
        val h = Harness(anonymousId = ANON)
        val mos = h.mos { it.copy(offline = OfflineConfig(fallbackToLatest = false)) }
        mos.decide(article("article/1"))
        h.transport.handler = offline
        assertEquals(DecisionSource.Unavailable, mos.decide(article("article/2")).source)
        assertEquals(DecisionSource.Unavailable, mos.decide(article("article/2"), policy = CachePolicy.CacheOnly).source)
    }

    @Test
    fun `cacheFirst serves a fresh entry without the network and refreshes a stale one`() = runTest {
        val h = Harness(anonymousId = ANON)
        val mos = h.mos()
        mos.decide(article())
        h.clock.advance(10.minutes)

        // Decisions only: the cache hit queues a meter update that the network decision flushes in the background.
        val decisions = { h.transport.requests.count { it.url.endsWith("/surface-decisions") } }
        val fresh = mos.decide(article(), policy = CachePolicy.CacheFirst(15.minutes))
        assertEquals(DecisionSource.Cache(10.minutes.inWholeMilliseconds), fresh.source)
        assertNull(fresh.failure)
        assertEquals(1, decisions())

        val stale = mos.decide(article(), policy = CachePolicy.CacheFirst(5.minutes))
        assertEquals(DecisionSource.Network, stale.source)
        assertEquals(2, decisions())

        // Stale and offline: behaves as networkFirst.
        h.transport.handler = offline
        h.clock.advance(10.minutes)
        val offlineResult = mos.decide(article(), policy = CachePolicy.CacheFirst(5.minutes))
        assertIs<DecisionSource.Cache>(offlineResult.source)
        assertEquals(FailureReason.RequestFailed, offlineResult.failure?.reason)
    }

    @Test
    fun `cacheOnly never touches the network`() = runTest {
        val h = Harness(anonymousId = ANON)
        val mos = h.mos()
        val empty = mos.decide(article("article/1"), policy = CachePolicy.CacheOnly)
        assertEquals(DecisionSource.Unavailable, empty.source)
        assertNull(empty.failure)
        assertTrue(h.transport.requests.isEmpty())

        mos.decide(article("article/1"))
        val exact = mos.decide(article("article/1"), policy = CachePolicy.CacheOnly)
        val fallback = mos.decide(article("article/2"), policy = CachePolicy.CacheOnly)
        assertIs<DecisionSource.Cache>(exact.source)
        assertIs<DecisionSource.Fallback>(fallback.source)
        assertNull(exact.failure)
        assertNull(fallback.failure)
        assertEquals(1, h.transport.requests.size)
    }

    @Test
    fun `networkOnly never reads the cache but still writes it`() = runTest {
        val h = Harness(anonymousId = ANON)
        val mos = h.mos()
        assertEquals(DecisionSource.Network, mos.decide(article(), policy = CachePolicy.NetworkOnly).source)
        assertEquals(1, h.cacheStore.load().size)

        h.transport.handler = offline
        val failed = mos.decide(article(), policy = CachePolicy.NetworkOnly)
        assertEquals(DecisionSource.Unavailable, failed.source)
        assertEquals(FailureReason.RequestFailed, failed.failure?.reason)
        assertIs<DecisionSource.Cache>(mos.decide(article(), policy = CachePolicy.CacheOnly).source)
    }

    @Test
    fun `a meterable without a counterId keeps its hasAccess, never failOpen`() = runTest {
        // What a workflow that switches a property off returns: nothing to count, so no counterId.
        for (hasAccess in listOf(true, false)) {
            val h = Harness(anonymousId = ANON)
            h.transport.handler = { ok(decisionBody(hasAccess = hasAccess, consumedInRequest = false, counterId = null)) }
            val mos = h.mos { it.copy(offline = OfflineConfig(failOpen = !hasAccess)) }
            assertEquals(hasAccess, mos.decide(article()).hasAccess("article", "enabled"))
        }
    }

    @Test
    fun `failOpen true and false`() = runTest {
        for (failOpen in listOf(true, false)) {
            val h = Harness(anonymousId = ANON)
            h.transport.handler = offline
            val mos = h.mos { it.copy(offline = OfflineConfig(failOpen = failOpen)) }

            val unavailable = mos.decide(article())
            assertEquals(DecisionSource.Unavailable, unavailable.source)
            assertEquals(failOpen, unavailable.hasAccess("article", "enabled"))

            // With a decision, the actual value wins; a missing property is fail-open.
            h.transport.handler = { ok(decisionBody(hasAccess = false)) }
            val decided = mos.decide(article())
            assertFalse(decided.hasAccess("article", "enabled"))
            assertEquals(failOpen, decided.hasAccess("article", "missing"))
            assertEquals(failOpen, decided.hasAccess("missing", "enabled"))
        }
    }

    @Test
    fun `hasAccess reads boolean properties and fails open on null`() = runTest {
        val h = Harness(anonymousId = ANON)
        h.transport.handler = { ok(fixture("decision-success.json")) }
        val result = h.mos { it.copy(offline = OfflineConfig(failOpen = false)) }.decide(article())
        assertTrue(result.hasAccess("article", "enabled"))
        assertFalse(result.hasAccess("podcast", "enabled"))
        assertFalse(result.hasAccess("article", "adsFree"), "null boolean falls back to failOpen (false here)")
    }

    @Test
    fun `a corrupt cached decision is not served`() = runTest {
        val h = Harness(anonymousId = ANON)
        h.cacheStore.save(
            listOf(
                CacheEntry(
                    SURFACE, "anon:$ANON", "article/1", JsonObject(mapOf("status" to JsonPrimitive("nope"))), NOW, NOW + 1000,
                ),
            ),
        )
        assertEquals(DecisionSource.Unavailable, h.mos().decide(article("article/1"), policy = CachePolicy.CacheOnly).source)
    }

    @Test
    fun `clearCache drops cached decisions for every identity`() = runTest {
        val h = Harness(anonymousId = ANON)
        val other = CacheEntry(SURFACE, "jwt:other", "article/9", buildJsonObject { put("status", "success") }, NOW, NOW + 1.hours.inWholeMilliseconds)
        h.cacheStore.save(listOf(other))
        val mos = h.mos()
        mos.decide(article("article/1"))
        assertEquals(2, h.cacheStore.load().size)

        mos.clearCache()

        assertTrue(h.cacheStore.load().isEmpty())
        val cleared = h.logs.events.last { it.code == "cache:cleared" }
        assertEquals(JsonPrimitive("entitlements-changed"), cleared.context["reason"])
        assertIs<DecisionSource.Unavailable>(mos.decide(article("article/1"), policy = CachePolicy.CacheOnly).source)
        assertEquals("anon:$ANON", h.identityStore.lastIdentityKey(), "identity state is untouched")
        assertEquals(ANON, mos.anonymousIdentifier())

        // The next decision goes to the network and caches again.
        assertIs<DecisionSource.Network>(mos.decide(article("article/1"), policy = CachePolicy.CacheFirst(1.hours)).source)
        assertEquals(1, h.cacheStore.load().size)
    }

    @Test
    fun `clearCache leaves in-flight decisions uncached and unjoinable`() = runTest {
        val h = Harness(anonymousId = ANON)
        val mos = h.mos()
        val gate = CompletableDeferred<Unit>()
        h.transport.handler = { request ->
            if (h.transport.requests.size == 1) {
                gate.await()
                ok(decisionBody(eventId = "evt-before"))
            } else {
                ok(decisionBody(eventId = "evt-after"))
            }
        }

        val before = async { mos.decide(article("article/1")) }
        runCurrent()
        mos.clearCache()
        val after = mos.decide(article("article/1"))
        gate.complete(Unit)

        assertEquals(2, h.transport.requests.size, "a decide after clearCache never joins an earlier request")
        assertFalse("decision:coalesced" in h.logs.codes())
        val beforeResult = before.await()
        assertIs<DecisionSource.Network>(beforeResult.source)
        assertEquals("evt-before", beforeResult.decision?.eventId, "the in-flight caller still gets its result")
        assertEquals("evt-after", after.decision?.eventId)
        assertEquals(JsonPrimitive("evt-after"), h.cacheStore.load().single().decision["eventId"], "only the post-clear response is cached")
    }
}
