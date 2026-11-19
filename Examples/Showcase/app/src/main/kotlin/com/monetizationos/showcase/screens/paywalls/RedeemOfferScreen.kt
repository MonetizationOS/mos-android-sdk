package com.monetizationos.showcase.screens.paywalls

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.monetizationos.sdk.Action
import com.monetizationos.sdk.RedemptionResult
import com.monetizationos.sdk.Resource
import com.monetizationos.sdk.decision.Offer
import com.monetizationos.showcase.support.LastCall
import com.monetizationos.showcase.support.LocalShowcase
import com.monetizationos.showcase.support.ScreenScaffold
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/** One offer per redemption outcome, and what an app does with each. */
@Composable
fun RedeemOfferScreen(onBack: () -> Unit) {
    val showcase = LocalShowcase.current
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var offers by remember { mutableStateOf<List<Offer>>(emptyList()) }
    var outcome by remember { mutableStateOf<String?>(null) }
    var navigateTo by remember { mutableStateOf<String?>(null) }
    var lastCall by remember { mutableStateOf<LastCall?>(null) }

    LaunchedEffect(Unit) {
        val result = showcase.mos.decide(Resource("offers/redeem"))
        offers = result.decision?.experience("offers")?.offers.orEmpty().mapNotNull { it.offer }
        showcase.didDecide(result)
        lastCall = LastCall.decision(result, "decide(Resource(\"offers/redeem\"))")
    }

    fun redeem(offer: Offer) = scope.launch {
        val result = showcase.mos.redeem(offer.token)
        navigateTo = null
        outcome = when (result) {
            is RedemptionResult.Redeemed -> when (val action = result.action) {
                // Your handler for the custom type; for custom:play-billing, start your app's own purchase flow.
                is Action.Custom -> {
                    val product = listOf("productId", "basePlanId").mapNotNull { (action.properties[it] as? JsonPrimitive)?.content }
                    "Redeemed: ${action.type}. Hand it to your own code, e.g. buy ${product.joinToString(":").ifEmpty { "the product" }}."
                }
                is Action.Navigate -> {
                    navigateTo = action.url
                    "Redeemed: navigate. Open ${action.url}."
                }
                is Action.NoAction -> "Redeemed: none. Nothing to do but decide again for the new entitlements."
            }
            is RedemptionResult.Declined -> "Declined (${result.statusCode}): ${result.message ?: "no message"}. Show the message."
            is RedemptionResult.Failed ->
                "Failed: ${result.failure.reason.value}. The redemption may not have run. For a timeout or server error it may have, so check before retrying."
        }
        lastCall = LastCall.redemption(result, "redeem(offer.token) · ${offer.title}")
    }

    ScreenScaffold(
        title = "Redeem an offer",
        explainer = "redeem(offerToken) returns a RedemptionResult: Redeemed with an action (Custom, Navigate or NoAction), Declined, or Failed. Turn Offline on to see Failed.",
        lastCall = lastCall,
        onBack = onBack,
    ) {
        offers.forEachIndexed { index, offer ->
            OutlinedButton(onClick = { redeem(offer) }, modifier = Modifier.fillMaxWidth().testTag("redeem.offer.$index")) {
                Column(Modifier.fillMaxWidth()) {
                    Text(offer.title, style = MaterialTheme.typography.titleMedium)
                    Text(offer.text("subtitle").orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        outcome?.let {
            Text(
                it,
                modifier = Modifier.fillMaxWidth()
                    .background(Color.Gray.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                    .padding(16.dp)
                    .testTag("redeem.outcome"),
            )
        }
        navigateTo?.let { url -> TextButton(onClick = { uriHandler.openUri(url) }) { Text("Open $url") } }
    }
}
