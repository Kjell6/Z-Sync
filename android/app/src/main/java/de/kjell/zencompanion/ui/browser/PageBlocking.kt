package de.kjell.zencompanion.ui.browser

import android.net.Uri
import de.kjell.zencompanion.data.BlockCategory
import de.kjell.zencompanion.data.ContentBlocking
import de.kjell.zencompanion.data.ContentBlockingSettings
import de.kjell.zencompanion.data.blockingSiteKey
import java.util.concurrent.ConcurrentHashMap

/** What the card at the top of the browser menu says. */
sealed interface BlockingCard {
    /** Something was blocked on this page. */
    data class Blocked(val count: Int) : BlockingCard

    /** The page was reloaded once with blocking off. */
    data object ShowingOriginal : BlockingCard

    /** Every switch that is on in Settings is off for this site. */
    data class OffForSite(val site: String) : BlockingCard

    data object Hidden : BlockingCard

    companion object {
        fun of(
            settings: ContentBlockingSettings,
            host: String?,
            blockedCount: Int,
            showingOriginal: Boolean,
        ): BlockingCard = when {
            host.isNullOrEmpty() -> Hidden
            showingOriginal -> ShowingOriginal
            settings.allOffFor(host) -> OffForSite(blockingSiteKey(host))
            blockedCount > 0 -> Blocked(blockedCount)
            else -> Hidden
        }
    }
}

/**
 * Blocking for one quick-browser WebView. The WebView asks it about every
 * request on a background thread, so the page it is on and the switches are
 * kept in volatile fields rather than read from Compose state.
 */
class PageBlocker(private val onBlockedCountChange: (Int) -> Unit) {
    @Volatile
    var settings: ContentBlockingSettings = ContentBlockingSettings()

    @Volatile
    private var pageUrl: String? = null

    @Volatile
    private var pageHost: String? = null

    /** The page that "Restore original page" reloads, without blocking. */
    @Volatile
    private var originalPage: String? = null

    private val blocked = ConcurrentHashMap.newKeySet<String>()

    val showingOriginal: Boolean get() = originalPage != null

    /** A main-frame load started. Counting starts over for the new document. */
    fun pageStarted(url: String, isReload: Boolean = true) {
        val withoutFragment = url.substringBefore('#')
        if (!isReload && withoutFragment == pageUrl) return
        if (originalPage != null && originalPage != withoutFragment) originalPage = null
        pageUrl = withoutFragment
        pageHost = runCatching { Uri.parse(url).host }.getOrNull()
        blocked.clear()
        onBlockedCountChange(0)
    }

    /** True when the request should get an empty response instead. */
    fun shouldBlock(url: Uri, isMainFrame: Boolean): Boolean {
        if (isMainFrame) {
            pageStarted(url.toString())
            return false
        }
        if (originalPage != null) return false
        val scheme = url.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return false
        val host = url.host ?: return false
        ContentBlocking.blockedCategory(host, pageHost, settings) ?: return false
        if (blocked.add(url.toString())) onBlockedCountChange(blocked.size)
        return true
    }

    /** Reload the current page once with nothing blocked. */
    fun restoreOriginal(currentUrl: String) {
        originalPage = currentUrl.substringBefore('#')
    }

    fun blockAgain() {
        originalPage = null
    }

    /**
     * The cookie banner script for the switches, or null when it should not
     * run at all. Sites turned off, and the page shown as the original, are
     * left alone by a check at the top of the script.
     */
    fun consentScript(bundle: String?, settings: ContentBlockingSettings): String? {
        if (bundle == null || !settings.isOn(BlockCategory.COOKIE_BANNERS)) return null
        val skip = settings.cookieBannerSitesOff() +
            listOfNotNull(originalPage?.let { runCatching { Uri.parse(it).host }.getOrNull() }?.let(::blockingSiteKey))
        return consentScriptSource(bundle, skip)
    }
}

/**
 * Wraps the autoconsent bundle so it does nothing on the sites in [skip]. The
 * check uses the top page's host, which frames read from `ancestorOrigins`.
 */
internal fun consentScriptSource(bundle: String, skip: Set<String>): String {
    val sites = skip.sorted().joinToString(",") { "\"" + it.replace("\\", "").replace("\"", "") + "\"" }
    return """
        (function () {
          var skip = [$sites];
          var host = location.hostname;
          try {
            var a = location.ancestorOrigins;
            if (a && a.length) host = new URL(a[a.length - 1]).hostname;
          } catch (e) {}
          host = host.toLowerCase().replace(/^www\./, '');
          if (skip.indexOf(host) !== -1) return;
    """.trimIndent() + "\n" + bundle + "\n})();"
}
