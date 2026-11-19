package com.monetizationos.sdk

import kotlinx.serialization.json.JsonObject

/** `navigate` needs a url; `custom` and any `custom:*` type are the host's; everything else degrades to no action. */
internal fun parseAction(action: JsonObject?): Action {
    val properties = action?.obj("properties") ?: EmptyJsonObject
    val type = action?.string("type") ?: "none"
    val url = action?.string("url")
    return when {
        type == "navigate" && url != null -> Action.Navigate(url, properties)
        type == "custom" || type.startsWith("custom:") -> Action.Custom(type, properties)
        else -> Action.NoAction(properties)
    }
}

/** `pk_<a>_<b>_<secret>` to `<a>_<b>`. The secret may contain underscores, so take exactly two segments. */
internal fun environmentFromKey(publicKey: String): String? {
    val parts = publicKey.split('_')
    if (parts.size < 4 || parts[0] != "pk" || parts[1].isEmpty() || parts[2].isEmpty() || parts.drop(3).joinToString("_").isEmpty()) return null
    return "${parts[1]}_${parts[2]}"
}

/**
 * What the default stores are scoped by, safe in a file or preferences name: the key's environment with any character
 * but `A-Z a-z 0-9 _ -` replaced by `_`, or `default` when it has none.
 */
@InternalMosApi
public fun storageEnvironment(publicKey: String): String =
    environmentFromKey(publicKey)?.replace(UNSAFE_NAME_CHARACTER, "_")?.ifEmpty { null } ?: "default"

private val UNSAFE_NAME_CHARACTER = Regex("[^A-Za-z0-9_-]")
