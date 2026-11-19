package com.monetizationos.sdk.logging

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Wraps the host's `onLog`. Context values are primitives or JSON, so a token can only leak by being passed here. */
internal class Logger(private val sink: (LogEvent) -> Unit) {
    fun debug(code: String, message: String, vararg context: Pair<String, Any?>) = emit(LogLevel.Debug, code, message, context)
    fun info(code: String, message: String, vararg context: Pair<String, Any?>) = emit(LogLevel.Info, code, message, context)
    fun warn(code: String, message: String, vararg context: Pair<String, Any?>) = emit(LogLevel.Warn, code, message, context)
    fun error(code: String, message: String, vararg context: Pair<String, Any?>) = emit(LogLevel.Error, code, message, context)

    private fun emit(level: LogLevel, code: String, message: String, context: Array<out Pair<String, Any?>>) {
        val json = JsonObject(
            context.mapNotNull { (key, value) ->
                when (value) {
                    null -> null
                    is JsonElement -> key to value
                    is Number -> key to JsonPrimitive(value)
                    is Boolean -> key to JsonPrimitive(value)
                    else -> key to JsonPrimitive(value.toString())
                }
            }.toMap(),
        )
        try {
            sink(LogEvent(level, code, message, json))
        } catch (_: Exception) {
            // A throwing host logger must never break a call.
        }
    }
}
