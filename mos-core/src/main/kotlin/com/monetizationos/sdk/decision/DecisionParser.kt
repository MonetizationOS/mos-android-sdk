package com.monetizationos.sdk.decision

import com.monetizationos.sdk.EmptyJsonObject
import com.monetizationos.sdk.boolean
import com.monetizationos.sdk.number
import com.monetizationos.sdk.obj
import com.monetizationos.sdk.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Lenient decode, as on iOS: only `status: "success"` and a boolean `identity.isAuthenticated` are required. Every other
 * field defaults when absent or mistyped; malformed features and properties are skipped or unknown.
 */
internal fun parseDecision(body: JsonObject): Decision? {
    if (body.string("status") != "success") return null
    val identity = body.obj("identity") ?: return null
    val isAuthenticated = identity.boolean("isAuthenticated") ?: return null
    val customer = body.obj("customer") ?: EmptyJsonObject
    val surfaceBehavior = body.obj("surfaceBehavior") ?: EmptyJsonObject
    return Decision(
        eventId = body.string("eventId"),
        identity = DecisionIdentity(
            identifier = identity.string("identifier"),
            isAuthenticated = isAuthenticated,
            authType = identity.string("authType"),
            jwtClaims = identity.obj("jwtClaims") ?: EmptyJsonObject,
        ),
        customer = Customer(
            isCustomer = customer.boolean("isCustomer") ?: false,
            hasProducts = customer.boolean("hasProducts") ?: false,
            customerIdentifiers = (customer["customerIdentifiers"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                .orEmpty(),
        ),
        features = (body.obj("features") ?: EmptyJsonObject).mapNotNull { (slug, value) ->
            (value as? JsonObject)?.let { slug to parseFeature(slug, it) }
        }.toMap(),
        surfaceProperties = surfaceBehavior.obj("properties") ?: EmptyJsonObject,
        surfaceBehavior = surfaceBehavior,
        componentBehaviors = (body.obj("componentBehaviors") ?: EmptyJsonObject).mapNotNull { (slug, value) ->
            (value as? JsonObject)?.let {
                slug to Component(it.obj("properties") ?: EmptyJsonObject, it.obj("metadata") ?: EmptyJsonObject)
            }
        }.toMap(),
        componentsSkipped = body.boolean("componentsSkipped") ?: false,
        raw = body,
    )
}

private fun parseFeature(slug: String, feature: JsonObject): Feature = Feature(
    featureId = feature.string("featureId") ?: "",
    featureSlug = feature.string("featureSlug") ?: slug,
    properties = (feature.obj("properties") ?: EmptyJsonObject).mapNotNull { (key, value) ->
        (value as? JsonObject)?.let { key to parseProperty(it) }
    }.toMap(),
)

internal fun parseProperty(property: JsonObject): Property {
    val type = property.string("type") ?: ""
    val isFallback = property.boolean("isFallback") ?: false
    return when (type) {
        "meterable" -> {
            val counterId = property.string("counterId")
            val hasAccess = property.boolean("hasAccess")
            if (counterId == null || hasAccess == null) {
                UnknownProperty(type, property)
            } else {
                Meterable(
                    counterId = counterId,
                    hasAccess = hasAccess,
                    isFallback = isFallback,
                    consumedUnits = property.number("consumedUnits"),
                    remainingUnits = property.number("remainingUnits"),
                    totalUnits = property.number("totalUnits"),
                    periodStart = property.string("periodStart"),
                    uniqueResources = property.boolean("uniqueResources"),
                    resourceIdUsed = property.boolean("resourceIdUsed"),
                    consumedInRequest = property.boolean("consumedInRequest"),
                    consumedUnitsInRequest = property.number("consumedUnitsInRequest"),
                    excessUnitsInRequest = property.number("excessUnitsInRequest"),
                    lockedOffline = property.boolean("lockedOffline") ?: false,
                )
            }
        }
        "number" -> NumberProperty(property.number("value"), isFallback)
        "string" -> StringProperty(property.string("value"), isFallback)
        "boolean" -> BooleanProperty(property.boolean("value"), isFallback)
        else -> UnknownProperty(type, property)
    }
}

/** Every meterable property as `(feature, property, meter)`, sorted by feature then property. */
internal fun meterables(decision: Decision): List<Triple<String, String, Meterable>> =
    decision.features.keys.sorted().flatMap { featureSlug ->
        val properties = decision.features.getValue(featureSlug).properties
        properties.keys.sorted().mapNotNull { key -> (properties[key] as? Meterable)?.let { Triple(featureSlug, key, it) } }
    }
