package com.monetizationos.sdk

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Per-call surface: the effective surface keys the request, cache, fallback and coalescing. */
@OptIn(ExperimentalCoroutinesApi::class)
class SurfaceTest {
    @Test
    fun `surface override sends the right slug`() = runTest {
        val h = Harness(anonymousId = ANON)
        val mos = h.mos()

        mos.decide(article())
        mos.decide(article(), surface = "article-meter")

        assertEquals(listOf(SURFACE, "article-meter"), h.transport.requests.map { (it.json()["surfaceSlug"] as JsonPrimitive).content })
        val logged = h.logs.events.filter { it.code == "decision:start" }.map { (it.context["surface"] as JsonPrimitive).content }
        assertEquals(listOf(SURFACE, "article-meter"), logged)
    }

    @Test
    fun `fallback to latest stays within the surface`() = runTest {
        val h = Harness(anonymousId = ANON)
        val mos = h.mos()
        mos.decide(article("article/1"), surface = "other")
        h.clock.advance(kotlin.time.Duration.parse("1m"))
        mos.decide(article("article/2"))

        h.transport.handler = { throw IOException("offline") }
        val fallback = mos.decide(article("article/9"), surface = "other")
        assertEquals(DecisionSource.Fallback(60_000, "article/1"), fallback.source, "not the newer entry from the default surface")
        assertIs<DecisionSource.Unavailable>(mos.decide(article("article/9"), surface = "third").source)
    }

    @Test
    fun `empty surface refused without sending`() = runTest {
        val h = Harness(anonymousId = ANON)
        val mos = h.mos()
        mos.decide(article())

        for (policy in listOf(CachePolicy.NetworkFirst, CachePolicy.CacheOnly)) {
            val result = mos.decide(article(), policy = policy, surface = "")
            assertEquals(FailureReason.RequestFailed, result.failure?.reason)
            assertIs<DecisionSource.Unavailable>(result.source)
            assertNull(result.decision, "never served from cache")
        }
        assertEquals(1, h.transport.requests.size, "the refused calls sent nothing")
        assertFalse(h.cacheStore.load().any { it.surface.isEmpty() })
    }
}
