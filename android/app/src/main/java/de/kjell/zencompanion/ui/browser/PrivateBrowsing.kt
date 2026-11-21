package de.kjell.zencompanion.ui.browser

import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import de.kjell.zencompanion.data.SitePermissionStore

/**
 * Website data for private pages: one WebView profile of their own, shared by
 * the private pages open together so a sign-in carries across them, and
 * deleted once the last of them has closed. Main thread only.
 */
object PrivateBrowsing {
    private const val PROFILE = "private-pages"
    /** A WebView needs a moment after its page closes before it lets go. */
    private const val DELETE_DELAY_MS = 1_500L

    private val main = Handler(Looper.getMainLooper())
    /** What an earlier run left behind with no private page to show for it goes once. */
    private var cleaned = false
    /** Private pages stayed from an earlier run: their data stays with them. */
    private var keepsEarlierSession = false
    /** A private WebView ran since the profile was last deleted. */
    private var used = false
    /** The last private page closed and its data has not been deleted yet. */
    private var deletePending = false
    private var answers: SitePermissionStore? = null

    /** What private pages allowed or blocked: never written down. */
    val permissions: SitePermissionStore
        get() = answers ?: SitePermissionStore().also { answers = it }

    /** Older WebViews have a single profile: there private pages are not offered. */
    val isSupported: Boolean
        get() = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

    /**
     * The pages were read back as the app starts. Private pages that stayed
     * keep their sign-ins; with none of them left, an earlier run's data goes.
     */
    fun startUp(hasPrivatePages: Boolean) {
        // Only noted: the WebView is not woken for it before a page needs it.
        keepsEarlierSession = hasPrivatePages
    }

    /** Puts a new WebView on the private profile. Before it loads anything. */
    fun adopt(webView: WebView) {
        if (!isSupported) return
        main.removeCallbacksAndMessages(null)
        // A new private page right after the last one closed must not find
        // the old session's data, nor what an earlier run left behind.
        if (deletePending || (!cleaned && !keepsEarlierSession)) {
            cleaned = true
            deletePending = false
            runCatching { ProfileStore.getInstance().deleteProfile(PROFILE) }
        }
        cleaned = true
        ProfileStore.getInstance().getOrCreateProfile(PROFILE)
        WebViewCompat.setProfile(webView, PROFILE)
        used = true
    }

    /** The jar a WebView uses: the private one for a private page. */
    fun cookies(webView: WebView): CookieManager =
        if (isSupported) WebViewCompat.getProfile(webView).cookieManager else CookieManager.getInstance()

    /** No private page is left: the next one starts with nothing. */
    fun endSession() {
        answers = null
        // An earlier run's private pages are gone: the next one starts empty.
        keepsEarlierSession = false
        // Nothing private ran: no need to wake the WebView to ask.
        if (!used) return
        used = false
        deletePending = true
        main.removeCallbacksAndMessages(null)
        main.postDelayed(::deleteProfile, DELETE_DELAY_MS)
    }

    private fun deleteProfile(retries: Int = 2) {
        val deleted = runCatching { ProfileStore.getInstance().deleteProfile(PROFILE) }
        if (deleted.isSuccess) deletePending = false
        // Still held by a WebView on its way out: try again shortly.
        if (deleted.isFailure && retries > 0) {
            main.postDelayed({ deleteProfile(retries - 1) }, DELETE_DELAY_MS)
        }
    }
}
