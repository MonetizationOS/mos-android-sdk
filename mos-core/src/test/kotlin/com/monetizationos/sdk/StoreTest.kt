package com.monetizationos.sdk

import com.monetizationos.sdk.cache.CacheEntry
import com.monetizationos.sdk.cache.FileDecisionCacheStore
import com.monetizationos.sdk.cache.StoreSchemaMismatchException
import com.monetizationos.sdk.identity.InMemoryIdentityStore
import com.monetizationos.sdk.logging.LogLevel
import com.monetizationos.sdk.decision.Meterable
import com.monetizationos.sdk.metering.FileMeterLedgerStore
import com.monetizationos.sdk.metering.FileMeteringQueueStore
import com.monetizationos.sdk.metering.MeteringEntry
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoreTest {
    private fun tempDir(): File = Files.createTempDirectory("mos-sdk-test").toFile().apply { deleteOnExit() }

    @Test
    fun `file stores round trip with atomic replace`() = runTest {
        val dir = File(tempDir(), "mos-sdk")
        val cacheFile = File(dir, "cache.json")
        val queueFile = File(dir, "queue.json")
        val cache = FileDecisionCacheStore(cacheFile)
        val queue = FileMeteringQueueStore(queueFile)
        assertTrue(cache.load().isEmpty(), "a missing file is an empty store")
        assertTrue(queue.load().isEmpty())
        assertTrue(FileMeterLedgerStore(File(dir, "meters.json")).load().isEmpty())

        val entry = CacheEntry(SURFACE, "anon:$ANON", "article/1", buildJsonObject { put("status", "success") }, NOW, NOW + 1)
        val queued = MeteringEntry("jwt:abc", null, "default:feat_article.enabled", "article/1", 2.0, NOW)
        cache.save(listOf(entry))
        queue.save(listOf(queued))

        assertEquals(listOf(entry), FileDecisionCacheStore(cacheFile).load())
        assertEquals(listOf(queued), FileMeteringQueueStore(queueFile).load())
        assertEquals(setOf("cache.json", "queue.json"), dir.list()!!.toSet(), "no temp file left behind")
        assertFalse("anonymousIdentifier" in queueFile.readText(), "JWT entries persist no identifier")
        for (file in listOf(cacheFile, queueFile)) {
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            assertEquals(JsonPrimitive(1), root["schemaVersion"], file.name)
            assertEquals(1, root["entries"]!!.jsonArray.size, file.name)
        }
    }

    @Test
    fun `meters json keeps the offline count across instances`() = runTest {
        val dir = File(tempDir(), "mos-sdk")
        val metersFile = File(dir, "meters.json")
        val h = Harness(anonymousId = ANON)
        h.transport.handler = { ok(decisionBody(consumedUnits = 2.0, totalUnits = 5.0)) }
        val online = MOS(h.config(), DeviceInfo("Android", "16", "Pixel 9"), h.clock, storageDirectory = { dir })
        online.decide(article())
        assertEquals(listOf(2.0), FileMeterLedgerStore(metersFile).load().map { it.serverConsumedUnits })

        h.transport.handler = { throw IOException("offline") }
        val offline = MOS(h.config(), DeviceInfo("Android", "16", "Pixel 9"), h.clock, storageDirectory = { dir })
        val result = offline.decide(article("article/2"))
        assertEquals(2.0, (result.decision?.features?.get("article")?.properties?.get("enabled") as? Meterable)?.remainingUnits)
        assertEquals(listOf(1.0), FileMeterLedgerStore(metersFile).load().map { it.localUnits })
    }

    @Test
    fun `schema mismatch for cache and queue including an older bare-array format`() = runTest {
        val entry = CacheEntry(SURFACE, "anon:$ANON", "article/1", buildJsonObject { put("status", "success") }, NOW, NOW + 1)
        val queued = MeteringEntry("anon:$ANON", ANON, "default:feat_article.enabled", "article/1", 1.0, NOW)
        val bareCache = Json.encodeToString(ListSerializer(CacheEntry.serializer()), listOf(entry))
        val bareQueue = Json.encodeToString(ListSerializer(MeteringEntry.serializer()), listOf(queued))
        val cases = listOf(
            Triple(bareCache, bareQueue, "null"),
            Triple("""{"schemaVersion":2,"entries":[]}""", """{"schemaVersion":2,"entries":[]}""", "2"),
            Triple("""{"entries":[]}""", """{"schemaVersion":"1","entries":[]}""", null),
        )
        for ((cacheText, queueText, found) in cases) {
            val dir = tempDir()
            val cacheFile = File(dir, "cache.json").apply { writeText(cacheText) }
            val queueFile = File(dir, "queue.json").apply { writeText(queueText) }
            assertFailsWith<StoreSchemaMismatchException> { FileDecisionCacheStore(cacheFile).load() }
            assertFailsWith<StoreSchemaMismatchException> { FileMeteringQueueStore(queueFile).load() }

            val h = Harness(anonymousId = ANON)
            val mos = h.mos(cache = FileDecisionCacheStore(cacheFile), queue = FileMeteringQueueStore(queueFile))
            assertEquals(DecisionSource.Unavailable, mos.decide(article(), policy = CachePolicy.CacheOnly).source, cacheText)
            assertEquals(FlushResult(0, 0, 0, null), mos.flush())
            val mismatches = h.logs.events.filter { it.code == "cache:schema-mismatch" }
            assertEquals(listOf("cache", "queue"), mismatches.map { (it.context["store"] as JsonPrimitive).content })
            assertTrue(mismatches.all { it.level == LogLevel.Warn })
            if (found != null) assertTrue(mismatches.all { (it.context["found"] as JsonPrimitive).content == found }, cacheText)
            assertFalse("cache:corrupt" in h.logs.codes(), "a schema mismatch is not corruption")

            // Overwritten in the current shape on the next save.
            mos.decide(article("article/1"))
            h.transport.handler = { throw IOException("offline") }
            mos.decide(article("article/2"))
            assertEquals(1, FileDecisionCacheStore(cacheFile).load().size)
            assertEquals(1, FileMeteringQueueStore(queueFile).load().size)
        }
    }

    @Test
    fun `store corruption`() = runTest {
        val dir = tempDir()
        val cacheFile = File(dir, "cache.json").apply { writeText("{not json") }
        val queueFile = File(dir, "queue.json").apply { writeText("""{"schemaVersion":1,"entries":[{"identityKey":1}]}""") }
        val h = Harness(anonymousId = ANON)
        val mos = h.mos(cache = FileDecisionCacheStore(cacheFile), queue = FileMeteringQueueStore(queueFile))

        assertEquals(DecisionSource.Unavailable, mos.decide(article(), policy = CachePolicy.CacheOnly).source)
        assertEquals(FlushResult(0, 0, 0, null), mos.flush())
        val corrupt = h.logs.events.filter { it.code == "cache:corrupt" }.map { it.context["store"].toString() }
        assertEquals(listOf("\"cache\"", "\"queue\""), corrupt)

        // The SDK starts empty and the next write replaces the corrupt file.
        mos.decide(article("article/1"))
        h.transport.handler = { throw IOException("offline") }
        mos.decide(article("article/2"))
        assertEquals(1, FileMeteringQueueStore(queueFile).load().size)
        val reloaded = h.mos(cache = FileDecisionCacheStore(cacheFile), queue = FileMeteringQueueStore(queueFile))
        assertIs<DecisionSource.Cache>(reloaded.decide(article("article/1"), policy = CachePolicy.CacheOnly).source)
        assertEquals(2, h.logs.codes().count { it == "cache:corrupt" }, "each store logs corruption once")
    }

    @Test
    fun `default stores are scoped by the key's environment, made safe for a file name`() {
        assertEquals("test_env1", storageEnvironment("pk_test_env1_secret"))
        assertEquals("live_a-B9", storageEnvironment("pk_live_a-B9_s3cret_with_underscores"))
        assertEquals("te_st_env_1", storageEnvironment("pk_te.st_env/1_secret"))
        assertEquals("default", storageEnvironment("pk_nokey"))
        assertEquals("default", storageEnvironment("pk__env1_secret"))
    }

    @Test
    fun `inMemory keeps the cache and the anonymous id in memory, unless an identity store is set`() = runTest {
        val transport = FakeTransport()
        val config = MOSConfig(PK, SURFACE, AppInfo("TestApp", "1.0"), "https://api.test", transport = transport)
        val mos = MOS.inMemory(config)
        mos.decide(article())
        assertEquals(ANON, mos.anonymousIdentifier())
        assertIs<DecisionSource.Cache>(mos.decide(article(), policy = CachePolicy.CacheOnly).source)

        val store = identityStore()
        MOS.inMemory(config.copy(identity = IdentityConfig(store = store))).decide(article())
        assertEquals(ANON, store.anonymousIdentifier())
    }

    @Test
    fun `a failing store write never breaks a decision`() = runTest {
        val dir = tempDir()
        File(dir, "cache.json").mkdirs() // a directory where the file should be: every write fails
        val h = Harness(anonymousId = ANON)
        val mos = h.mos(cache = FileDecisionCacheStore(File(dir, "cache.json")), queue = null)
        assertEquals(DecisionSource.Network, mos.decide(article()).source)
        assertIs<DecisionSource.Cache>(mos.decide(article(), policy = CachePolicy.CacheOnly).source)
    }

    @Test
    fun `the storage directory is resolved once on first use, not at construction`() = runTest {
        val dir = File(tempDir(), "mos-sdk")
        val resolved = AtomicInteger()
        val h = Harness(anonymousId = ANON)
        val mos = MOS(h.config(), DeviceInfo("Android", "16", "Pixel 9"), h.clock, storageDirectory = { resolved.incrementAndGet(); dir })
        assertEquals(0, resolved.get(), "construction touches no disk")

        assertEquals(DecisionSource.Network, mos.decide(article()).source)
        mos.flush()

        assertEquals(1, resolved.get(), "cache and queue share one resolution")
        assertTrue(File(dir, "cache.json").exists())
    }

    @Test
    fun `a write does not use a fixed temp path another instance could share`() = runTest {
        val dir = tempDir()
        val file = File(dir, "cache.json")
        File(dir, "cache.json.tmp").mkdir()
        val cache = FileDecisionCacheStore(file)
        val entry = CacheEntry(SURFACE, "anon:$ANON", "article/1", buildJsonObject { put("status", "success") }, NOW, NOW + 1)

        cache.save(listOf(entry))

        assertEquals(listOf(entry), FileDecisionCacheStore(file).load())
        assertEquals(setOf("cache.json", "cache.json.tmp"), dir.list()!!.toSet(), "no temp file left behind")
    }
}
