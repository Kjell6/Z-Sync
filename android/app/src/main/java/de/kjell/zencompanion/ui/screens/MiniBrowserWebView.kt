package de.kjell.zencompanion.ui.screens

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Uri
import android.net.http.SslError
import android.os.Message
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.viewinterop.AndroidView
import de.kjell.zencompanion.ui.components.openURLExternally

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
    textZoomPercent: Int,
    onWebViewCreated: (WebView) -> Unit,
    onLoadingChange: (Boolean) -> Unit,
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
    onScrollChange: (scrollY: Int) -> Unit,
    onFindResult: (active: Int, total: Int) -> Unit,
) {
    // The factory runs once; read the live callbacks instead of the first values.
    val addressFocused by rememberUpdatedState(isAddressFocused)
    val textInputChange by rememberUpdatedState(onTextInputChange)
    val onPageStart by rememberUpdatedState(onPageStarted)
    val loadFailed by rememberUpdatedState(onLoadFailed)
    val rendererGone by rememberUpdatedState(onRendererGone)
    val linkUnavailable by rememberUpdatedState(onLinkUnavailable)

    // Native Android WebView
    AndroidView(
        factory = { ctx ->
            // The page a tapped link is loading. A certificate error reports only
            // its own address, and the WebView may still show the previous page.
            var mainFrameRequest: String? = null
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
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
                        // A failed load reports its error after this, so the
                        // new page always starts without the old panel.
                        onPageStart()
                        onLoadingChange(true)
                        onSiteThemeColorChange(null)
                        url?.let { onCurrentUrlChange(it) }
                        onCanGoBackChange(view?.canGoBack() == true)
                        onCanGoForwardChange(view?.canGoForward() == true)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        onLoadingChange(false)
                        url?.let {
                            onCurrentUrlChange(it)
                            if (!addressFocused) textInputChange(TextFieldValue(text = addressDisplay(it)))
                        }
                        view?.title?.let { onTitleChange(it) }
                        onCanGoBackChange(view?.canGoBack() == true)
                        onCanGoForwardChange(view?.canGoForward() == true)

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
                        view?.evaluateJavascript(js) { result ->
                            val parsed = parseCssColor(result)
                            if (parsed != null) {
                                onSiteThemeColorChange(parsed)
                            }
                        }
                        view?.evaluateJavascript(SCROLL_BINDING_SCRIPT, null)
                    }

                    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                        super.onReceivedError(view, request, error)
                        if (request?.isForMainFrame != true || error == null) return
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
                            loadFailed(BrowserPageError.INSECURE, url)
                        }
                    }

                    // Without this the whole app dies with the page's renderer.
                    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
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
                // The WebView has no download UI; hand web files to the system,
                // which opens them in the default browser's downloader. The page
                // controls this URL, so only http(s) leaves the app.
                setDownloadListener { url, _, _, _, _ ->
                    webUrlOrNull(url)?.let { openURLExternally(ctx, it) }
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
                        // Only a tap may replace the page. Windows a page opens on its own are dropped.
                        if (!isUserGesture) return false
                        val hit = view.hitTestResult
                        val destination = tappedLinkUrl(hit.type, hit.extra)
                        if (destination != null) {
                            view.loadUrl(destination)
                            return false
                        }
                        // A popup aimed at another app's scheme goes to that app,
                        // never into a window we cannot render.
                        if (hit.type == WebView.HitTestResult.SRC_ANCHOR_TYPE) {
                            val target = hit.extra?.trim().orEmpty()
                            if (target.isNotEmpty() && !loadsInWebView(target)) {
                                if (!openURLExternally(view.context, Uri.parse(target))) linkUnavailable()
                                return true
                            }
                        }
                        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                        val popup = WebView(view.context)
                        popup.settings.javaScriptEnabled = true
                        popup.webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                popupView: WebView,
                                request: WebResourceRequest,
                            ): Boolean {
                                val next = sameTabUrl(request.url?.toString()) ?: return false
                                view.loadUrl(next)
                                popupView.post { popupView.destroy() }
                                return true
                            }
                        }
                        transport.webView = popup
                        resultMsg.sendToTarget()
                        return true
                    }
                }

                setFindListener { active, total, _ -> onFindResult(active, total) }
                // A WebView scrolls its content internally, so its own
                // `setOnScrollChangeListener` never fires. The page reports its
                // scroll offset instead; only that int crosses the bridge.
                addJavascriptInterface(
                    ScrollBridge { scrollY -> post { onScrollChange(scrollY) } },
                    SCROLL_BRIDGE_NAME,
                )

                val loadTarget = formatBrowserInput(initialUrl ?: "")
                if (loadTarget.isNotEmpty()) {
                    if (isWebScheme(Uri.parse(loadTarget).scheme)) {
                        loadUrl(loadTarget)
                    } else if (!openURLExternally(ctx, Uri.parse(loadTarget))) {
                        // A saved or shared address that names another app's scheme.
                        post { linkUnavailable() }
                    }
                }
                onWebViewCreated(this)
            }
        },
        update = { view ->
            view.settings.textZoom = textZoomPercent
            val defaultUserAgent = view.tag as? String
            val desired = if (desktopSite) DesktopUserAgent else defaultUserAgent
            if (desired != null && view.settings.userAgentString != desired) {
                view.settings.userAgentString = desired
                view.reload()
            }
        },
        modifier = Modifier.fillMaxSize(),
        // Stop audio, video and timers when the browser closes.
        onRelease = { web ->
            web.stopLoading()
            web.destroy()
        },
    )
}

/** Receives the page's scroll offset. Called on a WebView thread. */
private class ScrollBridge(private val onScroll: (Int) -> Unit) {
    @JavascriptInterface
    fun update(scrollY: Int) = onScroll(scrollY)
}

private const val SCROLL_BRIDGE_NAME = "ZenScroll"

/** Binds the page's scroll offset to the bridge once per document. */
private const val SCROLL_BINDING_SCRIPT = """
    (function() {
      if (window.__zenScrollBound) return;
      window.__zenScrollBound = true;
      window.addEventListener('scroll', function() {
        try { ZenScroll.update(Math.round(window.scrollY)); } catch (e) {}
      }, { passive: true });
    })()
"""

internal fun addressDisplay(url: String): String {
    val host = runCatching { Uri.parse(url).host?.removePrefix("www.") }.getOrNull()
    return if (!host.isNullOrEmpty()) host else url
}

/**
 * A download address from the page, or null when it is not http(s).
 * Only web addresses leave the app, so a page cannot push `intent://`,
 * `javascript:` or any other scheme into an implicit `ACTION_VIEW`.
 */
private fun webUrlOrNull(raw: String?): String? {
    if (raw.isNullOrEmpty()) return null
    return when (Uri.parse(raw).scheme?.lowercase()) {
        "http", "https" -> raw
        else -> null
    }
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
