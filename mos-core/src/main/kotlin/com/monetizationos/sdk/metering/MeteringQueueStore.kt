package com.monetizationos.sdk.metering

import com.monetizationos.sdk.cache.JsonFile
import kotlinx.serialization.Serializable
import java.io.File

/** A deferred `counter-updates` call. JWT identities store no token: `anonymousIdentifier` is set for `anon:` keys only. */
@Serializable
internal data class MeteringEntry(
    val identityKey: String,
    val anonymousIdentifier: String? = null,
    val counterId: String,
    val resourceId: String,
    val update: Double,
    val enqueuedAt: Long,
)

/** Persistence for the metering queue, same contract as the cache store. */
internal interface MeteringQueueStore {
    /** Everything stored, empty when nothing is. Throws as [com.monetizationos.sdk.cache.DecisionCacheStore.load] does. */
    suspend fun load(): List<MeteringEntry>

    suspend fun save(entries: List<MeteringEntry>)
}

internal class InMemoryMeteringQueueStore(initial: List<MeteringEntry> = emptyList()) : MeteringQueueStore {
    @Volatile private var entries: List<MeteringEntry> = initial.toList()

    override suspend fun load(): List<MeteringEntry> = entries

    override suspend fun save(entries: List<MeteringEntry>) {
        this.entries = entries.toList()
    }
}

/** A single JSON file next to the cache, `queue.json`, same shape as the cache file. */
internal class FileMeteringQueueStore(locate: suspend () -> File) : MeteringQueueStore {
    constructor(file: File) : this({ file })

    private val json = JsonFile(locate, MeteringEntry.serializer())

    override suspend fun load(): List<MeteringEntry> = json.read().orEmpty()

    override suspend fun save(entries: List<MeteringEntry>): Unit = json.write(entries)
}
