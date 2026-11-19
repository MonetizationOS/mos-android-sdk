@file:OptIn(InternalMosApi::class)

package com.monetizationos.sdk.android

import android.content.Context
import com.monetizationos.sdk.InternalMosApi
import com.monetizationos.sdk.identity.IdentityStore
import com.monetizationos.sdk.storageEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Private SharedPreferences, not EncryptedSharedPreferences: that is deprecated, and the anon id is an
 * unsigned, mintable identifier rather than a secret. The SDK's keys match the iOS Keychain accounts.
 *
 * Scoped by the environment of [publicKey], as `MOS.create` scopes its files, so a staging and a production key never
 * share an anonymous id. A write whose commit fails throws [IOException].
 */
public class AndroidIdentityStore(context: Context, publicKey: String) : IdentityStore {
    private val context = context.applicationContext
    private val name = "$PREFERENCES.${storageEnvironment(publicKey)}"

    // Opened on first use, which is always on Dispatchers.IO.
    private val preferences by lazy { this.context.getSharedPreferences(name, Context.MODE_PRIVATE) }

    override suspend fun get(key: String): String? = withContext(Dispatchers.IO) { preferences.getString(key, null) }

    override suspend fun set(key: String, value: String?) {
        withContext(Dispatchers.IO) {
            val editor = preferences.edit()
            if (value == null) editor.remove(key) else editor.putString(key, value)
            // The value stays readable from memory even then, so only the result tells.
            if (!editor.commit()) throw IOException("SharedPreferences commit failed.")
        }
    }
}

private const val PREFERENCES = "com.monetizationos.sdk"
