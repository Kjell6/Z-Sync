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

    /**
     * Older WebViews have a single profile: there private pages are not
     * offered. A WebView that cannot even be asked counts as one of them.
     */
    val isSupported: Boolean
        get() = runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }.getOrDefault(false)

    /** A profile for a new private page, used by no other. */
    fun newProfile(): String = PREFIX + UUID.randomUUID()

    /**
     * The pages were read back as the app starts: their profiles keep their
     * sign-ins. Only noted: the WebView is not woken for it before a page needs it.
     */
    fun startUp(profiles: Set<String>) {
        known += profiles
    }

    /**
     * Puts a new WebView on its page's profile. Before it loads anything.
     * False where profiles are missing: the page must then load nothing, or
     * it would write into the phone's own data.
     */
    fun adopt(webView: WebView, profile: String): Boolean {
        if (!isSupported) return false
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
        return true
    }

    /** The jar a WebView uses: its own profile's for a private page. */
    fun cookies(webView: WebView): CookieManager =
        if (isSupported) WebViewCompat.getProfile(webView).cookieManager else CookieManager.getInstance()

    /** Every profile no open page uses any more goes, with all it held. */
    fun releaseAllExcept(inUse: Set<String>) {
        val gone = known - inUse
        if (gone.isEmpty()) return
        known -= gone
        // Without profile support there is no profile store to ask, and no
        // private page could have written to one.
        if (!isSupported) return
        gone.forEach { profile -> main.postDelayed({ delete(profile) }, DELETE_DELAY_MS) }
    }

    private fun delete(profile: String) {
        // Taken up again in the meantime (cannot happen with fresh names, but cheap).
        if (profile in known) return
        val store = ProfileStore.getInstance()
        if (runCatching { store.deleteProfile(profile) }.isSuccess) return
        // A profile loaded in this run cannot be deleted until the next one.
        // Everything in it goes now; the empty profile is a leftover the next
        // run's first private page removes.
        store.getProfile(profile)?.let { loaded ->
            runCatching { loaded.cookieManager.removeAllCookies(null) }
            runCatching { loaded.webStorage.deleteAllData() }
            runCatching { loaded.geolocationPermissions.clearAll() }
        }
    }
}
