package com.monetizationos.showcase.screens.paywalls

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.monetizationos.sdk.DecisionResult
import com.monetizationos.sdk.Resource
import com.monetizationos.sdk.decision.Experience
import com.monetizationos.sdk.decision.Offer
import com.monetizationos.showcase.support.LastCall
import com.monetizationos.showcase.support.LocalShowcase
import com.monetizationos.showcase.support.ScreenScaffold
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

/** A native paywall: copy from the experience JSON, offers from its slots, redeem on tap. */
@Composable
fun NativePaywallScreen(onBack: () -> Unit) {
    val showcase = LocalShowcase.current
    var result by remember { mutableStateOf<DecisionResult?>(null) }
    var lastCall by remember { mutableStateOf<LastCall?>(null) }

    LaunchedEffect(Unit) {
        val decided = showcase.mos.decide(Resource("paywall/native"))
        result = decided
        showcase.didDecide(decided)
        lastCall = LastCall.decision(decided, "decide(Resource(\"paywall/native\"))")
    }

    ScreenScaffold(
        title = "Native paywall",
        explainer = "decision.experience(\"paywall\") is Experience.Native: Compose renders its copy and offer slots, and a tap calls redeem(offer.token).",
        lastCall = lastCall,
        onBack = onBack,
    ) {
        val experience = result?.decision?.experience("paywall")
        when {
            experience is Experience.Native -> NativePaywall(experience) { offer ->
                val redemption = showcase.mos.redeem(offer.token)
                lastCall = LastCall.redemption(redemption, "redeem(offer.token) · ${offer.title}")
            }
            experience != null -> Text("The workflow sent a web paywall under this key; see the Web paywall screen.")
            result != null -> Text("No paywall: this reader has access.", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("paywall.none"))
            else -> CircularProgressIndicator()
        }
    }
}

/**
 * The paywall itself. The copy is whatever shape your experience
 * defines; this one is `{ headline, body, benefits }`.
 */
@Composable
fun NativePaywall(experience: Experience.Native, onSelect: suspend (Offer) -> Unit) {
    val scope = rememberCoroutineScope()
    val copy = remember(experience) { runCatching { experience.decode<PaywallCopy>() }.getOrNull() }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(copy?.headline ?: "Paywall", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("paywall.headline"))
        copy?.body?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        copy?.benefits.orEmpty().forEach { benefit ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Check, contentDescription = null)
                Text(benefit)
            }
        }
        experience.offers.mapNotNull { it.offer }.forEachIndexed { index, offer ->
            Row(
                Modifier.fillMaxWidth()
                    .background(Color.Gray.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                    .clickable { scope.launch { onSelect(offer) } }
                    .padding(16.dp)
                    .testTag("paywall.offer.$index"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(offer.title, style = MaterialTheme.typography.titleMedium)
                    offer.text("subtitle")?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                offer.text("badge")?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.background(Color(0x66FFCC00), CircleShape).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                // A real app shows the store's localized price for offer.storeProductId.
                Text(offer.text("fallbackPrice").orEmpty())
            }
        }
    }
}

@Serializable
private data class PaywallCopy(val headline: String, val body: String? = null, val benefits: List<String> = emptyList())

/** A string from the offer's presentation, which is whatever JSON the org gave the offer. */
fun Offer.text(key: String): String? = (presentation[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

val Offer.title: String get() = text("title") ?: id
