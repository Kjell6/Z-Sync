package de.kjell.zencompanion

import android.webkit.WebView
import de.kjell.zencompanion.ui.components.ExternalLinkRoute
import de.kjell.zencompanion.ui.components.externalLinkRoute
import de.kjell.zencompanion.ui.screens.BrowserHitMenu
import de.kjell.zencompanion.ui.screens.BrowserLinkAction
import de.kjell.zencompanion.ui.screens.browserLinkAction
import de.kjell.zencompanion.ui.screens.incomingWebLink
import de.kjell.zencompanion.ui.screens.isWebScheme
import de.kjell.zencompanion.ui.screens.loadsInWebView
import de.kjell.zencompanion.ui.screens.schemeLoadsInWebView
import de.kjell.zencompanion.ui.screens.sameTabUrl
import de.kjell.zencompanion.ui.screens.tappedLinkUrl
import de.kjell.zencompanion.ui.screens.zsyncWebLink
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

    // MARK: - zsync:// links

    @Test
    fun zsyncOpenLinkCarriesItsTargetPage() {
        assertEquals(
            "https://example.com/a?b=1&c=2",
            zsyncWebLink("zsync://open?url=https%3A%2F%2Fexample.com%2Fa%3Fb%3D1%26c%3D2"),
        )
        assertEquals(
            "http://localhost:8765/redirect.html",
            zsyncWebLink("zsync://open?url=http%3A%2F%2Flocalhost%3A8765%2Fredirect.html"),
        )
        // Encoded parameters inside the target survive one decode.
        assertEquals(
            "https://example.com/search?q=a%20b&page=2",
            zsyncWebLink("zsync://open?url=https%3A%2F%2Fexample.com%2Fsearch%3Fq%3Da%2520b%26page%3D2"),
        )
        // A `+` is a plus in an address, not a space.
        assertEquals(
            "https://example.com/a+b",
            zsyncWebLink("zsync://open?url=https%3A%2F%2Fexample.com%2Fa+b"),
        )
        // Scheme and host match like any URI; a bare slash is the same host.
        assertEquals(
            "https://example.com/",
            zsyncWebLink("ZSYNC://OPEN/?url=https%3A%2F%2Fexample.com%2F"),
        )
        // The intent helper accepts the same link from a VIEW intent.
        assertEquals(
            "https://example.com/from-a-shortcut",
            incomingWebLink(
                "android.intent.action.VIEW",
                "zsync",
                "zsync://open?url=https%3A%2F%2Fexample.com%2Ffrom-a-shortcut",
            ),
        )
    }

    @Test
    fun zsyncOpenLinkRejectsEverythingElse() {
        // Another host or path, a missing or extra parameter, a plain web URL.
        assertEquals(null, zsyncWebLink("zsync://other?url=https%3A%2F%2Fexample.com"))
        assertEquals(null, zsyncWebLink("zsync://open/elsewhere?url=https%3A%2F%2Fexample.com"))
        assertEquals(null, zsyncWebLink("zsync://open"))
        assertEquals(null, zsyncWebLink("zsync://open?url="))
        assertEquals(null, zsyncWebLink("zsync://open?url=https%3A%2F%2Fexample.com&x=1"))
        assertEquals(null, zsyncWebLink("zsync://open?target=https%3A%2F%2Fexample.com"))
        assertEquals(null, zsyncWebLink("https://example.com/?url=https%3A%2F%2Fexample.com"))
        assertEquals(null, zsyncWebLink("zsync:open?url=https%3A%2F%2Fexample.com"))
        assertEquals(null, zsyncWebLink(null))
        assertEquals(null, zsyncWebLink("  "))
        // Targets that are not web pages.
        for (encoded in listOf(
            "javascript%3Aalert(1)",
            "file%3A%2F%2F%2Fetc%2Fhosts",
            "data%3Atext%2Fhtml%2Chi",
            "intent%3A%2F%2Fscan%2F%23Intent%3Bscheme%3Dzxing%3Bend",
            "mailto%3Anobody%40example.com",
        )) {
            assertEquals(encoded, null, zsyncWebLink("zsync://open?url=$encoded"))
        }
        // A web address without a host.
        assertEquals(null, zsyncWebLink("zsync://open?url=https%3A%2F%2F"))
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

    @Test
    fun aTextLinkOffersOnlyTheLinkActions() {
        val menu = BrowserHitMenu(link = "https://example.com/a")
        assertEquals("https://example.com/a", menu.title)
        assertFalse(menu.hasImageActions)
        assertFalse(menu.canSaveImage)
        assertFalse(menu.canOpenImage)
        assertFalse(menu.canCopyImage)
    }

    @Test
    fun aWebImageOffersAllThreeImageActions() {
        val menu = BrowserHitMenu(image = "https://example.com/picture.png")
        assertEquals("https://example.com/picture.png", menu.title)
        assertTrue(menu.canSaveImage)
        assertTrue(menu.canOpenImage)
        assertTrue(menu.canCopyImage)
    }

    @Test
    fun anImageLinkKeepsTheLinkFirstAndItsImageBelow() {
        val menu = BrowserHitMenu(
            link = "https://example.com/page",
            image = "https://cdn.example.com/picture.png",
        )
        // The menu names the link; the image sits in its own block under it.
        assertEquals("https://example.com/page", menu.title)
        assertTrue(menu.canSaveImage)
    }

    @Test
    fun aPageLocalImageIsSavedButNeitherOpenedNorCopied() {
        val blob = BrowserHitMenu(image = "blob:https://example.com/1")
        assertTrue(blob.hasImageActions)
        assertTrue(blob.canSaveImage)
        assertFalse(blob.canOpenImage)
        assertFalse(blob.canCopyImage)

        val data = BrowserHitMenu(image = "data:image/png;base64,AAAA")
        assertTrue(data.canSaveImage)
        assertFalse(data.canOpenImage)
        assertFalse(data.canCopyImage)
    }

    @Test
    fun anImageNoActionCanTakeOffersNothing() {
        val menu = BrowserHitMenu(image = "ftp://example.com/picture.png")
        assertFalse(menu.hasImageActions)
        assertFalse(menu.canSaveImage)
        assertFalse(menu.canOpenImage)
        assertFalse(menu.canCopyImage)
    }

    @Test
    fun aDataImageIsNamedByItsHeadOnly() {
        val payload = "A".repeat(4_000)
        val menu = BrowserHitMenu(image = "data:image/png;base64,$payload")
        assertTrue(menu.title.length < 320)
        assertTrue(menu.title.startsWith("data:image/png;base64,"))
        assertTrue(menu.title.endsWith("…"))
    }
}
