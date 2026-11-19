package com.monetizationos.sdk.android

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Looper
import android.view.View
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ApplicationProvider
import com.monetizationos.sdk.AppInfo
import com.monetizationos.sdk.IdentityConfig
import com.monetizationos.sdk.MOS
import com.monetizationos.sdk.MOSConfig
import com.monetizationos.sdk.PriceProvider
import com.monetizationos.sdk.RedemptionResult
import com.monetizationos.sdk.StorePrice
import com.monetizationos.sdk.identity.InMemoryIdentityStore
import com.monetizationos.sdk.logging.LogEvent
import com.monetizationos.sdk.logging.LogLevel
import com.monetizationos.sdk.transport.HttpRequest
import com.monetizationos.sdk.transport.HttpResponse
import com.monetizationos.sdk.transport.HttpTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.IdentityHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A redemption that waits at the transport until the test lets it finish. */
private class GatedTransport : HttpTransport {
    val reached = CountDownLatch(1)
    val sends = AtomicInteger()
    private val gate = CompletableDeferred<Unit>()

    fun open() {
        gate.complete(Unit)
    }

    override suspend fun send(request: HttpRequest): HttpResponse {
        sends.incrementAndGet()
        reached.countDown()
        gate.await()
        return HttpResponse(500, "{}")
    }
}

/** What the host app sees. */
private class Host {
    val redemptions = mutableListOf<RedemptionResult>()
    val opened = mutableListOf<String>()
    var closes = 0
}

/** A WebView standing in for the page the navigation callbacks are about: the URL it committed, and whether a load was stopped. */
private class FakePage(context: Context, private val committed: String?) : WebView(context) {
    var stops = 0

    override fun getUrl(): String? = committed

    override fun stopLoading() {
        stops++
    }
}

private fun request(url: String, mainFrame: Boolean = true, method: String = "GET"): WebResourceRequest = object : WebResourceRequest {
    override fun getUrl(): Uri = Uri.parse(url)
    override fun isForMainFrame() = mainFrame
    override fun isRedirect() = false
    override fun hasGesture() = false
    override fun getMethod() = method
    override fun getRequestHeaders(): Map<String, String> = emptyMap()
}

/** A result as the SDK makes one: with no identity yet, a redemption fails without sending anything. */
private fun aResult(): RedemptionResult =
    runBlocking { MOS.inMemory(MOSConfig("pk_test_env1_secret", "app", AppInfo("TestApp", "1.0"))).redeem("tok") }

@Suppress("DEPRECATION")
private fun rendererDied() = object : RenderProcessGoneDetail() {
    override fun didCrash() = true
    override fun rendererPriorityAtExit() = 0
}

@RunWith(RobolectricTestRunner::class)
class MOSWebPaywallViewTest {
    private val application: Context = ApplicationProvider.getApplicationContext()
    private val transport = GatedTransport()
    private val host = Host()
    private val retainer = MOSWebPaywallRetainer()
    private val logs = CopyOnWriteArrayList<LogEvent>()
    private val mos = MOS.inMemory(
        MOSConfig(
            publicKey = "pk_test_env1_s3cret_with_underscores",
            surface = "app-paywall",
            app = AppInfo("TestApp", "1.0"),
            apiBaseUrl = "https://api.test",
            identity = IdentityConfig(store = InMemoryIdentityStore(mapOf("anonymousIdentifier" to "anon-1"))),
            onLog = { logs += it },
            transport = transport,
        ),
    )

    @After
    fun tearDown() {
        transport.open()
        retainer.release()
    }

    private fun activity(): Activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    private fun view(
        activity: Activity,
        retainer: MOSWebPaywallRetainer? = this.retainer,
        priceProvider: PriceProvider? = null,
        html: String = "<p data-mos-product='p1'>price</p>",
    ): MOSWebPaywallView {
        val view = MOSWebPaywallView(
            activity,
            html,
            mos,
            priceProvider,
            baseCss = null,
            onRedemption = { host.redemptions.add(it) },
            onClose = { host.closes++ },
            onOpenUrl = { host.opened.add(it) },
            retainer = retainer,
        )
        activity.setContentView(view)
        return view
    }

    /** What a rotation does to the old view: it leaves its window for good, then the host destroys it. */
    private fun detach(view: MOSWebPaywallView) {
        (view.parent as? android.view.ViewGroup)?.removeView(view)
        view.destroy()
    }

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    /** Runs the main looper until [condition] holds; work on other threads lands there. */
    private fun awaitMain(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (true) {
            idleMain()
            if (condition()) return
            check(System.nanoTime() < deadline) { "Timed out waiting for $what" }
            Thread.sleep(5)
        }
    }

    private fun WebView.client(): WebViewClient = checkNotNull(webViewClient)

    private fun WebView.commitDocument() = shadowOf(this).pushEntryToHistory("about:blank")

    private fun redeem(view: WebView, token: String = "tok") {
        val page = FakePage(application, "about:blank")
        view.client().shouldOverrideUrlLoading(page, request("mos://redeem?token=$token"))
    }

    // Retained WebView

    @Test
    fun aRetainedWebViewMovesToTheNextViewWithoutReloading() {
        val first = activity()
        val second = activity()
        val view1 = view(first)
        val webView = checkNotNull(retainer.webView)
        assertSame(first, (webView.context as ContextWrapper).baseContext)

        detach(view1)
        assertSame(application, (webView.context as ContextWrapper).baseContext, "the Activity is let go while detached")
        assertSame(webView, retainer.webView)

        view(second)
        assertSame(webView, retainer.webView)
        assertSame(second, (webView.context as ContextWrapper).baseContext)
    }

    @Test
    fun aDifferentDocumentGetsAFreshWebView() {
        val first = view(activity(), html = "<p>one</p>")
        val webView = checkNotNull(retainer.webView)
        detach(first)
        view(activity(), html = "<p>two</p>")
        assertNotSame(webView, retainer.webView)
        assertTrue(shadowOf(webView).wasDestroyCalled())
    }

    @Test
    fun releaseDestroysTheWebViewAndDropsWhatWasPending() {
        val view = view(activity())
        val webView = checkNotNull(retainer.webView)
        detach(view)
        retainer.release()
        assertTrue(shadowOf(webView).wasDestroyCalled())
        assertNull(retainer.webView)
        retainer.deliver(aResult())
        idleMain()
        assertTrue(host.redemptions.isEmpty())
    }

    // The client of a detached WebView

    @Test
    fun aDetachedWebViewsClientReachesNoView() {
        val view = view(activity())
        val webView = checkNotNull(retainer.webView)
        detach(view)

        assertFalse(reaches(webView.client(), view), "the client of a detached WebView must not keep the view and its Activity alive")
        val page = FakePage(application, "about:blank")
        webView.client().shouldOverrideUrlLoading(page, request("mos://close"))
        webView.client().shouldOverrideUrlLoading(page, request("https://example.com/terms"))
        assertEquals(0, host.closes, "a navigation while detached has no one to tell")
        assertTrue(host.opened.isEmpty())
    }

    @Test
    fun theClientFollowsTheViewThatAdoptsTheWebView() {
        val view1 = view(activity())
        val webView = checkNotNull(retainer.webView)
        detach(view1)
        view(activity())

        webView.client().shouldOverrideUrlLoading(FakePage(application, "about:blank"), request("https://example.com/terms"))
        assertEquals(listOf("https://example.com/terms"), host.opened)
    }

    @Test
    fun aRedemptionStartedBeforeRotationReachesTheNewView() {
        val view1 = view(activity())
        val webView = checkNotNull(retainer.webView)
        redeem(webView)
        assertTrue(transport.reached.await(10, TimeUnit.SECONDS))

        detach(view1)
        view(activity())
        transport.open()
        awaitMain("the redemption") { host.redemptions.isNotEmpty() }
        idleMain()
        assertEquals(1, host.redemptions.size)
    }

    @Test
    fun aRedemptionThatLandsWhileNoViewIsAttachedWaitsForTheNextOne() {
        val view1 = view(activity())
        val webView = checkNotNull(retainer.webView)
        redeem(webView)
        assertTrue(transport.reached.await(10, TimeUnit.SECONDS))

        detach(view1)
        transport.open()
        // Let the redemption finish with nothing attached.
        Thread.sleep(300)
        idleMain()
        assertTrue(host.redemptions.isEmpty())

        view(activity())
        awaitMain("the redemption") { host.redemptions.isNotEmpty() }
        assertEquals(1, host.redemptions.size)
    }

    @Test
    fun aSecondRedeemWhileOneIsInFlightIsIgnoredAndWorksAgainAfter() {
        view(activity())
        val webView = checkNotNull(retainer.webView)
        redeem(webView, "tok1")
        assertTrue(transport.reached.await(10, TimeUnit.SECONDS))
        redeem(webView, "tok1")
        redeem(webView, "tok2")

        transport.open()
        awaitMain("the redemption") { host.redemptions.isNotEmpty() }
        Thread.sleep(100)
        idleMain()
        assertEquals(1, host.redemptions.size, "a double tap reaches the host once")
        assertEquals(1, transport.sends.get())

        redeem(webView, "tok3")
        awaitMain("the next redemption") { host.redemptions.size == 2 }
        assertEquals(2, transport.sends.get())
    }

    // Main-thread confinement

    @Test
    fun aRedemptionCompletingMidRotationGoesToTheViewThatIsAttachedWhenItIsHandled() {
        val oldHost = Host()
        val first = activity()
        val view1 = MOSWebPaywallView(
            first, "<p>x</p>", mos, null, null,
            onRedemption = { oldHost.redemptions.add(it) }, onClose = {}, onOpenUrl = {}, retainer = retainer,
        )
        first.setContentView(view1)

        // The redemption finishes on a background thread while view 1 is still attached...
        val failed = aResult()
        thread { retainer.deliver(failed) }.join()

        // ...and the rotation replaces the view before the main thread has handled it.
        detach(view1)
        view(activity())
        awaitMain("the redemption") { host.redemptions.isNotEmpty() }
        idleMain()

        assertEquals(listOf<RedemptionResult>(failed), host.redemptions, "delivered once, to the new view")
        assertTrue(oldHost.redemptions.isEmpty(), "not to the view that was torn down")
    }

    @Test
    fun deliverFromAnotherThreadDoesNothingUntilTheMainThreadRuns() {
        val failed = aResult()
        view(activity())
        thread { retainer.deliver(failed) }.join()
        assertTrue(host.redemptions.isEmpty(), "nothing is handled off the main thread")
        awaitMain("the redemption") { host.redemptions.isNotEmpty() }
        assertEquals(listOf<RedemptionResult>(failed), host.redemptions)
    }

    // Prices

    private fun prices() = PriceProvider { ids -> ids.associateWith { StorePrice("£4.99") } }

    private fun answerCollect(webView: WebView) {
        val shadow = shadowOf(webView)
        assertTrue(checkNotNull(shadow.lastEvaluatedJavascript).contains("data-mos-product"), "the collect script runs first")
        shadow.lastEvaluatedJavascriptCallback.onReceiveValue("[\"p1\"]")
    }

    @Test
    fun aPriceFillThatCompletesAfterRotationStillRuns() {
        val view1 = view(activity(), priceProvider = prices())
        val webView = checkNotNull(retainer.webView)
        webView.commitDocument()
        webView.client().onPageFinished(webView, "about:blank")
        answerCollect(webView)

        // The fill is computed off the main thread; the view is replaced before it comes back.
        detach(view1)
        view(activity(), priceProvider = prices())
        awaitMain("the price fill") { checkNotNull(shadowOf(webView).lastEvaluatedJavascript).contains("4.99") }
    }

    @Test
    fun aPageThatFinishedWhileNoViewWasAttachedIsFilledByTheNextView() {
        val view1 = view(activity(), priceProvider = prices())
        val webView = checkNotNull(retainer.webView)
        detach(view1)
        webView.commitDocument()
        webView.client().onPageFinished(webView, "about:blank")
        assertTrue(shadowOf(webView).lastEvaluatedJavascript.orEmpty().isEmpty(), "no view, so no fill yet")

        view(activity(), priceProvider = prices())
        answerCollect(webView)
        awaitMain("the price fill") { checkNotNull(shadowOf(webView).lastEvaluatedJavascript).contains("4.99") }
    }

    @Test
    fun aFillIsNotStartedTwice() {
        val view1 = view(activity(), priceProvider = prices())
        val webView = checkNotNull(retainer.webView)
        webView.client().onPageFinished(webView, "about:blank")
        val first = shadowOf(webView).lastEvaluatedJavascriptCallback
        detach(view1)
        view(activity(), priceProvider = prices())
        assertSame(first, shadowOf(webView).lastEvaluatedJavascriptCallback, "adoption does not start a second fill")
    }

    // Navigation policy

    @Test
    fun aPostedFormIsAnsweredWithNoContentAndHandedToTheAppOnceOnTheMainThread() {
        view(activity())
        val webView = checkNotNull(retainer.webView)
        val page = FakePage(application, "about:blank")

        // WebView asks on a background thread.
        var response: WebResourceResponse? = null
        thread { response = webView.client().shouldInterceptRequest(page, request("https://evil.example/collect", method = "POST")) }.join()

        val answer = checkNotNull(response)
        assertEquals(204, answer.statusCode, "a 204 does not commit, so the paywall document stays")
        assertEquals(0, answer.data.available())
        assertTrue(host.opened.isEmpty(), "nothing reaches the app off the main thread")
        idleMain()
        assertEquals(listOf("https://evil.example/collect"), host.opened)
        assertEquals(0, page.stops)

        // A commit that still happened is stopped, and not handed over a second time.
        webView.client().onPageStarted(page, "https://evil.example/collect", null)
        idleMain()
        assertEquals(1, page.stops)
        assertEquals(listOf("https://evil.example/collect"), host.opened)
    }

    @Test
    fun aPostedFormWhileDetachedReachesNoView() {
        val view = view(activity())
        val webView = checkNotNull(retainer.webView)
        detach(view)

        val response = webView.client().shouldInterceptRequest(FakePage(application, "about:blank"), request("https://evil.example/collect", method = "POST"))
        idleMain()

        assertEquals(204, response?.statusCode)
        assertTrue(host.opened.isEmpty())
    }

    @Test
    fun aPostedFormToAnotherSchemeIsStoppedAndDropped() {
        view(activity())
        val webView = checkNotNull(retainer.webView)
        val page = FakePage(application, "about:blank")

        webView.client().onPageStarted(page, "mos://redeem?token=t", null)
        webView.client().onPageStarted(page, "file:///etc/passwd", null)

        assertEquals(2, page.stops)
        assertTrue(host.opened.isEmpty())
        assertEquals(0, host.closes)
        assertTrue(host.redemptions.isEmpty())
    }

    @Test
    fun theDocumentItselfIsNeverStopped() {
        view(activity())
        val webView = checkNotNull(retainer.webView)
        val page = FakePage(application, "about:blank")

        webView.client().onPageStarted(page, "about:blank", null)
        webView.client().onPageStarted(page, "about:blank#plans", null)

        assertEquals(0, page.stops)
    }

    @Test
    fun aRemotePageThatGotInCannotRedeemOrClose() {
        view(activity())
        val webView = checkNotNull(retainer.webView)
        val remote = FakePage(application, "https://evil.example/pay")

        webView.client().shouldOverrideUrlLoading(remote, request("mos://redeem?token=t"))
        webView.client().shouldOverrideUrlLoading(remote, request("mos://close"))

        assertFalse(transport.reached.await(300, TimeUnit.MILLISECONDS), "no redemption request was sent")
        assertEquals(0, host.closes)
    }

    @Test
    fun theDocumentCanCloseAndOpenLinks() {
        view(activity())
        val webView = checkNotNull(retainer.webView)
        val page = FakePage(application, "about:blank")

        webView.client().shouldOverrideUrlLoading(page, request("mos://close"))
        webView.client().shouldOverrideUrlLoading(page, request("https://example.com/terms"))

        assertEquals(1, host.closes)
        assertEquals(listOf("https://example.com/terms"), host.opened)
    }

    @Test
    fun aMainFrameRequestForARemoteSiteNeverReachesTheNetwork() {
        view(activity())
        val webView = checkNotNull(retainer.webView)
        val page = FakePage(application, "about:blank")

        val blocked = webView.client().shouldInterceptRequest(page, request("https://evil.example/collect", mainFrame = true))
        assertNotNull(blocked)
        assertEquals(204, blocked.statusCode)
        assertEquals(0, blocked.data.available())
        assertNull(webView.client().shouldInterceptRequest(page, request("https://cdn.example/logo.png", mainFrame = false)))
    }

    // Renderer gone

    @Test
    fun aDeadRendererDestroysTheWebViewForgetsItAndClosesTheView() {
        val view1 = view(activity())
        val webView = checkNotNull(retainer.webView)
        val client = webView.client()

        assertTrue(client.onRenderProcessGone(webView, rendererDied()), "the app stays alive")

        assertEquals(1, host.closes)
        val terminated = logs.single { it.code == "webpaywall:process-terminated" }
        assertEquals(LogLevel.Warn, terminated.level)
        assertEquals(JsonObject(mapOf("action" to JsonPrimitive("close"))), terminated.context)
        assertTrue(shadowOf(webView).wasDestroyCalled())
        assertNull(retainer.webView)
        assertNull(retainer.document)
        assertFalse(retainer.page.loaded, "the next WebView loads from scratch")
        assertNull(view1.parent?.let { (it as android.view.ViewGroup).findWebView() }, "the dead WebView is out of the view")
    }

    @Test
    fun aDeadRendererKeepsTheScopeSoARedemptionStillLands() {
        view(activity())
        val webView = checkNotNull(retainer.webView)
        redeem(webView)
        assertTrue(transport.reached.await(10, TimeUnit.SECONDS))

        webView.client().onRenderProcessGone(webView, rendererDied())
        transport.open()
        Thread.sleep(300)
        idleMain()
        assertTrue(host.redemptions.isEmpty(), "no view yet")

        view(activity())
        awaitMain("the redemption") { host.redemptions.isNotEmpty() }
        assertEquals(1, host.redemptions.size)
    }

    @Test
    fun theViewAfterADeadRendererGetsAFreshWebView() {
        view(activity())
        val dead = checkNotNull(retainer.webView)
        dead.client().onRenderProcessGone(dead, rendererDied())

        view(activity())
        val fresh = checkNotNull(retainer.webView)
        assertNotSame(dead, fresh)
        assertTrue(shadowOf(fresh).lastLoadDataWithBaseURL != null, "the document was loaded again")
    }

    @Test
    fun aRendererThatDiesWhileDetachedIsForgottenWithoutClosingAnything() {
        val view = view(activity())
        val webView = checkNotNull(retainer.webView)
        detach(view)

        webView.client().onRenderProcessGone(webView, rendererDied())

        assertEquals(0, host.closes, "no view is showing it")
        assertEquals(
            JsonObject(mapOf("action" to JsonPrimitive("reload"))),
            logs.single { it.code == "webpaywall:process-terminated" }.context,
            "the next view loads the page afresh",
        )
        assertNull(retainer.webView)
        assertTrue(shadowOf(webView).wasDestroyCalled())
    }

    @Test
    fun forgetIgnoresAWebViewThatIsNotTheRetainedOne() {
        view(activity())
        val retained = checkNotNull(retainer.webView)
        retainer.forget(WebView(application))
        assertSame(retained, retainer.webView)
    }

    @Test
    fun withoutARetainerADeadRendererClosesTheView() {
        val view = view(activity(), retainer = null)
        val webView = findWebView(view)
        webView.client().onRenderProcessGone(webView, rendererDied())
        assertEquals(1, host.closes)
        assertTrue(shadowOf(webView).wasDestroyCalled())
        detach(view)
    }

    @Test
    fun withoutARetainerNavigationsAndPricesWorkOnThePage() {
        val view = view(activity(), retainer = null, priceProvider = prices())
        val webView = findWebView(view)
        webView.client().shouldOverrideUrlLoading(FakePage(application, "about:blank"), request("mos://close"))
        assertEquals(1, host.closes)

        webView.client().onPageFinished(webView, "about:blank")
        answerCollect(webView)
        awaitMain("the price fill") { checkNotNull(shadowOf(webView).lastEvaluatedJavascript).contains("4.99") }
        detach(view)
    }

    private fun findWebView(view: View): WebView = checkNotNull((view as android.view.ViewGroup).findWebView())

    private fun android.view.ViewGroup.findWebView(): WebView? =
        (0 until childCount).map(::getChildAt).filterIsInstance<WebView>().firstOrNull()

    /** True when [target] can be reached from [root] through fields of the SDK's own classes and its lambdas. */
    private fun reaches(root: Any, target: Any): Boolean {
        val seen = IdentityHashMap<Any, Boolean>()
        val queue = ArrayDeque<Any>().apply { add(root) }
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current === target) return true
            if (seen.put(current, true) != null) continue
            if (!current.javaClass.name.startsWith("com.monetizationos.sdk.android")) continue
            var type: Class<*>? = current.javaClass
            while (type != null && type.name.startsWith("com.monetizationos.sdk.android")) {
                for (field in type.declaredFields) {
                    if (field.type.isPrimitive || java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
                    field.isAccessible = true
                    field.get(current)?.let(queue::add)
                }
                type = type.superclass
            }
        }
        return false
    }
}
