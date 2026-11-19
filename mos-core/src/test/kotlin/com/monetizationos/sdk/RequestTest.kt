package com.monetizationos.sdk

import com.monetizationos.sdk.identity.jwtIdentityKey
import com.monetizationos.sdk.metering.MeteringEntry
import com.monetizationos.sdk.transport.HttpRequest
import com.monetizationos.sdk.transport.HttpResponse
import com.monetizationos.sdk.transport.buildUserAgent
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class RequestTest {
    private val failures: List<Triple<FailureReason, Int?, suspend (HttpRequest) -> HttpResponse>> = listOf(
        Triple(FailureReason.RequestFailed, null, { throw IOException("connection reset") }),
        Triple(FailureReason.Timeout, null, { delay(11.seconds); ok(decisionBody()) }),
        Triple(FailureReason.Timeout, null, { throw SocketTimeoutException("read timed out") }),
        Triple(FailureReason.InvalidJson, 200, { ok(fixture("invalid-json.txt")) }),
        Triple(FailureReason.ApiError, 200, { ok(fixture("error-400.json")) }),
        Triple(FailureReason.ApiError, 404, { ok(fixture("error-404.json"), 404) }),
        Triple(FailureReason.HttpError, 502, { ok(fixture("invalid-json.txt"), 502) }),
        Triple(FailureReason.InvalidResponse, 200, { ok("""{"status":"success"}""") }),
        Triple(FailureReason.RateLimited, 429, { ok(fixture("error-429.json"), 429) }),
        Triple(FailureReason.Unauthorized, 401, { ok(fixture("error-401.json"), 401) }),
    )

    @Test
    fun `no retry`() = runTest {
        for ((reason, status, handler) in failures) {
            for (policy in listOf(CachePolicy.NetworkFirst, CachePolicy.NetworkOnly, CachePolicy.CacheFirst(1.seconds))) {
                val h = Harness(anonymousId = ANON)
                h.transport.handler = handler

                val result = h.mos().decide(article(), policy = policy)

                assertEquals(reason, result.failure?.reason, "$reason under $policy")
                assertEquals(status, result.failure?.status, "$reason status")
                assertEquals(1, h.transport.requests.size, "exactly one call for $reason under $policy")
            }
        }
    }

    @Test
    fun `timeout is a failure that serves the cache`() = runTest {
        val h = Harness(anonymousId = ANON)
        val mos = h.mos { it.copy(requestTimeout = 2.seconds) }
        mos.decide(article())
        h.transport.handler = { delay(3.seconds); ok(decisionBody()) }

        val result = mos.decide(article())

        assertEquals(FailureReason.Timeout, result.failure?.reason)
        assertIs<DecisionSource.Cache>(result.source)
        assertEquals(2, h.transport.requests.size)
        assertEquals(2000L, h.transport.requests.last().timeoutMs)
    }

    @Test
    fun `headers`() = runTest {
        val h = Harness(anonymousId = ANON)
        h.mos().decide(article())

        val request = h.transport.requests.single()
        assertEquals("https://api.test/api/v1/surface-decisions", request.url)
        assertEquals(
            mapOf(
                "Authorization" to "Bearer $PK",
                "Content-Type" to "application/json",
                "User-Agent" to "TestApp/1.0 (Android 16; Pixel 9) MOS-Android/$MOS_VERSION",
                "X-MOS-Mobile-Version" to "android/$MOS_VERSION",
            ),
            request.headers,
        )
    }

    @Test
    fun `JWT decisions ask for an anonymous fallback`() = runTest {
        val h = Harness(anonymousId = ANON)
        val mos = h.mos()
        val token = userJwt("user_42")
        mos.identify(token)

        mos.decide(article())

        assertEquals(
            buildJsonObject {
                put("userJwt", token)
                put("createAnonymousIdentifierFallback", true)
            },
            h.transport.requests.single().json()["identity"],
        )
    }

    @Test
    fun `a request's toString redacts the body and identity headers`() = runTest {
        val h = Harness(anonymousId = ANON)
        val token = userJwt("user_42")
        val mos = h.mos()
        mos.identify(token)
        mos.decide(article())

        val sent = h.transport.requests.single()
        val body = checkNotNull(sent.body)
        assertTrue(token in body, "the token is on the wire")
        val shown = sent.toString()
        assertFalse(token in shown, shown)
        assertTrue("body=<${body.encodeToByteArray().size} bytes redacted>" in shown, shown)
        assertTrue("url=https://api.test/api/v1/surface-decisions" in shown, shown)
        assertTrue("Authorization=<redacted>" in shown, shown)
        assertTrue("X-MOS-Mobile-Version=android/$MOS_VERSION" in shown, shown)

        val headers = mapOf(
            "X-MOS-User-JWT" to token, "x-mos-anonymous-identifier" to ANON, "Authorization" to "Bearer $token",
            "Proxy-Authorization" to "Basic $token", "cookie" to "session=$token", "X-MOS-Key" to "Bearer $PK",
            "Accept" to "application/json",
        )
        val endpoint = HttpRequest("https://api.test/x", headers, body = "é", timeoutMs = 5000, method = "GET").toString()
        assertEquals(
            "HttpRequest(url=https://api.test/x, headers={X-MOS-User-JWT=<redacted>, x-mos-anonymous-identifier=<redacted>, " +
                "Authorization=<redacted>, Proxy-Authorization=<redacted>, cookie=<redacted>, X-MOS-Key=<redacted>, " +
                "Accept=application/json}, body=<2 bytes redacted>, timeoutMs=5000, method=GET)",
            endpoint,
        )
        assertFalse(ANON in endpoint)
        assertEquals("body=null", Regex("body=[^,]*").find(HttpRequest("https://api.test/x", emptyMap(), null, 1).toString())?.value)
    }

    @Test
    fun `JWT counter updates send the bare token`() = runTest {
        val h = Harness()
        val token = userJwt("user_42")
        h.queueStore.save(listOf(MeteringEntry(jwtIdentityKey(token), null, "default:feat_article.enabled", "article/1", 1.0, NOW)))
        val mos = h.mos()
        mos.identify(token)
        h.transport.handler = { ok(SUCCESS) }

        assertEquals(1, mos.flush().sent)
        assertEquals(buildJsonObject { put("userJwt", token) }, h.transport.to(COUNTER_UPDATES_PATH).single().json()["identity"])
    }

    @Test
    fun `user agent shape`() {
        val config = Harness().config()
        val ua = MOS(config.copy(app = AppInfo("Future Herald", "2.3 (45)")), DeviceInfo("Android", "16", "Pixel 9 Pro"), Clock.System, null).userAgent
        assertEquals("Future-Herald/2.3-45 (Android 16; Pixel 9 Pro) MOS-Android/$MOS_VERSION", ua)
        val jvm = MOS.inMemory(config).userAgent
        assertTrue(Regex("""^TestApp/1\.0 \([^;()]+; JVM\) MOS-Android/""" + Regex.escape(MOS_VERSION) + "$").matches(jvm), jvm)
        for (word in listOf("bot", "crawler", "spider", "okhttp", "curl", "Dalvik")) {
            assertTrue(word !in ua.lowercase(), "UA must not contain $word")
        }
    }

    @Test
    fun `user agent is printable ASCII, the same as the iOS SDK's up to the SDK suffix`() {
        val cases = listOf(
            AppInfo("Herald", "2.1") to DeviceInfo("iOS", "18.2", "iPhone17,1") to "Herald/2.1 (iOS 18.2; iPhone17,1)",
            AppInfo("Future Herald", "2.3 (45)") to DeviceInfo("Android", "16", "Pixel 9 Pro") to "Future-Herald/2.3-45 (Android 16; Pixel 9 Pro)",
            AppInfo("Zeit für Brot – News", "3.0 β") to DeviceInfo("iOS", "26.1", "iPhone18,1") to "Zeit-f-r-Brot-News/3.0 (iOS 26.1; iPhone18,1)",
            AppInfo("新聞アプリ", "1.0") to DeviceInfo("Android", "16", "Pixel 9") to "app/1.0 (Android 16; Pixel 9)",
            AppInfo("-Herald-", "1.0--beta") to DeviceInfo("iOS", " 18.2 ", "Pixel (9); Pro\\ü") to "Herald/1.0--beta (iOS 18.2; Pixel 9 Pro)",
            AppInfo("Herald", "2.1") to DeviceInfo("ü", "16", "") to "Herald/2.1 (16; )",
            // A blank name or version is not an error: each becomes `app`.
            AppInfo("", "") to DeviceInfo("iOS", "18.2", "iPhone17,1") to "app/app (iOS 18.2; iPhone17,1)",
            AppInfo(" ", "\t") to DeviceInfo("Android", "16", "Pixel 9") to "app/app (Android 16; Pixel 9)",
            AppInfo("Herald", " ") to DeviceInfo("iOS", "18.2", "iPhone17,1") to "Herald/app (iOS 18.2; iPhone17,1)",
        )
        for ((input, expected) in cases) {
            assertEquals("$expected MOS-Android/$MOS_VERSION", buildUserAgent(input.first, input.second))
        }
    }

    @Test
    fun `request body carries only surfaceSlug, identity, resource and properties`() = runTest {
        val h = Harness(anonymousId = ANON)
        val resource = Resource(
            id = "article/123",
            meta = mapOf("section" to "sport"),
            extra = buildJsonObject {
                put("fullPath", "/sport/123")
                put("id", "ignored")
            },
        )
        h.mos().decide(resource)

        assertEquals(
            buildJsonObject {
                put("surfaceSlug", SURFACE)
                putJsonObject("identity") { put("anonymousIdentifier", ANON) }
                putJsonObject("resource") {
                    put("fullPath", "/sport/123")
                    put("id", "article/123")
                    putJsonObject("meta") { put("section", "sport") }
                }
                putJsonObject("properties") { put("platform", "android") }
            },
            h.transport.requests.single().json(),
        )
    }

    @Test
    fun `a resource with no meta or extra sends only its id`() = runTest {
        val h = Harness(anonymousId = ANON)
        h.mos().decide(Resource("article/1"))
        assertEquals(buildJsonObject { put("id", "article/1") }, h.transport.requests.single().json()["resource"])
    }

    @Test
    fun `sk_ refusal`() {
        for (key in listOf("sk_live_env_secret", "", "pkey_live_x_y", "live_pk_x")) {
            val logs = LogCollector()
            val config = Harness().config().copy(publicKey = key, onLog = logs)
            assertFailsWith<IllegalArgumentException> { MOS.inMemory(config) }
            assertEquals(listOf("config:invalid-key"), logs.codes())
            if (key.isNotEmpty()) assertTrue(logs.events.none { key in it.toString() }, "the key is never logged")
        }
    }

    @Test
    fun `a blank app name or version constructs, and the User-Agent says app for each`() {
        val mos = MOS.inMemory(Harness().config().copy(app = AppInfo(" ", "")))
        assertTrue(mos.userAgent.startsWith("app/app ("), mos.userAgent)
    }

    @Test
    fun `429 maps to rateLimited`() = runTest {
        for (body in listOf(fixture("error-429.json"), "", "<html>slow down</html>")) {
            val h = Harness(anonymousId = ANON)
            h.transport.handler = { ok(body, 429) }
            val failure = h.mos().decide(article()).failure!!
            assertEquals(FailureReason.RateLimited, failure.reason)
            assertEquals(429, failure.status)
        }
        // An envelope saying 429 is rate limiting whatever the HTTP status.
        val h = Harness(anonymousId = ANON)
        h.transport.handler = { ok(fixture("error-429.json"), 200) }
        assertEquals(FailureReason.RateLimited, h.mos().decide(article()).failure?.reason)
    }

    @Test
    fun `invalid JSON`() = runTest {
        val h = Harness(anonymousId = ANON)
        h.transport.handler = { ok("{\"status\":") }
        val failure = h.mos().decide(article()).failure!!
        assertEquals(FailureReason.InvalidJson, failure.reason)
        assertEquals(200, failure.status)

        h.transport.handler = { ok("") }
        assertEquals(FailureReason.InvalidJson, h.mos().decide(article()).failure?.reason)
    }

    @Test
    fun `error envelope on 200`() = runTest {
        val h = Harness(anonymousId = ANON)
        h.transport.handler = { ok(fixture("error-402.json"), 200) }
        val result = h.mos().decide(article())
        assertEquals(Failure(FailureReason.ApiError, status = 200, statusCode = 402, message = "org_blocked"), result.failure)
        assertEquals(DecisionSource.Unavailable, result.source)
    }

    @Test
    fun `apiBaseUrl tolerates a trailing slash`() = runTest {
        val h = Harness(anonymousId = ANON)
        h.mos { it.copy(apiBaseUrl = "https://api.test/") }.decide(article())
        assertEquals("https://api.test/api/v1/surface-decisions", h.transport.requests.single().url)
    }

    @Test
    fun `a 2xx body that is not strict JSON is invalidJson`() = runTest {
        val h = Harness(anonymousId = ANON)
        for (body in listOf("OK", "<html>ok</html>")) {
            h.transport.handler = { ok(body) }
            val failure = h.mos().decide(article()).failure!!
            assertEquals(FailureReason.InvalidJson, failure.reason, body)
            assertEquals(200, failure.status)
        }
    }
}
