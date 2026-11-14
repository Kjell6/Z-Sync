package de.kjell.zencompanion

import de.kjell.zencompanion.ui.browser.downloadReferrer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A download's `Referer` is only the page's origin (`strict-origin`), so a
 * page's private query or fragment never reaches a file host, whichever way
 * the download is redirected.
 */
class DownloadReferrerTests {

    private val page = "https://example.com/album/photo?id=42#comments"
    private val image = "https://example.com/assets/full.jpg"

    @Test
    fun theSameSiteGetsOnlyThePageOrigin() {
        // The downloader may still be redirected elsewhere, so not even the
        // page's own site gets its path and query.
        assertEquals("https://example.com/", downloadReferrer(page, image))
    }

    @Test
    fun anotherSiteGetsOnlyThePageOrigin() {
        assertEquals("https://example.com/", downloadReferrer(page, "https://cdn.example.net/full.jpg"))
    }

    @Test
    fun aPlainHttpTargetOnASecurePageGetsNothing() {
        assertNull(downloadReferrer(page, "http://cdn.example.net/full.jpg"))
    }

    @Test
    fun aPlainHttpPageKeepsItsOriginForAnyTarget() {
        assertEquals("http://example.com/", downloadReferrer("http://example.com/a?b=c", "http://cdn.example.net/f"))
    }

    @Test
    fun aNonDefaultPortStaysInTheOrigin() {
        assertEquals(
            "https://example.com:8443/",
            downloadReferrer("https://example.com:8443/album/photo?id=42", "https://cdn.example.net/full.jpg"),
        )
    }

    @Test
    fun aNamedDefaultPortIsLeftOut() {
        assertEquals("https://example.com/", downloadReferrer("https://example.com:443/album?id=42", image))
    }

    @Test
    fun theHostIsWrittenInLowerCase() {
        assertEquals("https://example.com/", downloadReferrer("https://Example.COM/a", image))
    }

    @Test
    fun aPageWithNoWebAddressGivesNothing() {
        assertNull(downloadReferrer(null, image))
        assertNull(downloadReferrer("about:blank", image))
    }

    @Test
    fun aTargetThatIsNoAddressGivesNothing() {
        assertNull(downloadReferrer(page, "not an address"))
    }

    @Test
    fun aRedirectKeepsOnlyTheOriginAndDropsItOnTheWayToHttp() {
        // What enqueueWebDownload does after resolving redirects: the policy
        // runs again on the referrer chosen for the first address.
        val first = downloadReferrer(page, image)
        assertEquals("https://example.com/", downloadReferrer(first, "https://cdn.example.net/full.jpg"))
        assertNull(downloadReferrer(first, "http://cdn.example.net/full.jpg"))
    }
}
