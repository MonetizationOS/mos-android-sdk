package com.monetizationos.showcase.screens.basics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
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
import com.monetizationos.sdk.DecisionResult
import com.monetizationos.sdk.Resource
import com.monetizationos.sdk.decision.Meterable
import com.monetizationos.showcase.support.LastCall
import com.monetizationos.showcase.support.LocalShowcase
import com.monetizationos.showcase.support.LockedPlaceholder
import com.monetizationos.showcase.support.PlaceholderArticle
import com.monetizationos.showcase.support.ScreenScaffold
import com.monetizationos.showcase.support.ValueRow
import kotlinx.coroutines.launch

/** A meter on its own surface: each new item id consumes one free view; opening it again does not. */
@Composable
fun MeteredAccessScreen(onBack: () -> Unit) {
    val showcase = LocalShowcase.current
    val scope = rememberCoroutineScope()
    var item by remember { mutableStateOf<Int?>(null) }
    var result by remember { mutableStateOf<DecisionResult?>(null) }
    var lastCall by remember { mutableStateOf<LastCall?>(null) }

    fun open(number: Int) = scope.launch {
        // Per-call surface: this screen's meter lives on its own surface, not the app's default.
        val decided = showcase.mos.decide(Resource("item/$number"), surface = "showcase-metered")
        item = number
        result = decided
        showcase.didDecide(decided)
        lastCall = LastCall.decision(decided, "decide(Resource(\"item/$number\"), surface = \"showcase-metered\")")
    }

    ScreenScaffold(
        title = "Metered access",
        explainer = "decide(resource, surface = \"showcase-metered\") consumes the items.views meter once per new item id; re-opening an item is free (uniqueResources). 3 free items.",
        lastCall = lastCall,
        onBack = onBack,
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (number in 1..5) {
                OutlinedButton(onClick = { open(number) }, modifier = Modifier.testTag("metered.item.$number")) { Text("Item $number") }
            }
        }
        val opened = item
        val decided = result
        if (opened != null && decided != null) {
            val meter = decided.decision?.features?.get("items")?.properties?.get("views") as? Meterable
            ValueRow("Remaining", meter?.let { "${(it.remainingUnits ?: 0.0).toInt()} of ${(it.totalUnits ?: 0.0).toInt()}" } ?: "-", "metered.remaining")
            ValueRow("Consumed by this call", if (meter?.consumedInRequest == true) "yes" else "no", "metered.consumed")
            ValueRow("Item already counted", if (meter?.resourceIdUsed == true) "yes" else "no", "metered.used")
            if (decided.hasAccess(feature = "items", property = "views")) {
                PlaceholderArticle("Item $opened")
            } else {
                LockedPlaceholder("You have used your 3 free items.", Modifier.testTag("metered.locked"))
            }
        }
    }
}
