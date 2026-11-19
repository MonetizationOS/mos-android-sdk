package com.monetizationos.showcase.screens.paywalls

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.compose.LocalActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.monetizationos.sdk.DecisionResult
import com.monetizationos.sdk.PriceProvider
import com.monetizationos.sdk.RedemptionResult
import com.monetizationos.sdk.Resource
import com.monetizationos.sdk.StorePrice
import com.monetizationos.sdk.android.MOSWebPaywallRetainer
import com.monetizationos.sdk.android.MOSWebPaywallView
import com.monetizationos.sdk.decision.Experience
import com.monetizationos.showcase.support.LastCall
import com.monetizationos.showcase.support.LocalShowcase
import com.monetizationos.showcase.support.ScreenScaffold
import com.monetizationos.showcase.support.ValueRow
import com.monetizationos.showcase.support.json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The one ViewModel in the app: it outlives Activity recreation (rotation, fold), so the retained
 * WebView keeps its loaded page and an in-flight redemption still lands.
 */
class WebPaywallModel : ViewModel() {
    val retainer = MOSWebPaywallRetainer()
    var result by mutableStateOf<DecisionResult?>(null)
    var lastCall by mutableStateOf<LastCall?>(null)

    override fun onCleared() = retainer.release()
}

/** A server-rendered html paywall in MOSWebPaywallView, priced by the app. */
@Composable
fun WebPaywallScreen(onBack: () -> Unit) {
    val showcase = LocalShowcase.current
    val model: WebPaywallModel = viewModel()
    val activity = LocalActivity.current
    var closed by rememberSaveable { mutableStateOf(false) }
    var event by rememberSaveable { mutableStateOf("none yet") }

    LaunchedEffect(Unit) {
        if (model.result != null) return@LaunchedEffect
        val decided = showcase.mos.decide(Resource("paywall/web"))
        model.result = decided
        showcase.didDecide(decided)
        model.lastCall = LastCall.decision(decided, "decide(Resource(\"paywall/web\"))")
    }
    // Leaving the screen (not a recreation) forgets the decision, so the next visit decides again.
    DisposableEffect(Unit) {
        onDispose {
            if (activity?.isChangingConfigurations != true) {
                model.result = null
                model.lastCall = null
            }
        }
    }

    ScreenScaffold(
        title = "Web paywall",
        explainer = "decision.experience(\"webPaywall\") is Experience.Web: MOSWebPaywallView shows its html, fills prices from a PriceProvider and reports onRedemption and onClose.",
        lastCall = model.lastCall,
        onBack = onBack,
    ) {
        ValueRow("Page event", event, "webPaywall.event")
        val result = model.result
        when (val experience = result?.decision?.experience("webPaywall")) {
            is Experience.Web -> if (!closed) {
                key(experience.html) {
                    AndroidView(
                        factory = { context ->
                            MOSWebPaywallView(
                                context = context,
                                html = experience.html,
                                mos = showcase.mos,
                                priceProvider = FixedPrices,
                                onRedemption = { redemption ->
                                    event = "onRedemption: ${describe(redemption)}"
                                    model.lastCall = LastCall.redemption(redemption, "onRedemption (mos://redeem in the page)")
                                },
                                onClose = {
                                    closed = true
                                    event = "onClose"
                                    model.lastCall = LastCall.text("onClose (mos://close in the page)", buildJsonObject { put("closed", true) })
                                },
                                retainer = model.retainer,
                            )
                        },
                        onRelease = { it.destroy() },
                        modifier = Modifier.fillMaxWidth().height(380.dp).clip(RoundedCornerShape(12.dp)).testTag("webPaywall.view"),
                    )
                }
            } else {
                OutlinedButton(onClick = { closed = false }, modifier = Modifier.testTag("webPaywall.reopen")) {
                    Text("The page closed. Show it again")
                }
            }
            is Experience.Native -> Text("The workflow sent a native paywall under this key; see the Native paywall screen.")
            null -> if (result != null) {
                Text("No paywall: this reader has access.", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("paywall.none"))
            } else {
                CircularProgressIndicator()
            }
        }
    }
}

private fun describe(redemption: RedemptionResult): String = when (redemption) {
    is RedemptionResult.Redeemed -> "redeemed, ${(redemption.action.json["type"] as? JsonPrimitive)?.content.orEmpty()}"
    is RedemptionResult.Declined -> "declined"
    is RedemptionResult.Failed -> "failed, ${redemption.failure.reason.value}"
}

/**
 * Fixed prices standing in for Play Billing, so the swap from the page's fallback text is visible.
 * A real app answers from its billing library's product details.
 */
object FixedPrices : PriceProvider {
    private val known = mapOf(
        "premium:monthly" to StorePrice(displayPrice = "€4.99", displayPeriod = "month"),
        "premium:annual" to StorePrice(displayPrice = "€39.99", displayPeriod = "year"),
    )

    override suspend fun prices(productIds: Set<String>): Map<String, StorePrice> = known.filterKeys { it in productIds }
}
