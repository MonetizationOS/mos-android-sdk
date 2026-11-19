package com.monetizationos.showcase.support

import android.content.Context
import com.monetizationos.showcase.BuildConfig

/**
 * What the Settings screen edits, kept in SharedPreferences. The live fields are prefilled from
 * local.properties through BuildConfig; the first run is live when that sets a public key, fixture mode otherwise.
 */
data class ShowcaseSettings(
    val mode: Mode = Mode.Fixture,
    val publicKey: String,
    val apiBaseUrl: String,
    val namespace: String,
) {
    enum class Mode { Fixture, Live }

    val effectivePublicKey: String get() = if (mode == Mode.Fixture) FixtureTransport.PUBLIC_KEY else publicKey
    val effectiveApiBaseUrl: String get() = if (mode == Mode.Fixture) FixtureTransport.API_BASE_URL else apiBaseUrl

    fun save(context: Context) {
        prefs(context).edit()
            .putString("mode", mode.name)
            .putString("publicKey", publicKey)
            .putString("apiBaseUrl", apiBaseUrl)
            .putString("namespace", namespace)
            .apply()
    }

    companion object {
        val defaults: ShowcaseSettings
            get() = ShowcaseSettings(
                mode = if (BuildConfig.MOS_PUBLIC_KEY.isBlank()) Mode.Fixture else Mode.Live,
                publicKey = BuildConfig.MOS_PUBLIC_KEY,
                apiBaseUrl = BuildConfig.MOS_API_BASE_URL,
                namespace = BuildConfig.MOS_ENDPOINT_NAMESPACE,
            )

        /** [defaults] in fixture mode: UI tests, and the fallback when live settings fail to build. */
        val fixtureDefaults: ShowcaseSettings get() = defaults.copy(mode = Mode.Fixture)

        fun load(context: Context): ShowcaseSettings {
            val prefs = prefs(context)
            val mode = prefs.getString("mode", null)?.let { name -> Mode.entries.firstOrNull { it.name == name } } ?: return defaults
            return ShowcaseSettings(
                mode = mode,
                publicKey = prefs.getString("publicKey", null) ?: defaults.publicKey,
                apiBaseUrl = prefs.getString("apiBaseUrl", null) ?: defaults.apiBaseUrl,
                namespace = prefs.getString("namespace", null) ?: defaults.namespace,
            )
        }

        fun clear(context: Context) {
            prefs(context).edit().clear().apply()
        }

        private fun prefs(context: Context) = context.getSharedPreferences("showcase.settings", Context.MODE_PRIVATE)
    }
}
