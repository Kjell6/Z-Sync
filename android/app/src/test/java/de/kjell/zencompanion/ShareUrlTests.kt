package de.kjell.zencompanion

import de.kjell.zencompanion.share.looksLikeHttpUrl
import de.kjell.zencompanion.share.sharedLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareUrlTests {
    @Test
    fun httpAndHttpsAreAcceptedRegardlessOfCase() {
        assertTrue(looksLikeHttpUrl("https://example.com"))
        assertTrue(looksLikeHttpUrl("http://example.com/path"))
        assertTrue(looksLikeHttpUrl("HTTPS://EXAMPLE.COM"))
    }

    @Test
    fun fileAndScriptSchemesAreRejected() {
        assertFalse(looksLikeHttpUrl(""))
        assertFalse(looksLikeHttpUrl("file:///tmp/x"))
        assertFalse(looksLikeHttpUrl("javascript:alert(1)"))
        assertFalse(looksLikeHttpUrl("content://media/1"))
        assertFalse(looksLikeHttpUrl("not a url"))
    }

    /** A sentence mark right after the address is never part of it. */
    @Test
    fun linkEndingASentenceKeepsNoPunctuation() {
        assertEquals("https://example.com/a", sharedLink("read https://example.com/a. now"))
        assertEquals("https://example.com/a", sharedLink("https://example.com/a."))
        assertEquals("https://example.com/x", sharedLink("https://example.com/x!?"))
    }

    /** Only the unmatched bracket goes; a pair inside the path stays. */
    @Test
    fun linkInBracketsOrQuotesDropsJustTheMarks() {
        assertEquals("https://example.com/b", sharedLink("read (https://example.com/b) now"))
        assertEquals("https://example.com/b", sharedLink("(https://example.com/b)"))
        assertEquals(
            "https://en.wikipedia.org/wiki/Function_(mathematics)",
            sharedLink("(https://en.wikipedia.org/wiki/Function_(mathematics))"),
        )
        assertEquals("https://example.com/q", sharedLink("he said \"https://example.com/q\""))
        assertEquals("https://example.com/r", sharedLink("look (https://example.com/r)."))
        assertEquals("https://example.com/s", sharedLink("'https://example.com/s'."))
    }

    /** A query and a trailing slash belong to the address. */
    @Test
    fun linkKeepsQueryAndTrailingSlash() {
        assertEquals("https://example.com/a?b=c", sharedLink("https://example.com/a?b=c"))
        assertEquals("https://example.com/a?b=c", sharedLink("see https://example.com/a?b=c."))
        assertEquals("https://example.com/path/", sharedLink("https://example.com/path/"))
    }

    /** A mark the sentence added inside its quotes is the address's own. */
    @Test
    fun quotedLinkKeepsItsOwnMark() {
        assertEquals(
            "https://example.com/search?q=wow!",
            sharedLink("he said \"https://example.com/search?q=wow!\""),
        )
        assertEquals("https://example.com/search?q=wow!", sharedLink("\"https://example.com/search?q=wow!\""))
    }

    /** A Unicode domain has a host, so it is a link; it is kept as shared. */
    @Test
    fun linkWithUnicodeDomainIsAccepted() {
        assertEquals("https://bücher.example/a", sharedLink("https://bücher.example/a"))
        assertEquals("https://bücher.example/a?q=1", sharedLink("see https://bücher.example/a?q=1."))
    }

    @Test
    fun firstOfSeveralLinksWins() {
        assertEquals(
            "https://first.example/a",
            sharedLink("https://first.example/a and https://second.example/b"),
        )
        assertEquals(
            "https://first.example/a",
            sharedLink("look at this https://first.example/a nice"),
        )
    }

    @Test
    fun textWithoutALinkHasNone() {
        assertNull(sharedLink("nothing to see here"))
        assertNull(sharedLink("write to hi@example.com"))
        assertNull(sharedLink("file:///tmp/x"))
        assertNull(sharedLink("https://"))
        assertNull(sharedLink(""))
    }
}
