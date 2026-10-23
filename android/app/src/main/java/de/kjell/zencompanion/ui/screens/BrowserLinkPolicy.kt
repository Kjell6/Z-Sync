package de.kjell.zencompanion.ui.screens

import android.webkit.WebView

internal enum class BrowserLinkAction {
    LOAD_IN_WEBVIEW,
    OPEN_EXTERNALLY,
}

/**
 * Schemes the WebView renders itself: http(s) plus the page-internal ones.
 * Every other scheme belongs to another app, and letting the WebView try it
 * ends on its own `net::ERR_UNKNOWN_URL_SCHEME` page, which leaves the
 * browser stranded on a dead document.
 */
private val WEB_SCHEMES = setOf("http", "https", "about", "data", "blob", "file", "javascript")

/** True when the WebView may load this scheme at all. */
internal fun schemeLoadsInWebView(scheme: String?): Boolean =
    scheme?.lowercase() in WEB_SCHEMES

/** Same question for a whole URL, as typed into the bar or sent by a page. */
internal fun loadsInWebView(url: String): Boolean =
    schemeLoadsInWebView(url.trim().substringBefore(':', missingDelimiterValue = ""))

internal fun browserLinkAction(
    scheme: String?,
    isRedirect: Boolean,
    hasGesture: Boolean,
    resolvedPackage: String?,
    browserPackages: Set<String>,
    ownPackage: String,
): BrowserLinkAction {
    val normalizedScheme = scheme?.lowercase()
    if (!schemeLoadsInWebView(normalizedScheme)) {
        return BrowserLinkAction.OPEN_EXTERNALLY
    }
    if (normalizedScheme != "http" && normalizedScheme != "https") {
        return BrowserLinkAction.LOAD_IN_WEBVIEW
    }
    if (isRedirect || !hasGesture) {
        return BrowserLinkAction.LOAD_IN_WEBVIEW
    }
    if (resolvedPackage == null || resolvedPackage == ownPackage) {
        return BrowserLinkAction.LOAD_IN_WEBVIEW
    }
    if (resolvedPackage in browserPackages) {
        return BrowserLinkAction.LOAD_IN_WEBVIEW
    }
    return BrowserLinkAction.OPEN_EXTERNALLY
}

/** http(s) address a new window should load in the only tab. Blank windows stay put. */
internal fun sameTabUrl(raw: String?): String? {
    val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val scheme = trimmed.substringBefore(':', missingDelimiterValue = "").lowercase()
    if (scheme != "http" && scheme != "https") return null
    return trimmed
}

/**
 * Address a tapped new-window link should load in the only tab. Only plain links count:
 * for an image link the hit test reports the image, not the link, so the popup finds it.
 */
internal fun tappedLinkUrl(hitType: Int, extra: String?): String? {
    if (hitType != WebView.HitTestResult.SRC_ANCHOR_TYPE) return null
    return sameTabUrl(extra)
}
