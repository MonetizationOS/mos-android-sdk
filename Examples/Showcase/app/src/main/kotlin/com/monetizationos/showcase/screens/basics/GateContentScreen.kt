package com.monetizationos.showcase.screens.basics

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.monetizationos.sdk.DecisionResult
import com.monetizationos.sdk.Resource
import com.monetizationos.showcase.support.LastCall
import com.monetizationos.showcase.support.LocalShowcase
import com.monetizationos.showcase.support.LockedPlaceholder
import com.monetizationos.showcase.support.PlaceholderArticle
import com.monetizationos.showcase.support.ScreenScaffold

/** Decide on an article, then show it or a locked placeholder from one boolean feature property. */
@Composable
fun GateContentScreen(onBack: () -> Unit) {
    val showcase = LocalShowcase.current
    var premium by rememberSaveable { mutableStateOf(false) }
    var result by remember { mutableStateOf<DecisionResult?>(null) }
    var lastCall by remember { mutableStateOf<LastCall?>(null) }

    LaunchedEffect(premium) {
        // `meta` is open context for the workflow; here it tells the two tiers apart.
        val resource = if (premium) Resource("article/2", meta = mapOf("tier" to "premium")) else Resource("article/1", meta = mapOf("tier" to "free"))
        val decided = showcase.mos.decide(resource)
        result = decided
        showcase.didDecide(decided)
        lastCall = LastCall.decision(decided, "decide(Resource(\"${resource.id}\"))")
    }

    ScreenScaffold(
        title = "Gate content",
        explainer = "decide(resource) on an article, then hasAccess(feature = \"article\", property = \"enabled\") picks the content or a locked placeholder.",
        lastCall = lastCall,
        onBack = onBack,
    ) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf(false to "Free · article/1", true to "Premium · article/2").forEachIndexed { index, (isPremium, label) ->
                SegmentedButton(
                    selected = premium == isPremium,
                    onClick = { premium = isPremium },
                    shape = SegmentedButtonDefaults.itemShape(index, 2),
                ) { Text(label) }
            }
        }
        val decided = result
        when {
            decided == null -> CircularProgressIndicator()
            decided.hasAccess(feature = "article", property = "enabled") -> {
                Text("Access granted", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("gate.access"))
                PlaceholderArticle(if (premium) "Article 2" else "Article 1")
            }
            else -> {
                Text("No access", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("gate.access"))
                LockedPlaceholder("Article 2 is for subscribers.")
            }
        }
    }
}
