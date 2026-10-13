package de.kjell.zencompanion.ui.screens

import android.webkit.WebViewClient
import de.kjell.zencompanion.util.PageTitle

/** Why the quick browser shows a panel instead of the page. Same cases as the iOS `BrowserPageError`. */
enum class BrowserPageError { OFFLINE, HOST_NOT_FOUND, NOT_RESPONDING, INSECURE, CRASHED }

/**
 * The panel for a main-frame load that failed, or null when the failure
 * needs none. WebView reports a lookup failure the same way whether the
 * network is down or the host doesn't exist, so [isOnline] and Chromium's
 * error name in [description] tell them apart.
 */
internal fun pageErrorFor(errorCode: Int, description: String?, isOnline: Boolean): BrowserPageError? {
    if (description?.contains(INTERNET_DISCONNECTED) == true) return BrowserPageError.OFFLINE
    return when (errorCode) {
        WebViewClient.ERROR_HOST_LOOKUP ->
            if (isOnline) BrowserPageError.HOST_NOT_FOUND else BrowserPageError.OFFLINE
        WebViewClient.ERROR_CONNECT, WebViewClient.ERROR_TIMEOUT ->
            if (isOnline) BrowserPageError.NOT_RESPONDING else BrowserPageError.OFFLINE
        WebViewClient.ERROR_FAILED_SSL_HANDSHAKE -> BrowserPageError.INSECURE
        else -> null
    }
}

/**
 * True when a certificate error belongs to the page itself rather than an
 * image or script on it. [pageUrls] are the addresses the main frame is on
 * or loading.
 */
internal fun isPageCertificateError(errorUrl: String?, pageUrls: List<String?>): Boolean =
    pageUrls.any { it != null && (it == errorUrl || PageTitle.sameDocument(it, errorUrl)) }

/** What "Go back" on the panel does. */
enum class BrowserErrorExit {
    /** The failed load never replaced the page underneath: show it again. */
    REVEAL,
    GO_BACK,

    /** Nothing to go back to: close the browser. */
    DISMISS,
}

internal fun browserErrorExit(currentUrl: String?, failedUrl: String?, canGoBack: Boolean): BrowserErrorExit = when {
    !currentUrl.isNullOrEmpty() && currentUrl != failedUrl && !PageTitle.sameDocument(currentUrl, failedUrl) ->
        BrowserErrorExit.REVEAL
    canGoBack -> BrowserErrorExit.GO_BACK
    else -> BrowserErrorExit.DISMISS
}

private const val INTERNET_DISCONNECTED = "ERR_INTERNET_DISCONNECTED"
