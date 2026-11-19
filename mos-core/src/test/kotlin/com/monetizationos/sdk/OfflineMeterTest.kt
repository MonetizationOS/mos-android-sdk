package com.monetizationos.sdk

import com.monetizationos.sdk.cache.CacheEntry
import com.monetizationos.sdk.decision.Meterable
import com.monetizationos.sdk.metering.CountedResource
import com.monetizationos.sdk.metering.MeterLedgerEntry
import com.monetizationos.sdk.metering.MeteringEntry
import com.monetizationos.sdk.metering.MeteringQueueStore
import com.monetizationos.sdk.metering.periodStartMs
import com.monetizationos.sdk.transport.HttpRequest
import com.monetizationos.sdk.transport.HttpResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

class OfflineMeterTest {
    private val counter = "default:feat_article.enabled"
    private val day = 1.days.inWholeMilliseconds
    private val networkDown: suspend (HttpRequest) -> HttpResponse = { throw IOException("offline") }

    /** Decisions get [decision]; counter updates get [counterUpdate]. */
    private fun route(decision: String, counterUpdate: suspend () -> HttpResponse = { ok(SUCCESS) }): suspend (HttpRequest) -> HttpResponse =
        { request -> if (request.url.endsWith(COUNTER_UPDATES_PATH)) counterUpdate() else ok(decision) }

    /** The server's answer for a counted meter: [consumed] of [total] used, this request included. */
    private fun counted(
        consumed: Double,
        total: Double = 5.0,
        unique: Boolean = false,
        consumedInRequest: Boolean = true,
        resourceIdUsed: Boolean = false,
        periodStart: String? = null,
    ) = decisionBody(
        hasAccess = consumed - (if (consumedInRequest) 1 else 0) < total,
        consumedInRequest = consumedInRequest,
        consumedUnitsInRequest = if (consumedInRequest) 1 else 0,
        consumedUnits = consumed,
        totalUnits = total,
        uniqueResources = unique,
        resourceIdUsed = resourceIdUsed,
        periodStart = periodStart,
    )

    private fun meter(result: DecisionResult): Meterable? = result.decision?.features?.get("article")?.properties?.get("enabled") as? Meterable

    private fun ledgerEntry(
        consumed: Double,
        total: Double = 5.0,
        unique: Boolean = false,
        local: Double = 0.0,
        resources: List<String> = emptyList(),
        takenAt: Long = NOW,
    ) = MeterLedgerEntry("anon:$ANON", counter, consumed, total, unique, takenAt, local, resources.map { CountedResource(it, takenAt) })

    private fun queued(resourceId: String) = MeteringEntry("anon:$ANON", ANON, counter, resourceId, 1.0, NOW)

    private fun json(body: String) = Json.parseToJsonElement(body).jsonObject

    /** A MOS whose cache holds [body] for article/1 and whose network is down. */
    private suspend fun offline(body: String, ledger: List<MeterLedgerEntry>, enforce: Boolean = false): Pair<Harness, MOS> {
        val h = Harness(anonymousId = ANON)
        h.clock.now = NOW + 1_000
        h.cacheStore.save(listOf(CacheEntry(SURFACE, "anon:$ANON", "article/1", json(body), NOW, NOW + day)))
        h.meterStore.save(ledger)
        h.transport.handler = networkDown
        return h to h.mos { it.copy(offline = it.offline.copy(enforceMeters = enforce)) }
    }

    /** One decision through the network, then its background flush. */
    private suspend fun MOS.decideOnline(id: String = "article/1"): DecisionResult = decide(article(id)).also { backgroundFlush?.join() }

    @Test
    fun `a network decision sets the server count, plus the updates still queued`() = runTest {
        val h = Harness(anonymousId = ANON)
        h.queueStore.save(listOf(queued("article/q")))
        h.transport.handler = route(counted(2.0))
        h.mos().decideOnline()
        assertEquals(listOf(ledgerEntry(2.0, local = 1.0)), h.meterStore.load())
        assertTrue(h.queueStore.load().isEmpty())
    }

    @Test
    fun `reads served from the cache count the meter down`() = runTest {
        val (h, mos) = offline(counted(2.0), listOf(ledgerEntry(2.0)))
        val first = mos.decide(article("article/2"))
        assertEquals(3.0, meter(first)?.consumedUnits)
        assertEquals(2.0, meter(first)?.remainingUnits)
        assertEquals(true, meter(first)?.consumedInRequest)
        val second = mos.decide(article("article/3"))
        assertEquals(1.0, meter(second)?.remainingUnits)
        assertEquals(2.0, h.meterStore.load().first().localUnits)
        assertEquals(listOf("article/2", "article/3"), h.queueStore.load().map { it.resourceId })
    }

    @Test
    fun `without enforceMeters, access stays on past the limit and reads still queue`() = runTest {
        val (h, mos) = offline(counted(5.0), listOf(ledgerEntry(5.0)))
        val result = mos.decide(article("article/2"))
        assertTrue(result.hasAccess("article", "enabled"))
        assertEquals(0.0, meter(result)?.remainingUnits)
        assertEquals(false, meter(result)?.lockedOffline)
        assertEquals(1, h.queueStore.load().size)
    }

    @Test
    fun `with enforceMeters, a used-up meter locks - no access, lockedOffline, nothing queued`() = runTest {
        val (h, mos) = offline(counted(4.0), listOf(ledgerEntry(4.0)), enforce = true)
        val last = mos.decide(article("article/2"))
        assertTrue(last.hasAccess("article", "enabled"))
        assertEquals(0.0, meter(last)?.remainingUnits)

        val locked = mos.decide(article("article/3"))
        assertFalse(locked.hasAccess("article", "enabled"))
        assertEquals(true, meter(locked)?.lockedOffline)
        assertEquals(false, meter(locked)?.consumedInRequest)
        assertEquals(5.0, meter(locked)?.consumedUnits)
        assertEquals(listOf("article/2"), h.queueStore.load().map { it.resourceId })
        val expected = buildJsonObject {
            put("counterId", counter)
            put("resourceId", "article/3")
            put("consumedUnits", 5.0)
            put("totalUnits", 5.0)
        }
        assertEquals(expected, h.logs.events.first { it.code == "metering:locked" }.context)
    }

    @Test
    fun `a unique-resource re-read counts nothing and stays open when the meter is used up`() = runTest {
        val (h, mos) = offline(counted(4.0, unique = true), listOf(ledgerEntry(4.0, unique = true)), enforce = true)
        mos.decide(article("article/2"))
        val reread = mos.decide(article("article/2"))
        assertTrue(reread.hasAccess("article", "enabled"))
        assertEquals(false, meter(reread)?.consumedInRequest)
        assertEquals(true, meter(reread)?.resourceIdUsed)
        assertEquals(5.0, meter(reread)?.consumedUnits)
        assertEquals(1.0, h.meterStore.load().first().localUnits)
        assertEquals(1, h.queueStore.load().size)
    }

    @Test
    fun `a non-unique meter counts what is queued, so a deduped re-read counts nothing`() = runTest {
        val (_, mos) = offline(counted(1.0), listOf(ledgerEntry(1.0)))
        mos.decide(article("article/2"))
        val reread = mos.decide(article("article/2"))
        assertEquals(2.0, meter(reread)?.consumedUnits)
        assertEquals(false, meter(reread)?.consumedInRequest)
    }

    @Test
    fun `a flush keeps the local count until the next network decision`() = runTest {
        val (h, mos) = offline(counted(2.0), listOf(ledgerEntry(2.0)))
        mos.decide(article("article/2"))
        h.transport.handler = { request -> if (request.url.endsWith(COUNTER_UPDATES_PATH)) ok(SUCCESS) else throw IOException("offline") }
        assertEquals(1, mos.flush().sent)
        h.transport.handler = networkDown
        assertEquals(4.0, meter(mos.decide(article("article/3")))?.consumedUnits)

        // Online again, the server has counted article/2 and article/4 but not article/3, still queued.
        h.transport.handler = route(counted(4.0), counterUpdate = { throw IOException("offline") })
        mos.decideOnline("article/4")
        assertEquals(listOf(ledgerEntry(4.0, local = 1.0, takenAt = NOW + 1_000)), h.meterStore.load())
    }

    @Test
    fun `an update queued for the resource the server just counted is not counted twice`() = runTest {
        val h = Harness(anonymousId = ANON)
        h.queueStore.save(listOf(queued("article/1"), queued("article/2")))
        h.transport.handler = route(counted(3.0, unique = true), counterUpdate = { throw IOException("offline") })
        h.mos().decideOnline()
        val entry = h.meterStore.load().first()
        assertEquals(1.0, entry.localUnits)
        assertEquals(listOf("article/1"), entry.resources.map { it.id })
    }

    @Test
    fun `a meter without a count, on or off, is left as the server set it`() = runTest {
        val body = decisionBody(hasAccess = true, consumedInRequest = null, consumedUnitsInRequest = null)
        val (_, mos) = offline(body, listOf(ledgerEntry(9.0)), enforce = true)
        val result = mos.decide(article("article/2"))
        assertTrue(result.hasAccess("article", "enabled"))
        assertEquals(json(body), result.decision?.raw)
    }

    @Test
    fun `a meter the cached decision did not consume shows the count without adding to it`() = runTest {
        val (h, mos) = offline(counted(1.0, consumedInRequest = false), listOf(ledgerEntry(1.0, local = 2.0)))
        val result = mos.decide(article("home"))
        assertEquals(2.0, meter(result)?.remainingUnits)
        assertEquals(false, meter(result)?.consumedInRequest)
        assertTrue(h.queueStore.load().isEmpty())
    }

    @Test
    fun `a meter without a ledger entry is queued as before and not rewritten`() = runTest {
        val body = counted(5.0)
        val (h, mos) = offline(body, emptyList(), enforce = true)
        val result = mos.decide(article("article/2"))
        assertTrue(result.hasAccess("article", "enabled"))
        assertEquals(json(body), result.decision?.raw)
        assertEquals(1, h.queueStore.load().size)
    }

    @Test
    fun `a new period drops the resources counted before it`() = runTest {
        val old = MeterLedgerEntry("anon:$ANON", counter, 1.0, 5.0, true, NOW - day, 0.0, listOf(CountedResource("article/old", NOW - day)))
        val h = Harness(anonymousId = ANON)
        h.meterStore.save(listOf(old))
        // NOW is Sat, 17 Oct 2026 00:00:00 GMT.
        h.transport.handler = route(counted(1.0, unique = true, periodStart = "Sat, 17 Oct 2026 00:00:00 GMT"))
        h.mos().decideOnline()
        assertEquals(listOf("article/1"), h.meterStore.load().first().resources.map { it.id })
    }

    @Test
    fun `periodStart parses as an HTTP date`() {
        assertEquals(1_699_920_000_000L, periodStartMs("Tue, 14 Nov 2023 00:00:00 GMT"))
        assertEquals(NOW, periodStartMs("Sat, 17 Oct 2026 00:00:00 GMT"))
        assertNull(periodStartMs("not a date"))
    }

    @Test
    fun `a network decision drops ledger entries older than the cache ttl`() = runTest {
        val stale = MeterLedgerEntry("anon:$ANON", "default:feat_video.enabled", 1.0, 5.0, false, NOW - day - 1, 0.0, emptyList())
        val h = Harness(anonymousId = ANON)
        h.meterStore.save(listOf(stale))
        h.transport.handler = route(counted(1.0))
        h.mos().decideOnline()
        assertEquals(listOf(counter), h.meterStore.load().map { it.counterId })
    }

    @Test
    fun `reset forgets the offline counts`() = runTest {
        val (h, mos) = offline(counted(2.0), listOf(ledgerEntry(2.0)))
        mos.reset()
        assertTrue(h.meterStore.load().isEmpty())
    }

    /** The queue file write waits for [gate], as a file store's withContext(IO) can be cancelled mid-write. */
    private class GatedQueueStore(private val inner: MeteringQueueStore) : MeteringQueueStore by inner {
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()

        override suspend fun save(entries: List<MeteringEntry>) {
            inner.save(entries)
            entered.complete(Unit)
            gate.await()
        }
    }

    @Test
    fun `a cancelled cached serve still counts in both the queue and the ledger`() = runTest {
        val (h, _) = offline(counted(2.0), listOf(ledgerEntry(2.0)))
        val queue = GatedQueueStore(h.queueStore)
        val mos = h.mos(queue = queue)

        val job = launch { mos.decide(article("article/2"), policy = CachePolicy.CacheOnly) }
        queue.entered.await()
        job.cancel()
        queue.gate.complete(Unit)
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(listOf("article/2"), h.queueStore.load().map { it.resourceId })
        assertEquals(listOf(ledgerEntry(2.0, local = 1.0)), h.meterStore.load())
    }
}
