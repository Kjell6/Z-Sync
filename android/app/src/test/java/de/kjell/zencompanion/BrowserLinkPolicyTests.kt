package de.kjell.zencompanion

import android.webkit.WebView
import de.kjell.zencompanion.ui.components.ExternalLinkRoute
import de.kjell.zencompanion.ui.components.externalLinkRoute
import de.kjell.zencompanion.ui.screens.BrowserLinkAction
import de.kjell.zencompanion.ui.screens.browserLinkAction
import de.kjell.zencompanion.ui.screens.incomingWebLink
import de.kjell.zencompanion.ui.screens.isWebScheme
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
        isMainFrame: Boolean = true,
        resolvedPackage: String? = youtubePackage,
    ): BrowserLinkAction = browserLinkAction(
        scheme = scheme,
        isRedirect = isRedirect,
        hasGesture = hasGesture,
        isMainFrame = isMainFrame,
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

    /** Declaring the browser filter puts Z-Sync in `browserPackages`; that must
     * change nothing for a link the WebView can load itself. */
    @Test
    fun ownPackageInTheBrowserListStillLoadsInTheWebView() {
        assertEquals(
            BrowserLinkAction.LOAD_IN_WEBVIEW,
            browserLinkAction(
                scheme = "https",
                isRedirect = false,
                hasGesture = true,
                isMainFrame = true,
                resolvedPackage = ownPackage,
                browserPackages = browsers + ownPackage,
                ownPackage = ownPackage,
            ),
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
    fun nonWebSchemesOpenExternallyOnlyAfterATap() {
        for (scheme in listOf("mailto", "tel", "intent", "youtube")) {
            assertEquals(
                scheme,
                BrowserLinkAction.OPEN_EXTERNALLY,
                action(scheme, hasGesture = true, resolvedPackage = null),
            )
            assertEquals(
                scheme,
                BrowserLinkAction.BLOCK,
                action(scheme, hasGesture = false, resolvedPackage = null),
            )
        }
    }

    /**
     * A tap inside an iframe must not leave the app; only a tap in the main
     * frame may. Mirrors iOS `BrowserNavigationPolicy.opensExternally`.
     */
    @Test
    fun appSchemesOpenOnlyFromAMainFrameTap() {
        for (scheme in listOf("mailto", "tel", "intent", "youtube")) {
            assertEquals(
                scheme,
                BrowserLinkAction.OPEN_EXTERNALLY,
                action(scheme, hasGesture = true, isMainFrame = true, resolvedPackage = null),
            )
            assertEquals(
                scheme,
                BrowserLinkAction.BLOCK,
                action(scheme, hasGesture = true, isMainFrame = false, resolvedPackage = null),
            )
        }
    }

    @Test
    fun webSchemeCheckMatchesThePolicy() {
        assertTrue(isWebScheme("HTTPS"))
        assertTrue(isWebScheme(null))
        assertFalse(isWebScheme("mailto"))
        assertFalse(isWebScheme("intent"))
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

    // MARK: - Links from other apps

    @Test
    fun onlyViewIntentsWithWebDataAreAccepted() {
        val view = "android.intent.action.VIEW"
        assertEquals(
            "https://example.com/a?b=1",
            incomingWebLink(view, "https", "https://example.com/a?b=1"),
        )
        assertEquals(
            "http://localhost:8765/redirect.html",
            incomingWebLink(view, "http", "http://localhost:8765/redirect.html"),
        )
        assertEquals(
            "scheme matching is case-insensitive",
            "HTTPS://Example.com",
            incomingWebLink(view, "HTTPS", "HTTPS://Example.com"),
        )
    }

    @Test
    fun everythingElseInAnIncomingIntentIsIgnored() {
        val view = "android.intent.action.VIEW"
        // Other actions, other schemes, and a missing or unusable address.
        assertEquals(null, incomingWebLink("android.intent.action.MAIN", "https", "https://example.com"))
        assertEquals(null, incomingWebLink(view, "javascript", "javascript:alert(1)"))
        assertEquals(null, incomingWebLink(view, "file", "file:///etc/hosts"))
        assertEquals(null, incomingWebLink(view, "content", "content://com.example/secret"))
        assertEquals(null, incomingWebLink(view, "intent", "intent://scan/#Intent;scheme=zxing;end"))
        assertEquals(null, incomingWebLink(view, "mailto", "mailto:nobody@example.com"))
        assertEquals(null, incomingWebLink(view, "https", null))
        assertEquals(null, incomingWebLink(view, "https", "   "))
        assertEquals(null, incomingWebLink(view, null, "https://example.com"))
    }

    @Test
    fun externalHandOffNeverChoosesThisApp() {
        // A real default browser takes the link as it is.
        assertEquals(
            ExternalLinkRoute.StartWithPackage("com.android.chrome"),
            route(listOf(ownPackage, "com.android.chrome"), resolved = "com.android.chrome"),
        )
        // No default and several handlers: the resolver reports the system's
        // chooser activity, not null. Starting the intent unchanged would list
        // Z-Sync among the browsers the reader can pick.
        assertEquals(
            ExternalLinkRoute.StartWithPackage("com.android.chrome"),
            route(listOf(ownPackage, "com.android.chrome"), resolved = "android"),
        )
        // Z-Sync itself is the default: another browser has to take the link.
        assertEquals(
            ExternalLinkRoute.StartWithPackage("org.mozilla.firefox"),
            route(listOf(ownPackage, "org.mozilla.firefox"), resolved = ownPackage),
        )
        // The handler is not a known browser: ask the reader, without us.
        assertEquals(
            ExternalLinkRoute.ChooserWithoutUs,
            route(listOf(ownPackage, "com.example.viewer"), resolved = "android"),
        )
        // Only Z-Sync handles this address: the caller reports it.
        assertEquals(
            ExternalLinkRoute.NoOtherApp,
            route(listOf(ownPackage), resolved = ownPackage),
        )
        // Z-Sync does not handle the link at all: today's behaviour.
        assertEquals(
            ExternalLinkRoute.StartAsIs,
            route(listOf("com.android.chrome"), resolved = "android"),
        )
        assertEquals(ExternalLinkRoute.StartAsIs, route(emptyList()))
    }

    private fun route(
        handlers: List<String>,
        resolved: String? = null,
    ): ExternalLinkRoute = externalLinkRoute(
        handlerPackages = handlers,
        resolvedPackage = resolved,
        ownPackage = ownPackage,
        browserPackages = browsers.toList(),
    )
}
