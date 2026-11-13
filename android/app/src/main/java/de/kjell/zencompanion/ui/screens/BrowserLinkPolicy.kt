package de.kjell.zencompanion.ui.screens

import android.content.Intent
import android.webkit.WebView

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

/**
 * The address a `VIEW` intent from another app hands to Z-Sync, or null when
 * it is not a link Z-Sync opens. Any app on the device can send the intent, so
 * only the data URI counts and nothing else in it is read. Two forms pass:
 * an `http`/`https` link, and Z-Sync's own `zsync://open?url=` link.
 */
internal fun incomingWebLink(action: String?, scheme: String?, data: String?): String? {
    if (action != Intent.ACTION_VIEW) return null
    val normalized = scheme?.lowercase()
    if (normalized == "zsync") return zsyncWebLink(data)
    if (normalized != "http" && normalized != "https") return null
    return sameTabUrl(data)
}

/**
 * The page a `zsync://open?url=<percent-encoded address>` link names, or null
 * when it is anything else: another host or path, a missing or extra
 * parameter, or a target that is not an `http`/`https` address with a host.
 * A Shortcut or a link can open a page in Z-Sync this way before the system
 * offers the app as a browser.
 *
 * Parsed as text rather than with `android.net.Uri`, so the query stays raw
 * until its single value is decoded: an encoded `&` inside the target must not
 * split it into parameters (and this stays a pure function for the tests).
 */
internal fun zsyncWebLink(raw: String?): String? {
    val link = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val schemeEnd = link.indexOf(':')
    if (schemeEnd <= 0) return null
    if (!link.substring(0, schemeEnd).equals("zsync", ignoreCase = true)) return null
    val rest = link.substring(schemeEnd + 1)
    if (!rest.startsWith("//")) return null

    // The authority runs to the first slash, query or fragment.
    val afterSlashes = rest.substring(2)
    val authorityEnd = afterSlashes.indexOfFirst { it == '/' || it == '?' || it == '#' }
    val host = if (authorityEnd < 0) afterSlashes else afterSlashes.substring(0, authorityEnd)
    if (!host.equals("open", ignoreCase = true)) return null
    val afterHost = if (authorityEnd < 0) "" else afterSlashes.substring(authorityEnd)

    // The path is either absent or a bare slash; a fragment only counts when
    // it follows the query.
    val fragmentStart = afterHost.indexOf('#')
    val queryStart = afterHost.indexOf('?')
    val pathEnd = minOf(
        if (queryStart >= 0) queryStart else afterHost.length,
        if (fragmentStart >= 0) fragmentStart else afterHost.length,
    )
    val path = afterHost.substring(0, pathEnd)
    if (path.isNotEmpty() && path != "/") return null
    if (queryStart < 0 || (fragmentStart in 0 until queryStart)) return null
    val query = afterHost.substring(
        queryStart + 1,
        if (fragmentStart > queryStart) fragmentStart else afterHost.length,
    )

    // Exactly one parameter, named `url`: nothing else is accepted.
    if (query.isEmpty() || query.contains('&')) return null
    if (query.substringBefore('=') != "url") return null
    val encoded = query.substringAfter('=', missingDelimiterValue = "")
    if (encoded.isEmpty()) return null

    val target = decodeQueryValue(encoded) ?: return null
    return webAddressWithHost(target)
}

/**
 * Percent-decodes one query value. A `+` stays a plus — in an address it is a
 * space only in form encoding, which this link does not use.
 */
private fun decodeQueryValue(raw: String): String? = runCatching {
    java.net.URLDecoder.decode(raw.replace("+", "%2B"), Charsets.UTF_8.name())
}.getOrNull()

/** The address when it is `http`/`https` and names a host, or null. */
private fun webAddressWithHost(raw: String): String? {
    val address = sameTabUrl(raw) ?: return null
    val rest = address.substringAfter(':')
    if (!rest.startsWith("//")) return null
    val host = rest.substring(2).takeWhile { it != '/' && it != '?' && it != '#' }
    return address.takeIf { host.isNotEmpty() }
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
