package de.kjell.zencompanion

import android.webkit.WebViewClient
import de.kjell.zencompanion.ui.screens.BrowserErrorExit
import de.kjell.zencompanion.ui.screens.BrowserPageError
import de.kjell.zencompanion.ui.screens.browserErrorExit
import de.kjell.zencompanion.ui.screens.isPageCertificateError
import de.kjell.zencompanion.ui.screens.pageErrorFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserPageErrorTests {
    @Test
    fun aLookupFailureIsOfflineOnlyWithoutANetwork() {
        // WebView reports both as ERR_NAME_NOT_RESOLVED (checked on the emulator).
        val lookup = WebViewClient.ERROR_HOST_LOOKUP
        assertEquals(BrowserPageError.HOST_NOT_FOUND, pageErrorFor(lookup, "net::ERR_NAME_NOT_RESOLVED", isOnline = true))
        assertEquals(BrowserPageError.OFFLINE, pageErrorFor(lookup, "net::ERR_NAME_NOT_RESOLVED", isOnline = false))
    }

    @Test
    fun connectionFailuresMapToNotRespondingOrOffline() {
        for (code in listOf(WebViewClient.ERROR_CONNECT, WebViewClient.ERROR_TIMEOUT)) {
            assertEquals(BrowserPageError.NOT_RESPONDING, pageErrorFor(code, null, isOnline = true))
            assertEquals(BrowserPageError.OFFLINE, pageErrorFor(code, null, isOnline = false))
        }
        assertEquals(
            BrowserPageError.OFFLINE,
            pageErrorFor(WebViewClient.ERROR_UNKNOWN, "net::ERR_INTERNET_DISCONNECTED", isOnline = true),
        )
    }

    @Test
    fun aFailedHandshakeIsInsecure() {
        assertEquals(BrowserPageError.INSECURE, pageErrorFor(WebViewClient.ERROR_FAILED_SSL_HANDSHAKE, null, isOnline = true))
    }

    @Test
    fun otherFailuresKeepWebViewsOwnPage() {
        assertNull(pageErrorFor(WebViewClient.ERROR_UNSUPPORTED_SCHEME, null, isOnline = true))
        assertNull(pageErrorFor(WebViewClient.ERROR_FILE_NOT_FOUND, null, isOnline = false))
    }

    @Test
    fun onlyThePagesOwnCertificateErrorShowsThePanel() {
        val page = "https://expired.badssl.com/"
        assertTrue(isPageCertificateError(page, listOf("https://example.com/", page)))
        assertTrue(isPageCertificateError("https://expired.badssl.com", listOf(page)))
        assertFalse(isPageCertificateError("https://cdn.example/img.png", listOf(page, null)))
    }

    @Test
    fun goBackShowsThePageUnderneathWhenTheLoadNeverReplacedIt() {
        // A cancelled certificate error leaves the WebView on the previous page.
        val failed = "https://expired.badssl.com/"
        assertEquals(BrowserErrorExit.REVEAL, browserErrorExit("https://example.com/", failed, canGoBack = true))
        assertEquals(BrowserErrorExit.GO_BACK, browserErrorExit(failed, failed, canGoBack = true))
        assertEquals(BrowserErrorExit.DISMISS, browserErrorExit(failed, failed, canGoBack = false))
        assertEquals(BrowserErrorExit.DISMISS, browserErrorExit(null, failed, canGoBack = false))
    }
}
