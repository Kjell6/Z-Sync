package de.kjell.zencompanion.ui.screens

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.HttpAuthHandler
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.viewinterop.AndroidView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import de.kjell.zencompanion.data.ContentBlocking
import de.kjell.zencompanion.data.ContentBlockingSettings
import de.kjell.zencompanion.data.SitePermission
import de.kjell.zencompanion.ui.browser.BrowserDownload
import de.kjell.zencompanion.ui.browser.BrowserPrompt
import de.kjell.zencompanion.ui.browser.MiniBrowserZoom
import de.kjell.zencompanion.ui.browser.PageBlocker
import de.kjell.zencompanion.ui.browser.grantableResources
import de.kjell.zencompanion.ui.browser.sitePermissionsFor
import de.kjell.zencompanion.ui.components.openURLExternally
import de.kjell.zencompanion.ui.theme.LocalZenColors
import java.io.ByteArrayInputStream
import java.util.Locale

/** Chrome-like desktop user agent for the "Request Desktop Site" toggle. */
private const val DesktopUserAgent =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun MiniBrowserWebView(
    initialUrl: String?,
    isAddressFocused: Boolean,
    desktopSite: Boolean,
    /** 1.0 = 100%. Page zoom for the page's document. */
    pageZoom: Float,
    /** The back and forward list to come back to, if the page has one. */
    savedHistory: () -> Bundle?,
    /** The `window.open` this WebView answers, if it is a popup's page. */
    takePopup: () -> Message?,
    onWebViewCreated: (WebView) -> Unit,
    /** The WebView is about to be destroyed. */
    onWebViewReleased: (WebView) -> Unit,
    /** A tapped new-window link: its window becomes a new page. */
    onOpenPopup: (Message) -> Unit,
    /** `window.close()`. */
    onCloseWindow: () -> Unit,
    /** Long press on a link or an image. */
    onHitLongPress: (BrowserHitMenu) -> Unit,
    onLoadingChange: (Boolean) -> Unit,
    /** The document finished loading. */
    onPageLoaded: () -> Unit,
    /** The reader pulled the page down: reload it as the bar's button does. */
    onPullRefresh: () -> Unit,
    /** False while something over the page owns the gestures. */
    pullToRefreshEnabled: Boolean,
    onSiteThemeColorChange: (Color?) -> Unit,
    onCurrentUrlChange: (String) -> Unit,
    onCanGoBackChange: (Boolean) -> Unit,
    onCanGoForwardChange: (Boolean) -> Unit,
    onTextInputChange: (TextFieldValue) -> Unit,
    onTitleChange: (String) -> Unit,
    onProgressChange: (Float) -> Unit,
    /** A fresh document started: the chrome comes back and the error panel goes. */
    onPageStarted: () -> Unit,
    onLoadFailed: (BrowserPageError, String) -> Unit,
    onRendererGone: (String?) -> Unit,
    /** A link nobody can take: no app handles it, or the page never loaded. */
    onLinkUnavailable: () -> Unit,
    /** What the page asks of the reader or the system. */
    requests: PageRequests,
    onScrollChange: (scrollY: Int) -> Unit,
    onFindResult: (active: Int, total: Int) -> Unit,
    /** Decides which requests get an empty answer, and counts them. */
    blocker: PageBlocker,
    /** The autoconsent bundle; the script around it follows the switches. */
    consentBundle: String?,
    /** Passed so a change recomposes; the current value is read from [ContentBlocking]. */
    blockingSettings: ContentBlockingSettings,
    /**
     * Bumped to reload after a blocking change. The reload waits until the
     * new switches and script are in place, so it can't load with the old ones.
     */
    blockingReload: Int,
) {
    // The factory runs once; read the live callbacks instead of the first values.
    val addressFocused by rememberUpdatedState(isAddressFocused)
    val textInputChange by rememberUpdatedState(onTextInputChange)
    val onPageStart by rememberUpdatedState(onPageStarted)
    val loadFailed by rememberUpdatedState(onLoadFailed)
    val rendererGone by rememberUpdatedState(onRendererGone)
    val linkUnavailable by rememberUpdatedState(onLinkUnavailable)
    val openPopup by rememberUpdatedState(onOpenPopup)
    val closeWindow by rememberUpdatedState(onCloseWindow)
    val hitLongPress by rememberUpdatedState(onHitLongPress)
    val pullRefresh by rememberUpdatedState(onPullRefresh)
    val webViewReleased by rememberUpdatedState(onWebViewReleased)
    val consent = remember { ConsentScriptSlot(handledReload = blockingReload) }
    val pageZoomSlot = remember { PageZoomSlot() }
    // The indicator sits on the page, so it takes the app's own ink and paper.
    val zen = LocalZenColors.current

    // Native Android WebView, inside the platform's pull-to-refresh indicator.
    AndroidView(
        factory = { ctx ->
            // The page a tapped link is loading. A certificate error reports only
            // its own address, and the WebView may still show the previous page.
            var mainFrameRequest: String? = null
            val page = BrowserPullRefresh(ctx)
            val webView = WebView(ctx)
            page.addView(
                webView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            page.webView = webView
            page.setOnRefreshListener {
                // A page that never reports back must not keep the spinner going.
                page.beginRefreshTimeout()
                pullRefresh()
            }
            webView.apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                // Logins set up on one site often read their cookie from another.
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                // Password managers fill the page through the system's autofill.
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
                requests.installPageFileBridge(this)
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    setSupportZoom(true)
                    builtInZoomControls = true
                    displayZoomControls = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    // target=_blank and window.open do nothing unless this is on.
                    setSupportMultipleWindows(true)
                    javaScriptCanOpenWindowsAutomatically = true
                }
                // The stock user agent, restored when desktop mode is turned off.
                tag = settings.userAgentString
                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        // Back and forward can skip the main-frame request.
                        url?.let { blocker.pageStarted(it, isReload = false) }
                        // Leaving the page shown as the original: this document
                        // started with the script that skipped it, so run the
                        // current one now as well.
                        view?.let { consent.sync(it, blocker, consentBundle, runNow = true) }
                        // A failed load reports its error after this, so the
                        // new page always starts without the old panel.
                        onPageStart()
                        onLoadingChange(true)
                        url?.let { onCurrentUrlChange(it) }
                        onCanGoBackChange(view?.canGoBack() == true)
                        onCanGoForwardChange(view?.canGoForward() == true)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        // However the load ended, the pull's indicator is done.
                        page.finishRefresh()
                        // Whatever the document-start script did, the finished
                        // document gets the zoom the reader picked.
                        view?.let(pageZoomSlot::applyNow)
                        // The jar otherwise reaches disk on its own schedule, and
                        // a sign-in must outlast the app being killed right after.
                        CookieManager.getInstance().flush()
                        onLoadingChange(false)
                        url?.let {
                            onCurrentUrlChange(it)
                            if (!addressFocused) textInputChange(TextFieldValue(text = addressDisplay(it)))
                        }
                        view?.title?.let { onTitleChange(it) }
                        onCanGoBackChange(view?.canGoBack() == true)
                        onCanGoForwardChange(view?.canGoForward() == true)
                        onPageLoaded()

                        // Extract theme-color / background from web page DOM
                        val js = """
                            (function() {
                                var meta = document.querySelector('meta[name="theme-color"]');
                                if (meta && meta.content) return meta.content;
                                var bg = window.getComputedStyle(document.body).backgroundColor;
                                if (bg && bg !== 'rgba(0, 0, 0, 0)' && bg !== 'transparent') return bg;
                                var htmlBg = window.getComputedStyle(document.documentElement).backgroundColor;
                                if (htmlBg && htmlBg !== 'rgba(0, 0, 0, 0)' && htmlBg !== 'transparent') return htmlBg;
                                return null;
                            })()
                        """.trimIndent()
                        // The previous page's color stays until this one has its
                        // own: resetting at the start would flash the default
                        // on every reload. A page without a color drops it here.
                        view?.evaluateJavascript(js) { result ->
                            onSiteThemeColorChange(parseCssColor(result))
                        }
                        view?.evaluateJavascript(SCROLL_BINDING_SCRIPT, null)
                    }

                    // Runs on a WebView thread for every request the page makes.
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        request ?: return null
                        if (!blocker.shouldBlock(request.url, request.isForMainFrame)) return null
                        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                    }

                    // Older WebViews can't add a script at document start; the
                    // cookie banner and page zoom then run once the page is visible.
                    override fun onPageCommitVisible(view: WebView?, url: String?) {
                        super.onPageCommitVisible(view, url)
                        if (!consent.atDocumentStart) consent.current?.let { view?.evaluateJavascript(it, null) }
                        if (!pageZoomSlot.atDocumentStart) view?.let(pageZoomSlot::applyNow)
                    }

                    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                        super.onReceivedError(view, request, error)
                        if (request?.isForMainFrame != true || error == null) return
                        // A failed load ends the pull's wait just like a finished one.
                        page.finishRefresh()
                        val kind = pageErrorFor(
                            errorCode = error.errorCode,
                            description = error.description?.toString(),
                            isOnline = hasValidatedNetwork(ctx.getSystemService(ConnectivityManager::class.java)),
                        ) ?: return
                        loadFailed(kind, request.url.toString())
                    }

                    // The default cancels the load and shows nothing. Cancel too,
                    // and explain it when the page itself is the bad one.
                    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                        handler?.cancel()
                        val url = error?.url ?: return
                        if (isPageCertificateError(url, listOf(view?.url, mainFrameRequest))) {
                            page.finishRefresh()
                            loadFailed(BrowserPageError.INSECURE, url)
                        }
                    }

                    override fun onReceivedHttpAuthRequest(
                        view: WebView?,
                        handler: HttpAuthHandler?,
                        host: String?,
                        realm: String?,
                    ) {
                        handler ?: return
                        requests.httpLogin(
                            BrowserPrompt.HttpLogin(
                                host = host.orEmpty(),
                                proceed = { user, password -> handler.proceed(user, password) },
                                cancel = { handler.cancel() },
                            ),
                        )
                    }

                    // Without this the whole app dies with the page's renderer.
                    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                        page.finishRefresh()
                        rendererGone(view?.url)
                        return true
                    }

                    // Single-page apps change the URL without a page load.
                    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                        super.doUpdateVisitedHistory(view, url, isReload)
                        if (url == null) return
                        onCurrentUrlChange(url)
                        if (!addressFocused) textInputChange(TextFieldValue(text = addressDisplay(url)))
                        onCanGoBackChange(view?.canGoBack() == true)
                        onCanGoForwardChange(view?.canGoForward() == true)
                    }

                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        if (view == null || request == null) return false
                        if (request.isForMainFrame) mainFrameRequest = request.url.toString()
                        val context = view.context
                        val action = browserLinkAction(
                            scheme = request.url.scheme,
                            isRedirect = request.isRedirect,
                            hasGesture = request.hasGesture(),
                            isMainFrame = request.isForMainFrame,
                            resolvedPackage = resolveHandlerPackage(context, request.url),
                            browserPackages = browserPackages(context),
                            ownPackage = context.packageName,
                        )
                        return when (action) {
                            BrowserLinkAction.OPEN_EXTERNALLY -> {
                                if (!openURLExternally(context, request.url)) linkUnavailable()
                                true
                            }
                            BrowserLinkAction.BLOCK -> true
                            BrowserLinkAction.LOAD_IN_WEBVIEW -> false
                        }
                    }
                }
                // The WebView has no download UI of its own. The page's own
                // downloads ask the reader first; the page's question queue owns
                // the answer, so it can wait behind the page's other questions.
                setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                    if (url.isNullOrEmpty()) return@setDownloadListener
                    requests.askDownload(
                        this,
                        BrowserDownload(url, userAgent, contentDisposition, mimeType, referer = this.url),
                    )
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        onProgressChange(newProgress / 100f)
                    }

                    override fun onReceivedTitle(view: WebView?, title: String?) {
                        title?.let { onTitleChange(it) }
                    }

                    override fun onCreateWindow(
                        view: WebView,
                        isDialog: Boolean,
                        isUserGesture: Boolean,
                        resultMsg: Message,
                    ): Boolean {
                        // Windows a page opens on its own are dropped.
                        if (!isUserGesture) return false
                        val hit = view.hitTestResult
                        // A popup aimed at another app's scheme goes to that app,
                        // never into a window we cannot render.
                        if (hit.type == WebView.HitTestResult.SRC_ANCHOR_TYPE) {
                            val target = hit.extra?.trim().orEmpty()
                            if (target.isNotEmpty() && !loadsInWebView(target)) {
                                if (!openURLExternally(view.context, Uri.parse(target))) linkUnavailable()
                                return true
                            }
                        }
                        // A real window: the page keeps its `window.opener`, and a
                        // blank popup can still be given its address afterwards.
                        // The message is answered by the new page's WebView.
                        openPopup(resultMsg)
                        return true
                    }

                    override fun onCloseWindow(window: WebView?) {
                        closeWindow()
                    }

                    override fun onPermissionRequest(request: PermissionRequest) {
                        val kinds = sitePermissionsFor(request.resources)
                        if (kinds.isEmpty()) {
                            request.deny()
                            return
                        }
                        val resources = grantableResources(request.resources)
                        requests.askPermission(kinds, request.origin.host.orEmpty()) { granted ->
                            if (granted) request.grant(resources) else request.deny()
                        }
                    }

                    override fun onGeolocationPermissionsShowPrompt(
                        origin: String?,
                        callback: GeolocationPermissions.Callback?,
                    ) {
                        origin ?: return
                        callback ?: return
                        val host = Uri.parse(origin).host.orEmpty()
                        // Not retained: the browser's own answers are the only record.
                        requests.askPermission(listOf(SitePermission.LOCATION), host) { granted ->
                            callback.invoke(origin, granted, false)
                        }
                    }

                    override fun onShowFileChooser(
                        webView: WebView?,
                        filePathCallback: ValueCallback<Array<Uri>>?,
                        fileChooserParams: FileChooserParams?,
                    ): Boolean {
                        filePathCallback ?: return false
                        fileChooserParams ?: return false
                        return requests.chooseFiles(filePathCallback, fileChooserParams)
                    }

                    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                        view ?: return
                        callback ?: return
                        requests.showFullscreen(view, callback)
                    }

                    override fun onHideCustomView() = requests.hideFullscreen()
                }

                setOnLongClickListener {
                    val hit = hitTestResult
                    when (hit.type) {
                        WebView.HitTestResult.SRC_ANCHOR_TYPE -> {
                            val link = tappedLinkUrl(hit.type, hit.extra)
                                ?: return@setOnLongClickListener false
                            hitLongPress(BrowserHitMenu(link = link))
                            true
                        }
                        WebView.HitTestResult.IMAGE_TYPE -> {
                            val image = hit.extra?.trim().orEmpty()
                            val menu = BrowserHitMenu(image = image)
                            // An address no action can take opens no empty menu.
                            if (image.isEmpty() || !menu.hasImageActions) {
                                return@setOnLongClickListener false
                            }
                            hitLongPress(menu)
                            true
                        }
                        WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                            // The hit test reports the image; the link has to be asked for.
                            // Both come back together, so one menu carries link and image.
                            val image = hit.extra?.trim()?.takeIf { it.isNotEmpty() }
                            val answer = Handler(Looper.getMainLooper()) { message ->
                                val menu = BrowserHitMenu(
                                    link = sameTabUrl(message.data.getString("url")),
                                    image = image,
                                )
                                if (menu.link != null || menu.hasImageActions) hitLongPress(menu)
                                true
                            }
                            requestFocusNodeHref(answer.obtainMessage())
                            true
                        }
                        else -> false
                    }
                }

                setFindListener { active, total, _ -> onFindResult(active, total) }
                // A WebView scrolls its content internally, so its own
                // `setOnScrollChangeListener` never fires. The page reports its
                // scroll offset instead; only that int crosses the bridge. It
                // also says whether this touch may pull the page down.
                addJavascriptInterface(
                    ScrollBridge(
                        onScroll = { scrollY -> post { onScrollChange(scrollY) } },
                        onPull = { allowed -> post { page.pageAllowsPull = allowed } },
                    ),
                    SCROLL_BRIDGE_NAME,
                )

                // Before the first load, which happens ahead of `update`.
                consent.sync(this, blocker, consentBundle, runNow = false)
                val loadTarget = formatBrowserInput(initialUrl ?: "")
                val popup = takePopup()
                val history = savedHistory()
                if (popup != null) {
                    (popup.obj as? WebView.WebViewTransport)?.webView = this
                    popup.sendToTarget()
                } else if (history != null && restoreState(history) != null) {
                    // Back on its own page, with its Back and Forward.
                } else if (loadTarget.isNotEmpty()) {
                    if (isWebScheme(Uri.parse(loadTarget).scheme)) {
                        loadUrl(loadTarget)
                    } else if (!openURLExternally(ctx, Uri.parse(loadTarget))) {
                        // A saved or shared address that names another app's scheme.
                        post { linkUnavailable() }
                    }
                }
            }
            onWebViewCreated(webView)
            page
        },
        update = { page ->
            val view = page.webView
            // With a sheet or a panel over the page, a pull belongs to that.
            page.isEnabled = pullToRefreshEnabled
            page.setRefreshColors(circle = zen.background.toArgb(), arrow = zen.ink.toArgb())
            pageZoomSlot.sync(view, pageZoom)
            consent.sync(view, blocker, consentBundle, runNow = false)
            if (blockingReload != consent.handledReload) {
                consent.handledReload = blockingReload
                view.reload()
            }
            val defaultUserAgent = view.tag as? String
            val desired = if (desktopSite) DesktopUserAgent else defaultUserAgent
            if (desired != null && view.settings.userAgentString != desired) {
                view.settings.userAgentString = desired
                view.reload()
            }
        },
        modifier = Modifier.fillMaxSize(),
        // Stop audio, video and timers when the browser closes.
        onRelease = { page ->
            val web = page.webView
            webViewReleased(web)
            web.stopLoading()
            web.destroy()
        },
    )
}

/**
 * The page's WebView inside the platform's pull-to-refresh indicator.
 *
 * A WebView scrolls its content itself and never says whether it can scroll
 * up. The page answers that over the scroll bridge for every touch, so a pull
 * only refreshes at its very top and never inside a map, a chat list or any
 * other element that scrolls on its own.
 */
internal class BrowserPullRefresh(context: Context) : SwipeRefreshLayout(context) {
    lateinit var webView: WebView

    /** Whether the page allowed the touch under way to pull. It is reset at every touch down. */
    @Volatile
    var pageAllowsPull = false

    private val endRefresh = Runnable { isRefreshing = false }
    private var circleColor: Int? = null
    private var arrowColor: Int? = null

    init {
        setOnChildScrollUpCallback { _, _ ->
            // "The child can scroll up, do not refresh" unless both hold.
            !(pageAllowsPull && webView.scrollY == 0)
        }
    }

    /** A pull started a load: the indicator goes when it ends, or after ten seconds. */
    fun beginRefreshTimeout() {
        removeCallbacks(endRefresh)
        postDelayed(endRefresh, REFRESH_TIMEOUT_MS)
    }

    /** The page finished or failed: nothing is left to wait for. */
    fun finishRefresh() {
        removeCallbacks(endRefresh)
        if (isRefreshing) isRefreshing = false
    }

    fun setRefreshColors(circle: Int, arrow: Int) {
        if (circle == circleColor && arrow == arrowColor) return
        circleColor = circle
        arrowColor = arrow
        setProgressBackgroundColorSchemeColor(circle)
        setColorSchemeColors(arrow)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // The page has to allow this very touch: a page that allowed the last
        // one says nothing about this one.
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) pageAllowsPull = false
        return super.onInterceptTouchEvent(ev)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(endRefresh)
        super.onDetachedFromWindow()
    }

    private companion object {
        const val REFRESH_TIMEOUT_MS = 10_000L
    }
}

/** Receives the page's scroll offset and pull permission. Called on a WebView thread. */
private class ScrollBridge(
    private val onScroll: (Int) -> Unit,
    private val onPull: (Boolean) -> Unit,
) {
    @JavascriptInterface
    fun update(scrollY: Int) = onScroll(scrollY)

    @JavascriptInterface
    fun pull(allowed: Boolean) = onPull(allowed)
}

private const val SCROLL_BRIDGE_NAME = "ZenScroll"

/**
 * Binds the page's scroll offset and its pull permission to the bridge once
 * per document. A pull is allowed only at the document's own top, with no
 * scrolled scroller under the finger and no overscroll claimed by the page.
 */
private const val SCROLL_BINDING_SCRIPT = """
    (function() {
      if (window.__zenScrollBound) return;
      window.__zenScrollBound = true;
      window.addEventListener('scroll', function() {
        try { ZenScroll.update(Math.round(window.scrollY)); } catch (e) {}
      }, { passive: true });

      function overscrollAllows(style) {
        var value = style.overscrollBehaviorY || style.getPropertyValue('overscroll-behavior-y');
        return value !== 'contain' && value !== 'none';
      }

      function mayPull(target) {
        if (window.scrollY > 0) return false;
        // A map, a chat list or a web app scrolls inside an element of its own;
        // pulling there belongs to that element, not to the page.
        for (var node = target; node && node !== document.documentElement; node = node.parentNode) {
          if (node.nodeType !== 1) continue;
          var style = getComputedStyle(node);
          var scrollable = (style.overflowY === 'auto' || style.overflowY === 'scroll') &&
            node.scrollHeight > node.clientHeight;
          if (scrollable && node.scrollTop > 0) return false;
        }
        if (!overscrollAllows(getComputedStyle(document.documentElement))) return false;
        if (document.body && !overscrollAllows(getComputedStyle(document.body))) return false;
        return true;
      }

      document.addEventListener('touchstart', function(event) {
        try { ZenScroll.pull(mayPull(event.target)); } catch (e) {}
      }, { passive: true, capture: true });
    })()
"""

internal fun addressDisplay(url: String): String {
    val host = runCatching { Uri.parse(url).host?.removePrefix("www.") }.getOrNull()
    return if (!host.isNullOrEmpty()) host else url
}

@Suppress("DEPRECATION")
private fun resolveHandlerPackage(context: Context, uri: Uri): String? =
    context.packageManager
        .resolveActivity(Intent(Intent.ACTION_VIEW, uri), PackageManager.MATCH_DEFAULT_ONLY)
        ?.activityInfo
        ?.packageName

@Suppress("DEPRECATION")
private fun browserPackages(context: Context): Set<String> {
    val packageManager = context.packageManager
    val packages = packageManager.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER),
        0,
    ).mapTo(mutableSetOf()) { it.activityInfo.packageName }
    packageManager
        .resolveActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(BROWSER_PROBE_URL)),
            PackageManager.MATCH_DEFAULT_ONLY,
        )
        ?.activityInfo
        ?.packageName
        ?.let(packages::add)
    return packages
}

private const val BROWSER_PROBE_URL = "https://example.com"

/**
 * The cookie banner script added to every document the WebView loads. Swapped
 * whenever the switches change, so a site turned off stops getting it.
 */
private class ConsentScriptSlot(var handledReload: Int) {
    private var handler: ScriptHandler? = null
    private var key: Pair<ContentBlockingSettings, String?>? = null

    /** The script for the switches last synced, or null for none. */
    var current: String? = null
        private set

    val atDocumentStart: Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    /**
     * Hands the current switches to [blocker] and swaps the script when they
     * changed. Read from [ContentBlocking] rather than Compose state, which can
     * lag a frame behind a switch the user just changed. [runNow] also runs a
     * changed script in the document that has already started.
     */
    fun sync(view: WebView, blocker: PageBlocker, bundle: String?, runNow: Boolean) {
        val settings = ContentBlocking.settings.value
        blocker.settings = settings
        val wanted = settings to blocker.originalHost
        if (wanted == key) return
        key = wanted
        current = blocker.consentScript(bundle, settings)
        if (atDocumentStart) {
            handler?.remove()
            handler = current?.let { WebViewCompat.addDocumentStartJavaScript(view, it, setOf("*")) }
        }
        if (runNow) current?.let { view.evaluateJavascript(it, null) }
    }
}

/**
 * Page zoom for the mini browser. CSS `zoom` on the root element is the one
 * thing that reflows the way a browser's page zoom does: the layout viewport
 * narrows, text wraps earlier, images scale with it and nothing runs off the
 * screen. `WebSettings.textZoom` is no substitute - it grows fonts only and
 * leaves images and spacing at their size.
 *
 * iOS port: `PageZoomScript.source(for:)` in `Browser/MiniBrowserModel.swift`
 * builds the same function.
 */
internal object PageZoomScript {
    /** True when the zoom is 100%: the page keeps its own layout. */
    fun isIdentity(zoom: Float): Boolean = kotlin.math.abs(zoom - MiniBrowserZoom.default) <= 0.001f

    /** Applies [zoom] (1.0 = 100%) to the document that is already loaded. */
    fun source(zoom: Float): String {
        val body = if (isIdentity(zoom)) {
            "root.style.removeProperty('zoom');"
        } else {
            "root.style.zoom = '${String.format(Locale.US, "%.2f", zoom)}';"
        }
        return """
            (function () {
              // Only the top document: an embedded frame is already scaled with
              // its parent's layout, and zooming it again would double the size.
              try {
                if (window.self !== window.top) return;
              } catch (e) {
                return;
              }
              function apply() {
                var root = document.documentElement;
                if (!root) return;
                $body
              }
              if (document.documentElement) apply();
              else document.addEventListener('DOMContentLoaded', apply, false);
            })()
        """.trimIndent()
    }
}

/**
 * The page zoom script added to every document, swapped when the zoom changes
 * so a page that loads while zoomed never paints at 100% first.
 */
private class PageZoomSlot {
    private var handler: ScriptHandler? = null
    private var applied: Float? = null

    val atDocumentStart: Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    /** Follows the zoom the state asks for. Runs it on the page that is up. */
    fun sync(view: WebView, zoom: Float) {
        val previous = applied
        if (previous != null && kotlin.math.abs(previous - zoom) <= 0.001f) return
        applied = zoom
        if (atDocumentStart) {
            handler?.remove()
            handler = if (PageZoomScript.isIdentity(zoom)) {
                null
            } else {
                WebViewCompat.addDocumentStartJavaScript(view, PageZoomScript.source(zoom), setOf("*"))
            }
        }
        applyNow(view)
    }

    fun applyNow(view: WebView) {
        val zoom = applied ?: return
        view.evaluateJavascript(PageZoomScript.source(zoom), null)
    }
}
