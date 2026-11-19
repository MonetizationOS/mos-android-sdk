package com.monetizationos.sdk.cache

import com.monetizationos.sdk.MosJson
import com.monetizationos.sdk.parseStrictJson
import com.monetizationos.sdk.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** The `schemaVersion` the cache and queue files are written with. */
internal const val STORE_SCHEMA_VERSION: Int = 1

/** Valid store data in another schema version (or an older format's bare array). [found] is JSON null when there is none. */
internal class StoreSchemaMismatchException(val found: JsonElement) :
    Exception("Store schemaVersion is $found, expected $STORE_SCHEMA_VERSION.")

/**
 * `{ "schemaVersion": 1, "entries": [...] }` written by atomic replace, so a crash mid-write leaves the previous version intact.
 * One writer per file: two instances over one directory would overwrite each other's entries.
 */
internal class JsonFile<T>(private val locate: suspend () -> File, elementSerializer: KSerializer<T>) {
    private val serializer = ListSerializer(elementSerializer)

    /** Null when the file does not exist. Throws when it cannot be decoded, [StoreSchemaMismatchException] for another version. */
    suspend fun read(): List<T>? = withContext(Dispatchers.IO) {
        val file = locate()
        if (!file.exists()) return@withContext null
        val root = parseStrictJson(file.readText()) ?: throw SerializationException("Store file is not JSON.")
        val document = root as? JsonObject
        val version = document?.get("schemaVersion")
        val versionNumber = (version as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        if (document == null || versionNumber != STORE_SCHEMA_VERSION) throw StoreSchemaMismatchException(version ?: JsonNull)
        val entries = document["entries"] ?: throw SerializationException("Store file has no entries.")
        MosJson.decodeFromJsonElement(serializer, entries)
    }

    suspend fun write(value: List<T>): Unit = withContext(Dispatchers.IO) {
        val file = locate()
        val dir = file.absoluteFile.parentFile
        dir.mkdirs()
        val temp = File.createTempFile("${file.name}.", ".tmp", dir)
        val document = buildJsonObject {
            put("schemaVersion", STORE_SCHEMA_VERSION)
            put("entries", MosJson.encodeToJsonElement(serializer, value))
        }
        try {
            // Synced before the rename, or a power loss can leave the renamed file empty.
            FileOutputStream(temp).use { out ->
                out.write(document.toString().encodeToByteArray())
                out.fd.sync()
            }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            temp.delete()
        }
    }
}

/** A directory resolved on the first store read or write, on the IO dispatcher, so creating a `MOS` touches no disk. */
internal class StorageDirectory(private val resolve: suspend () -> File) {
    private val lock = Mutex()
    @Volatile private var resolved: File? = null

    suspend fun file(name: String): File = File(resolved ?: lock.withLock { resolved ?: resolve().also { resolved = it } }, name)
}

/** A store's contents, or empty with `cache:schema-mismatch` or `cache:corrupt` logged. */
internal suspend fun <T> loadOrEmpty(store: String, logger: Logger, load: suspend () -> List<T>): MutableList<T> = try {
    load().toMutableList()
} catch (e: CancellationException) {
    throw e
} catch (e: StoreSchemaMismatchException) {
    logger.warn("cache:schema-mismatch", "Stored $store has another schema version; starting empty.", "store" to store, "found" to e.found)
    mutableListOf()
} catch (_: Exception) {
    logger.warn("cache:corrupt", "Stored $store was unreadable; starting empty.", "store" to store)
    mutableListOf()
}
