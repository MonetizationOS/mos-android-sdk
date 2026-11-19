package com.monetizationos.showcase.screens.endpoints

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.monetizationos.sdk.EndpointMethod
import com.monetizationos.sdk.EndpointResult
import com.monetizationos.showcase.support.LastCall
import com.monetizationos.showcase.support.LocalShowcase
import com.monetizationos.showcase.support.ScreenScaffold
import com.monetizationos.showcase.support.ValueRow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Plain HTTP to an installed app's endpoints. */
@Composable
fun AppEndpointsScreen(onBack: () -> Unit) {
    val showcase = LocalShowcase.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var lastCall by remember { mutableStateOf<LastCall?>(null) }

    fun show(result: EndpointResult, call: String) {
        status = when (result) {
            // Any status is a response; the workflow decides what 4xx means.
            is EndpointResult.Response -> "HTTP ${result.response.status}${if (result.response.isSuccess) "" else " (not a success)"}"
            is EndpointResult.Failed -> "failed: ${result.failure.reason.value}"
        }
        lastCall = LastCall.endpoint(result, call)
    }

    fun getProfile() = scope.launch {
        val app = showcase.mos.app(showcase.settings.namespace)
        show(app.call("profile", EndpointMethod.Get), "app(\"${app.namespace}\").call(\"profile\", EndpointMethod.Get)")
    }

    fun postNote() = scope.launch {
        val app = showcase.mos.app(showcase.settings.namespace)
        val result = app.call("notes", EndpointMethod.Post, body = buildJsonObject { put("text", "Note 1") })
        show(result, "app(\"${app.namespace}\").call(\"notes\", EndpointMethod.Post, body)")
    }

    ScreenScaffold(
        title = "App endpoints",
        explainer = "mos.app(namespace).call(path) returns EndpointResult.Response for any HTTP status, or Failed when nothing came back.",
        lastCall = lastCall,
        onBack = onBack,
    ) {
        ValueRow("Namespace", showcase.settings.namespace, "endpoints.namespace")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            OutlinedButton(onClick = { getProfile() }, modifier = Modifier.testTag("endpoints.get")) { Text("GET profile") }
            OutlinedButton(onClick = { postNote() }, modifier = Modifier.testTag("endpoints.post")) { Text("POST notes") }
        }
        status?.let { ValueRow("Result", it, "endpoints.status") }
    }
}
