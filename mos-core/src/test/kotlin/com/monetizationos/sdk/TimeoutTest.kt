package com.monetizationos.sdk

import com.monetizationos.sdk.metering.MeteringEntry
import com.monetizationos.sdk.transport.HttpRequest
import com.monetizationos.sdk.transport.HttpResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The fail-fast tier. Virtual time measures how long the caller waited; the injected clock drives the 10-minute window. */
@OptIn(ExperimentalCoroutinesApi::class)
class TimeoutTest {
    private val hang: suspend (HttpRequest) -> HttpResponse = { delay(1.hours); ok(decisionBody()) }

    private fun Harness.count(code: String) = logs.codes().count { it == code }

    private suspend fun TestScope.timed(block: suspend () -> Unit): Long {
        val start = testScheduler.currentTime
        block()
        return testScheduler.currentTime - start
    }

    @Test
    fun `fail-fast tier entry, use of 2s, and recovery on any response`() = runTest {
        val h = Harness(anonymousId = ANON)
        val mos = h.mos()
        h.transport.handler = hang

        // Entry: the first timeout takes the full 5s and logs degraded once.
        assertEquals(5_000L, timed { assertEquals(FailureReason.Timeout, mos.decide(article()).failure?.reason) })
        assertEquals(5_000L, h.transport.requests.last().timeoutMs)
        assertEquals(1, h.count("transport:degraded"))

        // Degraded: decisions and counter updates give up after 2s.
        assertEquals(2_000L, timed { assertEquals(FailureReason.Timeout, mos.decide(article()).failure?.reason) })
        h.queueStore.save(listOf(MeteringEntry("anon:$ANON", ANON, "default:feat_article.enabled", "article/1", 1.0, NOW)))
        assertEquals(2_000L, timed { assertEquals(FailureReason.Timeout, mos.flush().failure?.reason) })
        assertEquals(listOf(5_000L, 2_000L, 2_000L), h.transport.requests.map { it.timeoutMs })
        assertEquals(1, h.count("transport:degraded"), "logged on entry only")

        // Any HTTP response, even an error, recovers.
        h.transport.handler = { ok("oops", 500) }
        assertEquals(FailureReason.HttpError, mos.decide(article()).failure?.reason)
        assertEquals(2_000L, h.transport.requests.last().timeoutMs)
        val recovered = h.logs.events.single { it.code == "transport:recovered" }
        assertEquals(JsonPrimitive("response"), recovered.context["reason"])
        mos.decide(article())
        assertEquals(5_000L, h.transport.requests.last().timeoutMs)
        assertEquals(1, h.count("transport:recovered"))
    }

    @Test
    fun `redemptions and endpoint calls get the full timeout in fail-fast and never enter it`() = runTest {
        val h = Harness(anonymousId = ANON)
        val mos = h.mos()
        // A workflow that takes 3s: fail-fast's 2s would report a timeout though the server ran it.
        val slow: suspend (HttpRequest) -> HttpResponse = { delay(3.seconds); ok(fixture("redemption-none.json")) }

        h.transport.handler = hang
        mos.decide(article())
        h.transport.handler = slow
        assertEquals(3_000L, timed { assertIs<EndpointResult.Response>(mos.callEndpoint("registration", body = JsonObject(emptyMap()))) })
        assertEquals(5_000L, h.transport.requests.last().timeoutMs)
        assertEquals(1, h.count("transport:recovered"), "a response to it still ends fail-fast")

        h.transport.handler = hang
        mos.decide(article())
        h.transport.handler = slow
        assertEquals(3_000L, timed { assertIs<RedemptionResult.Redeemed>(mos.redeem(offerToken(null))) })
        assertEquals(5_000L, h.transport.requests.last().timeoutMs)
        assertEquals(2, h.count("transport:recovered"))

        // Their own timeouts take the full 5s and leave the tier alone.
        h.transport.handler = hang
        assertEquals(5_000L, timed { assertEquals(FailureReason.Timeout, assertIs<EndpointResult.Failed>(mos.callEndpoint("registration")).failure.reason) })
        assertEquals(5_000L, timed { assertEquals(FailureReason.Timeout, assertIs<RedemptionResult.Failed>(mos.redeem(offerToken(null))).failure.reason) })
        assertEquals(2, h.count("transport:degraded"))
        mos.decide(article())
        assertEquals(5_000L, h.transport.requests.last().timeoutMs, "still out of fail-fast")

        // They never check the window, so one answered after it ran out ends fail-fast as a response.
        h.clock.advance(10.minutes)
        h.transport.handler = { ok("{}") }
        mos.callEndpoint("registration")
        assertEquals(JsonPrimitive("response"), h.logs.events.last { it.code == "transport:recovered" }.context["reason"])
        assertEquals(3, h.count("transport:recovered"))
    }

    @Test
    fun `fail-fast tier expiry after 10 minutes`() = runTest {
        val h = Harness(anonymousId = ANON)
        val mos = h.mos()
        h.transport.handler = hang
        mos.decide(article())

        // A network error is neither a timeout nor a response: the window keeps running from the timeout.
        h.transport.handler = { throw IOException("offline") }
        h.clock.advance(9.minutes)
        mos.decide(article())
        assertEquals(2_000L, h.transport.requests.last().timeoutMs)
        assertEquals(0, h.count("transport:recovered"))

        h.clock.advance(1.minutes)
        mos.decide(article())
        assertEquals(5_000L, h.transport.requests.last().timeoutMs)
        assertEquals(JsonPrimitive("expired"), h.logs.events.single { it.code == "transport:recovered" }.context["reason"])

        // A later timeout enters the tier again.
        h.transport.handler = hang
        mos.decide(article())
        assertEquals(2, h.count("transport:degraded"))
    }

    @Test
    fun `the window runs from entering fail-fast, so a slow network gets the full timeout back`() = runTest {
        val h = Harness(anonymousId = ANON)
        val mos = h.mos()
        h.transport.handler = hang
        mos.decide(article())

        // The network answers in 3s: every fail-fast call times out, and none of those timeouts extends the window.
        h.transport.handler = { delay(3.seconds); ok(decisionBody()) }
        h.clock.advance(9.minutes)
        assertEquals(FailureReason.Timeout, mos.decide(article(), policy = CachePolicy.NetworkOnly).failure?.reason)
        h.clock.advance(1.minutes)
        assertEquals(DecisionSource.Network, mos.decide(article(), policy = CachePolicy.NetworkOnly).source)
        assertEquals(listOf(5_000L, 2_000L, 5_000L), h.transport.requests.map { it.timeoutMs })
        assertEquals(JsonPrimitive("expired"), h.logs.events.single { it.code == "transport:recovered" }.context["reason"])

        // A timeout after the window enters fail-fast again, with a window of its own.
        h.transport.handler = hang
        mos.decide(article())
        assertEquals(2, h.count("transport:degraded"))
        h.clock.advance(9.minutes)
        mos.decide(article())
        h.clock.advance(1.minutes)
        mos.decide(article())
        assertEquals(listOf(5_000L, 2_000L, 5_000L), h.transport.requests.drop(3).map { it.timeoutMs })
    }

    @Test
    fun `fail-fast tier never lengthens a shorter request timeout`() = runTest {
        val h = Harness(anonymousId = ANON)
        val mos = h.mos { it.copy(requestTimeout = 800.milliseconds) }
        h.transport.handler = hang
        mos.decide(article())
        assertEquals(800L, timed { mos.decide(article()) })
        assertEquals(listOf(800L, 800L), h.transport.requests.map { it.timeoutMs })
    }

    @Test
    fun `a transport SocketTimeoutException also enters the tier`() = runTest {
        val h = Harness(anonymousId = ANON)
        val mos = h.mos()
        h.transport.handler = { throw java.net.SocketTimeoutException("read timed out") }
        assertEquals(FailureReason.Timeout, mos.decide(article()).failure?.reason)
        mos.decide(article())
        assertEquals(1, h.count("transport:degraded"))
        assertEquals(2_000L, h.transport.requests.last().timeoutMs)
    }
}
