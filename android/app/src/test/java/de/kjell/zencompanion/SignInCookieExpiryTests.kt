package de.kjell.zencompanion

import de.kjell.zencompanion.ui.screens.expiringCookieLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure half of the scoped Mozilla sign-in purge: the cookie jar is
 * process-wide, so sign-out expires the sign-in page's own cookies by name
 * instead of clearing the whole jar the mini-browser lives in.
 */
class SignInCookieExpiryTests {

    @Test
    fun everyCookieOfTheHeaderGetsAnExpiringLine() {
        val lines = expiringCookieLines("fxa_session=abc; fxa_uid=42")

        assertEquals(
            listOf(
                "fxa_session=; Max-Age=0; Path=/; Secure",
                "fxa_uid=; Max-Age=0; Path=/; Secure",
            ),
            lines,
        )
    }

    @Test
    fun aDomainCookieIsAlsoExpiredForItsParentDomain() {
        val lines = expiringCookieLines("__cf_bm=xyz", domains = listOf(".firefox.com"))

        assertEquals(
            listOf(
                "__cf_bm=; Max-Age=0; Path=/; Secure",
                "__cf_bm=; Max-Age=0; Path=/; Secure; Domain=.firefox.com",
            ),
            lines,
        )
    }

    @Test
    fun aValueFullOfEqualsSignsStillNamesItsCookie() {
        val lines = expiringCookieLines("token=YWJjZA==; flag=1")

        assertTrue(lines.contains("token=; Max-Age=0; Path=/; Secure"))
        assertTrue(lines.contains("flag=; Max-Age=0; Path=/; Secure"))
    }

    @Test
    fun oneNameIsExpiredOnceEvenWhenTheHeaderRepeatsIt() {
        val lines = expiringCookieLines("same=1; same=2")

        assertEquals(listOf("same=; Max-Age=0; Path=/; Secure"), lines)
    }

    @Test
    fun aCookieValueIsNeverWrittenBackOut() {
        val lines = expiringCookieLines("session=secret-value-123")

        assertTrue(lines.none { it.contains("secret-value-123") })
    }

    @Test
    fun noCookiesMeanNoLines() {
        assertEquals(emptyList<String>(), expiringCookieLines(null))
        assertEquals(emptyList<String>(), expiringCookieLines(""))
        assertEquals(emptyList<String>(), expiringCookieLines("; ;"))
    }
}
