package com.monetizationos.sdk

import com.monetizationos.sdk.identity.IdentityStore
import com.monetizationos.sdk.identity.IdentityStoreKeys.ANONYMOUS_IDENTIFIER
import com.monetizationos.sdk.identity.IdentityStoreKeys.LAST_IDENTITY_KEY
import com.monetizationos.sdk.identity.IdentityStoreKeys.PENDING_PREVIOUS_ANONYMOUS_IDENTIFIER
import com.monetizationos.sdk.identity.InMemoryIdentityStore
import com.monetizationos.sdk.identity.anonIdentityKey
import com.monetizationos.sdk.identity.javaScriptNumber
import com.monetizationos.sdk.identity.jwtIdentityKey
import com.monetizationos.sdk.identity.sha256Hex
import com.monetizationos.sdk.logging.LogLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class IdentityTest {
    private fun identityOf(h: Harness, index: Int): JsonObject = h.transport.requests[index].json()["identity"]!!.jsonObject

    private fun propertiesOf(h: Harness, index: Int): JsonObject? = h.transport.requests[index].json()["properties"]?.jsonObject

    @Test
    fun `identity order and persistence`() = runTest {
        val h = Harness()
        var provided: String? = null
        val mos = h.mos { it.copy(identity = it.identity.copy(jwtProvider = { provided })) }
        val explicit = userJwt("explicit")
        val fromProvider = userJwt("provider")

        // Nothing known: ask the server to mint, then persist what it returned.
        mos.decide(article())
        assertEquals(buildJsonObject { put("createAnonymousIdentifier", true) }, identityOf(h, 0))
        assertEquals(ANON, h.identityStore.anonymousIdentifier())
        assertEquals(ANON, mos.anonymousIdentifier())

        // Persisted anon id.
        mos.decide(article())
        assertEquals(buildJsonObject { put("anonymousIdentifier", ANON) }, identityOf(h, 1))

        // The provider is read fresh at each call and beats the anon id.
        provided = fromProvider
        h.transport.handler = { ok(decisionBody(identifier = "user_provider", isAuthenticated = true)) }
        mos.decide(article())
        assertEquals(
            buildJsonObject {
                put("userJwt", fromProvider)
                put("createAnonymousIdentifierFallback", true)
            },
            identityOf(h, 2),
        )
        // Authenticated responses are never persisted.
        assertEquals(ANON, h.identityStore.anonymousIdentifier())

        // An explicit identify() beats the provider.
        mos.identify(explicit)
        mos.decide(article())
        assertEquals(JsonPrimitive(explicit), identityOf(h, 3)["userJwt"])

        // identify(null) falls back to the provider, then to the same persisted anon id.
        mos.identify(null)
        mos.decide(article())
        assertEquals(JsonPrimitive(fromProvider), identityOf(h, 4)["userJwt"])
        provided = null
        h.transport.handler = { ok(decisionBody()) }
        mos.decide(article())
        assertEquals(buildJsonObject { put("anonymousIdentifier", ANON) }, identityOf(h, 5))
    }

    @Test
    fun `JWT fallback persistence`() = runTest {
        val h = Harness()
        val mos = h.mos()
        mos.identify(userJwt("expired"))
        h.transport.handler = { ok(fixture("decision-success-jwt-fallback.json")) }

        mos.decide(article())

        assertEquals(JsonPrimitive(true), identityOf(h, 0)["createAnonymousIdentifierFallback"])
        assertEquals("mos-anon-v1-minted", h.identityStore.anonymousIdentifier())
        assertTrue("identity:anon-persisted" in h.logs.codes())
    }

    @Test
    fun `JWT fallback keeps a stored anonymous id`() = runTest {
        // Every fallback mints a new id. Replacing the stored one would lose its meters and the purchases its
        // appAccountToken names once the user signs out.
        val h = Harness(anonymousId = ANON)
        val mos = h.mos()
        mos.identify(userJwt("expired"))
        h.transport.handler = { ok(fixture("decision-success-jwt-fallback.json")) }

        mos.decide(article())

        assertEquals(ANON, h.identityStore.anonymousIdentifier())
        assertFalse("identity:anon-persisted" in h.logs.codes())
    }

    @Test
    fun `the store keys are the default stores' storage names, so stored values read back`() = runTest {
        assertEquals("anonymousIdentifier", ANONYMOUS_IDENTIFIER)
        assertEquals("lastIdentityKey", LAST_IDENTITY_KEY)
        assertEquals("pendingPreviousAnonymousIdentifier", PENDING_PREVIOUS_ANONYMOUS_IDENTIFIER)

        val h = Harness()
        val mos = h.mos()
        mos.decide(article())
        mos.identify(userJwt("user_42"))
        assertEquals(ANON, h.identityStore.get("anonymousIdentifier"))
        assertEquals(jwtIdentityKey(userJwt("user_42")), h.identityStore.get("lastIdentityKey"))
        assertEquals(ANON, h.identityStore.get("pendingPreviousAnonymousIdentifier"))

        val stored = InMemoryIdentityStore(mapOf("anonymousIdentifier" to "mos-anon-stored", "lastIdentityKey" to "anon:mos-anon-stored"))
        val relaunched = Harness()
        val next = relaunched.mos { it.copy(identity = it.identity.copy(store = stored)) }
        assertEquals(IdentifyResult(true, IdentityKind.Anonymous, IdentityKind.Jwt, true), next.identify(userJwt("user_42")))
        assertEquals("mos-anon-stored", stored.get("pendingPreviousAnonymousIdentifier"))
    }

    @Test
    fun `identity key and hashing`() {
        assertEquals("573baabb5ca42a23f3a118d027eadd8dc9bed70e40708a1b1ed9410b00feed0e", sha256Hex("user_42"))
        // Keyed by sub, so a refreshed token for the same user keeps its cache and queue.
        assertEquals("jwt:" + sha256Hex("user_42"), jwtIdentityKey(userJwt("user_42")))
        assertEquals(jwtIdentityKey(jwt("""{"sub":"user_42","iat":1}""")), jwtIdentityKey(jwt("""{"sub":"user_42","iat":2}""")))
        // No sub, or a token that does not decode: hash the whole token.
        val noSub = jwt("""{"email":"a@b.c"}""")
        assertEquals("jwt:" + sha256Hex(noSub), jwtIdentityKey(noSub))
        assertEquals("jwt:" + sha256Hex("opaque-token"), jwtIdentityKey("opaque-token"))
        assertEquals("jwt:" + sha256Hex("a.!!!.c"), jwtIdentityKey("a.!!!.c"))
        assertEquals("anon:$ANON", anonIdentityKey(ANON))
    }

    @Test
    fun `cache and queue are keyed by identity key`() = runTest {
        val h = Harness(anonymousId = ANON)
        val mos = h.mos()
        mos.identify(userJwt("user_42"))
        h.transport.handler = { ok(decisionBody(identifier = "user_42", isAuthenticated = true)) }

        mos.decide(article())

        assertEquals("jwt:" + sha256Hex("user_42"), h.cacheStore.load().single().identityKey)
    }

    @Test
    fun `identity change purges cache and sends previousAnonymousIdentifier exactly once`() = runTest {
        val h = Harness()
        val mos = h.mos()
        mos.decide(article("article/1"), JsonObject(mapOf("plan" to JsonPrimitive("x"))))
        assertNull(propertiesOf(h, 0)?.get("previousAnonymousIdentifier"))
        assertEquals(1, h.cacheStore.load().size)

        // Sign in: the next call sees a new identity key.
        mos.identify(userJwt("user_42"))
        h.transport.handler = { throw java.io.IOException("offline") }
        mos.decide(article("article/2"), JsonObject(mapOf("plan" to JsonPrimitive("x"))))
        assertTrue(h.cacheStore.load().isEmpty(), "cache purged on identity change")
        assertTrue("identity:changed" in h.logs.codes())
        // Failed request: it rode along but stays pending.
        assertEquals(JsonPrimitive(ANON), propertiesOf(h, 1)!!["previousAnonymousIdentifier"])
        assertEquals(JsonPrimitive("x"), propertiesOf(h, 1)!!["plan"])
        assertEquals(0, h.logs.codes().count { it == "identity:previous-anon-sent" }, "logged only once the server accepts it")

        h.transport.handler = { ok(decisionBody(identifier = "user_42", isAuthenticated = true)) }
        mos.decide(article("article/2"))
        assertEquals(JsonPrimitive(ANON), propertiesOf(h, 2)!!["previousAnonymousIdentifier"])

        // Acknowledged with a 2xx success: never sent again.
        mos.decide(article("article/3"))
        assertEquals(PLATFORM_ONLY, propertiesOf(h, 3))
        assertEquals(1, h.logs.codes().count { it == "identity:previous-anon-sent" })
        assertEquals(1, h.logs.codes().count { it == "identity:changed" })
    }

    @Test
    fun `the SDK owns previousAnonymousIdentifier but host keys, platform included, pass through`() = runTest {
        val h = Harness(anonymousId = ANON)
        val mos = h.mos()
        mos.decide(article())
        mos.identify(userJwt("user_42"))
        mos.decide(
            article(),
            JsonObject(
                mapOf(
                    "previousAnonymousIdentifier" to JsonPrimitive("host"), "hostFlag" to JsonPrimitive(true), "platform" to JsonPrimitive("wearos"),
                ),
            ),
        )
        val properties = propertiesOf(h, 1)!!
        assertEquals(JsonPrimitive(ANON), properties["previousAnonymousIdentifier"])
        assertEquals(JsonPrimitive(true), properties["hostFlag"])
        assertEquals(JsonPrimitive("wearos"), properties["platform"])
    }

    @Test
    fun `identify nil returns to the same persisted anonymous id`() = runTest {
        val h = Harness()
        val mos = h.mos()
        mos.decide(article())
        mos.identify(userJwt("user_42"))
        mos.decide(article())
        mos.identify(null)
        mos.decide(article())

        assertEquals(buildJsonObject { put("anonymousIdentifier", ANON) }, identityOf(h, 2))
        assertEquals(1, h.transport.requests.count { it.json()["identity"]!!.jsonObject.containsKey("createAnonymousIdentifier") })
    }

    @Test
    fun `redeem and counter updates cannot mint and fail with notConfigured`() = runTest {
        val h = Harness()
        val mos = h.mos()

        val result = mos.redeem(offerToken(null))

        assertEquals(FailureReason.NotConfigured, assertIs<RedemptionResult.Failed>(result).failure.reason)
        assertTrue(h.transport.requests.isEmpty())
        assertEquals(FlushResult(0, 0, 0, null), mos.flush())
        assertTrue(h.transport.requests.isEmpty())
    }

    @Test
    fun `a throwing jwtProvider falls through to anonymous`() = runTest {
        val h = Harness(anonymousId = ANON)
        val mos = h.mos { it.copy(identity = it.identity.copy(jwtProvider = { error("keychain locked") })) }

        val result = mos.decide(article())

        assertEquals(DecisionSource.Network, result.source)
        assertEquals(buildJsonObject { put("anonymousIdentifier", ANON) }, identityOf(h, 0))
    }

    @Test
    fun `reset forgets the anonymous id, cache and queue`() = runTest {
        val h = Harness()
        val mos = h.mos()
        mos.decide(article("article/1"))
        h.transport.handler = { throw java.io.IOException("offline") }
        mos.decide(article("article/2"))
        assertEquals(1, h.queueStore.load().size)

        mos.reset()

        assertNull(mos.anonymousIdentifier())
        assertTrue(h.cacheStore.load().isEmpty())
        assertTrue(h.queueStore.load().isEmpty())
        h.transport.handler = { ok(decisionBody(identifier = "mos-anon-v1-new")) }
        mos.decide(article())
        assertEquals(JsonPrimitive(true), identityOf(h, 2)["createAnonymousIdentifier"])
        assertFalse(propertiesOf(h, 2)?.containsKey("previousAnonymousIdentifier") ?: false)
    }

    @Test
    fun `identify result fields for anon to jwt, jwt to jwt', jwt to nil and same-token`() = runTest {
        val h = Harness(anonymousId = ANON)
        val mos = h.mos()
        mos.decide(article())
        assertEquals(1, h.cacheStore.load().size)
        val calls = h.transport.requests.size

        // anon -> jwt: resolved now, cache purged, link pending, no network call.
        assertEquals(IdentifyResult(true, IdentityKind.Anonymous, IdentityKind.Jwt, linkPending = true), mos.identify(userJwt("user_42")))
        assertTrue(h.cacheStore.load().isEmpty())
        assertEquals(1, h.logs.codes().count { it == "identity:changed" })
        assertEquals(calls, h.transport.requests.size)

        // Same token (and a refreshed token for the same sub): no change, the link still waits.
        assertEquals(IdentifyResult(false, IdentityKind.Jwt, IdentityKind.Jwt, linkPending = true), mos.identify(userJwt("user_42")))
        h.transport.handler = { ok(decisionBody(identifier = "user_42", isAuthenticated = true)) }
        mos.decide(article())
        assertEquals(IdentifyResult(false, IdentityKind.Jwt, IdentityKind.Jwt, linkPending = false), mos.identify(userJwt("user_42")))

        // jwt -> jwt'.
        assertEquals(IdentifyResult(true, IdentityKind.Jwt, IdentityKind.Jwt, linkPending = false), mos.identify(userJwt("user_43")))

        // jwt -> nil: back to the persisted anon id.
        assertEquals(IdentifyResult(true, IdentityKind.Jwt, IdentityKind.Anonymous, linkPending = false), mos.identify(null))
        assertEquals(3, h.logs.codes().count { it == "identity:changed" })
        assertEquals(calls + 1, h.transport.requests.size)
    }

    @Test
    fun `identify with nothing resolved yet and jwt to nil with no anonymous id`() = runTest {
        val h = Harness()
        var provided: String? = null
        val mos = h.mos { it.copy(identity = it.identity.copy(jwtProvider = { provided })) }

        // No previous key: the first identity is not a change.
        assertEquals(IdentifyResult(false, null, IdentityKind.Jwt, linkPending = false), mos.identify(userJwt("user_42")))
        // identify(nil) resolves through the provider.
        provided = userJwt("provider")
        assertEquals(IdentifyResult(true, IdentityKind.Jwt, IdentityKind.Jwt, linkPending = false), mos.identify(null))
        // Nothing left: the next decision would mint.
        provided = null
        assertEquals(IdentifyResult(true, IdentityKind.Jwt, null, linkPending = false), mos.identify(null))
        assertTrue(h.transport.requests.isEmpty())
    }

    @Test
    fun `persisted pending link survives a new MOS instance over the same stores`() = runTest {
        val h = Harness(anonymousId = ANON)
        h.mos().decide(article())
        val token = userJwt("user_42")
        h.mos().identify(token) // reads the persisted last key, so this is a change: purge and pending link
        assertTrue(h.cacheStore.load().isEmpty())
        assertEquals(jwtIdentityKey(token), h.identityStore.lastIdentityKey())
        assertEquals(ANON, h.identityStore.pendingPreviousAnonymousIdentifier())

        // "Relaunch": a third instance sends the link on its first decision.
        val relaunched = h.mos()
        assertEquals(IdentifyResult(false, IdentityKind.Jwt, IdentityKind.Jwt, linkPending = true), relaunched.identify(token))
        h.transport.handler = { ok(decisionBody(identifier = "user_42", isAuthenticated = true)) }
        assertTrue(relaunched.decide(article()).linkedAnonymousIdentifier)
        assertEquals(JsonPrimitive(ANON), propertiesOf(h, 1)!!["previousAnonymousIdentifier"])
        assertNull(h.identityStore.pendingPreviousAnonymousIdentifier())

        relaunched.decide(article())
        assertEquals(PLATFORM_ONLY, propertiesOf(h, 2))
    }

    @Test
    fun `persisted last identity key purges the cache after relaunch`() = runTest {
        val h = Harness(anonymousId = ANON)
        h.mos().decide(article())
        assertEquals(anonIdentityKey(ANON), h.identityStore.lastIdentityKey())
        assertEquals(1, h.cacheStore.load().size)

        // The host signed in while the app was dead; the relaunched SDK only sees the provider's token.
        val relaunched = h.mos { it.copy(identity = it.identity.copy(jwtProvider = { userJwt("user_42") })) }
        h.transport.handler = { throw java.io.IOException("offline") }
        val result = relaunched.decide(article())

        assertTrue(h.cacheStore.load().isEmpty(), "purged although this instance never saw the anonymous identity")
        assertTrue("identity:changed" in h.logs.codes())
        assertEquals(JsonPrimitive(ANON), propertiesOf(h, 1)!!["previousAnonymousIdentifier"])
        assertEquals(DecisionSource.Unavailable, result.source)
        assertFalse(result.linkedAnonymousIdentifier)
    }

    @Test
    fun `linkedAnonymousIdentifier`() = runTest {
        val h = Harness(anonymousId = ANON)
        val mos = h.mos()
        assertFalse(mos.decide(article()).linkedAnonymousIdentifier, "nothing to link")

        mos.identify(userJwt("user_42"))
        h.transport.handler = { ok(fixture("error-531.json"), 531) }
        assertFalse(mos.decide(article()).linkedAnonymousIdentifier, "carried but not accepted")
        h.transport.handler = { ok(decisionBody(identifier = "user_42", isAuthenticated = true)) }
        assertFalse(mos.decide(article(), policy = CachePolicy.CacheOnly).linkedAnonymousIdentifier, "not a network result")

        val linked = mos.decide(article())
        assertTrue(linked.linkedAnonymousIdentifier)
        assertEquals(DecisionSource.Network, linked.source)
        assertFalse(mos.decide(article()).linkedAnonymousIdentifier, "sent once")
    }

    @Test
    fun `an expired token answered anonymously keeps the link pending until an authenticated decision delivers it`() = runTest {
        val h = Harness(anonymousId = ANON)
        val mos = h.mos()
        mos.decide(article())
        mos.identify(userJwt("user_42"))

        // The server falls back to anonymous for a token it rejects, so that workflow never ran as the user.
        h.transport.handler = { ok(fixture("decision-success-jwt-fallback.json")) }
        val anonymous = mos.decide(article())
        assertEquals(DecisionSource.Network, anonymous.source)
        assertEquals(JsonPrimitive(ANON), propertiesOf(h, 1)!!["previousAnonymousIdentifier"])
        assertFalse(anonymous.linkedAnonymousIdentifier)
        assertEquals(ANON, h.identityStore.pendingPreviousAnonymousIdentifier())
        assertTrue("identity:previous-anon-sent" !in h.logs.codes())

        h.transport.handler = { ok(decisionBody(identifier = "user_42", isAuthenticated = true)) }
        val linked = mos.decide(article())
        assertEquals(JsonPrimitive(ANON), propertiesOf(h, 2)!!["previousAnonymousIdentifier"], "resent")
        assertTrue(linked.linkedAnonymousIdentifier)
        assertNull(h.identityStore.pendingPreviousAnonymousIdentifier())
        assertEquals(1, h.logs.codes().count { it == "identity:previous-anon-sent" })

        mos.decide(article())
        assertEquals(PLATFORM_ONLY, propertiesOf(h, 3), "cleared")
    }

    @Test
    fun `reset clears the persisted identity state`() = runTest {
        val h = Harness(anonymousId = ANON)
        val mos = h.mos()
        mos.decide(article())
        mos.identify(userJwt("user_42"))
        assertEquals(ANON, h.identityStore.pendingPreviousAnonymousIdentifier())

        mos.reset()

        assertNull(h.identityStore.anonymousIdentifier())
        assertNull(h.identityStore.lastIdentityKey())
        assertNull(h.identityStore.pendingPreviousAnonymousIdentifier())
        assertEquals(IdentifyResult(false, null, IdentityKind.Jwt, linkPending = false), mos.identify(userJwt("user_42")))
    }

    @Test
    fun `no self-link when identity returns to the pending anonymous id`() = runTest {
        val h = Harness(anonymousId = ANON)
        val mos = h.mos()
        mos.decide(article())
        assertTrue(mos.identify(userJwt("user_42")).linkPending)

        assertEquals(IdentifyResult(true, IdentityKind.Jwt, IdentityKind.Anonymous, linkPending = false), mos.identify(null))
        assertNull(h.identityStore.pendingPreviousAnonymousIdentifier())
        mos.decide(article())
        assertEquals(PLATFORM_ONLY, propertiesOf(h, 1), "the anon id is never linked to itself")
        assertTrue("identity:previous-anon-sent" !in h.logs.codes())
    }

    @Test
    fun `no self-link after relaunch with a persisted pending id`() = runTest {
        val h = Harness()
        val store = identityStore(ANON, jwtIdentityKey(userJwt("user_42")), ANON)
        val mos = h.mos { it.copy(identity = it.identity.copy(store = store)) }

        val result = mos.decide(article())

        assertEquals(PLATFORM_ONLY, propertiesOf(h, 0))
        assertFalse(result.linkedAnonymousIdentifier)
        assertNull(store.pendingPreviousAnonymousIdentifier())
    }

    /** An identity store whose first anonymous-id read parks on [gate], to hold a call between resolving and applying its identity. */
    private class ParkingIdentityStore(private val inner: InMemoryIdentityStore, val reached: CompletableDeferred<Unit>, val gate: CompletableDeferred<Unit>) :
        IdentityStore by inner {
        override suspend fun get(key: String): String? {
            val value = inner.get(key)
            if (key != ANONYMOUS_IDENTIFIER) return value
            reached.complete(Unit)
            gate.await()
            return value
        }
    }

    @Test
    fun `a reset between resolving the identity and applying it does not bring the old anonymous id back`() = runTest {
        val h = Harness()
        val reached = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val inner = identityStore(ANON)
        val store = ParkingIdentityStore(inner, reached, gate)
        val mos = h.mos { it.copy(identity = it.identity.copy(store = store)) }
        h.transport.handler = { ok(decisionBody(identifier = "mos-anon-new")) }

        val decision = async { mos.decide(article()) }
        reached.await()
        mos.reset()
        gate.complete(Unit)
        decision.await()

        assertEquals(buildJsonObject { put("createAnonymousIdentifier", true) }, identityOf(h, 0))
        assertEquals("anon:mos-anon-new", inner.lastIdentityKey())
        assertEquals("mos-anon-new", inner.anonymousIdentifier())
        assertNull(inner.pendingPreviousAnonymousIdentifier())
    }

    @Test
    fun `an identify during a slow jwtProvider wins over its anonymous result`() = runTest {
        val h = Harness(anonymousId = ANON)
        val gate = CompletableDeferred<Unit>()
        val mos = h.mos { it.copy(identity = it.identity.copy(jwtProvider = { gate.await(); null })) }
        h.transport.handler = { ok(decisionBody(identifier = "user_42", isAuthenticated = true)) }
        val token = userJwt("user_42")

        val decision = async { mos.decide(article()) }
        runCurrent()
        mos.identify(token)
        gate.complete(Unit)
        decision.await()

        assertEquals(JsonPrimitive(token), identityOf(h, 0)["userJwt"])
    }

    @Test
    fun `a pending anonymous link does not survive jwt to a different jwt`() = runTest {
        val h = Harness(anonymousId = ANON)
        val mos = h.mos()
        mos.decide(article())

        mos.identify(userJwt("first"))
        mos.identify(userJwt("second"))
        h.transport.handler = { ok(decisionBody(identifier = "second", isAuthenticated = true)) }
        mos.decide(article())

        assertNull(propertiesOf(h, 1)!!["previousAnonymousIdentifier"])
        assertNull(h.identityStore.pendingPreviousAnonymousIdentifier())
    }

    @Test
    fun `reset clears the explicit jwt`() = runTest {
        val h = Harness()
        val mos = h.mos()
        mos.identify(userJwt("user_42"))

        mos.reset()
        mos.decide(article())

        assertEquals(buildJsonObject { put("createAnonymousIdentifier", true) }, identityOf(h, 0))
    }

    @Test
    fun `coalesced decides share one result but only the sender is told the link was accepted`() = runTest {
        val h = Harness(anonymousId = ANON)
        val mos = h.mos()
        mos.decide(article())
        mos.identify(userJwt("user_42"))
        val gate = CompletableDeferred<Unit>()
        h.transport.handler = { gate.await(); ok(decisionBody(identifier = "user_42", isAuthenticated = true)) }

        val calls = (1..3).map { async { mos.decide(article()) } }
        runCurrent()
        gate.complete(Unit)
        val results = calls.awaitAll()

        assertEquals(2, h.transport.requests.size)
        assertEquals(1, results.count { it.linkedAnonymousIdentifier })
    }

    @Test
    fun `a jwtProvider that times out itself is a provider failure, not a cancellation`() = runTest {
        val h = Harness(anonymousId = ANON)
        val mos = h.mos { it.copy(identity = it.identity.copy(jwtProvider = { withTimeout(10.milliseconds) { awaitCancellation() } })) }

        val result = mos.decide(article())

        assertEquals(DecisionSource.Network, result.source)
        assertEquals(buildJsonObject { put("anonymousIdentifier", ANON) }, identityOf(h, 0))
        assertTrue("identity:provider-failed" in h.logs.codes())
        assertEquals(0, mos.flush().remaining)
        mos.redeem(offerToken(null))
    }

    @Test
    fun `a transport that times out itself is a requestFailed, not a cancellation`() = runTest {
        val h = Harness(anonymousId = ANON)
        h.transport.handler = { withTimeout(10.milliseconds) { awaitCancellation() } }

        val result = h.mos().decide(article())

        assertEquals(FailureReason.RequestFailed, result.failure?.reason)
    }

    @Test
    fun `a cancelled caller still cancels`() = runTest {
        val h = Harness(anonymousId = ANON)
        val gate = CompletableDeferred<Unit>()
        val mos = h.mos { it.copy(identity = it.identity.copy(jwtProvider = { gate.await(); null })) }

        val decision = async { mos.decide(article()) }
        runCurrent()
        decision.cancel()

        assertFailsWith<CancellationException> { decision.await() }
    }

    /** Like AndroidIdentityStore, whose writes are a cancellable withContext(IO): each last-key write waits for [gate]. */
    private class GatedStore(private val inner: InMemoryIdentityStore) : IdentityStore by inner {
        @Volatile var gate: CompletableDeferred<Unit>? = null
        val entered = CompletableDeferred<Unit>()

        override suspend fun set(key: String, value: String?) {
            inner.set(key, value)
            if (key != LAST_IDENTITY_KEY) return
            gate?.let {
                entered.complete(Unit)
                it.await()
            }
        }
    }

    @Test
    fun `cancelling the call that notices a sign-in still purges and keeps the link`() = runTest {
        val h = Harness(anonymousId = ANON)
        val store = GatedStore(h.identityStore)
        val mos = h.mos { it.copy(identity = it.identity.copy(store = store)) }
        mos.decide(article())
        val gate = CompletableDeferred<Unit>()
        store.gate = gate

        val job = launch { mos.identify(userJwt("user_42")) }
        store.entered.await()
        job.cancel()
        gate.complete(Unit)
        job.join()
        store.gate = null

        assertTrue(job.isCancelled)
        assertTrue("identity:changed" in h.logs.codes())
        assertTrue(h.cacheStore.load().isEmpty())
        assertEquals(ANON, h.identityStore.pendingPreviousAnonymousIdentifier())
        h.transport.handler = { ok(decisionBody(identifier = "user_42", isAuthenticated = true)) }
        val linked = mos.decide(article("article/2"))
        assertEquals(JsonPrimitive(ANON), propertiesOf(h, 1)!!["previousAnonymousIdentifier"])
        assertTrue(linked.linkedAnonymousIdentifier)
    }

    @Test
    fun `a cancelled reset still forgets everything`() = runTest {
        val h = Harness(anonymousId = ANON)
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val store = object : IdentityStore by h.identityStore {
            override suspend fun set(key: String, value: String?) {
                h.identityStore.set(key, value)
                if (key != ANONYMOUS_IDENTIFIER || value != null) return
                entered.complete(Unit)
                gate.await()
            }
        }
        val mos = h.mos { it.copy(identity = it.identity.copy(store = store)) }
        mos.decide(article())

        val job = launch { mos.reset() }
        entered.await()
        job.cancel()
        gate.complete(Unit)
        job.join()

        assertTrue(h.cacheStore.load().isEmpty())
        assertNull(h.identityStore.lastIdentityKey())
        mos.decide(article())
        assertEquals(buildJsonObject { put("createAnonymousIdentifier", true) }, identityOf(h, 1))
    }

    @Test
    fun `a load of the persisted identity cancelled half-way runs again`() = runTest {
        val h = Harness(anonymousId = ANON)
        h.identityStore.set(LAST_IDENTITY_KEY, "anon:$ANON")
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val store = object : IdentityStore by h.identityStore {
            override suspend fun get(key: String): String? {
                if (key == LAST_IDENTITY_KEY && !gate.isCompleted) {
                    entered.complete(Unit)
                    gate.await()
                }
                return h.identityStore.get(key)
            }
        }
        val mos = h.mos { it.copy(identity = it.identity.copy(store = store)) }

        val job = launch { mos.identify(userJwt("user_42")) }
        entered.await()
        job.cancel()
        job.join()
        gate.complete(Unit)

        h.transport.handler = { ok(decisionBody(identifier = "user_42", isAuthenticated = true)) }
        mos.decide(article())
        assertTrue("identity:changed" in h.logs.codes())
        assertEquals(JsonPrimitive(ANON), propertiesOf(h, 0)!!["previousAnonymousIdentifier"])
    }

    @Test
    fun `an anonymous answer to a JWT request is returned but neither cached nor metered`() = runTest {
        val h = Harness(anonymousId = ANON)
        val mos = h.mos()
        mos.identify(userJwt("user_42"))
        h.transport.handler = { ok(decisionBody(identifier = "user_42", isAuthenticated = true, consumedUnits = 1.0, totalUnits = 5.0)) }
        mos.decide(article())
        val cached = h.cacheStore.load()
        val ledger = h.meterStore.load()
        assertEquals(1, cached.size)
        assertEquals(1, ledger.size)

        // An expired token: the server answers as a fresh anonymous identity, paywalled.
        h.transport.handler = {
            ok(decisionBody(identifier = "mos-anon-v1-minted", hasAccess = false, consumedUnits = 5.0, totalUnits = 5.0))
        }
        val result = mos.decide(article())

        assertEquals(DecisionSource.Network, result.source)
        assertEquals(false, result.decision?.identity?.isAuthenticated)
        assertEquals(cached, h.cacheStore.load())
        assertEquals(ledger, h.meterStore.load())
        assertEquals(ANON, h.identityStore.anonymousIdentifier())
        val skipped = h.logs.events.single { it.code == "cache:skipped" }
        assertEquals(LogLevel.Debug, skipped.level)
        assertEquals(
            buildJsonObject {
                put("reason", "anonymous-answer")
                put("surface", SURFACE)
            },
            skipped.context,
        )
    }

    @Test
    fun `a minted id the store does not keep is used from memory for the process, logged once`() = runTest {
        val stores: List<(InMemoryIdentityStore) -> IdentityStore> = listOf(
            { inner ->
                object : IdentityStore by inner {
                    override suspend fun set(key: String, value: String?) = if (key == ANONYMOUS_IDENTIFIER) Unit else inner.set(key, value)
                }
            },
            { inner ->
                object : IdentityStore by inner {
                    override suspend fun set(key: String, value: String?) =
                        if (key == ANONYMOUS_IDENTIFIER) throw IOException("disk full") else inner.set(key, value)
                }
            },
        )
        for (make in stores) {
            val h = Harness()
            val mos = h.mos { it.copy(identity = it.identity.copy(store = make(h.identityStore))) }

            mos.decide(article())
            mos.decide(article())
            mos.decide(article())

            assertEquals(buildJsonObject { put("anonymousIdentifier", ANON) }, identityOf(h, 1))
            assertEquals(buildJsonObject { put("anonymousIdentifier", ANON) }, identityOf(h, 2))
            assertEquals(ANON, mos.anonymousIdentifier())
            assertEquals(1, h.cacheStore.load().size)
            val failed = h.logs.events.single { it.code == "identity:store-failed" }
            assertEquals(LogLevel.Warn, failed.level)
            assertEquals(buildJsonObject { put("store", "identity") }, failed.context)
            assertTrue("identity:anon-persisted" !in h.logs.codes())

            mos.reset()
            assertNull(mos.anonymousIdentifier())
        }
    }

    @Test
    fun `a number is printed as JavaScript's Number toString prints it, the same table as iOS`() {
        val printed = listOf(
            123.0 to "123", -5.0 to "-5", 0.0 to "0", -0.0 to "0", 1.5 to "1.5", 0.1 to "0.1", 123.456 to "123.456",
            123_456_789_012.0 to "123456789012", 9_007_199_254_740_992.0 to "9007199254740992", 1e20 to "100000000000000000000",
            1e21 to "1e+21", 1.2345e25 to "1.2345e+25", 0.000001 to "0.000001", 0.0000015 to "0.0000015", 1e-7 to "1e-7",
            1.5e-10 to "1.5e-10", 5e-324 to "5e-324", 1.7976931348623157e308 to "1.7976931348623157e+308",
        )
        for ((value, expected) in printed) assertEquals(expected, javaScriptNumber(value), "$value")
        assertNull(javaScriptNumber(Double.POSITIVE_INFINITY))
        assertNull(javaScriptNumber(Double.NaN))
    }

    @Test
    fun `claimKey names the claim the identity key hashes, a number printed as JavaScript does`() {
        fun key(claim: String) = jwtIdentityKey(jwt("""{"uid":$claim,"sub":"other"}"""), claimKey = "uid")
        val printed = mapOf(
            "123" to "123", "123.0" to "123", "-0" to "0", "1E5" to "100000", "1.5" to "1.5",
            "12345678901234567890" to "12345678901234567000", "1e21" to "1e+21", "\"user_42\"" to "user_42",
        )
        for ((claim, expected) in printed) assertEquals("jwt:" + sha256Hex(expected), key(claim), claim)
        // Missing, empty, not finite or another type: the whole token.
        val wholeToken = listOf(
            """{"sub":"user_42"}""", """{"uid":""}""", """{"uid":true}""", """{"uid":null}""", """{"uid":{"id":1}}""", """{"uid":[1]}""",
            """{"uid":1e400}""",
        )
        for (payload in wholeToken) {
            val token = jwt(payload)
            assertEquals("jwt:" + sha256Hex(token), jwtIdentityKey(token, claimKey = "uid"), payload)
        }
        assertEquals("jwt:" + sha256Hex("user_42"), jwtIdentityKey(userJwt("user_42")), "sub by default")
    }

    @Test
    fun `a refreshed token keeps the identity when claimKey names a numeric claim without sub`() = runTest {
        val h = Harness(anonymousId = ANON)
        var token = jwt("""{"uid":42,"iat":1}""")
        val mos = h.mos { it.copy(identity = it.identity.copy(jwtProvider = { token }, claimKey = "uid")) }
        h.transport.handler = { ok(decisionBody(identifier = "42", isAuthenticated = true)) }
        mos.decide(article())
        token = jwt("""{"uid":42,"iat":2}""")
        mos.decide(article("article/2"))

        assertTrue("identity:changed" !in h.logs.codes())
        assertEquals(listOf("jwt:" + sha256Hex("42")), h.cacheStore.load().map { it.identityKey }.distinct())
        assertEquals(2, h.cacheStore.load().size)
    }

    @Test
    fun `jwt keys treat an empty sub as absent and accept two-part tokens like Swift`() {
        val emptySub = jwt("""{"sub":""}""")
        val other = jwt("""{"sub":"","iat":2}""")
        assertEquals("jwt:" + sha256Hex(emptySub), jwtIdentityKey(emptySub))
        assertNotEquals(jwtIdentityKey(emptySub), jwtIdentityKey(other))
        val twoParts = userJwt("user_42").substringBeforeLast('.')
        assertEquals("jwt:" + sha256Hex("user_42"), jwtIdentityKey(twoParts))
        val fourParts = userJwt("user_42") + ".extra"
        assertEquals("jwt:" + sha256Hex("user_42"), jwtIdentityKey(fourParts))
    }

    @Test
    fun `a link already sent is still reported once when the account switches before the response`() = runTest {
        val h = Harness(anonymousId = ANON)
        val mos = h.mos()
        mos.decide(article())
        mos.identify(userJwt("user_42"))
        val gate = CompletableDeferred<Unit>()
        h.transport.handler = { gate.await(); ok(decisionBody(identifier = "user_42", isAuthenticated = true)) }

        val inFlight = async { mos.decide(article()) }
        runCurrent()
        assertEquals(JsonPrimitive(ANON), propertiesOf(h, 1)!!["previousAnonymousIdentifier"])
        mos.identify(userJwt("user_43"))
        gate.complete(Unit)
        val result = inFlight.await()

        assertTrue(result.linkedAnonymousIdentifier)
        assertNull(h.identityStore.pendingPreviousAnonymousIdentifier())
        assertEquals(1, h.logs.codes().count { it == "identity:previous-anon-sent" })
        h.transport.handler = { ok(decisionBody(identifier = "user_43", isAuthenticated = true)) }
        mos.decide(article())
        assertNull(propertiesOf(h, 2)!!["previousAnonymousIdentifier"])
    }
}
