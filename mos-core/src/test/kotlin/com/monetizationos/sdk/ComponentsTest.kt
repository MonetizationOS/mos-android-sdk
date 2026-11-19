package com.monetizationos.sdk

import com.monetizationos.sdk.cache.CacheEntry
import com.monetizationos.sdk.cache.FileDecisionCacheStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Component selection: the sorted set is sent and keys the cache, fallback and coalescing. */
@OptIn(ExperimentalCoroutinesApi::class)
class ComponentsTest {
    private fun slugs(vararg values: String) = JsonArray(values.map(::JsonPrimitive))

    @Test
    fun `components are sent sorted and deduplicated, empty as is, null omitted`() = runTest {
        val h = Harness(anonymousId = ANON)
        val mos = h.mos()

        mos.decide(article(), components = listOf("paywall", "banner", "paywall"))
        mos.decide(article(), components = emptyList())
        mos.decide(article())

        assertEquals(listOf(slugs("banner", "paywall"), slugs(), null), h.transport.requests.map { it.json()["components"] })
    }

    @Test
    fun `each selection has its own cache entry and order does not matter`() = runTest {
        val h = Harness(anonymousId = ANON)
        val mos = h.mos()
        mos.decide(article(), components = listOf("paywall", "banner"))
        assertEquals(listOf(listOf("banner", "paywall")), h.cacheStore.load().map { it.components })

        val sameSet = mos.decide(article(), policy = CachePolicy.CacheOnly, components = listOf("banner", "paywall"))
        assertIs<DecisionSource.Cache>(sameSet.source)
        for (other in listOf(null, emptyList(), listOf("paywall"))) {
            assertIs<DecisionSource.Unavailable>(mos.decide(article(), policy = CachePolicy.CacheOnly, components = other).source, "components: $other")
        }

        mos.decide(article())
        assertEquals(setOf(listOf("banner", "paywall"), null), h.cacheStore.load().map { it.components }.toSet())
    }

    @Test
    fun `fallback to latest stays within the selection`() = runTest {
        val h = Harness(anonymousId = ANON)
        val mos = h.mos()
        mos.decide(article("article/1"), components = listOf("paywall"))

        h.transport.handler = { throw IOException("offline") }
        assertEquals(DecisionSource.Fallback(0, "article/1"), mos.decide(article("article/2"), components = listOf("paywall")).source)
        for (other in listOf(null, emptyList(), listOf("banner"))) {
            assertIs<DecisionSource.Unavailable>(mos.decide(article("article/2"), components = other).source, "components: $other")
        }
    }

    @Test
    fun `different selections make separate calls and the same set in another order joins`() = runTest {
        val h = Harness(anonymousId = ANON)
        val mos = h.mos()
        val gate = CompletableDeferred<Unit>()
        h.transport.handler = { gate.await(); ok(decisionBody()) }

        val calls = listOf(
            async { mos.decide(article()) },
            async { mos.decide(article(), components = listOf("paywall", "banner")) },
            async { mos.decide(article(), components = listOf("banner", "paywall")) },
        )
        runCurrent()
        gate.complete(Unit)
        calls.awaitAll()

        assertEquals(2, h.transport.requests.size)
        assertEquals(1, h.logs.codes().count { it == "decision:coalesced" })
    }

    @Test
    fun `the cache file names a selection and leaves it out when there is none`() = runTest {
        val file = File(Files.createTempDirectory("mos-sdk-test").toFile().apply { deleteOnExit() }, "cache.json")
        val decision = buildJsonObject { put("status", "success") }
        val entries = listOf(
            CacheEntry(SURFACE, "anon:$ANON", "article/1", decision, NOW, NOW + 1, listOf("paywall")),
            CacheEntry(SURFACE, "anon:$ANON", "article/2", decision, NOW, NOW + 1),
        )
        FileDecisionCacheStore(file).save(entries)

        assertEquals(entries, FileDecisionCacheStore(file).load())
        val stored = Json.parseToJsonElement(file.readText()).jsonObject["entries"]!!.jsonArray.map { (it as JsonObject)["components"] }
        assertEquals(listOf(slugs("paywall"), null), stored)
    }
}
