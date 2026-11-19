package com.monetizationos.sdk.metering

import com.monetizationos.sdk.cache.JsonFile
import com.monetizationos.sdk.cache.loadOrEmpty
import com.monetizationos.sdk.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

internal const val MAX_LEDGER_RESOURCES = 200

/**
 * One counter's count for one identity: the server's from the latest network decision, plus the units this
 * device counted since, which the server has not counted yet.
 */
@Serializable
internal data class MeterLedgerEntry(
    val identityKey: String,
    val counterId: String,
    val serverConsumedUnits: Double,
    val totalUnits: Double,
    val uniqueResources: Boolean,
    val takenAt: Long,
    val localUnits: Double,
    /** For a unique-resource meter, the resources counted this period, so a re-read counts nothing. */
    val resources: List<CountedResource>,
) {
    val consumedUnits: Double get() = serverConsumedUnits + localUnits

    fun counted(resourceId: String): Boolean = uniqueResources && resources.any { it.id == resourceId }

    fun withResource(resourceId: String, at: Long): MeterLedgerEntry =
        if (!uniqueResources || counted(resourceId)) this else copy(resources = (resources + CountedResource(resourceId, at)).takeLast(MAX_LEDGER_RESOURCES))
}

/** [at] is when the SDK learned it was counted. */
@Serializable
internal data class CountedResource(val id: String, val at: Long)

/** In-memory view of the ledger store. Not thread-safe: MOS calls it under its state mutex. */
internal class MeterLedger(private val store: MeterLedgerStore, private val logger: Logger) {
    private var loaded: MutableList<MeterLedgerEntry>? = null

    private suspend fun entries(): MutableList<MeterLedgerEntry> =
        loaded ?: loadOrEmpty("meters", logger) { store.load() }.also { loaded = it }

    suspend fun entry(identityKey: String, counterId: String): MeterLedgerEntry? =
        entries().firstOrNull { it.identityKey == identityKey && it.counterId == counterId }

    /** Inserts or replaces; [save] persists. */
    suspend fun put(entry: MeterLedgerEntry) {
        val entries = entries()
        entries.removeAll { it.identityKey == entry.identityKey && it.counterId == entry.counterId }
        entries += entry
    }

    /** Drops entries taken before [takenBefore]; [save] persists. Returns whether any went. */
    suspend fun prune(takenBefore: Long): Boolean = entries().removeAll { it.takenAt < takenBefore }

    suspend fun clear() {
        entries().clear()
        save()
    }

    suspend fun save() {
        try {
            store.save(entries().toList())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Best effort: the in-memory view stays correct for this process.
        }
    }
}

/** A meter's `periodStart`, an HTTP date such as `Sun, 22 Nov 2026 00:00:00 GMT`, in ms. */
internal fun periodStartMs(value: String?): Long? = value?.let {
    try {
        ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }
}

/** Persistence for the meter ledger, same contract as the cache store. */
internal interface MeterLedgerStore {
    suspend fun load(): List<MeterLedgerEntry>

    suspend fun save(entries: List<MeterLedgerEntry>)
}

internal class InMemoryMeterLedgerStore(initial: List<MeterLedgerEntry> = emptyList()) : MeterLedgerStore {
    @Volatile private var entries: List<MeterLedgerEntry> = initial.toList()

    override suspend fun load(): List<MeterLedgerEntry> = entries

    override suspend fun save(entries: List<MeterLedgerEntry>) {
        this.entries = entries.toList()
    }
}

/** `meters.json` next to the cache, same shape as the cache file. */
internal class FileMeterLedgerStore(locate: suspend () -> File) : MeterLedgerStore {
    constructor(file: File) : this({ file })

    private val json = JsonFile(locate, MeterLedgerEntry.serializer())

    override suspend fun load(): List<MeterLedgerEntry> = json.read().orEmpty()

    override suspend fun save(entries: List<MeterLedgerEntry>): Unit = json.write(entries)
}
