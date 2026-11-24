package de.kjell.zencompanion

import de.kjell.zencompanion.ui.screens.FxAWeb
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirrors iOS `FxASocialAuthTests`: Continue with Google / Apple stays out of the sign-in WebView. */
class SignInSocialAuthTests {

    @Test
    fun googleAndAppleSignInHostsAreBlocked() {
        assertTrue(FxAWeb.isSocialAuthHost("accounts.google.com"))
        assertTrue(FxAWeb.isSocialAuthHost("ACCOUNTS.GOOGLE.COM"))
        assertTrue(FxAWeb.isSocialAuthHost("appleid.apple.com"))
    }

    @Test
    fun mozillaAndLookalikeHostsAreNotBlocked() {
        assertFalse(FxAWeb.isSocialAuthHost("accounts.firefox.com"))
        assertFalse(FxAWeb.isSocialAuthHost("evilaccounts.google.com"))
        assertFalse(FxAWeb.isSocialAuthHost("apple.com"))
        assertFalse(FxAWeb.isSocialAuthHost(null))
    }
}
