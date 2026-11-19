package com.monetizationos.showcase.support

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.monetizationos.sdk.Action
import com.monetizationos.sdk.DecisionResult
import com.monetizationos.sdk.EndpointResult
import com.monetizationos.sdk.Failure
import com.monetizationos.sdk.RedemptionResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What the bottom panel of every screen shows: the call, where its answer came from, and the raw JSON. */
data class LastCall(val call: String, val source: String?, val json: String) {
    companion object {
        fun decision(result: DecisionResult, call: String): LastCall {
            var source = result.source.label
            result.failure?.let { source += " · network failed: ${it.reason.value}" }
            return LastCall(call, source, result.decision?.let { pretty(it.raw) } ?: "No decision")
        }

        fun redemption(result: RedemptionResult, call: String): LastCall {
            val json = when (result) {
                is RedemptionResult.Redeemed -> buildJsonObject {
                    put("outcome", "redeemed")
                    put("action", result.action.json)
                    put("eventId", result.eventId)
                    put("statusCode", result.statusCode)
                }
                is RedemptionResult.Declined -> buildJsonObject {
                    put("outcome", "declined")
                    put("message", result.message)
                    put("statusCode", result.statusCode)
                    put("eventId", result.eventId)
                }
                is RedemptionResult.Failed -> buildJsonObject {
                    put("outcome", "failed")
                    put("failure", result.failure.json)
                }
            }
            return LastCall(call, null, pretty(json))
        }

        fun endpoint(result: EndpointResult, call: String): LastCall = when (result) {
            is EndpointResult.Response -> {
                val response = result.response
                val json = buildJsonObject {
                    put("headers", JsonObject(response.headers.mapValues { JsonPrimitive(it.value) }))
                    put("body", response.json ?: JsonPrimitive(response.text))
                }
                LastCall(call, "HTTP ${response.status}", pretty(json))
            }
            is EndpointResult.Failed -> LastCall(call, "failed", pretty(result.failure.json))
        }

        fun text(call: String, json: JsonElement): LastCall = LastCall(call, null, pretty(json))

        private val printer = Json { prettyPrint = true }

        fun pretty(json: JsonElement): String = printer.encodeToString(JsonElement.serializer(), sorted(json))

        private fun sorted(json: JsonElement): JsonElement = when (json) {
            is JsonObject -> JsonObject(json.entries.sortedBy { it.key }.associate { it.key to sorted(it.value) })
            is JsonArray -> JsonArray(json.map(::sorted))
            else -> json
        }
    }
}

val Action.json: JsonObject
    get() = when (this) {
        is Action.Navigate -> buildJsonObject {
            put("type", "navigate")
            put("url", url)
            put("properties", properties)
        }
        is Action.Custom -> buildJsonObject {
            put("type", type)
            put("properties", properties)
        }
        is Action.NoAction -> buildJsonObject {
            put("type", "none")
            put("properties", properties)
        }
    }

val Failure.json: JsonObject
    get() = buildJsonObject {
        put("reason", reason.value)
        put("status", status?.let(::JsonPrimitive) ?: JsonNull)
        put("message", message)
    }

/** The collapsible "Last call" panel at the bottom of each screen. */
@Composable
fun LastCallPanel(lastCall: LastCall?) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.testTag("lastCall.toggle")) {
            Text("Last call", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            Text(if (expanded) "Hide" else "Show", color = MaterialTheme.colorScheme.primary)
        }
        if (!expanded) return@Column
        if (lastCall == null) {
            Text("No call yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
        Text(lastCall.call, style = mono.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.testTag("lastCall.call"))
        lastCall.source?.let { Text("source: $it", style = mono, modifier = Modifier.testTag("lastCall.source")) }
        SelectionContainer(Modifier.horizontalScroll(rememberScrollState())) {
            Text(lastCall.json, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.testTag("lastCall.json"))
        }
    }
}
