package com.monetizationos.sdk.cache

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * One cached decision, keyed by `(surface, identityKey, resourceId, components)`. `decision` is the raw response body.
 * [components] is the sorted selection the request sent, null when it named none and all ran.
 */
@Serializable
internal data class CacheEntry(
    val surface: String,
    val identityKey: String,
    val resourceId: String,
    val decision: JsonObject,
    val storedAt: Long,
    val expiresAt: Long,
    val components: List<String>? = null,
)

/** Persistence for the decision cache. The SDK loads once and saves the whole set on every change. */
internal interface DecisionCacheStore {
    /**
     * Everything stored, empty when nothing is. Throw [StoreSchemaMismatchException] for another schema version
     * (the SDK logs `cache:schema-mismatch`) and anything else when the data is unreadable (`cache:corrupt`).
     */
    suspend fun load(): List<CacheEntry>

    suspend fun save(entries: List<CacheEntry>)
}

internal class InMemoryDecisionCacheStore : DecisionCacheStore {
    @Volatile private var entries: List<CacheEntry> = emptyList()

    override suspend fun load(): List<CacheEntry> = entries

    override suspend fun save(entries: List<CacheEntry>) {
        this.entries = entries.toList()
    }
}

/** A single JSON file, e.g. Android `noBackupFilesDir/mos-sdk/cache.json`, shaped `{ "schemaVersion": 1, "entries": [...] }`. */
internal class FileDecisionCacheStore(locate: suspend () -> File) : DecisionCacheStore {
    constructor(file: File) : this({ file })

    private val json = JsonFile(locate, CacheEntry.serializer())

    override suspend fun load(): List<CacheEntry> = json.read().orEmpty()

    override suspend fun save(entries: List<CacheEntry>): Unit = json.write(entries)
}
