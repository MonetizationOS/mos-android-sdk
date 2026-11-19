package com.monetizationos.showcase.support

import android.content.Context
import com.monetizationos.sdk.transport.HttpRequest
import com.monetizationos.sdk.transport.HttpResponse
import com.monetizationos.sdk.transport.HttpTransport
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/**
 * Answers every SDK request from the shared `fixtures/` folder, so the app runs with no key and no
 * backend while still going through the SDK's real code path. The rules (routes, templates, state)
 * are in fixtures/README.md; the iOS app implements the same ones. [read] returns a fixture file's
 * text by its path inside `fixtures/`.
 */
class FixtureTransport(private val read: (String) -> String?, private val latencyMs: Long = 150) : HttpTransport {
    private val routes = parse(read("routes.json")) as? JsonObject ?: JsonObject(emptyMap())
    private val offers: Map<String, JsonObject> = (parse(read("offers.json")) as? JsonArray).orEmpty()
        .mapNotNull { it as? JsonObject }
        .mapNotNull { offer -> offer.string("id")?.let { it to offer } }
        .toMap()

    private val lock = Mutex()

    /** `<caller key>|<counterId>` to the resource ids that consumed the meter. */
    private val meters = mutableMapOf<String, MutableSet<String>>()

    /** `<caller key>|<offer id>` for offers that can be claimed once. */
    private val claimed = mutableSetOf<String>()

    override suspend fun send(request: HttpRequest): HttpResponse {
        delay(latencyMs)
        return lock.withLock { answer(request) }
    }

    private fun answer(request: HttpRequest): HttpResponse {
        val path = URI(request.url).path.orEmpty()
        val body = parse(request.body) as? JsonObject ?: JsonObject(emptyMap())
        when (request.method to path) {
            "POST" to "/api/v1/surface-decisions" -> return decision(body)
            "POST" to "/api/v1/offer-redemptions" -> return redemption(body)
            "POST" to "/api/v1/counter-updates" -> return counterUpdate(body)
        }
        // /api/v1/envs/{env}[/namespaces/{namespace}]/endpoints/{path}
        val parts = path.split('/')
        if (parts.size > 5 && parts[1] == "api" && parts[2] == "v1" && parts[3] == "envs") {
            val rest = parts.drop(5)
            if (rest.size > 3 && rest[0] == "namespaces" && rest[2] == "endpoints") {
                return endpoint(rest.drop(3).joinToString("/"), request)
            }
            if (rest.size > 1 && rest[0] == "endpoints") return endpoint(rest.drop(1).joinToString("/"), request)
        }
        return envelope(routes.string("notFound"), mapOf("path" to path))
    }

    // Routes

    private fun decision(body: JsonObject): HttpResponse {
        val caller = Caller.of(body["identity"] as? JsonObject)
        val surface = body.string("surfaceSlug").orEmpty()
        val resourceId = (body["resource"] as? JsonObject)?.string("id").orEmpty()
        val route = routes.objects("decisions").firstOrNull { route ->
            val pattern = route.string("resource") ?: return@firstOrNull false
            route.string("surface") == surface &&
                if (pattern.endsWith("*")) resourceId.startsWith(pattern.dropLast(1)) else pattern == resourceId
        } ?: return envelope(routes.string("notFound"), mapOf("path" to "$surface $resourceId"))

        val file = route.string("authenticatedFile")?.takeIf { caller.isAuthenticated } ?: route.string("file").orEmpty()
        val decision = load(file) as? JsonObject ?: return json(buildJsonObject { put("status", "error") }, 500)
        val features = (decision["features"] as? JsonObject)?.mapValues { (_, value) ->
            val feature = value as? JsonObject ?: return@mapValues value
            val properties = feature["properties"] as? JsonObject ?: return@mapValues value
            JsonObject(
                feature + ("properties" to JsonObject(
                    properties.mapValues { (_, property) ->
                        if (property is JsonObject && property.string("type") == "meterable") consume(property, caller, resourceId) else property
                    },
                )),
            )
        }
        return json(JsonObject(decision + ("identity" to caller.decisionIdentity) + listOfNotNull(features?.let { "features" to JsonObject(it) })))
    }

    /** A unique-resource meter: a new resource id consumes one unit while any remain; a used one is free. */
    private fun consume(template: JsonObject, caller: Caller, resourceId: String): JsonObject {
        val total = template["totalUnits"]?.jsonPrimitive?.intOrNull ?: 0
        val used = meters.getOrPut("${caller.key}|${template.string("counterId").orEmpty()}") { mutableSetOf() }
        val wasUsed = resourceId in used
        val consumed = !wasUsed && used.size < total
        if (consumed) used += resourceId
        return JsonObject(
            template + mapOf(
                "hasAccess" to JsonPrimitive(wasUsed || consumed),
                "isFallback" to JsonPrimitive(false),
                "consumedUnits" to JsonPrimitive(used.size),
                "remainingUnits" to JsonPrimitive(maxOf(total - used.size, 0)),
                "resourceIdUsed" to JsonPrimitive(wasUsed),
                "consumedInRequest" to JsonPrimitive(consumed),
                "consumedUnitsInRequest" to JsonPrimitive(if (consumed) 1 else 0),
                "excessUnitsInRequest" to JsonPrimitive(if (wasUsed || consumed) 0 else 1),
            ),
        )
    }

    private fun redemption(body: JsonObject): HttpResponse {
        val caller = Caller.of(body["identity"] as? JsonObject)
        val offerId = body.string("offerToken")?.let(::payloadOfToken)?.string("id")
        val offer = offerId?.let(offers::get)
        val file = offer?.string("fixtureRedemption")
        if (offerId == null || offer == null || file == null) {
            return json(
                buildJsonObject {
                    put("status", "error")
                    put("statusCode", 400)
                    put("message", "Invalid offer token")
                },
                400,
            )
        }
        // An offer with a repeat answer is claimed by its first redemption.
        val repeat = offer.string("fixtureRepeatRedemption")
        val answer = if (repeat != null && !claimed.add("${caller.key}|$offerId")) repeat else file
        // Stands in for the offer's redemption values in MOS, which the redemption method's workflow reads.
        val values = (offer["fixtureRedemptionValues"] as? JsonObject).orEmpty().mapNotNull { (key, value) ->
            (value as? JsonPrimitive)?.takeIf { it.isString }?.let { key to it.content }
        }.toMap()
        return json(load(answer, values) ?: JsonObject(emptyMap()))
    }

    private fun counterUpdate(body: JsonObject): HttpResponse {
        val caller = Caller.of(body["identity"] as? JsonObject)
        val counterId = body.string("counterId")
        val resourceId = body.string("resourceId")
        if (counterId != null && resourceId != null) meters.getOrPut("${caller.key}|$counterId") { mutableSetOf() } += resourceId
        return json(load(routes.string("counterUpdate").orEmpty()) ?: JsonObject(emptyMap()))
    }

    private fun endpoint(path: String, request: HttpRequest): HttpResponse {
        val route = routes.objects("endpoints").firstOrNull { route ->
            route.string("method") == request.method && route.string("path") == path
        } ?: return envelope(routes.string("endpointNotFound"), mapOf("path" to path))
        return envelope(route.string("file"), mapOf("requestBody" to (request.body?.takeIf { it.isNotEmpty() } ?: "null")))
    }

    // Files

    /** A `{ status, headers, body }` file as an HTTP response. */
    private fun envelope(file: String?, values: Map<String, String> = emptyMap()): HttpResponse {
        val envelope = file?.let { load(it, values) } as? JsonObject ?: JsonObject(emptyMap())
        val headers = (envelope["headers"] as? JsonObject).orEmpty().mapValues { (_, value) -> value.jsonPrimitive.content }
        return json(envelope["body"] ?: JsonNull, envelope["status"]?.jsonPrimitive?.intOrNull ?: 200, headers)
    }

    /** Reads a fixture, fills its placeholders, then expands offer slots and html content files. */
    private fun load(file: String, values: Map<String, String> = emptyMap()): JsonElement? =
        parse(read(file)?.let { fill(it, values) })?.let(::expand)

    private fun fill(text: String, values: Map<String, String>): String {
        var result = text
        // A whole-value placeholder becomes raw JSON, so the quotes go with it.
        values["requestBody"]?.let { result = result.replace("\"{{requestBody}}\"", it) }
        result = result.replace("{{eventId}}", UUID.randomUUID().toString())
        for ((name, value) in values) if (name != "requestBody") result = result.replace("{{$name}}", value)
        return TOKEN_PLACEHOLDER.replace(result) { token(it.groupValues[1]) }
    }

    private fun expand(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> {
            val expanded = value.mapValues { (key, child) ->
                if (key == "mos-offer-slots" && child is JsonArray) {
                    JsonArray(child.map { item -> (item as? JsonPrimitive)?.takeIf { it.isString }?.let { slot(it.content) } ?: item })
                } else {
                    expand(child)
                }
            }
            val contentFile = value.string("contentFile")
            if (value.string("type") == "html" && contentFile != null) {
                JsonObject(expanded - "contentFile" + ("content" to JsonPrimitive(fill(read(contentFile).orEmpty(), emptyMap()))))
            } else {
                JsonObject(expanded)
            }
        }
        is JsonArray -> JsonArray(value.map(::expand))
        else -> value
    }

    private fun slot(offerId: String): JsonObject {
        val offer = offers[offerId] ?: return buildJsonObject { put("type", "single") }
        return buildJsonObject {
            put("type", "single")
            put(
                "offer",
                buildJsonObject {
                    put("id", offerId)
                    put("redemptionSchema", offer["redemptionSchema"] ?: JsonNull)
                    put("presentation", offer["presentation"] ?: JsonObject(emptyMap()))
                    put("token", token(offerId))
                },
            )
        }
    }

    /** Shaped like the server's offer token; the SDK reads the payload, never the signature. */
    private fun token(offerId: String): String {
        val offer = offers[offerId].orEmpty()
        return fakeJwt(
            buildJsonObject {
                put("id", offerId)
                put("presentation", offer["presentation"] ?: JsonObject(emptyMap()))
                put("redemptionSchema", offer["redemptionSchema"] ?: JsonNull)
                put("expirationMsUtc", System.currentTimeMillis() + 3_600_000)
            },
        )
    }

    /** The caller of a request: `jwt:<sub>` or `anon:<id>`, minting an anonymous id when asked. */
    private class Caller(val key: String, val identifier: String, val isAuthenticated: Boolean, val claims: JsonObject) {
        val decisionIdentity: JsonObject
            get() = buildJsonObject {
                put("identifier", identifier)
                put("isAuthenticated", isAuthenticated)
                put("authType", if (isAuthenticated) "jwt" else "anonymous")
                put("jwtClaims", claims)
            }

        companion object {
            fun of(identity: JsonObject?): Caller =
                identity?.string("userJwt")?.let(::jwt) ?: anonymous(identity?.string("anonymousIdentifier"))

            /** Fixture mode accepts any token; the subject comes from its payload when it has one. */
            private fun jwt(token: String): Caller {
                val claims = payloadOfToken(token) ?: JsonObject(emptyMap())
                val subject = claims.string("sub") ?: "user"
                return Caller("jwt:$subject", subject, true, claims)
            }

            private fun anonymous(identifier: String?): Caller {
                val id = identifier ?: "mos-anon-v1-fixture-${UUID.randomUUID()}"
                return Caller("anon:$id", id, false, JsonObject(emptyMap()))
            }
        }
    }

    companion object {
        /** Fixture mode never sends this key anywhere; it only names the environment `test_showcase`. */
        const val PUBLIC_KEY = "pk_test_showcase_fixtures"

        /** Never contacted: the fixture transport answers. */
        const val API_BASE_URL = "https://fixtures.showcase.invalid"

        private val TOKEN_PLACEHOLDER = Regex("""\{\{token:([^}]+)\}\}""")
        private val random = SecureRandom()

        /** Fixtures packaged as the app's assets (`assets.srcDir` in app/build.gradle.kts). */
        fun fromAssets(context: Context): FixtureTransport {
            val assets = context.applicationContext.assets
            return FixtureTransport({ path -> runCatching { assets.open(path).bufferedReader().use { it.readText() } }.getOrNull() })
        }

        /** An unsigned JWT: `base64url(header).base64url(payload).base64url(64 random bytes)`. */
        fun fakeJwt(payload: JsonObject): String {
            val encoder = Base64.getUrlEncoder().withoutPadding()
            fun part(json: JsonObject) = encoder.encodeToString(json.toString().encodeToByteArray())
            val signature = ByteArray(64).also(random::nextBytes)
            return part(buildJsonObject {
                put("alg", "ES256")
                put("typ", "JWT")
            }) + "." + part(payload) + "." + encoder.encodeToString(signature)
        }

        fun payloadOfToken(token: String): JsonObject? {
            val parts = token.split('.')
            if (parts.size != 3) return null
            return runCatching { Json.parseToJsonElement(Base64.getUrlDecoder().decode(parts[1]).decodeToString()) as? JsonObject }.getOrNull()
        }

        private fun json(element: JsonElement, status: Int = 200, headers: Map<String, String> = emptyMap()): HttpResponse =
            HttpResponse(status, element.toString(), mapOf("Content-Type" to "application/json") + headers)

        private fun parse(text: String?): JsonElement? = text?.takeIf { it.isNotBlank() }?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

        private fun JsonObject.objects(key: String): List<JsonObject> = (this[key] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    }
}
