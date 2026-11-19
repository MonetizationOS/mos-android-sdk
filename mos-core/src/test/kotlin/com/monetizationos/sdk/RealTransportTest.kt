package com.monetizationos.sdk

import com.sun.net.httpserver.Headers
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** The default HttpURLConnection transport against a real local server. runBlocking: real time, real sockets. */
class RealTransportTest {
    private data class Received(val method: String, val path: String, val query: String?, val headers: Headers, val body: String)

    private val received = CopyOnWriteArrayList<Received>()
    private val executor = Executors.newCachedThreadPool()
    private lateinit var server: HttpServer

    @Volatile private var respond: (HttpExchange) -> Pair<Int, String> = { 200 to fixture("decision-success-minimal.json") }

    /** Takes over the exchange after the request is recorded, for servers that misbehave. */
    @Volatile private var takeOver: ((HttpExchange) -> Unit)? = null

    /** Released at teardown so a handler that never answers does not outlive the test. */
    private val teardown = CountDownLatch(1)

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.executor = executor
        server.createContext("/") { exchange ->
            exchange.use {
                received += Received(it.requestMethod, it.requestURI.rawPath, it.requestURI.rawQuery, it.requestHeaders, it.requestBody.readBytes().decodeToString())
                takeOver?.let { custom -> return@use custom(it) }
                val (status, body) = respond(it)
                val bytes = body.encodeToByteArray()
                it.responseHeaders.add("Content-Type", "application/json")
                it.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                if (bytes.isNotEmpty()) it.responseBody.use { out -> out.write(bytes) }
            }
        }
        server.start()
    }

    @AfterTest
    fun stop() {
        teardown.countDown()
        server.stop(0)
        executor.shutdownNow()
    }

    private fun mos(logs: LogCollector = LogCollector(), timeoutMs: Long = 5_000) = MOS(
        MOSConfig(
            publicKey = PK,
            surface = SURFACE,
            app = AppInfo("TestApp", "1.0"),
            apiBaseUrl = "http://127.0.0.1:${server.address.port}",
            requestTimeout = timeoutMs.milliseconds,
            onLog = logs,
        ),
        DeviceInfo("Android", "16", "Pixel 9"),
        Clock.System,
        storageDirectory = null,
    )

    @Test
    fun `real transport sends the contract headers`() = runBlocking {
        val result = mos().decide(article("article/1"))

        assertEquals(DecisionSource.Network, result.source)
        assertEquals("55555555-5555-4555-8555-555555555555", result.decision?.eventId)
        val request = received.single()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/surface-decisions", request.path)
        assertEquals("Bearer $PK", request.headers.getFirst("Authorization"))
        assertEquals("application/json", request.headers.getFirst("Content-Type"))
        assertEquals("TestApp/1.0 (Android 16; Pixel 9) MOS-Android/$MOS_VERSION", request.headers.getFirst("User-Agent"))
        assertEquals("android/$MOS_VERSION", request.headers.getFirst("X-MOS-Mobile-Version"))
        assertNull(request.headers.getFirst("X-MOS-Key"))
        assertEquals(JsonPrimitive(SURFACE), kotlinx.serialization.json.Json.parseToJsonElement(request.body).let { (it as JsonObject)["surfaceSlug"] })
    }

    @Test
    fun `real transport custom endpoint uses X-MOS-Key and no Authorization`() = runBlocking {
        respond = { 200 to """{"ok":true}""" }

        val result = mos().callEndpoint("orders/checkout", body = JsonObject(mapOf("item" to JsonPrimitive("x"))), namespace = "inst_1")

        assertEquals(JsonObject(mapOf("ok" to JsonPrimitive(true))), assertIs<EndpointResult.Response>(result).response.json)
        val request = received.single()
        assertEquals("/api/v1/envs/test_env1/namespaces/inst_1/endpoints/orders/checkout", request.path)
        assertEquals("Bearer $PK", request.headers.getFirst("X-MOS-Key"))
        assertNull(request.headers.getFirst("Authorization"))
        assertEquals("application/json", request.headers.getFirst("Content-Type"))
        assertEquals("TestApp/1.0 (Android 16; Pixel 9) MOS-Android/$MOS_VERSION", request.headers.getFirst("User-Agent"))
        assertEquals("android/$MOS_VERSION", request.headers.getFirst("X-MOS-Mobile-Version"))
    }

    @Test
    fun `real transport reads error bodies`() = runBlocking {
        respond = { 429 to fixture("error-429.json") }
        val failure = mos().decide(article()).failure!!
        assertEquals(FailureReason.RateLimited, failure.reason)
        assertEquals(429, failure.statusCode)
        assertEquals("rate_limited", failure.message)
        assertEquals(1, received.size)
    }

    @Test
    fun `real transport times out without retrying`() = runBlocking {
        respond = {
            Thread.sleep(1_500)
            200 to fixture("decision-success-minimal.json")
        }
        val result = mos(timeoutMs = 300).decide(article())
        assertEquals(FailureReason.Timeout, result.failure?.reason)
        assertEquals(1, received.size)
    }

    @Test
    fun `end-to-end timeout honoured by the real transport when the server accepts and never answers`() = runBlocking {
        takeOver = { teardown.await() }
        val timeoutMs = 600L

        val started = System.nanoTime()
        val result = mos(timeoutMs = timeoutMs).decide(article())
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertEquals(FailureReason.Timeout, result.failure?.reason)
        assertTrue(elapsedMs in (timeoutMs - 50)..(timeoutMs + 1_000), "timeout after ${elapsedMs}ms")
        assertEquals(1, received.size)
    }

    @Test
    fun `end-to-end timeout bounds a response that trickles in and aborts the blocked read`() = runBlocking {
        // Each byte arrives well inside the socket read timeout, so only the end-to-end bound can stop it.
        val clientGone = CountDownLatch(1)
        takeOver = { exchange ->
            exchange.sendResponseHeaders(200, 0)
            try {
                while (teardown.count > 0) {
                    exchange.responseBody.write(' '.code)
                    exchange.responseBody.flush()
                    Thread.sleep(50)
                }
            } catch (_: IOException) {
                clientGone.countDown()
            }
        }
        val timeoutMs = 600L

        val started = System.nanoTime()
        val result = mos(timeoutMs = timeoutMs).decide(article())
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertEquals(FailureReason.Timeout, result.failure?.reason)
        assertTrue(elapsedMs in (timeoutMs - 50)..(timeoutMs + 1_000), "timeout after ${elapsedMs}ms")
        assertTrue(clientGone.await(5, TimeUnit.SECONDS), "the connection was closed, so the IO thread is free")
    }

    @Test
    fun `real transport endpoint PUT with a query string reads a response header and a 404 body`() = runBlocking {
        takeOver = { exchange ->
            val bytes = """{"status":"error","statusCode":404,"message":"no such endpoint"}""".encodeToByteArray()
            exchange.responseHeaders.add("X-Request-Id", "req-1")
            exchange.sendResponseHeaders(404, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        val result = mos().app("my app").call(
            "receipts/a b",
            method = EndpointMethod.Put,
            body = JsonObject(mapOf("id" to JsonPrimitive(1))),
            query = mapOf("z" to "1", "a" to "x y"),
        )

        val response = assertIs<EndpointResult.Response>(result).response
        assertEquals(404, response.status)
        assertEquals(false, response.isSuccess)
        assertEquals("req-1", response.headers["x-request-id"])
        assertEquals(JsonPrimitive("no such endpoint"), (response.json as JsonObject)["message"])
        val request = received.single()
        assertEquals("PUT", request.method)
        assertEquals("/api/v1/envs/test_env1/namespaces/my%20app/endpoints/receipts/a%20b", request.path)
        assertEquals("a=x%20y&z=1", request.query)
        assertEquals("application/json", request.headers.getFirst("Content-Type"))
        assertEquals("""{"id":1}""", request.body)
    }

    @Test
    fun `real transport endpoint GET sends no body`() = runBlocking {
        respond = { 200 to """{"items":[]}""" }

        val result = mos().callEndpoint("items", method = EndpointMethod.Get, query = mapOf("page" to "2"))

        assertEquals(200, assertIs<EndpointResult.Response>(result).response.status)
        val request = received.single()
        assertEquals("GET", request.method)
        assertEquals("page=2", request.query)
        assertEquals("", request.body)
        assertNull(request.headers.getFirst("Content-Type"))
    }

    @Test
    fun `a request cancelled before an IO thread picks it up is never sent`() = runBlocking {
        val queued = java.util.concurrent.LinkedBlockingQueue<Runnable>()
        val transport = com.monetizationos.sdk.transport.HttpUrlConnectionTransport { queued += it }
        val request = com.monetizationos.sdk.transport.HttpRequest("http://127.0.0.1:${server.address.port}/x", emptyMap(), "{}", 5_000)

        val call = launch(kotlinx.coroutines.Dispatchers.Unconfined) { runCatching { transport.send(request) } }
        call.cancelAndJoin()
        while (true) queued.poll()?.run() ?: break

        assertTrue(call.isCancelled)
        assertEquals(0, received.size)
    }

    @Test
    fun `real transport PATCH on the JDK fails with requestFailed`() = runBlocking {
        val result = mos().callEndpoint("items/1", method = EndpointMethod.Patch, body = JsonObject(emptyMap()))

        assertEquals(FailureReason.RequestFailed, assertIs<EndpointResult.Failed>(result).failure.reason)
        assertEquals(0, received.size)
    }

    @Test
    fun `real transport returns a redirect instead of following it with the key`() = runBlocking {
        val target = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val hits = CopyOnWriteArrayList<String>()
        target.createContext("/") { exchange ->
            exchange.use {
                hits += it.requestURI.rawPath
                it.sendResponseHeaders(200, -1)
            }
        }
        target.start()
        try {
            takeOver = { exchange ->
                exchange.responseHeaders.add("Location", "http://127.0.0.1:${target.address.port}/elsewhere")
                exchange.sendResponseHeaders(302, -1)
            }

            val result = mos().callEndpoint("items", method = EndpointMethod.Get)

            assertEquals(302, assertIs<EndpointResult.Response>(result).response.status)
            assertEquals(1, received.size)
            assertEquals(emptyList(), hits)
        } finally {
            target.stop(0)
        }
    }
}
