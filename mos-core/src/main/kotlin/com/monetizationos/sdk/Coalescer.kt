package com.monetizationos.sdk

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.completeWith
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.ContinuationInterceptor

/**
 * Shares one in-flight call between callers with the same key. The call runs in [scope], not in any
 * caller, so cancelling a caller (even the first) only stops its wait. The entry goes when the call completes.
 */
internal class Coalescer<T>(private val scope: CoroutineScope) {
    private val flights = ConcurrentHashMap<String, CompletableDeferred<T>>()

    /** [onJoin] runs for a caller that awaits another's call instead of starting one. */
    suspend fun run(key: String, onJoin: () -> Unit, call: suspend () -> T): T {
        val shared = CompletableDeferred<T>()
        val existing = flights.putIfAbsent(key, shared)
        if (existing != null) {
            onJoin()
            return existing.await()
        }
        // The first caller's dispatcher, without its Job: same threading as a direct call, own lifetime.
        val dispatcher = currentCoroutineContext()[ContinuationInterceptor] ?: Dispatchers.IO
        scope.launch(dispatcher) {
            val result = runCatching { call() }
            flights.remove(key, shared)
            shared.completeWith(result)
        }
        return shared.await()
    }
}

/** Sorted keys at every level, so two bodies with the same content compare equal whatever their key order. */
internal fun canonicalJson(element: JsonElement): String = when (element) {
    is JsonObject -> element.entries.sortedBy { it.key }
        .joinToString(",", "{", "}") { (key, value) -> "${JsonPrimitive(key)}:${canonicalJson(value)}" }
    is JsonArray -> element.joinToString(",", "[", "]") { canonicalJson(it) }
    else -> element.toString()
}
