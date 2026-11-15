package de.kjell.zencompanion.ui.screens

import android.net.Uri
import android.webkit.WebView
import de.kjell.zencompanion.ui.browser.isPageLocalDownload
import de.kjell.zencompanion.ui.browser.isWebDownload

internal enum class BrowserLinkAction {
    LOAD_IN_WEBVIEW,
    OPEN_EXTERNALLY,

    /** A page tried to open another app without a tap; drop it, as Chrome does. */
    BLOCK,
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

/** True when a URL with [scheme] loads in the WebView instead of another app. */
internal fun isWebScheme(scheme: String?): Boolean = scheme == null || scheme.lowercase() in WEB_SCHEMES

internal fun browserLinkAction(
    scheme: String?,
    isRedirect: Boolean,
    hasGesture: Boolean,
    isMainFrame: Boolean,
    resolvedPackage: String?,
    browserPackages: Set<String>,
    ownPackage: String,
): BrowserLinkAction {
    val normalizedScheme = scheme?.lowercase()
    if (normalizedScheme == null || normalizedScheme !in WEB_SCHEMES) {
        // Another app opens only from a tap in the main frame; a tap inside an
        // iframe is dropped, matching the iOS policy.
        return if (hasGesture && isMainFrame) {
            BrowserLinkAction.OPEN_EXTERNALLY
        } else {
            BrowserLinkAction.BLOCK
        }
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

/** The http(s) address of a link, or null for anything else. */
internal fun sameTabUrl(raw: String?): String? {
    val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val scheme = trimmed.substringBefore(':', missingDelimiterValue = "").lowercase()
    if (scheme != "http" && scheme != "https") return null
    return trimmed
}

/**
 * The web address of a link under the finger. Only plain links count:
 * for an image link the hit test reports the image, not the link, so the popup finds it.
 */
internal fun tappedLinkUrl(hitType: Int, extra: String?): String? {
    if (hitType != WebView.HitTestResult.SRC_ANCHOR_TYPE) return null
    return sameTabUrl(extra)
}

/**
 * What a long press found: the link's actions, then the image's below a
 * divider. An image link carries both; a plain image only its own address.
 */
internal data class BrowserHitMenu(
    /** The link under the finger, for the three link actions. */
    val link: String? = null,
    /** The image's own address, for the image actions. */
    val image: String? = null,
) {
    /** The address the menu names: the link when there is one. */
    val title: String get() = link ?: image?.let(::menuTitle).orEmpty()

    /** A web image opens as a page of its own. */
    val canOpenImage: Boolean get() = image?.let(::isWebDownload) == true

    /** A web image downloads as itself; a page-local one is read by its page. */
    val canSaveImage: Boolean
        get() = image?.let { isWebDownload(it) || isPageLocalDownload(it) } == true

    /** A page-local address dies with its page: only web ones are worth copying. */
    val canCopyImage: Boolean get() = image?.let(::isWebDownload) == true

    /** Whether the image block has anything in it at all. */
    val hasImageActions: Boolean get() = canOpenImage || canSaveImage || canCopyImage
}

/** An address as a menu names it: a data: image's payload is far too long to show. */
private fun menuTitle(address: String): String =
    if (address.length <= MENU_ADDRESS_LIMIT) address else address.take(MENU_ADDRESS_LIMIT - 1) + '…'

private const val MENU_ADDRESS_LIMIT = 300

/** The page's host for blocking and download questions, or null for a blank or non-web page. */
internal fun pageHost(url: String): String? {
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return null
    return uri.host?.takeIf { it.isNotEmpty() }
}
