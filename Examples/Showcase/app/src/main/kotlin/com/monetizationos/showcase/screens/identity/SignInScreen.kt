package com.monetizationos.showcase.screens.identity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.monetizationos.sdk.DecisionResult
import com.monetizationos.sdk.IdentifyResult
import com.monetizationos.sdk.IdentityKind
import com.monetizationos.sdk.Resource
import com.monetizationos.showcase.support.FixtureTransport
import com.monetizationos.showcase.support.LastCall
import com.monetizationos.showcase.support.LocalShowcase
import com.monetizationos.showcase.support.ScreenScaffold
import com.monetizationos.showcase.support.ShowcaseSettings
import com.monetizationos.showcase.support.ValueRow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Hand the SDK a signed-in reader's JWT and back, and see the anonymous reader linked on the next decision. */
@Composable
fun SignInScreen(onBack: () -> Unit) {
    val showcase = LocalShowcase.current
    val scope = rememberCoroutineScope()
    val live = showcase.settings.mode == ShowcaseSettings.Mode.Live
    var pastedJwt by remember { mutableStateOf("") }
    var anonymousBefore by remember { mutableStateOf<String?>(null) }
    var identifyResult by remember { mutableStateOf<IdentifyResult?>(null) }
    var decision by remember { mutableStateOf<DecisionResult?>(null) }
    var lastCall by remember { mutableStateOf<LastCall?>(null) }

    // Only an anonymous reader the server has seen can be linked, so decide once if there is none yet.
    LaunchedEffect(Unit) {
        if (showcase.mos.anonymousIdentifier() == null) showcase.didDecide(showcase.mos.decide(Resource("article/1")))
        anonymousBefore = showcase.mos.anonymousIdentifier()
    }

    suspend fun decideNext() {
        val result = showcase.mos.decide(Resource("article/1"))
        decision = result
        anonymousBefore = showcase.mos.anonymousIdentifier()
        showcase.didDecide(result)
        lastCall = LastCall.decision(result, "decide(Resource(\"article/1\")) after identify")
    }

    fun signIn() = scope.launch {
        // Your sign-in hands you the token. Fixture mode accepts any JWT-shaped string.
        val token = if (live) pastedJwt.trim() else sampleToken()
        if (token.isEmpty()) return@launch
        identifyResult = showcase.mos.identify(token)
        decideNext()
    }

    fun signOut() = scope.launch {
        identifyResult = showcase.mos.identify(null)
        decideNext()
    }

    ScreenScaffold(
        title = "Sign in and out",
        explainer = "identify(userJwt) switches the SDK to a signed-in reader; the next decision carries previousAnonymousIdentifier so the workflow can link the two. identify(null) signs out.",
        lastCall = lastCall,
        onBack = onBack,
    ) {
        ValueRow("Anonymous id", anonymousBefore ?: "none yet", "signIn.anonymousId")
        if (live) {
            OutlinedTextField(
                value = pastedJwt,
                onValueChange = { pastedJwt = it },
                label = { Text("Paste a JWT from your org's JWT integration") },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { signIn() }, modifier = Modifier.testTag("signIn.signIn")) { Text("Sign in") }
            OutlinedButton(onClick = { signOut() }, modifier = Modifier.testTag("signIn.signOut")) { Text("Sign out") }
        }
        identifyResult?.let { result ->
            fun kind(kind: IdentityKind?) = kind?.value ?: "none"
            ValueRow("IdentifyResult", "${kind(result.previous)} → ${kind(result.current)}", "signIn.identify")
            ValueRow("changed", "${result.changed}", "signIn.changed")
            ValueRow("linkPending", "${result.linkPending}", "signIn.linkPending")
        }
        decision?.let { result ->
            result.decision?.identity?.let { identity ->
                ValueRow("Next decision is for", "${if (identity.isAuthenticated) "jwt" else "anonymous"}: ${identity.identifier ?: "?"}", "signIn.decisionIdentity")
                ValueRow("Carried previousAnonymousIdentifier", "${result.linkedAnonymousIdentifier}", "signIn.linked")
            }
        }
    }
}

/** An unsigned token for fixture mode: `sub` is the signed-in reader. */
private fun sampleToken(): String {
    val now = System.currentTimeMillis() / 1000
    return FixtureTransport.fakeJwt(
        buildJsonObject {
            put("sub", "sample-user-1")
            put("name", "Sample User")
            put("iat", now)
            put("exp", now + 3600)
        },
    )
}
