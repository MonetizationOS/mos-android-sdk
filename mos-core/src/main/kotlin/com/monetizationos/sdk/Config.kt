package com.monetizationos.sdk

import com.monetizationos.sdk.identity.IdentityStore
import com.monetizationos.sdk.logging.LogEvent
import com.monetizationos.sdk.transport.HttpTransport
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

public data class MOSConfig(
    /** A public `pk_` key. Anything else is refused at construction. */
    val publicKey: String,
    val surface: String,
    val app: AppInfo,
    val apiBaseUrl: String = "https://api.monetizationos.com",
    val identity: IdentityConfig = IdentityConfig(),
    val offline: OfflineConfig = OfflineConfig(),
    /**
     * Bounds each API call end to end. A decision or counter-update timeout starts a ten-minute window in which those
     * calls use min(this, 2s); any response ends it. Redemptions and endpoint calls always get the full timeout.
     */
    val requestTimeout: Duration = 5.seconds,
    val onLog: (LogEvent) -> Unit = {},
    val transport: HttpTransport? = null,
)

/** Name and version for the User-Agent. A blank one is sent as `app`. */
public data class AppInfo(val name: String, val version: String) {
    public companion object
}

/**
 * `jwtProvider` is read fresh at every call. Return null only when the user is signed out: null, or a throw,
 * switches to the anonymous identity, which purges the decision cache and drops that user's queued counter
 * updates. If a refresh fails, catch it and return the last token, even an expired one: the server answers
 * that decision anonymously and the SDK keeps the user.
 */
public data class IdentityConfig(
    val jwtProvider: (suspend () -> String?)? = null,
    /**
     * Where the anonymous identifier and the rest of the identity state are kept. Null: in memory from `MOS.inMemory`,
     * private SharedPreferences from `MOS.create`.
     */
    val store: IdentityStore? = null,
    /**
     * The JWT claim MOS identifies users by. Set it to the claim key of your JWT integration in MOS. Tokens with the
     * same value share a cache and a metering queue; one without the claim is keyed by the whole token.
     */
    val claimKey: String = "sub",
)

public data class OfflineConfig(
    val ttl: Duration = 24.hours,
    val failOpen: Boolean = true,
    val fallbackToLatest: Boolean = true,
    val maxEntries: Int = 50,
    /**
     * Turn a meter's access off offline once the reads counted on this device since the last network decision use
     * it up. The cached decision has no paywall for that, so show your own.
     */
    val enforceMeters: Boolean = false,
)

/** OS and device for the User-Agent. The Android module passes `android.os.Build` values. */
@InternalMosApi
public data class DeviceInfo(val platform: String, val osVersion: String, val device: String) {
    public companion object {
        public fun jvm(): DeviceInfo = DeviceInfo(
            platform = System.getProperty("os.name") ?: "JVM",
            osVersion = System.getProperty("os.version") ?: "",
            device = "JVM",
        )
    }
}
