package com.monetizationos.sdk.logging

import kotlinx.serialization.json.JsonObject

public enum class LogLevel(public val value: String) {
    Debug("debug"),
    Info("info"),
    Warn("warn"),
    Error("error"),
}

/** A structured SDK event. `code` is stable across releases and platforms. */
public data class LogEvent(
    val level: LogLevel,
    val code: String,
    val message: String,
    val context: JsonObject = JsonObject(emptyMap()),
)

/** Opt-in logger that prints every event to standard output. The SDK never prints on its own. */
public val consoleLogger: (LogEvent) -> Unit = { event ->
    println("[mos] ${event.level.value} ${event.code}: ${event.message} ${event.context}")
}
