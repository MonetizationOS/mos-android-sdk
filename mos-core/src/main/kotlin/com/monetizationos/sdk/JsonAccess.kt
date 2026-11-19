package com.monetizationos.sdk

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

internal val MosJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

// Lenient typed reads: a wrong type reads as absent instead of failing the whole decode.
internal fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.boolean(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

internal fun JsonObject.number(key: String): Double? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal val EmptyJsonObject: JsonObject = JsonObject(emptyMap())

/** A copy with [value] at [path], creating objects along the way. */
internal fun JsonObject.setting(value: JsonElement, vararg path: String): JsonObject = setting(value, path.toList()) as JsonObject

private fun JsonElement?.setting(value: JsonElement, path: List<String>): JsonElement {
    if (path.isEmpty()) return value
    val obj = (this as? JsonObject)?.toMutableMap() ?: mutableMapOf()
    obj[path[0]] = obj[path[0]].setting(value, path.drop(1))
    return JsonObject(obj)
}

/** Whole numbers go on the wire as integers (`1`, not `1.0`). */
internal fun jsonNumber(value: Double): JsonElement =
    if (value % 1.0 == 0.0 && kotlin.math.abs(value) < 9.0e15) JsonPrimitive(value.toLong()) else JsonPrimitive(value)

private val jsonNumber = Regex("""-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?""")

/** Whether a non-string primitive's [content] is a JSON number, not `true`, `false`, `null` or a bare word. */
internal fun isJsonNumber(content: String): Boolean = jsonNumber.matches(content)

/** The parser takes a bare unquoted word as a literal anywhere; this refuses anything that is not real JSON. */
internal fun parseStrictJson(text: String): JsonElement? {
    val element = try {
        MosJson.parseToJsonElement(text)
    } catch (_: SerializationException) {
        return null
    }
    return element.takeIf(::isStrictJson)
}

private fun isStrictJson(element: JsonElement): Boolean = when (element) {
    is JsonNull -> true
    is JsonPrimitive -> element.isString || element.content == "true" || element.content == "false" || jsonNumber.matches(element.content)
    is JsonObject -> element.values.all(::isStrictJson)
    is JsonArray -> element.all(::isStrictJson)
}
