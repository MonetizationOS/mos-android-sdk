package com.monetizationos.sdk

import com.monetizationos.sdk.cache.CacheEntry
import com.monetizationos.sdk.cache.DecisionCache
import com.monetizationos.sdk.cache.InMemoryDecisionCacheStore
import com.monetizationos.sdk.cache.DecisionCacheStore
import com.monetizationos.sdk.cache.FileDecisionCacheStore
import com.monetizationos.sdk.cache.StoreSchemaMismatchException
import com.monetizationos.sdk.logging.LogLevel
import com.monetizationos.sdk.logging.Logger
import com.monetizationos.sdk.metering.InMemoryMeteringQueueStore
import com.monetizationos.sdk.metering.MeteringEntry
import com.monetizationos.sdk.metering.MeteringQueue
import com.monetizationos.sdk.metering.MeteringQueueStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

@OptIn(ExperimentalCoroutinesApi::class)
class LoggingTest {
    private val requiredCodes = listOf(
        "decision:start", "decision:success", "decision:error", "decision:cache-hit", "decision:fallback",
        "decision:unavailable", "cache:put", "cache:evicted", "cache:cleared", "cache:corrupt",
        "identity:anon-persisted", "identity:changed", "identity:previous-anon-sent",
        "metering:queued", "metering:deduped", "metering:dropped", "metering:rejected", "metering:flushed",
        "metering:flush-failed", "redeem:success", "redeem:declined", "redeem:error",
        "endpoint:success", "endpoint:error", "config:invalid-key",
    )

    @Test
    fun `log codes emitted and tokens absent from every logged string`() = runTest {
        val h = Harness()
        val secretKey = "sk_live_env_supersecret"
        val user = userJwt("user_42")
        val offer = offerToken(NOW + 1.hours.inWholeMilliseconds)
        val withOffer = decisionBody(surfaceProperties = buildJsonObject { putJsonObject("slot") { put("token", offer) } })
        val corruptCache = File(Files.createTempDirectory("mos-log").toFile(), "cache.json").apply { writeText("garbage") }
        val queueStore = InMemoryMeteringQueueStore(
            listOf(MeteringEntry("anon:$ANON", ANON, "default:feat_old.enabled", "old", 1.0, NOW - 8.days.inWholeMilliseconds)),
        )

        assertFailsWith<IllegalArgumentException> { MOS.inMemory(h.config().copy(publicKey = secretKey)) }
        val mos = h.mos(cache = FileDecisionCacheStore(corruptCache), queue = queueStore)

        // Mint, persist, cache (after reading a corrupt cache file).
        h.transport.handler = { ok(withOffer) }
        mos.decide(article("article/1"))
        mos.backgroundFlush?.join()

        // Offline: exact hit, fallback, dedupe, unavailable.
        h.transport.handler = { throw IOException("offline") }
        mos.decide(article("article/1"))
        mos.decide(article("article/2"))
        mos.decide(article("article/2"))
        mos.decide(article("article/3"), policy = CachePolicy.NetworkOnly)

        // Flush: the 8-day-old entry is dropped, a 5xx stops the flush, then a 400 rejects and a 200 sends.
        h.transport.handler = { ok(fixture("error-531.json"), 531) }
        mos.flush()
        h.transport.handler = { request ->
            if (request.json()["resourceId"] == JsonPrimitive("article/1")) ok(fixture("error-400.json"), 400) else ok(SUCCESS)
        }
        mos.flush()

        // Redemption and endpoints.
        h.transport.handler = { ok(fixture("redemption-navigate.json")) }
        mos.redeem(offer)
        h.transport.handler = { ok(fixture("redemption-failure.json")) }
        mos.redeem(offer)
        h.transport.handler = { ok(fixture("error-400.json"), 400) }
        mos.redeem(offer)
        h.transport.handler = { ok("""{"ok":true}""") }
        mos.callEndpoint("registration", body = buildJsonObject { put("userJwt", user) })
        h.transport.handler = { ok("oops", 500) }
        mos.callEndpoint("registration", body = buildJsonObject { put("userJwt", user) })

        // Sign in: identity change, previous anon id sent, offer-bounded expiry evicted later.
        mos.identify(user)
        h.transport.handler = { ok(withOffer.replace("\"isAuthenticated\":false", "\"isAuthenticated\":true")) }
        mos.decide(article("article/1"), JsonObject(mapOf("userJwt" to JsonPrimitive(user))))
        h.clock.advance(2.hours)
        mos.decide(article("article/1"), policy = CachePolicy.CacheOnly)

        val codes = h.logs.codes().toSet()
        assertEquals(emptyList(), requiredCodes.filter { it !in codes }, "every required code is emitted")

        for (event in h.logs.events) {
            val line = event.toString()
            for (secret in listOf(user, offer, secretKey, user.split('.')[1], offer.split('.')[1])) {
                assertTrue(secret !in line, "${event.code} leaks a token")
            }
            if (event.level != LogLevel.Debug) assertTrue(ANON !in line, "${event.code} logs the anon id above debug")
        }
        val change = h.logs.events.first { it.code == "identity:changed" }
        assertEquals(setOf("anon", "jwt"), change.context.values.map { (it as JsonPrimitive).content }.toSet())
    }

    @Test
    fun `coalescing, stale-response, schema-mismatch and fail-fast tier codes are emitted without tokens`() = runTest {
        val h = Harness()
        val user = userJwt("user_42")
        val other = userJwt("user_43")
        val offer = offerToken(NOW + 1.hours.inWholeMilliseconds)
        // In memory: real file IO would let runTest skip virtual time past the request timeout.
        val oldCache = object : DecisionCacheStore {
            override suspend fun load(): List<CacheEntry> = throw StoreSchemaMismatchException(JsonNull)
            override suspend fun save(entries: List<CacheEntry>) = Unit
        }
        val oldQueue = object : MeteringQueueStore {
            override suspend fun load(): List<MeteringEntry> = throw StoreSchemaMismatchException(JsonNull)
            override suspend fun save(entries: List<MeteringEntry>) = Unit
        }
        val mos = h.mos(cache = oldCache, queue = oldQueue)
        mos.identify(user)

        // Coalesced decisions and redemptions, made stale by a switch of user while in flight.
        val gate = CompletableDeferred<Unit>()
        h.transport.handler = { request ->
            gate.await()
            if (request.url.endsWith(OFFER_REDEMPTIONS_PATH)) ok(fixture("redemption-navigate.json")) else ok(decisionBody(identifier = "user_42", isAuthenticated = true))
        }
        val calls = listOf(async { mos.decide(article()) }, async { mos.decide(article()) }, async { mos.redeem(offer) }, async { mos.redeem(offer) })
        runCurrent()
        mos.identify(other)
        gate.complete(Unit)
        calls.awaitAll()

        // Degraded, then recovered.
        h.transport.handler = { delay(1.hours); ok("") }
        mos.decide(article())
        h.transport.handler = { ok(decisionBody(identifier = "user_43", isAuthenticated = true)) }
        mos.decide(article())
        mos.flush()

        val codes = listOf(
            "decision:coalesced", "redeem:coalesced", "identity:stale-response",
            "cache:schema-mismatch", "transport:degraded", "transport:recovered",
        )
        assertEquals(emptyList(), codes.filter { it !in h.logs.codes() }, "every code is emitted")
        for (event in h.logs.events) {
            val line = event.toString()
            for (secret in listOf(user, other, offer, user.split('.')[1], other.split('.')[1], offer.split('.')[1])) {
                assertTrue(secret !in line, "${event.code} leaks a token")
            }
        }
        val levels = h.logs.events.associate { it.code to it.level }
        assertEquals(LogLevel.Debug, levels["decision:coalesced"])
        assertEquals(LogLevel.Debug, levels["redeem:coalesced"])
        assertEquals(LogLevel.Debug, levels["identity:stale-response"])
        assertEquals(LogLevel.Warn, levels["cache:schema-mismatch"])
        assertEquals(LogLevel.Info, levels["transport:degraded"])
        assertEquals(LogLevel.Info, levels["transport:recovered"])
    }

    @Test
    fun `enumerated context values are lower kebab-case`() = runTest {
        val logs = LogCollector()
        val logger = Logger(logs)
        fun reasons(code: String) = logs.events.filter { it.code == code }.map { (it.context["reason"] as JsonPrimitive).content }.toSet()

        val cache = DecisionCache(InMemoryDecisionCacheStore(), maxEntries = 1, logger)
        val decision = buildJsonObject { put("status", "success") }
        cache.put(CacheEntry(SURFACE, "anon:$ANON", "article/1", decision, NOW, NOW + 10))
        cache.put(CacheEntry(SURFACE, "anon:$ANON", "article/2", decision, NOW + 1, NOW + 10))
        cache.exact(SURFACE, "anon:$ANON", "article/2", null, NOW + 20)
        assertEquals(setOf("max-entries", "expired"), reasons("cache:evicted"))

        val anon = { n: Int, at: Long -> MeteringEntry("anon:$ANON", ANON, "c$n", "r$n", 1.0, at) }
        val pruned = MeteringQueue(InMemoryMeteringQueueStore(listOf(anon(0, NOW - 8.days.inWholeMilliseconds), MeteringEntry("jwt:gone", null, "c", "r", 1.0, NOW))), logger)
        pruned.prune(NOW, "jwt:current")
        val full = MeteringQueue(InMemoryMeteringQueueStore((1..500).map { anon(it, NOW + it) }), logger)
        full.enqueue(anon(501, NOW + 501))
        assertEquals(setOf("expired", "identity-gone", "limit"), reasons("metering:dropped"))

        val h = Harness(anonymousId = ANON)
        val mos = h.mos()
        mos.decide(article())
        mos.identify(userJwt("user_42"))
        mos.reset()
        assertEquals(listOf("identity-changed", "reset"), h.logs.events.filter { it.code == "cache:cleared" }.map { (it.context["reason"] as JsonPrimitive).content })
    }

    @Test
    fun `nothing is logged without onLog and a throwing logger never breaks a call`() = runTest {
        val h = Harness(anonymousId = ANON)
        val mos = h.mos { it.copy(onLog = { error("logger bug") }) }
        assertEquals(DecisionSource.Network, mos.decide(article()).source)
    }
}
