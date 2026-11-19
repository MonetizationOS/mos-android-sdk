package com.monetizationos.sdk.cache

import com.monetizationos.sdk.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

/** In-memory view of the cache store. Not thread-safe: MOS calls it under its state mutex. */
internal class DecisionCache(
    private val store: DecisionCacheStore,
    private val maxEntries: Int,
    private val logger: Logger,
) {
    private var loaded: MutableList<CacheEntry>? = null

    private suspend fun entries(): MutableList<CacheEntry> =
        loaded ?: loadOrEmpty("cache", logger) { store.load() }.also { loaded = it }

    /** The exact entry, if present and unexpired. */
    suspend fun exact(surface: String, identityKey: String, resourceId: String, components: List<String>?, now: Long): CacheEntry? {
        evictExpired(now)
        return entries().firstOrNull {
            it.surface == surface && it.identityKey == identityKey && it.resourceId == resourceId && it.components == components
        }
    }

    /** The newest unexpired entry for this surface, identity and component selection, whatever its resource. */
    suspend fun latest(surface: String, identityKey: String, components: List<String>?, now: Long): CacheEntry? {
        evictExpired(now)
        return entries().filter { it.surface == surface && it.identityKey == identityKey && it.components == components }
            .maxByOrNull { it.storedAt }
    }

    suspend fun put(entry: CacheEntry) {
        val entries = entries()
        entries.removeAll {
            it.surface == entry.surface && it.identityKey == entry.identityKey && it.resourceId == entry.resourceId &&
                it.components == entry.components
        }
        entries += entry
        logger.debug("cache:put", "Decision cached.", "resourceId" to entry.resourceId, "expiresAt" to entry.expiresAt)
        while (entries.size > maxEntries.coerceAtLeast(0)) {
            val oldest = entries.minBy { it.storedAt }
            entries.remove(oldest)
            logger.debug("cache:evicted", "Oldest cached decision evicted.", "reason" to "max-entries", "resourceId" to oldest.resourceId)
        }
        persist()
    }

    suspend fun clear(reason: String) {
        entries().clear()
        persist()
        logger.info("cache:cleared", "Decision cache cleared.", "reason" to reason)
    }

    private suspend fun evictExpired(now: Long) {
        val entries = entries()
        val expired = entries.filter { now >= it.expiresAt }
        if (expired.isEmpty()) return
        entries.removeAll(expired)
        expired.forEach {
            logger.debug("cache:evicted", "Expired cached decision evicted.", "reason" to "expired", "resourceId" to it.resourceId)
        }
        persist()
    }

    private suspend fun persist() {
        try {
            store.save(entries().toList())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Best effort: the in-memory view stays correct for this process.
        }
    }
}

/** The cache is a file on disk; `identity.jwtClaims` often holds an email or a name and nothing reads it from a cached decision. */
internal fun withoutClaims(decision: JsonObject): JsonObject {
    val identity = decision["identity"] as? JsonObject ?: return decision
    if ("jwtClaims" !in identity) return decision
    return JsonObject(decision + ("identity" to JsonObject(identity - "jwtClaims")))
}
