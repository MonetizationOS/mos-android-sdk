package com.monetizationos.showcase.screens.basics

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.monetizationos.sdk.Resource
import com.monetizationos.showcase.support.LastCall
import com.monetizationos.showcase.support.LocalShowcase
import com.monetizationos.showcase.support.PlaceholderBlock
import com.monetizationos.showcase.support.ScreenScaffold
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Open JSON the workflow sets on the surface, rendered natively: a banner's text and tone. */
@Composable
fun SurfacePropertiesScreen(onBack: () -> Unit) {
    val showcase = LocalShowcase.current
    var banner by remember { mutableStateOf<JsonObject?>(null) }
    var decided by remember { mutableStateOf(false) }
    var lastCall by remember { mutableStateOf<LastCall?>(null) }

    LaunchedEffect(Unit) {
        val result = showcase.mos.decide(Resource("home"), surface = "showcase-home")
        banner = result.decision?.surfaceProperties?.get("banner") as? JsonObject
        decided = true
        showcase.didDecide(result)
        lastCall = LastCall.decision(result, "decide(Resource(\"home\"), surface = \"showcase-home\")")
    }

    ScreenScaffold(
        title = "Surface properties",
        explainer = "decision.surfaceProperties[\"banner\"] is open JSON from the workflow on the showcase-home surface; the app reads text, tone and cta and draws its own banner.",
        lastCall = lastCall,
        onBack = onBack,
    ) {
        val text = banner?.string("text")
        when {
            text != null -> {
                val tint = tint(banner?.string("tone"))
                Column(
                    Modifier.fillMaxWidth()
                        .background(tint.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                        .border(1.dp, tint, RoundedCornerShape(12.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(text, modifier = Modifier.testTag("surface.banner"))
                    banner?.string("cta")?.let { Text(it, fontWeight = FontWeight.Bold) }
                }
            }
            decided -> Text("The workflow set no banner.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> CircularProgressIndicator()
        }
        for (number in 1..3) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                PlaceholderBlock(height = 40.dp, width = 40.dp)
                Text("Home row $number")
            }
        }
    }
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** The workflow sends a tone, not a colour, so the app keeps its own palette. */
private fun tint(tone: String?): Color = when (tone) {
    "success" -> Color(0xFF34C759)
    "warning" -> Color(0xFFFF9500)
    else -> Color(0xFF007AFF)
}
