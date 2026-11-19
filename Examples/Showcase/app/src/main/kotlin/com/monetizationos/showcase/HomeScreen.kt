package com.monetizationos.showcase

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.monetizationos.showcase.screens.basics.FeatureSwitchScreen
import com.monetizationos.showcase.screens.basics.GateContentScreen
import com.monetizationos.showcase.screens.basics.MeteredAccessScreen
import com.monetizationos.showcase.screens.basics.SurfacePropertiesScreen
import com.monetizationos.showcase.screens.endpoints.AppEndpointsScreen
import com.monetizationos.showcase.screens.identity.SignInScreen
import com.monetizationos.showcase.screens.offline.OfflineScreen
import com.monetizationos.showcase.screens.paywalls.NativePaywallScreen
import com.monetizationos.showcase.screens.paywalls.RedeemOfferScreen
import com.monetizationos.showcase.screens.paywalls.WebPaywallScreen
import com.monetizationos.showcase.support.LocalShowcase
import com.monetizationos.showcase.support.ValueRow
import com.monetizationos.showcase.support.label

/** One row per screen, grouped by use case, under live rows for the current mode, identity and source. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpen: (ShowcaseScreen) -> Unit, onSettings: () -> Unit) {
    val showcase = LocalShowcase.current
    val source = showcase.lastSource?.label ?: "none yet"
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MOS Showcase") },
                actions = {
                    IconButton(onClick = onSettings, modifier = Modifier.testTag("home.settings")) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Section("Now") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ValueRow("Mode", showcase.modeLabel, "home.mode")
                    ValueRow("Identity", showcase.identity, "home.identity")
                    ValueRow("Last decision", if (showcase.isOffline) "$source · offline" else source, "home.source")
                }
            }
            for (group in ShowcaseScreen.Group.entries) {
                Section(group.title) {
                    ShowcaseScreen.entries.filter { it.group == group }.forEach { screen ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpen(screen) }.padding(horizontal = 16.dp, vertical = 14.dp).testTag("home.${screen.tag}"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(screen.title, style = MaterialTheme.typography.bodyLarge)
                        }
                        HorizontalDivider(Modifier.padding(start = 16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
    content()
}

/** The screens, in Home's order. [tag] names the row and the UI tests' screenshots. */
enum class ShowcaseScreen(val tag: String, val title: String, val group: Group) {
    Gate("gate", "Gate content", Group.Basics),
    Metered("metered", "Metered access", Group.Basics),
    FeatureSwitch("featureSwitch", "Feature switch", Group.Basics),
    SurfaceProperties("surfaceProperties", "Surface properties", Group.Basics),
    NativePaywall("nativePaywall", "Native paywall", Group.Paywalls),
    WebPaywall("webPaywall", "Web paywall", Group.Paywalls),
    Redeem("redeem", "Redeem an offer", Group.Paywalls),
    SignIn("signIn", "Sign in and out", Group.Identity),
    Offline("offline", "Offline", Group.Offline),
    Endpoints("endpoints", "App endpoints", Group.Endpoints),
    ;

    enum class Group(val title: String) { Basics("Basics"), Paywalls("Paywalls"), Identity("Identity"), Offline("Offline"), Endpoints("Endpoints") }

    @Composable
    fun Content(onBack: () -> Unit) = when (this) {
        Gate -> GateContentScreen(onBack)
        Metered -> MeteredAccessScreen(onBack)
        FeatureSwitch -> FeatureSwitchScreen(onBack)
        SurfaceProperties -> SurfacePropertiesScreen(onBack)
        NativePaywall -> NativePaywallScreen(onBack)
        WebPaywall -> WebPaywallScreen(onBack)
        Redeem -> RedeemOfferScreen(onBack)
        SignIn -> SignInScreen(onBack)
        Offline -> OfflineScreen(onBack)
        Endpoints -> AppEndpointsScreen(onBack)
    }
}
