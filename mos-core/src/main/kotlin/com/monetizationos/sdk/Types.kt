package com.monetizationos.sdk

import com.monetizationos.sdk.decision.BooleanProperty
import com.monetizationos.sdk.decision.Decision
import com.monetizationos.sdk.decision.Meterable
import com.monetizationos.sdk.decision.UnknownProperty
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlin.time.Duration

/** `extra` keys are merged into the top level of the request's `resource`; `id` and `meta` win on collision. */
public data class Resource(
    val id: String,
    val meta: Map<String, String>? = null,
    val extra: JsonObject = JsonObject(emptyMap()),
)

public sealed interface CachePolicy {
    /** Request; on any failure serve the exact entry, else the latest for this surface and identity. */
    public data object NetworkFirst : CachePolicy

    /** Serve the exact entry when it is at most [maxAge] old, else behave as [NetworkFirst]. */
    public data class CacheFirst(val maxAge: Duration) : CachePolicy

    /** Never read the cache; still write it on success. */
    public data object NetworkOnly : CachePolicy

    /** Never touch the network. */
    public data object CacheOnly : CachePolicy
}

public sealed interface DecisionSource {
    public data object Network : DecisionSource

    @ConsistentCopyVisibility
    public data class Cache internal constructor(val ageMs: Long) : DecisionSource

    @ConsistentCopyVisibility
    public data class Fallback internal constructor(val ageMs: Long, val fromResourceId: String) : DecisionSource

    public data object Unavailable : DecisionSource
}

public enum class FailureReason(public val value: String) {
    RequestFailed("requestFailed"),
    Timeout("timeout"),
    InvalidJson("invalidJson"),
    ApiError("apiError"),
    HttpError("httpError"),
    InvalidResponse("invalidResponse"),
    RateLimited("rateLimited"),
    Unauthorized("unauthorized"),
    NotConfigured("notConfigured"),
}

/** `status` is the HTTP status; `statusCode` is the one in an error envelope. */
public data class Failure(
    val reason: FailureReason,
    val status: Int? = null,
    val statusCode: Int? = null,
    val message: String,
)

public class DecisionResult internal constructor(
    public val decision: Decision?,
    public val source: DecisionSource,
    /** Present whenever the network was tried and failed, even when the cache served. */
    public val failure: Failure?,
    private val failOpen: Boolean,
    /** True only on the network result whose request carried `previousAnonymousIdentifier` and came back authenticated. */
    public val linkedAnonymousIdentifier: Boolean = false,
) {
    /** The decision's own value for a meterable or boolean property; `failOpen` when there is none. */
    public fun hasAccess(feature: String, property: String): Boolean =
        when (val value = decision?.features?.get(feature)?.properties?.get(property)) {
            is Meterable -> value.hasAccess
            is BooleanProperty -> value.value ?: failOpen
            // A malformed property that still says whether access is granted, e.g. a meterable without its
            // counterId: never fail open against an explicit answer.
            is UnknownProperty -> ((value.raw as? JsonObject)?.get("hasAccess") as? JsonPrimitive)?.booleanOrNull ?: failOpen
            else -> failOpen
        }

    override fun toString(): String =
        "DecisionResult(source=$source, failure=$failure, eventId=${decision?.eventId}, linkedAnonymousIdentifier=$linkedAnonymousIdentifier)"
}

public enum class IdentityKind(public val value: String) {
    Anonymous("anonymous"),
    Jwt("jwt"),
}

/**
 * What `identify` did. [previous] and [current] are null when there was no identity key (nothing resolved
 * yet, or the next decision will ask the server to mint one). [linkPending] means a
 * `previousAnonymousIdentifier` will ride the next decision.
 */
@ConsistentCopyVisibility
public data class IdentifyResult internal constructor(
    val changed: Boolean,
    val previous: IdentityKind?,
    val current: IdentityKind?,
    val linkPending: Boolean,
)

public sealed interface RedemptionResult {
    @ConsistentCopyVisibility
    public data class Redeemed internal constructor(val action: Action, val eventId: String?, val statusCode: Int) : RedemptionResult

    @ConsistentCopyVisibility
    public data class Declined internal constructor(val message: String?, val statusCode: Int, val eventId: String?) : RedemptionResult

    @ConsistentCopyVisibility
    public data class Failed internal constructor(val failure: Failure) : RedemptionResult
}

public sealed interface Action {
    public val properties: JsonObject

    @ConsistentCopyVisibility
    public data class Navigate internal constructor(val url: String, override val properties: JsonObject) : Action

    /** A `custom:*` type, or a bare `custom`. */
    @ConsistentCopyVisibility
    public data class Custom internal constructor(val type: String, override val properties: JsonObject) : Action

    @ConsistentCopyVisibility
    public data class NoAction internal constructor(override val properties: JsonObject) : Action
}

@ConsistentCopyVisibility
public data class FlushResult internal constructor(val sent: Int, val dropped: Int, val remaining: Int, val failure: Failure?)
