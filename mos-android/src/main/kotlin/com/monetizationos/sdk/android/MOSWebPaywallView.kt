@file:OptIn(InternalMosApi::class)

package com.monetizationos.sdk.android

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.MutableContextWrapper
import android.net.Uri
import android.os.SystemClock
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.FrameLayout
import com.monetizationos.sdk.InternalMosApi
import com.monetizationos.sdk.MOS
import com.monetizationos.sdk.PriceProvider
import com.monetizationos.sdk.RedemptionResult
import com.monetizationos.sdk.webpaywall.WebPaywall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps one paywall's WebView, and its page state, across Activity recreation (rotation, fold, resize). Hold it
 * in a `ViewModel`, pass it to every [MOSWebPaywallView] for that paywall, and call [release] in `onCleared`.
 * Create, use and release it on the main thread.
 */
public class MOSWebPaywallRetainer {
    internal var webView: WebView? = null
    internal var context: MutableContextWrapper? = null
    internal var document: String? = null
    internal var page = PageState()

    // Everything below is read and written on the main thread only; deliver() hops there first.
    internal var attached: PaywallHost? = null
    internal val pending = ArrayDeque<RedemptionResult>()
    private var released = false

    // Outlives each view, so an in-flight redemption survives the recreation.
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Destroys the retained WebView and cancels pending work. Call when the paywall is gone for good. */
    public fun release() {
        released = true
        scope.cancel()
        mainHandler.removeCallbacksAndMessages(this)
        webView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
        }
        webView = null
        context = null
        document = null
        attached = null
        pending.clear()
    }

    /** Destroys a WebView whose renderer died and forgets it, keeping the scope so a pending redemption still lands. */
    internal fun forget(view: WebView) {
        if (webView !== view) return
        (view.parent as? ViewGroup)?.removeView(view)
        view.destroy()
        webView = null
        context = null
        document = null
        page = PageState()
    }

    /** A redemption finished, on any thread. It is handled on the main thread, in order with attach and detach. */
    internal fun deliver(result: RedemptionResult) {
        mainHandler.postAtTime({ handOver(result) }, this, SystemClock.uptimeMillis())
    }

    /** Makes [host] the one that receives redemptions, and gives it any that landed while none was attached. */
    internal fun attach(host: PaywallHost) {
        attached = host
        mainHandler.postAtTime({ handOver(null) }, this, SystemClock.uptimeMillis())
    }

    private fun handOver(result: RedemptionResult?) {
        if (released) return
        if (result != null) pending.addLast(result)
        val host = attached ?: return
        while (pending.isNotEmpty()) host.redeemed(pending.removeFirst())
    }
}

/**
 * Shows an `Experience.Web` HTML wall; the page reaches the app only by navigating (`mos://redeem`, `mos://close`,
 * http links). Callbacks run on the main thread. There is no close control, so a renderer crash calls [onClose].
 */
@SuppressLint("ViewConstructor")
public class MOSWebPaywallView(
    context: Context,
    html: String,
    private val mos: MOS,
    priceProvider: PriceProvider? = null,
    baseCss: String? = null,
    private val onRedemption: (RedemptionResult) -> Unit,
    private val onClose: () -> Unit,
    private val onOpenUrl: (String) -> Unit = { url -> openInBrowser(context, url) },
    private val retainer: MOSWebPaywallRetainer? = null,
) : FrameLayout(context) {
    private val paywall = WebPaywall(mos, priceProvider)

    // Work runs off the main thread; results come back through the main handler, so no main dispatcher is needed.
    private val scope = retainer?.scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val webView: WebView
    private val page: PageState
    private var destroyed = false

    // What the client and the retainer call. A separate object, so none of it is API on the view.
    private val host = object : PaywallHost {
        override fun redeemed(result: RedemptionResult) = onRedemption(result)

        override fun openUrl(url: String) = onOpenUrl(url)

        override fun close() = onClose()

        override fun fillPrices() = startPriceFill()

        override fun rendererGone(dead: WebView) = onRendererGone(dead)
    }

    init {
        val document = paywall.document(html, baseCss)
        val retained = retainer?.webView?.takeIf { retainer.document == document }
        if (retained != null) {
            // Same page: move the live WebView into this view instead of reloading it.
            (retained.parent as? ViewGroup)?.removeView(retained)
            retainer.context?.baseContext = context
            webView = retained
            page = retainer.page
        } else {
            retainer?.webView?.destroy()
            val wrapper = MutableContextWrapper(context)
            webView = WebView(wrapper)
            page = PageState()
            configure(webView)
            webView.webViewClient = client(retainer)
            if (retainer != null) {
                retainer.webView = webView
                retainer.context = wrapper
                retainer.document = document
                retainer.page = page
            }
            // A null base URL gives the document an opaque origin.
            webView.loadDataWithBaseURL(null, document, "text/html", "utf-8", null)
        }
        addView(webView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        if (retainer != null) {
            retainer.attach(host)
            // A page that finished while no view was attached, or whose fill never got to run, still needs it.
            startPriceFill()
        }
    }

    /**
     * Without a retainer: cancels pending work (an in-flight redemption still completes server-side) and
     * releases the web view. With one: detaches the web view and keeps it for the next view.
     */
    public fun destroy() {
        removeView(webView)
        if (retainer == null) {
            if (destroyed) return
            destroyed = true
            scope.cancel()
            webView.destroy()
            return
        }
        // A newer view may have adopted the WebView first (Compose creates before it releases); leave it be.
        if (retainer.attached !== host) return
        retainer.attached = null
        // Let go of the Activity until the next view adopts the WebView. The WebView's client holds no view.
        retainer.context?.baseContext = context.applicationContext
    }

    private fun onRendererGone(dead: WebView) {
        if (dead !== webView) return
        removeView(webView)
        if (retainer == null) {
            destroy()
        } else {
            if (retainer.attached === host) retainer.attached = null
            retainer.forget(webView)
        }
        onClose()
    }

    // Retained: the client is the retainer's, so it must not reach this view while another has the WebView.
    // Not retained: the view and its WebView live and die together, so the client may refer to the view.
    private fun client(retainer: MOSWebPaywallRetainer?): PaywallClient =
        if (retainer != null) {
            retainer.client(mos, page)
        } else {
            PaywallClient(
                mos, scope, page,
                // A link handed over from another thread can land after destroy().
                host = { if (destroyed) null else host },
                deliver = { result -> mainHandler.post { if (!destroyed) onRedemption(result) } },
                detachedGone = {},
            )
        }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(view: WebView) {
        view.settings.apply {
            javaScriptEnabled = true // the scoped-CSS polyfill and the price fill need it
            allowFileAccess = false
            allowContentAccess = false
            setGeolocationEnabled(false)
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
    }

    // Starts at most once per page. The script is evaluated through the main handler on the WebView itself,
    // not through this view's post(): after a rotation this view is gone for good, and a post() to a view that is
    // never attached again never runs.
    private fun startPriceFill() {
        if (!page.startFill()) return
        val view = webView
        val paywall = paywall
        val page = page
        val scope = scope
        view.evaluateJavascript(WebPaywall.COLLECT_PRODUCT_IDS_SCRIPT) { collected ->
            scope.launch {
                val script = paywall.priceScript(collected)
                mainHandler.post {
                    view.evaluateJavascript(script) { result ->
                        page.fillDone()
                        paywall.pricesFilled(result)
                    }
                }
            }
        }
    }
}

/** The retainer's own client: finds the view showing the WebView through [MOSWebPaywallRetainer.attached]. */
internal fun MOSWebPaywallRetainer.client(mos: MOS, page: PageState): PaywallClient =
    PaywallClient(
        mos, scope, page,
        host = { attached },
        deliver = ::deliver,
        detachedGone = ::forget,
    )

private fun openInBrowser(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
    if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // No browser installed: nothing sensible to do.
    }
}
