package de.kjell.zencompanion

import de.kjell.zencompanion.util.PageTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageTitleTests {
    private val page = "https://pin.example/page"

    @Test
    fun usableTitleRejectsTheAddress() {
        assertEquals("Pinned Page", PageTitle.usable("Pinned Page", page))
        assertEquals("Tom & Jerry", PageTitle.usable("  Tom   & Jerry  ", page))
        assertNull(PageTitle.usable("   ", page))
        assertNull(PageTitle.usable("https://pin.example/page", page))
        assertNull(PageTitle.usable("https://pin.example/page/", page))
        assertNull(PageTitle.usable("http://www.pin.example/page", page))
        assertNull(PageTitle.usable("pin.example", page))
        assertNull(PageTitle.usable("www.pin.example", page))
        assertNull(PageTitle.usable("pin.example/page", page))
        assertEquals("Node.js", PageTitle.usable("Node.js", page))
    }

    @Test
    fun headlineFallsBackToHost() {
        assertEquals("Pinned Page", PageTitle.headline("Pinned Page", page))
        assertEquals("pin.example", PageTitle.headline("https://pin.example/page/", page))
        assertEquals("pin.example", PageTitle.headline("", page))
        assertEquals("Tab", PageTitle.headline("", null))
    }

    @Test
    fun sameDocumentIgnoresSlashWwwAndFragment() {
        assertTrue(PageTitle.sameDocument(page, "https://www.pin.example/page/"))
        assertTrue(PageTitle.sameDocument(page, "https://pin.example/page#section"))
        assertFalse(PageTitle.sameDocument(page, "https://other.example/page"))
        assertFalse(PageTitle.sameDocument(null, page))
    }

    @Test
    fun extractPrefersDocumentTitleThenSocialTitle() {
        val html = """
            <html><head>
            <title>  Tom &amp; Jerry&#39;s  </title>
            <meta property="og:title" content="Ignored">
            </head></html>
        """.trimIndent()
        assertEquals("Tom & Jerry's", PageTitle.extract(html, page))

        val urlShaped = """
            <title>https://pin.example/page</title>
            <meta property="og:title" content="From Open Graph">
        """.trimIndent()
        assertEquals("From Open Graph", PageTitle.extract(urlShaped, page))

        val twitter = """
            <title></title>
            <meta name="twitter:title" content="Tweet &amp; Title">
        """.trimIndent()
        assertEquals("Tweet & Title", PageTitle.extract(twitter, page))
        assertNull(PageTitle.extract("<html></html>", page))
    }

    @Test
    fun pickJsPayloadPrefersTheTabTitle() {
        assertEquals("Pinned Page", PageTitle.pickJsPayload("\"Pinned Page\\u001eOther\"", page))
        assertEquals(
            "From Open Graph",
            PageTitle.pickJsPayload("\"https://pin.example/page\\u001eFrom Open Graph\"", page),
        )
        assertNull(PageTitle.pickJsPayload("\"https://pin.example/page\\u001e\"", page))
    }
}
