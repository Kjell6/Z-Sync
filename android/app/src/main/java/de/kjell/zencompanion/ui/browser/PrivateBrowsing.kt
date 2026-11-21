package de.kjell.zencompanion.ui.browser

import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.util.UUID

/**
 * Website data for private pages: every private page has a WebView profile of
 * its own, so nothing it signs into reaches another page, private or not. A
 * popup a page opens shares its profile, or a sign-in in the popup would never
 * reach the page that asked for it. A profile stays with its page through a
 * restart and is deleted once the page has closed. Main thread only.
 */
object PrivateBrowsing {
    private const val PREFIX = "private-"
    /** A WebView needs a moment after its page closes before it lets go. */
    private const val DELETE_DELAY_MS = 1_500L

    private val main = Handler(Looper.getMainLooper())
    /** Profiles that may hold data: the stayed pages' from the start, and each one adopted since. */
    private val known = mutableSetOf<String>()
    /** What earlier runs left behind without a page is looked for once. */
    private var orphansChecked = false

    /** Older WebViews have a single profile: there private pages are not offered. */
    val isSupported: Boolean
        get() = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

    /** A profile for a new private page, used by no other. */
    fun newProfile(): String = PREFIX + UUID.randomUUID()

    /**
     * The pages were read back as the app starts: their profiles keep their
     * sign-ins. Only noted: the WebView is not woken for it before a page needs it.
     */
    fun startUp(profiles: Set<String>) {
        known += profiles
    }

    /** Puts a new WebView on its page's profile. Before it loads anything. */
    fun adopt(webView: WebView, profile: String) {
        if (!isSupported) return
        known += profile
        val store = ProfileStore.getInstance()
        if (!orphansChecked) {
            orphansChecked = true
            store.allProfileNames
                .filter { it.startsWith(PREFIX) && it !in known }
                .forEach { runCatching { store.deleteProfile(it) } }
        }
        store.getOrCreateProfile(profile)
        WebViewCompat.setProfile(webView, profile)
    }

    /** The jar a WebView uses: its own profile's for a private page. */
    fun cookies(webView: WebView): CookieManager =
        if (isSupported) WebViewCompat.getProfile(webView).cookieManager else CookieManager.getInstance()

    /** Every profile no open page uses any more goes, with all it held. */
    fun releaseAllExcept(inUse: Set<String>) {
        val gone = known - inUse
        if (gone.isEmpty()) return
        known -= gone
        gone.forEach { profile -> main.postDelayed({ delete(profile) }, DELETE_DELAY_MS) }
    }

    private fun delete(profile: String, retries: Int = 2) {
        // Taken up again in the meantime (cannot happen with fresh names, but cheap).
        if (profile in known) return
        val deleted = runCatching { ProfileStore.getInstance().deleteProfile(profile) }
        // Still held by a WebView on its way out: try again shortly.
        if (deleted.isFailure && retries > 0) {
            main.postDelayed({ delete(profile, retries - 1) }, DELETE_DELAY_MS)
        }
    }
}
