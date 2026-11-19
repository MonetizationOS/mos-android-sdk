@file:OptIn(InternalMosApi::class)

package com.monetizationos.sdk.android

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.monetizationos.sdk.AppInfo
import com.monetizationos.sdk.DeviceInfo
import com.monetizationos.sdk.InternalMosApi
import com.monetizationos.sdk.MOS
import com.monetizationos.sdk.MOSConfig
import com.monetizationos.sdk.storageEnvironment
import java.io.File

/**
 * A [MOS] with Android defaults: the anon id in private SharedPreferences, cache, queue and offline meter counts under
 * `noBackupFilesDir/mos-sdk/<environment>/` and a User-Agent from `android.os.Build`. The environment is the public
 * key's, so a staging and a production key never share an anonymous id, cache or queue. An identity store set in
 * [config] wins.
 *
 * Create one per process, e.g. in `Application.onCreate`, not one per Activity: instances with the same environment
 * use the same `cache.json`, `queue.json` and `meters.json`, so two overwrite each other's entries.
 *
 * No disk I/O on the calling thread, so it is safe in `Application.onCreate` under StrictMode.
 */
public fun MOS.Companion.create(context: Context, config: MOSConfig): MOS {
    val app = context.applicationContext
    val identity = config.identity.copy(store = config.identity.store ?: AndroidIdentityStore(app, config.publicKey))
    val device = DeviceInfo("Android", Build.VERSION.RELEASE.orEmpty(), Build.MODEL.orEmpty())
    val directory = "mos-sdk/" + storageEnvironment(config.publicKey)
    // Resolved by mos-core off this thread, before its first cache or queue access. Kept out of auto-backup, like the iOS files.
    return MOS(config.copy(identity = identity), device) { File(app.noBackupFilesDir, directory) }
}

/** The app's label and `versionName`, for [MOSConfig.app]. */
public fun AppInfo.Companion.from(context: Context): AppInfo {
    val packageManager = context.packageManager
    val packageInfo = if (Build.VERSION.SDK_INT >= 33) {
        packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(context.packageName, 0)
    }
    val label = context.applicationInfo.loadLabel(packageManager).toString().ifBlank { context.packageName }
    return AppInfo(label, packageInfo.versionName ?: "0")
}
