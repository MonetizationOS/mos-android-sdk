package com.monetizationos.sdk

import com.monetizationos.sdk.logging.LogLevel
import com.monetizationos.sdk.transport.HttpResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class EndpointTest {
    private val body = buildJsonObject { put("item", "x") }

    private fun EndpointResult.response(): EndpointResponse = assertIs<EndpointResult.Response>(this).response

    private fun EndpointResult.failure(): Failure = assertIs<EndpointResult.Failed>(this).failure

    @Test
    fun `endpoint URL for org and namespaced calls with encoded path, namespace and sorted query`() = runTest {
        val h = Harness()
        val mos = h.mos()

        mos.callEndpoint("orders/checkout", body = body)
        mos.callEndpoint("/registration", body = body, namespace = "inst_123")
        mos.callEndpoint("a b/ü/~x.y-z_", method = EndpointMethod.Get, namespace = "my app/1", query = mapOf("q" to "a&b=c", "b" to "2", "a" to "é"))

        assertEquals(
            listOf(
                "https://api.test/api/v1/envs/test_env1/endpoints/orders/checkout",
                "https://api.test/api/v1/envs/test_env1/namespaces/inst_123/endpoints/registration",
                "https://api.test/api/v1/envs/test_env1/namespaces/my%20app%2F1/endpoints/a%20b/%C3%BC/~x.y-z_?a=%C3%A9&b=2&q=a%26b%3Dc",
            ),
            h.transport.requests.map { it.url },
        )
    }

    @Test
    fun `endpoint empty and dot-dot segments are refused without sending`() = runTest {
        val h = Harness()
        val mos = h.mos()
        for (path in listOf("", "/", "a//b", "a/", "../x", "a/../b", "//a", ".", "a/./b", "./a")) {
            assertEquals(FailureReason.RequestFailed, mos.callEndpoint(path).failure().reason, path)
        }
        for (namespace in listOf("", ".", "..")) {
            assertEquals(FailureReason.RequestFailed, mos.callEndpoint("ok", namespace = namespace).failure().reason, namespace)
        }
        assertTrue(h.transport.requests.isEmpty())
        assertTrue(h.logs.codes().all { it == "endpoint:error" })
    }

    @Test
    fun `endpoint each method, body only for POST PUT and PATCH`() = runTest {
        val h = Harness()
        val mos = h.mos()
        for (method in EndpointMethod.entries) {
            val withBody = method in setOf(EndpointMethod.Post, EndpointMethod.Put, EndpointMethod.Patch)
            mos.callEndpoint("items/1", method = method, body = if (withBody) body else null)
        }
        for (method in listOf(EndpointMethod.Post, EndpointMethod.Put, EndpointMethod.Patch)) mos.callEndpoint("items", method = method)

        assertEquals(listOf("GET", "POST", "PUT", "DELETE", "PATCH", "POST", "PUT", "PATCH"), h.transport.requests.map { it.method })
        for (request in h.transport.requests.take(5)) {
            val withBody = request.method in setOf("POST", "PUT", "PATCH")
            assertEquals(if (withBody) body else null, request.body?.let { request.json() }, request.method)
            assertEquals(if (withBody) "application/json" else null, request.headers["Content-Type"], request.method)
        }
        for (request in h.transport.requests.drop(5)) {
            assertNull(request.body, "body = null sends none on ${request.method}")
            assertNull(request.headers["Content-Type"])
        }
    }

    @Test
    fun `endpoint body refused on GET and DELETE`() = runTest {
        val h = Harness()
        val mos = h.mos()
        for (method in listOf(EndpointMethod.Get, EndpointMethod.Delete)) {
            assertEquals(FailureReason.RequestFailed, mos.callEndpoint("items", method = method, body = body).failure().reason)
        }
        assertTrue(h.transport.requests.isEmpty())
    }

    @Test
    fun `endpoint headers sent including host headers`() = runTest {
        val h = Harness()
        val mos = h.mos()
        val token = userJwt("user_42")

        mos.callEndpoint(
            "registration", body = body,
            headers = mapOf("Authorization" to "Bearer $token", "X-Trace" to "t1", "accept" to "application/vnd.x+json"),
        )

        assertEquals(
            mapOf(
                "X-MOS-Key" to "Bearer $PK",
                "User-Agent" to "TestApp/1.0 (Android 16; Pixel 9) MOS-Android/$MOS_VERSION",
                "X-MOS-Mobile-Version" to "android/$MOS_VERSION",
                "Content-Type" to "application/json",
                "Authorization" to "Bearer $token",
                "X-Trace" to "t1",
                "accept" to "application/vnd.x+json",
            ),
            h.transport.requests.single().headers,
        )
        assertTrue(h.logs.events.none { token in it.toString() }, "the forwarded JWT is never logged")
        mos.callEndpoint("x")
        assertEquals("application/json", h.transport.requests.last().headers["Accept"])
    }

    @Test
    fun `endpoint SDK-owned header names dropped and logged`() = runTest {
        val h = Harness()
        val mos = h.mos()
        val owned = listOf("x-mos-key", "USER-AGENT", "X-MOS-Mobile-Version", "Content-Type", "content-length")

        mos.callEndpoint("x", body = body, headers = owned.associateWith { "host" } + ("X-Ok" to "yes"))

        val sent = h.transport.requests.single().headers
        assertTrue(sent.values.none { it == "host" }, sent.toString())
        assertEquals("yes", sent["X-Ok"])
        val ignored = h.logs.events.filter { it.code == "endpoint:header-ignored" }
        assertEquals(owned, ignored.map { (it.context["name"] as JsonPrimitive).content })
        assertTrue(ignored.all { it.level == LogLevel.Warn })
    }

    @Test
    fun `endpoint app namespace equivalence`() = runTest {
        val h = Harness()
        val mos = h.mos()
        val app = mos.app("inst_123")
        assertEquals("inst_123", app.namespace)

        val viaApp = app.call("orders", method = EndpointMethod.Put, body = body, query = mapOf("a" to "1"), headers = mapOf("X-A" to "1"))
        val direct = mos.callEndpoint("orders", EndpointMethod.Put, body, mapOf("a" to "1"), mapOf("X-A" to "1"), "inst_123")

        assertEquals(direct, viaApp)
        val (first, second) = h.transport.requests
        assertEquals(first, second)
    }

    @Test
    fun `endpoint any status returned as response with body, json, text and lowercased headers`() = runTest {
        val h = Harness()
        val mos = h.mos()
        for (status in listOf(200, 201, 204, 400, 401, 404, 429, 500, 531)) {
            val text = if (status == 204) "" else """{"status":"error","statusCode":$status}"""
            h.transport.handler = { HttpResponse(status, text, mapOf("Content-Type" to "application/json", "X-Multi" to "a", "x-multi" to "b")) }

            val response = mos.callEndpoint("x", body = body).response()

            assertEquals(status, response.status)
            assertEquals(status in 200..299, response.isSuccess)
            assertTrue(response.body.contentEquals(text.encodeToByteArray()))
            assertEquals(text, response.text)
            assertEquals(if (text.isEmpty()) null else MosJson.parseToJsonElement(text), response.json)
            assertEquals(mapOf("content-type" to "application/json", "x-multi" to "a, b"), response.headers)
        }
        assertEquals(3, h.logs.codes().count { it == "endpoint:success" })
        val failures = h.logs.events.filter { it.code == "endpoint:error" }
        assertEquals(listOf(400, 401, 404, 429, 500, 531), failures.map { (it.context["status"] as JsonPrimitive).content.toInt() })
        assertEquals(h.transport.requests.size, 9, "never retried")
    }

    @Test
    fun `endpoint non-JSON body`() = runTest {
        val h = Harness()
        val mos = h.mos()
        for (text in listOf("oops", "<html>hi</html>", """{"a": nope}""", "[1, 2,]")) {
            h.transport.handler = { ok(text, 502) }
            val response = mos.callEndpoint("x").response()
            assertNull(response.json, text)
            assertEquals(text, response.text)
        }
        h.transport.handler = { HttpResponse(200, byteArrayOf(0x68, 0xff.toByte(), 0x69)) }
        assertEquals("h�i", mos.callEndpoint("x").response().text, "lossy UTF-8")
        h.transport.handler = { ok("42") }
        assertEquals(JsonPrimitive(42), mos.callEndpoint("x").response().json)
    }

    @Test
    fun `endpoint pathSegments encodes each segment whole, on callEndpoint and the app handle, and refuses bad segments`() = runTest {
        val h = Harness()
        val mos = h.mos()

        mos.callEndpoint(listOf("users", "a/b", "ü ?#"))
        mos.app("inst_1").call(pathSegments = listOf("/x", "..a"))

        assertEquals(
            listOf(
                "https://api.test/api/v1/envs/test_env1/endpoints/users/a%2Fb/%C3%BC%20%3F%23",
                "https://api.test/api/v1/envs/test_env1/namespaces/inst_1/endpoints/%2Fx/..a",
            ),
            h.transport.requests.map { it.url },
        )
        assertEquals(JsonPrimitive("users/a%2Fb/%C3%BC%20%3F%23"), h.logs.events.first { it.code == "endpoint:success" }.context["path"])
        for (segments in listOf(emptyList(), listOf(""), listOf("a", "."), listOf("..", "b"))) {
            assertEquals(FailureReason.RequestFailed, mos.callEndpoint(segments).failure().reason, segments.toString())
        }
        assertEquals(2, h.transport.requests.size)
    }

    @Test
    fun `endpoint explicit timeout honoured and exempt from the fail-fast tier`() = runTest {
        val h = Harness()
        val mos = h.mos()
        h.transport.handler = { delay(1.hours); ok("{}") }

        var start = testScheduler.currentTime
        assertEquals(FailureReason.Timeout, mos.callEndpoint("slow", timeout = 7.seconds).failure().reason)
        assertEquals(7_000L, testScheduler.currentTime - start)
        assertEquals(7_000L, h.transport.requests.last().timeoutMs)
        assertFalse("transport:degraded" in h.logs.codes(), "its own timeout does not enter the tier")

        // A decision timeout enters the tier; an explicit timeout is still exact while degraded.
        mos.decide(article())
        assertTrue("transport:degraded" in h.logs.codes())
        start = testScheduler.currentTime
        mos.callEndpoint("slow", timeout = 7.seconds)
        assertEquals(7_000L, testScheduler.currentTime - start)

        // Any response to an explicit-timeout call still ends the tier.
        h.transport.handler = { ok("{}", 404) }
        mos.callEndpoint("fast", timeout = 7.seconds)
        assertTrue("transport:recovered" in h.logs.codes())
        mos.callEndpoint("fast")
        assertEquals(5_000L, h.transport.requests.last().timeoutMs)
    }

    @Test
    fun `endpoint two identical concurrent calls make two transport calls`() = runTest {
        val h = Harness()
        val mos = h.mos()
        val gate = CompletableDeferred<Unit>()
        h.transport.handler = { gate.await(); ok("{}") }

        val calls = (1..2).map { async { mos.callEndpoint("x", body = body) } }
        runCurrent()
        gate.complete(Unit)
        calls.awaitAll()

        assertEquals(2, h.transport.requests.size)
    }

    @Test
    fun `endpoint jwtProvider not called and no identity sent`() = runTest {
        val h = Harness(anonymousId = ANON)
        var providerCalls = 0
        val mos = h.mos { it.copy(identity = it.identity.copy(jwtProvider = { providerCalls++; userJwt("user_42") })) }
        mos.identify(userJwt("explicit"))
        providerCalls = 0

        mos.callEndpoint("x", body = body)

        assertEquals(0, providerCalls)
        val request = h.transport.requests.single()
        assertNull(request.headers["Authorization"])
        assertEquals(body, request.json())
    }

    @Test
    fun `endpoint failed only when no response arrived`() = runTest {
        val h = Harness()
        val mos = h.mos()
        h.transport.handler = { throw IOException("offline") }
        assertEquals(FailureReason.RequestFailed, mos.callEndpoint("x").failure().reason)
        val logged = h.logs.events.last { it.code == "endpoint:error" }
        assertEquals(JsonPrimitive("requestFailed"), logged.context["reason"])
        assertNull(logged.context["status"], "no response, no status")
        assertEquals(JsonNull, logged.context["namespace"])

        val unparseable = Harness().mos { it.copy(publicKey = "pk_onlyone") }
        assertEquals(FailureReason.NotConfigured, unparseable.callEndpoint("x").failure().reason)
        assertEquals(1, h.transport.requests.size)
    }

    @Test
    fun `endpoint success log context`() = runTest {
        val h = Harness()
        val mos = h.mos()
        mos.app("inst_1").call("orders", method = EndpointMethod.Delete)
        val logged = h.logs.events.single { it.code == "endpoint:success" }
        assertEquals(
            mapOf("namespace" to "inst_1", "path" to "orders", "method" to "DELETE", "status" to "200"),
            logged.context.mapValues { (_, value) -> (value as JsonPrimitive).content },
        )
    }

    @Test
    fun `identity header names dropped from host headers`() = runTest {
        val h = Harness(anonymousId = ANON)
        val mos = h.mos()
        val hostIdentity = mapOf("x-mos-user-jwt" to "forged", "X-MOS-Anonymous-Identifier" to "forged")

        mos.callEndpoint("a", headers = hostIdentity)

        val sent = h.transport.requests.single().headers
        assertTrue(sent.values.none { it == "forged" })
        assertTrue(sent.keys.none { it.startsWith("X-MOS-U", ignoreCase = true) || it.startsWith("X-MOS-A", ignoreCase = true) })
        val ignored = h.logs.events.filter { it.code == "endpoint:header-ignored" }.map { (it.context["name"] as JsonPrimitive).content }
        assertEquals(hostIdentity.keys.toList(), ignored)
    }

    @Test
    fun `endpoint log shows the encoded path joined with a slash for the string path too`() = runTest {
        val h = Harness()
        val mos = h.mos()

        mos.callEndpoint("/users/a b")
        mos.callEndpoint("users/a b")
        mos.callEndpoint(listOf("users", "a b"))

        val logged = h.logs.events.filter { it.code == "endpoint:success" }.map { it.context["path"] }
        assertEquals(List(3) { JsonPrimitive("users/a%20b") }, logged)
    }
}
