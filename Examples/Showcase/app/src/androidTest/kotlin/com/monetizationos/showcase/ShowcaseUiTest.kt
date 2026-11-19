package com.monetizationos.showcase

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.web.assertion.WebViewAssertions.webMatches
import androidx.test.espresso.web.sugar.Web.onWebView
import androidx.test.espresso.web.webdriver.DriverAtoms.findElement
import androidx.test.espresso.web.webdriver.DriverAtoms.getText
import androidx.test.espresso.web.webdriver.DriverAtoms.webClick
import androidx.test.espresso.web.webdriver.Locator
import org.hamcrest.Matchers.containsString
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Every screen in fixture mode, like the iOS ShowcaseUITests. Each screenshot is written to the app's
 * external files dir, `shots/<name>.png`; pull them with
 * `adb pull /sdcard/Android/data/com.monetizationos.showcase/files/shots`.
 */
@RunWith(AndroidJUnit4::class)
class ShowcaseUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun launch() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
            .putExtra(MainActivity.RESET_EXTRA, true)
        scenario = ActivityScenario.launch(intent)
        waitFor("home.mode", "Fixture", timeout = 30_000)
    }

    @Test
    fun everyScreenInFixtureMode() {
        shot("00-home")

        // Gate content
        open("gate")
        waitFor("gate.access", "Access granted")
        compose.onNode(hasText("Premium · article/2")).performClick()
        waitFor("gate.access", "No access")
        shot("01-gate-content")
        back()

        // Metered access: three new items consume, a repeat does not, the fourth is locked
        open("metered")
        tap("metered.item.1")
        waitFor("metered.remaining", "2 of 3")
        waitFor("metered.consumed", "yes")
        tap("metered.item.1")
        waitFor("metered.used", "yes")
        waitFor("metered.remaining", "2 of 3")
        waitFor("metered.consumed", "no")
        tap("metered.item.2")
        waitFor("metered.remaining", "1 of 3")
        tap("metered.item.3")
        waitFor("metered.remaining", "0 of 3")
        tap("metered.item.4")
        waitFor("metered.locked")
        shot("02-metered-access")
        back()

        // Feature switch: ads on for an anonymous reader
        open("featureSwitch")
        waitFor("feature.state", "on")
        waitFor("feature.adSlot")
        shot("03-feature-switch")
        back()

        // Surface properties
        open("surfaceProperties")
        waitFor("surface.banner", "Sign in to keep your reading list on every device.")
        shot("04-surface-properties")
        back()

        // Native paywall: copy from the experience, an offer redeems to custom:play-billing
        open("nativePaywall")
        waitFor("paywall.headline", "Unlock premium articles")
        tap("paywall.offer.0")
        waitFor("lastCall.json", "custom:play-billing", substring = true)
        shot("05-native-paywall")
        back()

        // Web paywall: the price provider fills the page, a plan redeems
        open("webPaywall")
        // The page's fallback says $4.99; the PriceProvider's €4.99 replaces it.
        val monthly = Locator.CSS_SELECTOR to "a.sc-plan"
        waitForPage(monthly, "€4.99 / month")
        shot("06-web-paywall")
        onWebView().withElement(findElement(monthly.first, monthly.second)).perform(webClick())
        waitFor("webPaywall.event", "onRedemption: redeemed, custom:play-billing")
        back()

        // Redeem an offer: custom, navigate, none, declined, and a repeat claim declined
        open("redeem")
        waitFor("redeem.offer.3")
        tap("redeem.offer.0")
        waitFor("redeem.outcome", "Redeemed: custom:play-billing", substring = true)
        tap("redeem.offer.1")
        waitFor("redeem.outcome", "Redeemed: navigate", substring = true)
        tap("redeem.offer.2")
        waitFor("redeem.outcome", "Redeemed: none", substring = true)
        tap("redeem.offer.3")
        waitFor("redeem.outcome", "Declined (410)", substring = true)
        tap("redeem.offer.2")
        waitFor("redeem.outcome", "Declined (409)", substring = true)
        shot("07-redeem-offer")
        back()

        // Sign in links the anonymous reader; sign out goes back to it
        open("signIn")
        waitFor("signIn.anonymousId", "mos-anon", substring = true)
        tap("signIn.signIn")
        waitFor("signIn.identify", "anonymous → jwt")
        waitFor("signIn.linked", "true")
        waitFor("signIn.decisionIdentity", "jwt: sample-user-1")
        shot("08-sign-in")
        tap("signIn.signOut")
        waitFor("signIn.identify", "jwt → anonymous")
        waitFor("signIn.decisionIdentity", "anonymous", substring = true)
        back()

        // Offline: network, cache, fallback, unavailable, then flush the queued meters
        open("offline")
        tap("offline.decide.1")
        waitFor("offline.source", "network")
        tap("offline.toggle")
        tap("offline.decide.1")
        waitFor("offline.source", "cache", substring = true)
        tap("offline.decide.2")
        waitFor("offline.source", "fallback (from offline/1)")
        waitFor("offline.queued", "2")
        tap("offline.flushButton")
        waitFor("offline.flush", "sent 0, remaining 2, requestFailed")
        tap("offline.clearCache")
        waitFor("lastCall.call", "clearCache()")
        tap("offline.decide.1")
        waitFor("offline.source", "unavailable")
        waitFor("offline.hasAccess", "true")
        shot("09-offline")
        tap("offline.toggle")
        tap("offline.flushButton")
        waitFor("offline.flush", "sent 2, remaining 0")
        waitFor("offline.queued", "0")
        back()

        // App endpoints: a GET, and a POST that echoes its body
        open("endpoints")
        tap("endpoints.get")
        waitFor("endpoints.status", "HTTP 200")
        shot("10-app-endpoints")
        tap("endpoints.post")
        waitFor("endpoints.status", "HTTP 201")
        waitFor("lastCall.json", "Note 1", substring = true)
        back()

        // Settings
        tap("home.settings")
        waitFor("settings.resetIdentity")
        shot("11-settings")
        tap("settings.resetIdentity")
        waitFor("settings.note")
        compose.onNode(hasText("Cancel")).performClick()
        waitFor("home.identity", "Anonymous", substring = true)
    }

    // Steps

    private fun open(screen: String) {
        tap("home.$screen")
        waitFor("screen.explainer")
    }

    private fun back() {
        tap("screen.back")
        waitFor("home.mode")
    }

    private fun tap(tag: String) {
        waitFor(tag)
        val node = compose.onNode(hasTestTag(tag))
        // Controls in the top bar have no scrolling parent.
        runCatching { node.performScrollTo() }
        node.performClick()
    }

    private fun nodes(matcher: SemanticsMatcher, merged: Boolean = false) =
        compose.onAllNodes(matcher, useUnmergedTree = !merged).fetchSemanticsNodes()

    /** Waits for a node with [tag], and when given, a text equal to [text] or containing it. */
    private fun waitFor(tag: String, text: String? = null, substring: Boolean = false, merged: Boolean = false, timeout: Long = 15_000) {
        val matcher = if (text == null) hasTestTag(tag) else hasTestTag(tag) and hasText(text, substring = substring)
        try {
            compose.waitUntil(timeout) { nodes(matcher, merged).isNotEmpty() }
        } catch (e: Throwable) {
            val actual = nodes(hasTestTag(tag), merged).map { it.config }
            throw AssertionError("No node $tag with text '$text'; found $actual", e)
        }
    }

    /** Waits until the web paywall's first element matching [selector] contains [text]. */
    private fun waitForPage(selector: Pair<Locator, String>, text: String, timeout: Long = 20_000) {
        // Polled through waitUntil, which also runs the screen's coroutines under the v2 rule.
        compose.waitUntil(timeout) {
            runCatching {
                onWebView().withElement(findElement(selector.first, selector.second)).check(webMatches(getText(), containsString(text)))
            }.isSuccess
        }
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "shots").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
