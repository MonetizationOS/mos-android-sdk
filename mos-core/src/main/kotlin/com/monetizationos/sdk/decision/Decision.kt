package com.monetizationos.sdk.decision

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@ConsistentCopyVisibility
public data class Decision internal constructor(
    val eventId: String?,
    val identity: DecisionIdentity,
    val customer: Customer,
    val features: Map<String, Feature>,
    /** `surfaceBehavior.properties`, or an empty object. */
    val surfaceProperties: JsonObject,
    val surfaceBehavior: JsonObject,
    val componentBehaviors: Map<String, Component>,
    val componentsSkipped: Boolean,
    /** The whole response body as received. Served from the cache, its meters carry the device's count since the last network decision. */
    val raw: JsonObject,
) {
    /** The resolved experience at `surfaceBehavior.properties[key]`, or `componentBehaviors[component].properties[key]`. */
    public fun experience(key: String, component: String? = null): Experience? {
        val properties = if (component == null) surfaceProperties else componentBehaviors[component]?.properties
        return Experience.decode(properties?.get(key))
    }

    public companion object {
        /** Decodes a `surface-decisions` response body, e.g. for a preview; null when it is not a decision. */
        public fun fromJson(json: JsonObject): Decision? = parseDecision(json)
    }
}

@ConsistentCopyVisibility
public data class DecisionIdentity internal constructor(
    val identifier: String?,
    val isAuthenticated: Boolean,
    val authType: String?,
    /** Empty when the response has none. */
    val jwtClaims: JsonObject,
)

@ConsistentCopyVisibility
public data class Customer internal constructor(
    val isCustomer: Boolean,
    val hasProducts: Boolean,
    val customerIdentifiers: List<String>,
)

@ConsistentCopyVisibility
public data class Feature internal constructor(
    val featureId: String,
    val featureSlug: String,
    val properties: Map<String, Property>,
)

@ConsistentCopyVisibility
public data class Component internal constructor(val properties: JsonObject, val metadata: JsonObject)

/** A feature property. Unknown `type` values decode to [UnknownProperty] rather than failing the decision. */
public sealed interface Property

@ConsistentCopyVisibility
public data class Meterable internal constructor(
    val counterId: String,
    val hasAccess: Boolean,
    val isFallback: Boolean,
    val consumedUnits: Double? = null,
    val remainingUnits: Double? = null,
    val totalUnits: Double? = null,
    val periodStart: String? = null,
    val uniqueResources: Boolean? = null,
    val resourceIdUsed: Boolean? = null,
    val consumedInRequest: Boolean? = null,
    val consumedUnitsInRequest: Double? = null,
    val excessUnitsInRequest: Double? = null,
    /**
     * Set by the SDK, never the server: `offline.enforceMeters` turned access off because reads counted on this
     * device since the last network decision used the meter up.
     */
    val lockedOffline: Boolean = false,
) : Property

@ConsistentCopyVisibility
public data class NumberProperty internal constructor(val value: Double?, val isFallback: Boolean) : Property

@ConsistentCopyVisibility
public data class StringProperty internal constructor(val value: String?, val isFallback: Boolean) : Property

@ConsistentCopyVisibility
public data class BooleanProperty internal constructor(val value: Boolean?, val isFallback: Boolean) : Property

@ConsistentCopyVisibility
public data class UnknownProperty internal constructor(val type: String, val raw: JsonElement) : Property
