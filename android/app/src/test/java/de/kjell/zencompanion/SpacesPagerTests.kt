package de.kjell.zencompanion

import de.kjell.zencompanion.ui.screens.shouldApplySettledPage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpacesPagerTests {
    @Test
    fun swipeSettleUpdatesSelection() {
        assertTrue(shouldApplySettledPage(settledPage = 1, selectedIndex = 0, programmaticTarget = null))
    }

    @Test
    fun alreadySelectedSettleIsIgnored() {
        assertFalse(shouldApplySettledPage(settledPage = 2, selectedIndex = 2, programmaticTarget = null))
    }

    @Test
    fun farJumpIgnoresOriginSettle() {
        // Tap space 3 while still visually on 0: pager may report settled 0
        // (failed/no-op animation or pre-jump). Must not revert selection.
        assertFalse(shouldApplySettledPage(settledPage = 0, selectedIndex = 3, programmaticTarget = 3))
    }

    @Test
    fun farJumpIgnoresIntermediateSettle() {
        assertFalse(shouldApplySettledPage(settledPage = 1, selectedIndex = 3, programmaticTarget = 3))
    }

    @Test
    fun farJumpAppliesOnceLanded() {
        assertFalse(
            shouldApplySettledPage(settledPage = 3, selectedIndex = 3, programmaticTarget = 3),
        )
    }

    @Test
    fun userSwipeDuringIdleStillApplies() {
        assertTrue(shouldApplySettledPage(settledPage = 2, selectedIndex = 0, programmaticTarget = null))
    }
}
