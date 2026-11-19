package com.monetizationos.sdk.metering

import com.monetizationos.sdk.cache.loadOrEmpty
import com.monetizationos.sdk.logging.Logger
import kotlinx.coroutines.CancellationException

internal const val MAX_QUEUE_ENTRIES = 500
internal const val MAX_QUEUE_AGE_MS = 7L * 24 * 60 * 60 * 1000

/** In-memory view of the queue store. Not thread-safe: MOS calls it under its state mutex. */
internal class MeteringQueue(private val store: MeteringQueueStore, private val logger: Logger) {
    private var loaded: MutableList<MeteringEntry>? = null

    private suspend fun entries(): MutableList<MeteringEntry> =
        loaded ?: loadOrEmpty("queue", logger) { store.load() }.also { loaded = it }

    /** Adds unless an entry with the same `(identityKey, counterId, resourceId)` is already waiting. Returns whether it added. */
    suspend fun enqueue(entry: MeteringEntry): Boolean {
        val entries = entries()
        val duplicate = entries.any {
            it.identityKey == entry.identityKey && it.counterId == entry.counterId && it.resourceId == entry.resourceId
        }
        if (duplicate) {
            logger.debug("metering:deduped", "Counter update already queued.", "counterId" to entry.counterId, "resourceId" to entry.resourceId)
            return false
        }
        entries += entry
        logger.debug(
            "metering:queued", "Counter update queued for a cached decision.",
            "counterId" to entry.counterId, "resourceId" to entry.resourceId, "update" to entry.update,
        )
        if (entries.size > MAX_QUEUE_ENTRIES) {
            entries.remove(entries.minBy { it.enqueuedAt })
            logger.warn("metering:dropped", "Metering queue full; oldest update dropped.", "reason" to "limit", "count" to 1)
        }
        persist()
        return true
    }

    /** Drops entries older than seven days and entries for a JWT identity that is no longer current. */
    suspend fun prune(now: Long, currentKey: String?): Int {
        val entries = entries()
        val expired = entries.filter { now - it.enqueuedAt > MAX_QUEUE_AGE_MS }
        val orphaned = entries.filter { it !in expired && it.identityKey.startsWith("jwt:") && it.identityKey != currentKey }
        if (expired.isEmpty() && orphaned.isEmpty()) return 0
        entries.removeAll(expired + orphaned)
        if (expired.isNotEmpty()) {
            logger.warn("metering:dropped", "Counter updates older than seven days dropped.", "reason" to "expired", "count" to expired.size)
        }
        if (orphaned.isNotEmpty()) {
            logger.warn("metering:dropped", "Counter updates for a signed-out JWT identity dropped.", "reason" to "identity-gone", "count" to orphaned.size)
        }
        persist()
        return expired.size + orphaned.size
    }

    suspend fun oldestFor(identityKey: String): MeteringEntry? =
        entries().filter { it.identityKey == identityKey }.minByOrNull { it.enqueuedAt }

    suspend fun remove(entry: MeteringEntry) {
        if (entries().remove(entry)) persist()
    }

    suspend fun entriesFor(identityKey: String): List<MeteringEntry> = entries().filter { it.identityKey == identityKey }

    suspend fun size(): Int = entries().size

    suspend fun countFor(identityKey: String?): Int = entries().count { it.identityKey == identityKey }

    suspend fun clear() {
        entries().clear()
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
