package de.kjell.zencompanion

import android.webkit.WebView
import de.kjell.zencompanion.ui.screens.BrowserLinkAction
import de.kjell.zencompanion.ui.screens.browserLinkAction
import de.kjell.zencompanion.ui.screens.loadsInWebView
import de.kjell.zencompanion.ui.screens.schemeLoadsInWebView
import de.kjell.zencompanion.ui.screens.sameTabUrl
import de.kjell.zencompanion.ui.screens.tappedLinkUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserLinkPolicyTests {

    private val ownPackage = "de.kjell.zencompanion"
    private val browsers = setOf("com.android.chrome", "org.mozilla.firefox")
    private val youtubePackage = "com.google.android.youtube"

    private fun action(
        scheme: String?,
        isRedirect: Boolean = false,
        hasGesture: Boolean = true,
        resolvedPackage: String? = youtubePackage,
    ): BrowserLinkAction = browserLinkAction(
        scheme = scheme,
        isRedirect = isRedirect,
        hasGesture = hasGesture,
        resolvedPackage = resolvedPackage,
        browserPackages = browsers,
        ownPackage = ownPackage,
    )

    @Test
    fun webUrlClaimedByAppOpensExternally() {
        assertEquals(BrowserLinkAction.OPEN_EXTERNALLY, action("https"))
        assertEquals(BrowserLinkAction.OPEN_EXTERNALLY, action("http"))
    }

    @Test
    fun schemeMatchingIsCaseInsensitive() {
        assertEquals(BrowserLinkAction.OPEN_EXTERNALLY, action("HTTPS"))
        assertEquals(BrowserLinkAction.OPEN_EXTERNALLY, action("HtTp"))
    }

    @Test
    fun webUrlResolvedToBrowserStaysInWebView() {
        assertEquals(
            BrowserLinkAction.LOAD_IN_WEBVIEW,
            action("https", resolvedPackage = "com.android.chrome"),
        )
    }

    @Test
    fun webUrlWithoutHandlerStaysInWebView() {
        assertEquals(BrowserLinkAction.LOAD_IN_WEBVIEW, action("https", resolvedPackage = null))
    }

    @Test
    fun webUrlResolvedToOwnAppStaysInWebView() {
        assertEquals(
            BrowserLinkAction.LOAD_IN_WEBVIEW,
            action("https", resolvedPackage = ownPackage),
        )
    }

    @Test
    fun typedUrlWithoutGestureStaysInWebView() {
        assertEquals(BrowserLinkAction.LOAD_IN_WEBVIEW, action("https", hasGesture = false))
    }

    @Test
    fun redirectStaysInWebView() {
        assertEquals(
            BrowserLinkAction.LOAD_IN_WEBVIEW,
            action("https", isRedirect = true),
        )
    }

    @Test
    fun nonWebSchemesAlwaysOpenExternally() {
        assertEquals(
            BrowserLinkAction.OPEN_EXTERNALLY,
            action("mailto", hasGesture = false, resolvedPackage = null),
        )
        assertEquals(
            BrowserLinkAction.OPEN_EXTERNALLY,
            action("tel", hasGesture = false, resolvedPackage = null),
        )
        assertEquals(
            BrowserLinkAction.OPEN_EXTERNALLY,
            action("intent", hasGesture = false, resolvedPackage = null),
        )
        assertEquals(
            BrowserLinkAction.OPEN_EXTERNALLY,
            action("youtube", hasGesture = false, resolvedPackage = null),
        )
    }

    @Test
    fun newWindowKeepsHttpAndHttpsInTheSameTab() {
        assertEquals(
            "http://www.org.chemie.uni-muenchen.de/ac/kornath/flusssaeure-hf/",
            sameTabUrl("http://www.org.chemie.uni-muenchen.de/ac/kornath/flusssaeure-hf/"),
        )
        assertEquals(
            "https://www.google.com/goto?url=abc",
            sameTabUrl("https://www.google.com/goto?url=abc"),
        )
    }

    @Test
    fun blankNewWindowDoesNotReplaceThePage() {
        assertEquals(null, sameTabUrl("about:blank"))
        assertEquals(null, sameTabUrl("javascript:void(0)"))
        assertEquals(null, sameTabUrl(null))
        assertEquals(null, sameTabUrl("  "))
    }

    @Test
    fun tappedTextLinkLoadsInTheSameTab() {
        assertEquals(
            "https://example.com/next",
            tappedLinkUrl(WebView.HitTestResult.SRC_ANCHOR_TYPE, "https://example.com/next"),
        )
    }

    @Test
    fun tappedImageLinkLeavesTheAddressToThePopup() {
        // For an image link the hit test reports the image, not where the link goes.
        assertEquals(
            null,
            tappedLinkUrl(WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE, "https://example.com/picture.png"),
        )
        assertEquals(null, tappedLinkUrl(WebView.HitTestResult.UNKNOWN_TYPE, null))
    }

    @Test
    fun inPageSchemesStayInWebView() {
        assertEquals(
            BrowserLinkAction.LOAD_IN_WEBVIEW,
            action("about", hasGesture = false, resolvedPackage = null),
        )
        assertEquals(
            BrowserLinkAction.LOAD_IN_WEBVIEW,
            action("data", hasGesture = false, resolvedPackage = null),
        )
        assertEquals(
            BrowserLinkAction.LOAD_IN_WEBVIEW,
            action("javascript", hasGesture = false, resolvedPackage = null),
        )
    }

    @Test
    fun onlyWebSchemesMayReachTheWebView() {
        assertTrue(schemeLoadsInWebView("https"))
        assertTrue(schemeLoadsInWebView("HTTPS"))
        assertTrue(schemeLoadsInWebView("http"))
        assertTrue(schemeLoadsInWebView("about"))
        assertTrue(schemeLoadsInWebView("data"))
        assertTrue(schemeLoadsInWebView("blob"))
        assertTrue(schemeLoadsInWebView("file"))
        assertTrue(schemeLoadsInWebView("javascript"))
        assertFalse(schemeLoadsInWebView("snssdk1340"))
        assertFalse(schemeLoadsInWebView("intent"))
        assertFalse(schemeLoadsInWebView("market"))
        assertFalse(schemeLoadsInWebView("tel"))
        assertFalse(schemeLoadsInWebView(null))
        assertFalse(loadsInWebView(""))
    }

    @Test
    fun appSchemesAreRecognisedInAFullUrl() {
        // TikTok's share links land here: the WebView must never try them.
        assertFalse(loadsInWebView("snssdk1340://aweme/detail/7698629723966033184?refer=probe"))
        assertFalse(loadsInWebView("mailto:nobody@example.com"))
        assertFalse(loadsInWebView("intent://scan/#Intent;scheme=zxing;end"))
        assertTrue(loadsInWebView("https://example.com/a"))
        assertTrue(loadsInWebView("http://localhost:8765/redirect.html"))
    }
}
