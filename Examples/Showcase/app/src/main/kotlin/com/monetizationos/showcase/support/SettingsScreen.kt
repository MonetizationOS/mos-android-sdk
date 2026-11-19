package com.monetizationos.showcase.support

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Mode, key, host and endpoint namespace, kept across launches. Saving a new mode, key or host
 * rebuilds the MOS instance.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(showcase: Showcase, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf(showcase.settings) }
    var error by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    val noAutocorrect = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { TextButton(onClick = onDone) { Text("Cancel") } },
                actions = {
                    TextButton(
                        onClick = {
                            scope.launch {
                                error = showcase.apply(draft)
                                if (error == null) onDone()
                            }
                        },
                        modifier = Modifier.testTag("settings.save"),
                    ) { Text("Save") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().testTag("settings.mode")) {
                ShowcaseSettings.Mode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = draft.mode == mode,
                        onClick = { draft = draft.copy(mode = mode) },
                        shape = SegmentedButtonDefaults.itemShape(index, ShowcaseSettings.Mode.entries.size),
                    ) { Text(mode.name) }
                }
            }
            Text(
                if (draft.mode == ShowcaseSettings.Mode.Fixture) {
                    "Fixture mode answers every request from the bundled fixtures. No key, no network."
                } else {
                    "Live mode talks to your org. See the README for what the org needs."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text("Live org", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = draft.publicKey,
                onValueChange = { draft = draft.copy(publicKey = it) },
                label = { Text("Public key (pk_…)") },
                singleLine = true,
                keyboardOptions = noAutocorrect,
                modifier = Modifier.fillMaxWidth().testTag("settings.publicKey"),
            )
            OutlinedTextField(
                value = draft.apiBaseUrl,
                onValueChange = { draft = draft.copy(apiBaseUrl = it) },
                label = { Text("API base URL") },
                singleLine = true,
                keyboardOptions = noAutocorrect.copy(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth().testTag("settings.apiBaseUrl"),
            )

            Text("App endpoints", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = draft.namespace,
                onValueChange = { draft = draft.copy(namespace = it) },
                label = { Text("Namespace") },
                singleLine = true,
                keyboardOptions = noAutocorrect,
                modifier = Modifier.fillMaxWidth().testTag("settings.namespace"),
            )

            OutlinedButton(
                onClick = {
                    scope.launch {
                        showcase.resetIdentity()
                        note = "A new anonymous reader. The cache and the queue are empty."
                    }
                },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.testTag("settings.resetIdentity"),
            ) { Text("Reset identity") }
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("settings.note")) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("settings.error")) }
        }
    }
}
