package com.monetizationos.sdk.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.monetizationos.sdk.AppInfo
import com.monetizationos.sdk.MOS
import com.monetizationos.sdk.MOSConfig
import com.monetizationos.sdk.Resource
import com.monetizationos.sdk.logging.LogEvent
import com.monetizationos.sdk.transport.HttpRequest
import com.monetizationos.sdk.transport.HttpResponse
import com.monetizationos.sdk.transport.HttpTransport
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class MOSCreateTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Mints `anon-<environment>`, so each environment's id shows where it was stored. */
    private class MintingTransport : HttpTransport {
        val requests = CopyOnWriteArrayList<HttpRequest>()

        override suspend fun send(request: HttpRequest): HttpResponse {
            requests += request
            val environment = request.headers.getValue("Authorization").removePrefix("Bearer pk_").substringBeforeLast('_')
            return HttpResponse(200, """{"status":"success","identity":{"isAuthenticated":false,"identifier":"anon-$environment"}}""")
        }
    }

    private fun config(publicKey: String, transport: HttpTransport, onLog: (LogEvent) -> Unit = {}) =
        MOSConfig(publicKey = publicKey, surface = "app", app = AppInfo("Test", "1.0"), transport = transport, onLog = onLog)

    private fun preferences(environment: String) = context.getSharedPreferences("com.monetizationos.sdk.$environment", Context.MODE_PRIVATE)

    @Test
    fun storesAreScopedByTheKeysEnvironmentAndOpenedOnFirstUse() = runBlocking {
        val transport = MintingTransport()
        val staging = MOS.create(context, config("pk_test_env1_secret", transport))
        val production = MOS.create(context, config("pk_live_env1_secret", transport))
        val unparsable = MOS.create(context, config("pk_nokey", transport))
        val unsafe = MOS.create(context, config("pk_te.st_env/1_secret", transport))
        val root = File(context.noBackupFilesDir, "mos-sdk")
        assertFalse(root.exists(), "create touches no disk")

        staging.decide(Resource("article/1"))
        production.decide(Resource("article/1"))
        unparsable.decide(Resource("article/1"))
        unsafe.decide(Resource("article/1"))

        assertEquals("anon-test_env1", staging.anonymousIdentifier())
        assertEquals("anon-live_env1", production.anonymousIdentifier())
        assertEquals("anon-test_env1", preferences("test_env1").getString("anonymousIdentifier", null))
        assertEquals("anon-live_env1", preferences("live_env1").getString("anonymousIdentifier", null))
        assertEquals("anon-nokey", preferences("default").getString("anonymousIdentifier", null))
        assertEquals("anon-te.st_env/1", preferences("te_st_env_1").getString("anonymousIdentifier", null))
        for (environment in listOf("test_env1", "live_env1", "default", "te_st_env_1")) {
            assertTrue(File(root, "$environment/cache.json").exists(), environment)
        }
        assertFalse(File(root, "cache.json").exists())
    }

    @Test
    fun identityStateStoredUnderTheSdksKeysIsReadBack() = runBlocking {
        preferences("test_env3").edit()
            .putString("anonymousIdentifier", "anon-stored")
            .putString("lastIdentityKey", "anon:anon-stored")
            .commit()
        val transport = MintingTransport()
        val mos = MOS.create(context, config("pk_test_env3_secret", transport))

        assertEquals("anon-stored", mos.anonymousIdentifier())
        assertTrue(mos.identify("header.eyJzdWIiOiJ1c2VyXzQyIn0.sig").changed, "the stored last identity key was read")
        assertEquals("anon-stored", preferences("test_env3").getString("pendingPreviousAnonymousIdentifier", null))

        val store = AndroidIdentityStore(context, "pk_test_env3_secret")
        store.set("anonymousIdentifier", null)
        assertFalse(preferences("test_env3").contains("anonymousIdentifier"), "null removes the value")
    }

    @Test
    fun aFailedCommitIsAFailedWriteAndTheMintedIdIsKeptInMemory() = runBlocking {
        val sharedPreferences = File(context.dataDir, "shared_prefs").apply { mkdirs() }
        assertTrue(sharedPreferences.setWritable(false))
        try {
            assertFailsWith<IOException> { AndroidIdentityStore(context, "pk_test_env1_secret").set("anonymousIdentifier", "anon-1") }

            val logs = CopyOnWriteArrayList<LogEvent>()
            val transport = MintingTransport()
            val mos = MOS.create(context, config("pk_test_env2_secret", transport) { logs += it })
            mos.decide(Resource("article/1"))
            mos.decide(Resource("article/1"))

            assertEquals(listOf("identity:store-failed"), logs.map { it.code }.filter { it.startsWith("identity:") })
            assertTrue(transport.requests.last().body!!.contains(""""anonymousIdentifier":"anon-test_env2""""))
        } finally {
            sharedPreferences.setWritable(true)
        }
    }
}
