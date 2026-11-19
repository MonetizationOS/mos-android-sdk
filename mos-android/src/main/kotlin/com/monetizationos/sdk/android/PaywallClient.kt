@file:OptIn(InternalMosApi::class)

package com.monetizationos.sdk.android

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.monetizationos.sdk.InternalMosApi
import com.monetizationos.sdk.MOS
import com.monetizationos.sdk.RedemptionResult
import com.monetizationos.sdk.webpaywall.WebPaywall
import com.monetizationos.sdk.webpaywall.WebPaywallNavigation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream

internal val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

/**
 * The URL `loadDataWithBaseURL(null, ...)` gives the paywall document (`about:blank`, or a `data:` URL on some
 * WebView builds), with or without a fragment. Anything else in the main frame is not the document.
 */
internal fun isDocumentUrl(url: String?): Boolean {
    if (url == null) return false
    val base = url.substringBefore('#')
    return base == "about:blank" || base.startsWith("data:", ignoreCase = true)
}

private fun isHttpUrl(url: String): Boolean =
    url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

/** Where a page's load and price-fill state lives: one per WebView, so it survives a view being replaced. */
internal class PageState {
    var loaded = false
        private set
    private var fill = Fill.Idle

    private enum class Fill { Idle, Running, Done }

    /** True the first time the document finishes loading. */
    fun markLoaded(): Boolean {
        if (loaded) return false
        loaded = true
        return true
    }

    /** True when a fill should start now: the page is loaded and no fill has started or finished. */
    fun startFill(): Boolean {
        if (!loaded || fill != Fill.Idle) return false
        fill = Fill.Running
        return true
    }

    fun fillDone() {
        fill = Fill.Done
    }
}

/**
 * What the client and the retainer need from the view currently showing the WebView. All of it runs on the main thread.
 */
internal interface PaywallHost {
    fun redeemed(result: RedemptionResult)
    fun openUrl(url: String)
    fun close()
    fun fillPrices()
    fun rendererGone(dead: WebView)
}

/**
 * The WebView's client. It is not an inner class: a retained WebView outlives its view, so this holds the
 * retainer's scope and the host lookup, never a view or an Activity. [host] returns the view showing the
 * WebView now, null while detached; navigations that need a host are dropped then.
 */
internal class PaywallClient(
    private val mos: MOS,
    private val scope: CoroutineScope,
    private val page: PageState,
    private val host: () -> PaywallHost?,
    private val deliver: (RedemptionResult) -> Unit,
    private val detachedGone: (WebView) -> Unit,
) : WebViewClient() {
    private val paywall = WebPaywall(mos)

    // Main thread only. A second tap while a redemption is in flight is ignored, so the host hears of one.
    private var redeeming = false

    // Android does not call this for a POST (a form submit); that reaches shouldInterceptRequest below.
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        when (val navigation = paywall.navigation(request.url.toString(), request.isForMainFrame)) {
            WebPaywallNavigation.Allow -> return false
            is WebPaywallNavigation.Redeem ->
                // A leaked remote page must never reach the SDK, so only the original document may redeem.
                if (isDocumentUrl(view.url)) redeem(navigation.token)
            WebPaywallNavigation.Close -> if (isDocumentUrl(view.url)) host()?.close()
            is WebPaywallNavigation.OpenUrl -> host()?.openUrl(navigation.url)
            is WebPaywallNavigation.Blocked -> Unit
        }
        return true
    }

    // A main-frame http(s) request that got this far (a POST form, which skips shouldOverrideUrlLoading) never
    // reaches the network. A 204 does not commit, so the paywall document stays and can still close and redeem.
    // Its URL goes to the app like a link's; this runs off the main thread, so the host is found on it.
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url.toString()
        if (!request.isForMainFrame || !isHttpUrl(url)) return null
        val navigation = paywall.navigation(url, isMainFrame = true)
        if (navigation is WebPaywallNavigation.OpenUrl) mainHandler.post { host()?.openUrl(navigation.url) }
        return WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    }

    // The only main-frame load that may commit is the paywall document itself; stop anything else that gets there.
    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        if (url == null || isDocumentUrl(url)) return
        view.stopLoading()
    }

    override fun onPageFinished(view: WebView, url: String?) {
        if (!isDocumentUrl(url) || !page.markLoaded()) return
        paywall.loaded()
        host()?.fillPrices()
    }

    // Returning true keeps the app alive.
    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        val current = host()
        paywall.processTerminated(closing = current != null)
        if (current != null) current.rendererGone(view) else detachedGone(view)
        return true
    }

    // Runs on the retainer's scope, not the view's, so a redemption survives the view being replaced.
    private fun redeem(token: String) {
        if (redeeming) return
        redeeming = true
        scope.launch {
            try {
                deliver(mos.redeem(token))
            } finally {
                mainHandler.post { redeeming = false }
            }
        }
    }
}
