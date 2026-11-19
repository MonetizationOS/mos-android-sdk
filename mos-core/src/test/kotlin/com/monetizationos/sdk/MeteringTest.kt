package com.monetizationos.sdk

import com.monetizationos.sdk.identity.jwtIdentityKey
import com.monetizationos.sdk.logging.LogLevel
import com.monetizationos.sdk.logging.Logger
import com.monetizationos.sdk.metering.InMemoryMeteringQueueStore
import com.monetizationos.sdk.metering.MAX_QUEUE_ENTRIES
import com.monetizationos.sdk.metering.MeteringEntry
import com.monetizationos.sdk.metering.MeteringQueue
import com.monetizationos.sdk.transport.HttpRequest
import com.monetizationos.sdk.transport.HttpResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalCoroutinesApi::class)
class MeteringTest {
    private val offline: suspend (HttpRequest) -> HttpResponse = { throw IOException("offline") }

    /** Caches article/1 online, then serves article/2 from the fallback while offline. */
    private suspend fun cachedServe(h: Harness, mos: MOS, body: String = decisionBody()): DecisionResult {
        h.transport.handler = { ok(body) }
        mos.decide(article("article/1"))
        h.transport.handler = offline
        return mos.decide(article("article/2"))
    }

    private fun entry(
        counterId: String = "default:feat_article.enabled",
        resourceId: String = "article/1",
        identityKey: String = "anon:$ANON",
        anonymousIdentifier: String? = ANON,
        update: Double = 1.0,
        enqueuedAt: Long = NOW,
    ) = MeteringEntry(identityKey, anonymousIdentifier, counterId, resourceId, update, enqueuedAt)

    @Test
    fun `metering is not enqueued for a network decision`() = runTest {
        val h = Harness(anonymousId = ANON)
        h.mos().decide(article())
        assertTrue(h.queueStore.load().isEmpty())
    }

    @Test
    fun `metering is not enqueued when hasAccess is false`() = runTest {
        val h = Harness(anonymousId = ANON)
        assertEquals("article/1", (cachedServe(h, h.mos(), decisionBody(hasAccess = false)).source as DecisionSource.Fallback).fromResourceId)
        assertTrue(h.queueStore.load().isEmpty())
    }

    @Test
    fun `metering is not enqueued when consumedInRequest is false`() = runTest {
        val h = Harness(anonymousId = ANON)
        cachedServe(h, h.mos(), decisionBody(consumedInRequest = false))
        assertTrue(h.queueStore.load().isEmpty())
    }

    @Test
    fun `metering is not enqueued for a meter without a counterId`() = runTest {
        val h = Harness(anonymousId = ANON)
        cachedServe(h, h.mos(), decisionBody(counterId = null))
        assertTrue(h.queueStore.load().isEmpty())
    }

    @Test
    fun `cached serve enqueues the consumed meter for the requested resource`() = runTest {
        val h = Harness(anonymousId = ANON)
        val mos = h.mos()
        cachedServe(h, mos, decisionBody(consumedUnitsInRequest = null))

        assertEquals(listOf(entry(resourceId = "article/2")), h.queueStore.load())
        assertTrue("metering:queued" in h.logs.codes())

        // Exact cache hits enqueue too, with consumedUnitsInRequest as the update.
        val h2 = Harness(anonymousId = ANON)
        cachedServe(h2, h2.mos(), decisionBody(consumedUnitsInRequest = 3))
        assertEquals(3.0, h2.queueStore.load().single().update)
    }

    @Test
    fun `JWT identities queue no token`() = runTest {
        val h = Harness()
        val mos = h.mos()
        val token = userJwt("user_42")
        mos.identify(token)
        cachedServe(h, mos, decisionBody(identifier = "user_42", isAuthenticated = true))

        val queued = h.queueStore.load().single()
        assertTrue(queued.identityKey.startsWith("jwt:"))
        assertNull(queued.anonymousIdentifier)
        assertTrue(token !in queued.toString())
    }

    @Test
    fun `dedupe`() = runTest {
        val h = Harness(anonymousId = ANON)
        val mos = h.mos()
        cachedServe(h, mos)
        mos.decide(article("article/2"))
        mos.decide(article("article/2"), policy = CachePolicy.CacheOnly)

        assertEquals(1, h.queueStore.load().size)
        assertEquals(2, h.logs.codes().count { it == "metering:deduped" })
        mos.decide(article("article/3"))
        assertEquals(2, h.queueStore.load().size)
    }

    @Test
    fun `queue drops the oldest beyond 500 entries`() = runTest {
        val logs = LogCollector()
        val store = InMemoryMeteringQueueStore()
        val queue = MeteringQueue(store, Logger(logs))
        for (i in 0..MAX_QUEUE_ENTRIES) queue.enqueue(entry(resourceId = "article/$i", enqueuedAt = NOW + i))

        val stored = store.load()
        assertEquals(500, stored.size)
        assertEquals("article/1", stored.first().resourceId)
        assertEquals(1, logs.codes().count { it == "metering:dropped" })
    }

    @Test
    fun `flush sends oldest first and removes on 2xx success`() = runTest {
        val h = Harness(anonymousId = ANON)
        h.queueStore.save(listOf(entry(resourceId = "article/2", enqueuedAt = NOW + 1), entry(resourceId = "article/1")))
        h.transport.handler = { ok(fixture("counter-update-success.json")) }

        val result = h.mos().flush()

        assertEquals(FlushResult(sent = 2, dropped = 0, remaining = 0, failure = null), result)
        val bodies = h.transport.to(COUNTER_UPDATES_PATH).map { it.json() }
        assertEquals(
            buildJsonObject {
                put("identity", buildJsonObject { put("anonymousIdentifier", ANON) })
                put("counterId", "default:feat_article.enabled")
                put("update", 1)
                put("resourceId", "article/1")
            },
            bodies.first(),
        )
        assertEquals(JsonPrimitive("article/2"), bodies[1]["resourceId"])
        assertTrue("metering:flushed" in h.logs.codes())
    }

    @Test
    fun `flush removes and logs a 4xx rejection, then carries on`() = runTest {
        val h = Harness(anonymousId = ANON)
        h.queueStore.save(listOf(entry(resourceId = "article/1"), entry(resourceId = "article/2", enqueuedAt = NOW + 1)))
        h.transport.handler = { request ->
            if (request.json()["resourceId"] == JsonPrimitive("article/1")) ok(fixture("error-400.json"), 400) else ok(SUCCESS)
        }

        val result = h.mos().flush()

        assertEquals(FlushResult(sent = 1, dropped = 1, remaining = 0, failure = null), result)
        assertTrue("metering:rejected" in h.logs.codes())
    }

    @Test
    fun `flush stops and keeps everything on 429, 5xx and network errors`() = runTest {
        val cases: List<Pair<FailureReason, suspend (HttpRequest) -> HttpResponse>> = listOf(
            FailureReason.RateLimited to { ok(fixture("error-429.json"), 429) },
            FailureReason.ApiError to { ok(fixture("error-531.json"), 531) },
            FailureReason.HttpError to { ok(fixture("invalid-json.txt"), 503) },
            FailureReason.RequestFailed to offline,
        )
        for ((reason, handler) in cases) {
            val h = Harness(anonymousId = ANON)
            val queued = listOf(entry(resourceId = "article/1"), entry(resourceId = "article/2", enqueuedAt = NOW + 1))
            h.queueStore.save(queued)
            h.transport.handler = handler

            val result = h.mos().flush()

            assertEquals(reason, result.failure?.reason, "reason for $reason")
            assertEquals(FlushResult(0, 0, 2, result.failure), result)
            assertEquals(1, h.transport.requests.size, "stops after the first failure for $reason")
            assertEquals(queued, h.queueStore.load())
            assertTrue("metering:flush-failed" in h.logs.codes())
        }
    }

    @Test
    fun `a 401 stops the flush and keeps the entry`() = runTest {
        val h = Harness(anonymousId = ANON)
        val queued = listOf(entry(resourceId = "article/1"), entry(resourceId = "article/2", enqueuedAt = NOW + 1))
        h.queueStore.save(queued)
        h.transport.handler = { ok(fixture("error-401.json"), 401) }

        val result = h.mos().flush()

        assertEquals(FailureReason.Unauthorized, result.failure?.reason)
        assertEquals(FlushResult(sent = 0, dropped = 0, remaining = 2, failure = result.failure), result)
        assertEquals(1, h.transport.requests.size)
        assertEquals(queued, h.queueStore.load())
        assertTrue("metering:rejected" !in h.logs.codes())
    }

    @Test
    fun `a 401 or 429 in either the HTTP status or the envelope statusCode keeps the entry`() = runTest {
        val envelope400 = """{"status":"error","statusCode":400,"message":"Bad request"}"""
        val replies = listOf(
            ok(envelope400, 401),
            ok(envelope400, 429),
            ok(fixture("error-401.json"), 400),
            ok(fixture("error-429.json"), 400),
        )
        for (reply in replies) {
            val h = Harness(anonymousId = ANON)
            h.queueStore.save(listOf(entry()))
            h.transport.handler = { reply }

            val result = h.mos().flush()

            assertEquals(FlushResult(sent = 0, dropped = 0, remaining = 1, failure = result.failure), result, "HTTP ${reply.status}: ${reply.text}")
            assertEquals(listOf(entry()), h.queueStore.load())
        }
    }

    @Test
    fun `metering log contexts match iOS`() = runTest {
        val h = Harness(anonymousId = ANON)
        h.queueStore.save(listOf(entry(resourceId = "article/1"), entry(resourceId = "article/2", enqueuedAt = NOW + 1)))
        h.transport.handler = { request ->
            if (request.json()["resourceId"] == JsonPrimitive("article/1")) ok(fixture("error-400.json"), 400) else ok(SUCCESS)
        }
        val mos = h.mos()
        mos.flush()
        assertEquals(
            buildJsonObject {
                put("counterId", "default:feat_article.enabled")
                put("resourceId", "article/1")
                put("status", 400)
                put("statusCode", 400)
            },
            h.logs.events.single { it.code == "metering:rejected" }.context,
        )
        assertEquals(
            buildJsonObject {
                put("sent", 1)
                put("remaining", 0)
                put("dropped", 1)
            },
            h.logs.events.single { it.code == "metering:flushed" }.context,
        )

        h.queueStore.save(listOf(entry(resourceId = "article/3"), entry(resourceId = "article/4", enqueuedAt = NOW + 1)))
        val reload = h.mos()
        h.transport.handler = { request ->
            if (request.json()["resourceId"] == JsonPrimitive("article/3")) ok(SUCCESS) else throw IOException("offline")
        }
        reload.flush()
        assertEquals(
            buildJsonObject {
                put("reason", "requestFailed")
                put("status", JsonNull)
                put("sent", 1)
                put("remaining", 1)
            },
            h.logs.events.single { it.code == "metering:flush-failed" }.context,
        )

        val served = Harness(anonymousId = ANON)
        cachedServe(served, served.mos())
        assertEquals(LogLevel.Debug, served.logs.events.single { it.code == "metering:queued" }.level)
    }

    @Test
    fun `flush treats an error envelope on a 200 by its statusCode`() = runTest {
        val h = Harness(anonymousId = ANON)
        h.queueStore.save(listOf(entry()))
        h.transport.handler = { ok(fixture("error-400.json"), 200) }
        assertEquals(FlushResult(0, 1, 0, null), h.mos().flush())
    }

    @Test
    fun `entries older than seven days are dropped at flush`() = runTest {
        val h = Harness(anonymousId = ANON)
        h.queueStore.save(listOf(entry(resourceId = "old", enqueuedAt = NOW - 8.days.inWholeMilliseconds), entry(resourceId = "new")))
        h.transport.handler = { ok(SUCCESS) }

        val result = h.mos().flush()

        assertEquals(FlushResult(sent = 1, dropped = 1, remaining = 0, failure = null), result)
        assertEquals(listOf(JsonPrimitive("new")), h.transport.requests.map { it.json()["resourceId"] })
        assertTrue("metering:dropped" in h.logs.codes())
    }

    @Test
    fun `identity scoping at flush`() = runTest {
        val h = Harness(anonymousId = ANON)
        val other = entry(identityKey = "anon:other", anonymousIdentifier = "other", resourceId = "b")
        h.queueStore.save(listOf(entry(resourceId = "a"), other, entry(identityKey = "jwt:gone", anonymousIdentifier = null, resourceId = "c")))
        h.transport.handler = { ok(SUCCESS) }

        val result = h.mos().flush()

        // Current anon entries sent; another anon identity kept but not counted; a JWT identity no longer current dropped.
        assertEquals(FlushResult(sent = 1, dropped = 1, remaining = 0, failure = null), result)
        assertEquals(listOf(other), h.queueStore.load())
        assertEquals(listOf(JsonPrimitive("a")), h.transport.requests.map { it.json()["resourceId"] })
    }

    @Test
    fun `flush sends the current JWT for jwt entries`() = runTest {
        val h = Harness()
        val mos = h.mos()
        val token = userJwt("user_42")
        mos.identify(token)
        cachedServe(h, mos, decisionBody(identifier = "user_42", isAuthenticated = true))
        h.transport.handler = { ok(SUCCESS) }

        assertEquals(1, mos.flush().sent)
        assertEquals(buildJsonObject { put("userJwt", token) }, h.transport.to(COUNTER_UPDATES_PATH).single().json()["identity"])
    }

    @Test
    fun `auto-flush after a network decision`() = runTest {
        val h = Harness(anonymousId = ANON)
        val mos = h.mos()
        cachedServe(h, mos)
        assertEquals(1, h.queueStore.load().size)

        val gate = CompletableDeferred<Unit>()
        h.transport.handler = { request ->
            if (request.url.endsWith(COUNTER_UPDATES_PATH)) {
                gate.await()
                ok(SUCCESS)
            } else {
                ok(decisionBody())
            }
        }
        val result = mos.decide(article("article/3"))

        // The decision came back while the flush is still blocked on its request.
        assertEquals(DecisionSource.Network, result.source)
        assertEquals(1, h.queueStore.load().size)
        gate.complete(Unit)
        mos.backgroundFlush!!.join()
        assertTrue(h.queueStore.load().isEmpty())
        assertEquals(1, h.transport.to(COUNTER_UPDATES_PATH).size)
        assertTrue("metering:flushed" in h.logs.codes())
    }

    @Test
    fun `no automatic flush without a successful network decision`() = runTest {
        val h = Harness(anonymousId = ANON)
        val mos = h.mos()
        cachedServe(h, mos)
        h.clock.advance(1.minutes)
        mos.decide(article("article/4"), policy = CachePolicy.CacheOnly)
        assertNull(mos.backgroundFlush)
        assertTrue(h.transport.to(COUNTER_UPDATES_PATH).isEmpty())
    }

    @Test
    fun `concurrent calls keep cache and queue state consistent`() = runBlocking(Dispatchers.Default) {
        val h = Harness(anonymousId = ANON)
        val mos = h.mos()
        mos.decide(article("article/0"))
        h.transport.handler = offline

        val results = (1..40).map { i -> async { mos.decide(article("article/${i % 20 + 1}")) } }.awaitAll()

        assertTrue(results.all { it.source is DecisionSource.Fallback })
        assertEquals((1..20).map { "article/$it" }.toSet(), h.queueStore.load().map { it.resourceId }.toSet())
        assertEquals(20, h.queueStore.load().size)
        assertEquals(20, h.logs.codes().count { it == "metering:deduped" })
    }

    @Test
    fun `an automatic flush resolves the identity when it runs, so it never prunes the current user's meters`() = runTest {
        val h = Harness()
        val userY = userJwt("Y")
        val keyY = jwtIdentityKey(userY)
        h.queueStore.save(listOf(entry(identityKey = keyY, anonymousIdentifier = null, resourceId = "article/8"), entry(identityKey = keyY, anonymousIdentifier = null, resourceId = "article/9", enqueuedAt = NOW + 1)))
        val mos = h.mos()
        val gate = CompletableDeferred<Unit>()
        var counterUpdates = 0
        h.transport.handler = { request ->
            if (request.url.endsWith("/counter-updates")) {
                if (counterUpdates++ == 0) {
                    gate.await()
                    ok(SUCCESS)
                } else {
                    ok("{}", 500)
                }
            } else {
                ok(decisionBody(identifier = "X", isAuthenticated = true))
            }
        }

        // A flush for Y holds the flush lock on its first update.
        mos.identify(userY)
        val first = async { mos.flush() }
        runCurrent()
        // A decision as X queues an automatic flush behind it, then the user is Y again.
        mos.identify(userJwt("X"))
        mos.decide(article())
        mos.identify(userY)
        gate.complete(Unit)
        assertEquals(1, first.await().sent)
        mos.backgroundFlush!!.join()

        assertEquals(listOf("article/9"), h.queueStore.load().map { it.resourceId })
        assertTrue(h.logs.events.none { it.code == "metering:dropped" })
    }

    @Test
    fun `a 2xx counter update that is not a success is dropped and logged, then the flush carries on`() = runTest {
        for (reply in listOf("{}", "", "OK")) {
            val h = Harness(anonymousId = ANON)
            h.queueStore.save(listOf(entry(resourceId = "article/1"), entry(resourceId = "article/2", enqueuedAt = NOW + 1)))
            h.transport.handler = { request ->
                if (request.json()["resourceId"] == JsonPrimitive("article/1")) ok(reply) else ok(SUCCESS)
            }

            val result = h.mos().flush()

            assertEquals(FlushResult(sent = 1, dropped = 1, remaining = 0, failure = null), result, "reply '$reply'")
            assertEquals(2, h.transport.requests.size)
            assertTrue(h.queueStore.load().isEmpty())
            assertEquals(1, h.logs.codes().count { it == "metering:rejected" })
        }
    }
}
