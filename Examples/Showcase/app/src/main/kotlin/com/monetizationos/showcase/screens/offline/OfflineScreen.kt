package com.monetizationos.showcase.screens.offline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.monetizationos.sdk.DecisionResult
import com.monetizationos.sdk.FlushResult
import com.monetizationos.sdk.Resource
import com.monetizationos.showcase.support.LastCall
import com.monetizationos.showcase.support.LocalShowcase
import com.monetizationos.showcase.support.ScreenScaffold
import com.monetizationos.showcase.support.ValueRow
import com.monetizationos.showcase.support.json
import com.monetizationos.showcase.support.label
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Decisions without a network: served from the cache, then a fallback, then unavailable, with
 * metering queued for later.
 */
@Composable
fun OfflineScreen(onBack: () -> Unit) {
    val showcase = LocalShowcase.current
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<DecisionResult?>(null) }
    var flushResult by remember { mutableStateOf<FlushResult?>(null) }
    var lastCall by remember { mutableStateOf<LastCall?>(null) }

    fun decide(number: Int) = scope.launch {
        val decided = showcase.mos.decide(Resource("offline/$number"), surface = "showcase-metered")
        result = decided
        showcase.didDecide(decided)
        lastCall = LastCall.decision(decided, "decide(Resource(\"offline/$number\"), surface = \"showcase-metered\")")
    }

    // Call it when the app comes to the foreground or connectivity returns. A network decision also flushes.
    fun flush() = scope.launch {
        val flushed = showcase.mos.flush()
        flushResult = flushed
        lastCall = LastCall.text(
            "flush()",
            buildJsonObject {
                put("sent", flushed.sent)
                put("dropped", flushed.dropped)
                put("remaining", flushed.remaining)
                put("failure", flushed.failure?.json ?: JsonNull)
            },
        )
    }

    fun clearCache() = scope.launch {
        showcase.mos.clearCache()
        lastCall = LastCall.text("clearCache()", buildJsonObject { put("cleared", true) })
    }

    ScreenScaffold(
        title = "Offline",
        explainer = "With the network gone, decide() serves the cached decision, then the latest one for another resource, then nothing (failOpen answers hasAccess). Meters consumed offline queue until flush().",
        lastCall = lastCall,
        onBack = onBack,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Offline", Modifier.weight(1f))
            Switch(checked = showcase.isOffline, onCheckedChange = { showcase.isOffline = it }, modifier = Modifier.testTag("offline.toggle"))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (number in 1..2) {
                OutlinedButton(onClick = { decide(number) }, modifier = Modifier.testTag("offline.decide.$number")) { Text("Decide offline/$number") }
            }
        }
        result?.let { decided ->
            ValueRow("source", decided.source.label, "offline.source")
            ValueRow("hasAccess", "${decided.hasAccess(feature = "offline", property = "views")}", "offline.hasAccess")
            if (decided.decision == null) {
                Text(
                    "No decision: hasAccess answered OfflineConfig.failOpen, which this app sets to true.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        ValueRow("Queued counter updates", "${showcase.queuedUpdates}", "offline.queued")
        flushResult?.let { flushed ->
            ValueRow("Last flush", "sent ${flushed.sent}, remaining ${flushed.remaining}${flushed.failure?.let { ", ${it.reason.value}" }.orEmpty()}", "offline.flush")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { flush() }, modifier = Modifier.testTag("offline.flushButton")) { Text("Flush") }
            OutlinedButton(onClick = { clearCache() }, modifier = Modifier.testTag("offline.clearCache")) { Text("Clear cache") }
        }
    }
}
