package de.kjell.zencompanion

import de.kjell.zencompanion.ui.screens.BrowserLinkAction
import de.kjell.zencompanion.ui.screens.browserLinkAction
import org.junit.Assert.assertEquals
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
}
