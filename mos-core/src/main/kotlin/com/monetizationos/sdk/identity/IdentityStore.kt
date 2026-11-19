package com.monetizationos.sdk.identity

import java.util.concurrent.ConcurrentHashMap

/**
 * Persists the SDK's identity state as opaque string values under keys the SDK chooses. Store and return them unchanged.
 * iOS uses the Keychain, Android private SharedPreferences. Failures may throw; persistence is best effort and `MOS`
 * reads a new anonymous id back, keeping it in memory if [get] does not return it.
 */
public interface IdentityStore {
    public suspend fun get(key: String): String?

    /** Null removes the value. */
    public suspend fun set(key: String, value: String?)
}

public class InMemoryIdentityStore(values: Map<String, String> = emptyMap()) : IdentityStore {
    private val values = ConcurrentHashMap(values)

    override suspend fun get(key: String): String? = values[key]

    override suspend fun set(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

/** The keys the SDK stores under: the default stores' SharedPreferences keys and iOS Keychain accounts. */
internal object IdentityStoreKeys {
    /** The anonymous identifier. */
    const val ANONYMOUS_IDENTIFIER: String = "anonymousIdentifier"

    /** The identity key of the last call, so an identity change across an app kill still purges the cache. */
    const val LAST_IDENTITY_KEY: String = "lastIdentityKey"

    /** The anonymous id still waiting to ride a decision as `previousAnonymousIdentifier`. */
    const val PENDING_PREVIOUS_ANONYMOUS_IDENTIFIER: String = "pendingPreviousAnonymousIdentifier"
}
