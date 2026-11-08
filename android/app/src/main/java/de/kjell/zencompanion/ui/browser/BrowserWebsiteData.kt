package de.kjell.zencompanion.ui.browser

import android.content.Context
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewDatabase
import de.kjell.zencompanion.data.SitePermissions

/**
 * Everything websites left behind. Open pages and history are the reader's own
 * and stay. Your Zen sign-in does not live in these stores.
 */
fun clearBrowserWebsiteData(context: Context) {
    // Removal finishes later, so the jar is written once it has.
    CookieManager.getInstance().run { removeAllCookies { flush() } }
    WebStorage.getInstance().deleteAllData()
    GeolocationPermissions.getInstance().clearAll()
    WebViewDatabase.getInstance(context).apply {
        clearFormData()
        clearHttpAuthUsernamePassword()
    }
    // The cache is shared by every WebView, so a throwaway one reaches it.
    WebView(context).apply {
        clearCache(true)
        destroy()
    }
    SitePermissions.clear()
}
