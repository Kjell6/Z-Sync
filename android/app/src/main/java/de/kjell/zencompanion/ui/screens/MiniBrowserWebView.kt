package de.kjell.zencompanion.ui.screens

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
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
    onScrollChange: (scrollY: Int) -> Unit,
    onFindResult: (active: Int, total: Int) -> Unit,
) {
    // The factory runs once; read the live focus flag instead of the first value.
    val focused = rememberUpdatedState(isAddressFocused)

    AndroidView(
        factory = { ctx ->
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
                    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    // target=_blank and window.open do nothing unless this is on.
                    setSupportMultipleWindows(true)
                    javaScriptCanOpenWindowsAutomatically = true
                }
                // The stock user agent, restored when desktop mode is turned off.
                tag = settings.userAgentString
                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
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
                            if (!focused.value) {
                                val host = runCatching { Uri.parse(it).host?.removePrefix("www.") }.getOrNull()
                                val display = if (!host.isNullOrEmpty()) host else it
                                onTextInputChange(TextFieldValue(text = display))
                            }
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

                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        if (view == null || request == null) return false
                        val context = view.context
                        val action = browserLinkAction(
                            scheme = request.url.scheme,
                            isRedirect = request.isRedirect,
                            hasGesture = request.hasGesture(),
                            resolvedPackage = resolveHandlerPackage(context, request.url),
                            browserPackages = browserPackages(context),
                            ownPackage = context.packageName,
                        )
                        if (action == BrowserLinkAction.OPEN_EXTERNALLY) {
                            return openURLExternally(context, request.url)
                        }
                        return false
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        onProgressChange(newProgress / 100f)
                    }

                    override fun onReceivedTitle(view: WebView?, title: String?) {
                        title?.let { onTitleChange(it) }
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
                    loadUrl(loadTarget)
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
