package de.kjell.zencompanion

import de.kjell.zencompanion.ui.sheets.AccountOnlySettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which settings rows exist without a Mozilla account. Signed out the account's
 * rows are gone instead of disabled: they would have nothing to change.
 */
class AccountOnlySettingsTests {

    @Test
    fun accountRowsAreGoneSignedOut() {
        assertFalse(AccountOnlySettings.showsSaveKind(signedIn = false))
        assertFalse(AccountOnlySettings.showsEssentialsColumns(signedIn = false))
    }

    @Test
    fun accountRowsAreThereSignedIn() {
        assertTrue(AccountOnlySettings.showsSaveKind(signedIn = true))
        assertTrue(AccountOnlySettings.showsEssentialsColumns(signedIn = true))
    }
}
