package com.monetizationos.showcase.screens.basics

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.monetizationos.showcase.support.LastCall
import com.monetizationos.showcase.support.LocalShowcase
import com.monetizationos.showcase.support.PlaceholderBlock
import com.monetizationos.showcase.support.ScreenScaffold
import com.monetizationos.showcase.support.ValueRow
import kotlinx.coroutines.launch

/** An on/off feature property: the workflow decides whether this reader sees the ad slot. */
@Composable
fun FeatureSwitchScreen(onBack: () -> Unit) {
    val showcase = LocalShowcase.current
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<DecisionResult?>(null) }
    var lastCall by remember { mutableStateOf<LastCall?>(null) }

    suspend fun decide() {
        val decided = showcase.mos.decide(Resource("feed"))
        result = decided
        showcase.didDecide(decided)
        lastCall = LastCall.decision(decided, "decide(Resource(\"feed\"))")
    }
    LaunchedEffect(Unit) { decide() }

    ScreenScaffold(
        title = "Feature switch",
        explainer = "hasAccess(feature = \"ads\", property = \"enabled\") shows or hides an ad slot. The fixtures turn ads off for signed-in readers and subscribers.",
        lastCall = lastCall,
        onBack = onBack,
    ) {
        val decided = result
        if (decided == null) {
            CircularProgressIndicator()
        } else {
            val adsOn = decided.hasAccess(feature = "ads", property = "enabled")
            ValueRow("ads.enabled", if (adsOn) "on" else "off", "feature.state")
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FeedItem(1)
                FeedItem(2)
                if (adsOn) {
                    Box(
                        Modifier.fillMaxWidth().heightIn(min = 80.dp)
                            .border(1.dp, Color.Gray, RoundedCornerShape(8.dp))
                            .testTag("feature.adSlot"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Ad slot", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                FeedItem(3)
            }
        }
        OutlinedButton(onClick = { scope.launch { decide() } }) { Text("Decide again") }
    }
}

@Composable
private fun FeedItem(number: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PlaceholderBlock(height = 48.dp, width = 48.dp)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Item $number", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            PlaceholderBlock(height = 10.dp)
        }
    }
}
