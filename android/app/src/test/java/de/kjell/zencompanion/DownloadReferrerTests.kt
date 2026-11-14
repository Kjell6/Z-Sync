package de.kjell.zencompanion

import de.kjell.zencompanion.ui.browser.downloadReferrer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A download's `Referer` follows the browser default
 * `strict-origin-when-cross-origin`, so a page's private query or fragment
 * never reaches another site's file host.
 */
class DownloadReferrerTests {

    private val page = "https://example.com/album/photo?id=42#comments"
    private val image = "https://example.com/assets/full.jpg"

    @Test
    fun theSameSiteGetsThePageAddressWithoutItsFragment() {
        assertEquals(
            "https://example.com/album/photo?id=42",
            downloadReferrer(page, image),
        )
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
    fun aNonDefaultPortStaysInTheOrigin() {
        assertEquals(
            "https://example.com:8443/",
            downloadReferrer("https://example.com:8443/album/photo?id=42", "https://cdn.example.net/full.jpg"),
        )
    }

    @Test
    fun aNonDefaultPortIsTheSameOriginWhenBothNameIt() {
        assertEquals(
            "https://example.com:8443/album/photo?id=42",
            downloadReferrer("https://example.com:8443/album/photo?id=42", "https://example.com:8443/full.jpg"),
        )
    }

    @Test
    fun aNamedDefaultPortIsTheSameOriginAsNoPort() {
        assertEquals(
            "https://example.com/album/photo?id=42",
            downloadReferrer(page, "https://example.com:443/full.jpg"),
        )
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
    fun aRedirectToAnotherSiteKeepsOnlyTheOrigin() {
        // What enqueueWebDownload does after resolving redirects: the policy
        // runs again on the referrer chosen for the first address.
        val first = downloadReferrer(page, image)
        assertEquals("https://example.com/", downloadReferrer(first, "https://cdn.example.net/full.jpg"))
        assertNull(downloadReferrer(first, "http://cdn.example.net/full.jpg"))
        assertEquals("https://example.com/album/photo?id=42", downloadReferrer(first, image))
    }
}
