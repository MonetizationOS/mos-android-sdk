package com.monetizationos.showcase.support

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.monetizationos.sdk.DecisionResult
import com.monetizationos.sdk.DecisionSource
import com.monetizationos.sdk.MOS
import com.monetizationos.sdk.logging.LogEvent
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI

/**
 * App-wide plumbing every screen shares: the MOS instance built from Settings, the Offline switch
 * and the live rows on Home. Screens make their MOS calls themselves, on `showcase.mos`. One per
 * process, so it outlives Activity recreation.
 */
class Showcase private constructor(private val context: Context, resetAtLaunch: Boolean) {
    private val offlineSwitch = OfflineSwitch()
    private val main = MainScope()

    // UI tests start from a clean slate: fixture mode, new anonymous reader.
    private val initial = run {
        if (resetAtLaunch) {
            ShowcaseSettings.clear(context)
            ShowcaseSettings.fixtureDefaults.save(context)
        }
        val loaded = ShowcaseSettings.load(context)
        runCatching { loaded to build(loaded) }.getOrElse { ShowcaseSettings.fixtureDefaults to build(ShowcaseSettings.fixtureDefaults) }
    }

    var settings by mutableStateOf(initial.first)
        private set
    var mos: MOS by mutableStateOf(initial.second.first)
        private set

    /** Set in fixture mode. */
    var fixtures: FixtureTransport? by mutableStateOf(initial.second.second)
        private set

    /** Bumped whenever `mos` is rebuilt. */
    var generation by mutableIntStateOf(0)
        private set
    private var offlineState by mutableStateOf(false)

    /** The Offline screen's switch: while on, every request fails like airplane mode. */
    var isOffline: Boolean
        get() = offlineState
        set(on) {
            offlineSwitch.isOn = on
            offlineState = on
        }
    var lastSource: DecisionSource? by mutableStateOf(null)
        private set
    var identity by mutableStateOf("Anonymous, no id yet")
        private set

    /** Counter updates waiting for `flush()`, followed from the SDK's `metering:*` log events. */
    var queuedUpdates by mutableIntStateOf(0)
        private set

    init {
        main.launch {
            if (resetAtLaunch) mos.reset()
            refreshIdentity()
        }
    }

    val modeLabel: String
        get() = when (settings.mode) {
            ShowcaseSettings.Mode.Fixture -> "Fixture"
            ShowcaseSettings.Mode.Live -> "Live · ${runCatching { URI(settings.apiBaseUrl).host }.getOrNull() ?: settings.apiBaseUrl}"
        }

    /**
     * Rebuilds MOS for new settings. Another mode, key or host is another environment, so the old
     * instance forgets its identity, cache and queue first. Returns an error message for a bad key.
     */
    suspend fun apply(new: ShowcaseSettings): String? {
        val rebuild = new.mode != settings.mode || new.effectivePublicKey != settings.effectivePublicKey ||
            new.effectiveApiBaseUrl != settings.effectiveApiBaseUrl
        if (!rebuild) {
            settings = new
            new.save(context)
            return null
        }
        val (newMos, newFixtures) = try {
            build(new)
        } catch (_: IllegalArgumentException) {
            return "The public key must start with pk_."
        }
        mos.reset()
        settings = new
        new.save(context)
        mos = newMos
        fixtures = newFixtures
        generation++
        lastSource = null
        queuedUpdates = 0
        refreshIdentity()
        return null
    }

    /** Screens report each decision here for the Home rows. */
    fun didDecide(result: DecisionResult) {
        lastSource = result.source
        result.decision?.identity?.let { identity ->
            val id = identity.identifier ?: "?"
            this.identity = if (identity.isAuthenticated) "Signed in: $id" else "Anonymous: $id"
        }
    }

    /** A new anonymous reader: the SDK forgets the anonymous id, the identity state, the cache and the queue. */
    suspend fun resetIdentity() {
        mos.identify(null)
        mos.reset()
        lastSource = null
        queuedUpdates = 0
        refreshIdentity()
    }

    suspend fun refreshIdentity() {
        identity = mos.anonymousIdentifier()?.let { "Anonymous: $it" } ?: "Anonymous, no id yet"
    }

    private fun build(settings: ShowcaseSettings) = makeMOS(context, settings, offlineSwitch) { event ->
        main.launch { handle(event) }
    }

    private fun handle(event: LogEvent) {
        when (event.code) {
            "metering:queued" -> queuedUpdates++
            "metering:flushed", "metering:flush-failed" ->
                event.context["remaining"]?.jsonPrimitive?.intOrNull?.let { queuedUpdates = it }
        }
    }

    companion object {
        @Volatile private var instance: Showcase? = null

        /** The process's Showcase, made on first use. [resetAtLaunch] only counts then. */
        fun get(context: Context, resetAtLaunch: Boolean): Showcase =
            instance ?: synchronized(this) { instance ?: Showcase(context.applicationContext, resetAtLaunch).also { instance = it } }
    }
}

val LocalShowcase = staticCompositionLocalOf<Showcase> { error("No Showcase provided") }

/** Where a decision came from, as Home and the Last call panel show it. */
val DecisionSource.label: String
    get() = when (this) {
        DecisionSource.Network -> "network"
        is DecisionSource.Cache -> "cache (${ageMs / 1000} s old)"
        is DecisionSource.Fallback -> "fallback (from $fromResourceId)"
        DecisionSource.Unavailable -> "unavailable"
    }
