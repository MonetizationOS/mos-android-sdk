package com.monetizationos.sdk.identity

import com.monetizationos.sdk.MosJson
import com.monetizationos.sdk.isJsonNumber
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.security.MessageDigest
import java.util.Base64

/** The identity one call runs as. `key` scopes the cache and the metering queue; it never contains a token. */
internal sealed interface ResolvedIdentity {
    val key: String?

    /** `mintFallback` adds `createAnonymousIdentifierFallback`; only `surface-decisions` may send it. */
    fun requestJson(mintFallback: Boolean): JsonObject

    class Jwt(val token: String, claimKey: String = DEFAULT_CLAIM_KEY) : ResolvedIdentity {
        override val key: String = jwtIdentityKey(token, claimKey)

        // Elsewhere a bad token must fail with 401, not be charged to a throwaway anonymous identity.
        override fun requestJson(mintFallback: Boolean): JsonObject = JsonObject(
            buildMap {
                put("userJwt", JsonPrimitive(token))
                if (mintFallback) put("createAnonymousIdentifierFallback", JsonPrimitive(true))
            },
        )

        override fun toString(): String = "Jwt($key)"
    }

    data class Anonymous(val id: String) : ResolvedIdentity {
        override val key: String = anonIdentityKey(id)

        override fun requestJson(mintFallback: Boolean): JsonObject = JsonObject(mapOf("anonymousIdentifier" to JsonPrimitive(id)))
    }

    /** No JWT and no persisted anon id: only `surface-decisions` may ask the server to mint one. */
    data object Mint : ResolvedIdentity {
        override val key: String? = null

        override fun requestJson(mintFallback: Boolean): JsonObject = JsonObject(mapOf("createAnonymousIdentifier" to JsonPrimitive(true)))
    }
}

internal fun anonIdentityKey(id: String): String = "anon:$id"

internal const val DEFAULT_CLAIM_KEY = "sub"

/**
 * `jwt:` plus the SHA-256 of the [claimKey] claim: a string as is, a number as JavaScript prints it (`123`, not `123.0`).
 * The whole token when the claim is missing, empty, not finite or another type, or the payload does not decode.
 */
internal fun jwtIdentityKey(token: String, claimKey: String = DEFAULT_CLAIM_KEY): String {
    val claim = decodeJwtPayload(token, anyPartCount = true)?.get(claimKey) as? JsonPrimitive
    val value = when {
        claim == null -> null
        claim.isString -> claim.content.ifEmpty { null }
        isJsonNumber(claim.content) -> javaScriptNumber(claim.content.toDouble())
        else -> null
    }
    return "jwt:" + sha256Hex(value ?: token)
}

/**
 * [value] as JavaScript's `Number.prototype.toString()` prints it, so a numeric claim hashes as the server's does:
 * the shortest digits that read back, plain from 1e-7 up to 1e21 and exponential outside. Null when not finite.
 */
internal fun javaScriptNumber(value: Double): String? {
    if (!value.isFinite()) return null
    if (value == 0.0) return "0"
    if (value < 0) return javaScriptNumber(-value)?.let { "-$it" }
    // The fewest significant digits that read back as [value]; of two, the closer.
    val exact = BigDecimal(value)
    val shortest = (1..17).firstNotNullOf { precision ->
        listOf(RoundingMode.FLOOR, RoundingMode.CEILING)
            .map { exact.round(MathContext(precision, it)) }
            .filter { it.toDouble() == value }
            .minByOrNull { (it - exact).abs() }
    }.stripTrailingZeros()
    val digits = shortest.unscaledValue().toString()
    val k = digits.length
    val n = shortest.precision() - shortest.scale() // value = 0.digits × 10^n
    return when {
        n in k..21 -> digits + "0".repeat(n - k)
        n in 1..21 -> digits.substring(0, n) + "." + digits.substring(n)
        n in -5..0 -> "0." + "0".repeat(-n) + digits
        else -> {
            val exponent = n - 1
            val mantissa = if (k == 1) digits else digits[0] + "." + digits.substring(1)
            mantissa + "e" + (if (exponent < 0) "-" else "+") + kotlin.math.abs(exponent)
        }
    }
}

/** `anon` or `jwt`: the only form of an identity that may appear in a log line. */
internal fun identityKind(key: String?): String = key?.substringBefore(':') ?: "none"

internal fun sha256Hex(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray())
    val hex = "0123456789abcdef"
    return buildString(digest.size * 2) {
        for (byte in digest) {
            val v = byte.toInt() and 0xff
            append(hex[v shr 4]).append(hex[v and 0x0f])
        }
    }
}

/**
 * Unverified base64url decode of a JWT payload. Null for anything that is not a three-part JWT with an object payload;
 * with [anyPartCount] two or more parts will do, which is how the identity key reads a `sub`.
 */
internal fun decodeJwtPayload(token: String, anyPartCount: Boolean = false): JsonObject? {
    val parts = token.split('.')
    if (if (anyPartCount) parts.size < 2 else parts.size != 3) return null
    if (parts[1].isEmpty()) return null
    return try {
        MosJson.parseToJsonElement(Base64.getUrlDecoder().decode(parts[1]).decodeToString()) as? JsonObject
    } catch (_: IllegalArgumentException) {
        // Bad base64url and bad JSON (SerializationException) both land here.
        null
    }
}
