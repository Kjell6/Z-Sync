package de.kjell.zencompanion

import de.kjell.zencompanion.share.looksLikeHttpUrl
import org.junit.Assert.assertFalse
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
}
