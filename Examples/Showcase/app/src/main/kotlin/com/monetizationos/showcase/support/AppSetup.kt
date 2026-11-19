package com.monetizationos.showcase.support

import android.content.Context
import android.util.Log
import com.monetizationos.sdk.AppInfo
import com.monetizationos.sdk.MOS
import com.monetizationos.sdk.MOSConfig
import com.monetizationos.sdk.OfflineConfig
import com.monetizationos.sdk.android.create
import com.monetizationos.sdk.logging.LogEvent
import com.monetizationos.sdk.transport.HttpUrlConnectionTransport
import com.monetizationos.showcase.BuildConfig

/**
 * One MOS per app. Fixture mode swaps the transport; everything else is the real SDK.
 */
fun makeMOS(context: Context, settings: ShowcaseSettings, offline: OfflineSwitch, onLog: (LogEvent) -> Unit): Pair<MOS, FixtureTransport?> {
    val fixtures = if (settings.mode == ShowcaseSettings.Mode.Fixture) FixtureTransport.fromAssets(context) else null
    val mos = MOS.create(
        context,
        MOSConfig(
            publicKey = settings.effectivePublicKey,
            surface = "showcase",
            app = AppInfo("MOSShowcase", BuildConfig.VERSION_NAME),
            apiBaseUrl = settings.effectiveApiBaseUrl,
            offline = OfflineConfig(failOpen = true),
            onLog = { event ->
                Log.d("MOS", "${event.level.value} ${event.code}: ${event.message} ${event.context}")
                onLog(event)
            },
            transport = OfflineTransport(fixtures ?: HttpUrlConnectionTransport(), offline),
        ),
    )
    return mos to fixtures
}
