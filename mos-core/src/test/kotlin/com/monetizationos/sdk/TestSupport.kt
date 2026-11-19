package com.monetizationos.sdk

import com.monetizationos.sdk.cache.DecisionCacheStore
import com.monetizationos.sdk.cache.InMemoryDecisionCacheStore
import com.monetizationos.sdk.identity.IdentityStore
import com.monetizationos.sdk.identity.IdentityStoreKeys.ANONYMOUS_IDENTIFIER
import com.monetizationos.sdk.identity.IdentityStoreKeys.LAST_IDENTITY_KEY
import com.monetizationos.sdk.identity.IdentityStoreKeys.PENDING_PREVIOUS_ANONYMOUS_IDENTIFIER
import com.monetizationos.sdk.identity.InMemoryIdentityStore
import com.monetizationos.sdk.logging.LogEvent
import com.monetizationos.sdk.metering.InMemoryMeterLedgerStore
import com.monetizationos.sdk.metering.InMemoryMeteringQueueStore
import com.monetizationos.sdk.metering.MeterLedgerStore
import com.monetizationos.sdk.metering.MeteringQueueStore
import com.monetizationos.sdk.transport.HttpRequest
import com.monetizationos.sdk.transport.HttpResponse
import com.monetizationos.sdk.transport.HttpTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration

/** 2026-10-17T00:00:00Z. */
const val NOW = 1_792_195_200_000L
const val PK = "pk_test_env1_s3cret_with_underscores"
const val SURFACE = "app-paywall"
const val ANON = "mos-anon-v1-abc"

/** The properties of a decision that carries nothing but the platform the SDK adds. */
val PLATFORM_ONLY = JsonObject(mapOf("platform" to JsonPrimitive("android")))

internal class FakeClock(@Volatile var now: Long = NOW) : Clock {
    override fun nowMs(): Long = now

    fun advance(by: Duration) {
        now += by.inWholeMilliseconds
    }
}

/** Records every request. The handler decides the response; throwing simulates a network error. */
class FakeTransport(@Volatile var handler: suspend (HttpRequest) -> HttpResponse = { ok(decisionBody()) }) : HttpTransport {
    val requests = CopyOnWriteArrayList<HttpRequest>()

    override suspend fun send(request: HttpRequest): HttpResponse {
        requests += request
        return handler(request)
    }

    fun to(path: String): List<HttpRequest> = requests.filter { it.url.endsWith(path) }
}

class LogCollector : (LogEvent) -> Unit {
    val events = CopyOnWriteArrayList<LogEvent>()

    override fun invoke(event: LogEvent) {
        events += event
    }

    fun codes(): List<String> = events.map { it.code }
}

/** A MOS wired to fakes and in-memory stores that tests can inspect. */
internal class Harness(anonymousId: String? = null) {
    val clock = FakeClock()
    val transport = FakeTransport()
    val logs = LogCollector()
    val identityStore = identityStore(anonymousId)
    val cacheStore = InMemoryDecisionCacheStore()
    val queueStore = InMemoryMeteringQueueStore()
    val meterStore = InMemoryMeterLedgerStore()

    fun config(): MOSConfig = MOSConfig(
        publicKey = PK,
        surface = SURFACE,
        app = AppInfo("TestApp", "1.0"),
        apiBaseUrl = "https://api.test",
        identity = IdentityConfig(store = identityStore),
        onLog = logs,
        transport = transport,
    )

    fun mos(
        cache: DecisionCacheStore? = cacheStore,
        queue: MeteringQueueStore? = queueStore,
        meters: MeterLedgerStore? = meterStore,
        customise: (MOSConfig) -> MOSConfig = { it },
    ): MOS = MOS(
        customise(config()), DeviceInfo("Android", "16", "Pixel 9"), clock, storageDirectory = null,
        cacheStore = cache, queueStore = queue, meterStore = meters,
    )
}

/** An in-memory identity store holding these values under the SDK's keys. */
internal fun identityStore(anonymousId: String? = null, lastIdentityKey: String? = null, pendingPreviousAnonymousIdentifier: String? = null) =
    InMemoryIdentityStore(
        buildMap {
            anonymousId?.let { put(ANONYMOUS_IDENTIFIER, it) }
            lastIdentityKey?.let { put(LAST_IDENTITY_KEY, it) }
            pendingPreviousAnonymousIdentifier?.let { put(PENDING_PREVIOUS_ANONYMOUS_IDENTIFIER, it) }
        },
    )

internal suspend fun IdentityStore.anonymousIdentifier(): String? = get(ANONYMOUS_IDENTIFIER)

internal suspend fun IdentityStore.lastIdentityKey(): String? = get(LAST_IDENTITY_KEY)

internal suspend fun IdentityStore.pendingPreviousAnonymousIdentifier(): String? = get(PENDING_PREVIOUS_ANONYMOUS_IDENTIFIER)

fun ok(body: String, status: Int = 200) = HttpResponse(status, body)

fun HttpRequest.json(): JsonObject = Json.parseToJsonElement(checkNotNull(body) { "no body" }).jsonObject

fun fixture(name: String): String = File(fixturesDir(), name).readText()

fun fixturesDir(): File = File(checkNotNull(System.getProperty("mos.fixtures")) { "mos.fixtures is not set" })

private fun b64(json: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(json.encodeToByteArray())

/** An unsigned test JWT; the SDK never verifies, it only reads the payload. */
fun jwt(payload: String): String = "${b64("""{"alg":"ES256","typ":"JWT"}""")}.${b64(payload)}.c2lnbmF0dXJl"

fun userJwt(sub: String) = jwt("""{"sub":"$sub","iat":1}""")

fun offerToken(expirationMsUtc: Long?): String =
    jwt("""{"id":"offer_x","presentation":{},"redemptionSchema":"custom:purchase","expirationMsUtc":${expirationMsUtc ?: "null"},"redemption":"x"}""")

/** A decision with one `article.enabled` meter and optional open surface properties. */
fun decisionBody(
    identifier: String = ANON,
    isAuthenticated: Boolean = false,
    eventId: String = "evt-1",
    hasAccess: Boolean = true,
    consumedInRequest: Boolean? = true,
    consumedUnitsInRequest: Int? = 1,
    surfaceProperties: JsonObject = JsonObject(emptyMap()),
    counterId: String? = "default:feat_article.enabled",
    // A counted meter, as the server sends for a meter that is neither `on` nor `off`.
    consumedUnits: Double? = null,
    totalUnits: Double? = null,
    uniqueResources: Boolean? = null,
    resourceIdUsed: Boolean? = null,
    periodStart: String? = null,
): String = buildJsonObject {
    put("status", "success")
    put("eventId", eventId)
    putJsonObject("identity") {
        put("identifier", identifier)
        put("isAuthenticated", isAuthenticated)
        put("authType", if (isAuthenticated) "jwt" else "anonymous")
    }
    putJsonObject("customer") {
        put("isCustomer", false)
        put("hasProducts", false)
        putJsonArray("customerIdentifiers") {}
    }
    putJsonObject("features") {
        putJsonObject("article") {
            put("featureId", "feat_article")
            put("featureSlug", "article")
            putJsonObject("properties") {
                putJsonObject("enabled") {
                    put("type", "meterable")
                    put("isFallback", false)
                    counterId?.let { put("counterId", it) }
                    put("hasAccess", hasAccess)
                    consumedInRequest?.let { put("consumedInRequest", it) }
                    consumedUnitsInRequest?.let { put("consumedUnitsInRequest", it) }
                    consumedUnits?.let { put("consumedUnits", it) }
                    totalUnits?.let {
                        put("totalUnits", it)
                        put("remainingUnits", maxOf(0.0, it - (consumedUnits ?: 0.0)))
                    }
                    uniqueResources?.let { put("uniqueResources", it) }
                    resourceIdUsed?.let { put("resourceIdUsed", it) }
                    periodStart?.let { put("periodStart", it) }
                }
            }
        }
    }
    putJsonObject("surfaceBehavior") { put("properties", surfaceProperties) }
    put("componentsSkipped", false)
    putJsonObject("componentBehaviors") {}
}.toString()

val SUCCESS = """{"status":"success"}"""

fun article(id: String = "article/1") = Resource(id)
