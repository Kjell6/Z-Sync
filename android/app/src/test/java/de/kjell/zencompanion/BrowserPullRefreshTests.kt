package de.kjell.zencompanion

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import de.kjell.zencompanion.ui.screens.BarScrollDecision
import de.kjell.zencompanion.ui.screens.barScrollStep
import de.kjell.zencompanion.ui.screens.childCanScrollUp
import de.kjell.zencompanion.ui.screens.pullRefreshTint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pull layout answers its "can the child scroll up" question before it
 * handles an event. At the touch down the page has not answered yet, so only
 * the document's own offset may keep the layout from recording the touch;
 * every move after it follows the page's permission.
 */
class BrowserPullRefreshTests {

    @Test
    fun aTouchDownAtTheTopLetsTheLayoutRecordIt() {
        assertFalse(childCanScrollUp(isDownEvent = true, scrollY = 0, pageAllowsPull = false))
    }

    @Test
    fun aTouchDownOnAScrolledPageBelongsToThePage() {
        assertTrue(childCanScrollUp(isDownEvent = true, scrollY = 240, pageAllowsPull = false))
        // No permission for this touch can pull a document that is scrolled.
        assertTrue(childCanScrollUp(isDownEvent = true, scrollY = 240, pageAllowsPull = true))
    }

    @Test
    fun aMoveBeforeThePageAnswersIsIgnored() {
        assertTrue(childCanScrollUp(isDownEvent = false, scrollY = 0, pageAllowsPull = false))
    }

    @Test
    fun aMoveWithPermissionAtTheTopMayPull() {
        assertFalse(childCanScrollUp(isDownEvent = false, scrollY = 0, pageAllowsPull = true))
    }

    @Test
    fun aMoveWithPermissionOnAScrolledPageMayNotPull() {
        assertTrue(childCanScrollUp(isDownEvent = false, scrollY = 1, pageAllowsPull = true))
    }
}

/**
 * The bar and the pull indicator follow the page, not the app. Only the
 * reader's own scrolling may tuck the bar away, and the spinner has to
 * contrast with what sits behind it.
 */
class MiniBrowserBarTests {

    @Test
    fun aRestoredOffsetNeverHidesTheBar() {
        // A back or a forward puts the page at its old offset without a finger.
        val step = barScrollStep(delta = 800, scrollY = 800, downRun = 0, userDriven = false)
        assertEquals(BarScrollDecision.KEEP, step.decision)
        assertEquals(0, step.downRun)
    }

    @Test
    fun aDownwardDragTucksTheBarAwayOnceItHasRun() {
        val jitter = barScrollStep(delta = 8, scrollY = 100, downRun = 0, userDriven = true)
        assertEquals(BarScrollDecision.KEEP, jitter.decision)

        val drag = barScrollStep(delta = 30, scrollY = 138, downRun = jitter.downRun, userDriven = true)
        assertEquals(BarScrollDecision.HIDE, drag.decision)
        assertEquals(38, drag.downRun)
    }

    @Test
    fun anUpwardDragBringsTheBarBack() {
        val step = barScrollStep(delta = -12, scrollY = 200, downRun = 40, userDriven = true)
        assertEquals(BarScrollDecision.SHOW, step.decision)
        assertEquals(0, step.downRun)
    }

    @Test
    fun theTopOfAPageKeepsTheBar() {
        // Even a long downward drag at the top cannot hide it.
        val step = barScrollStep(delta = 30, scrollY = 20, downRun = 40, userDriven = true)
        assertEquals(BarScrollDecision.SHOW, step.decision)
    }

    @Test
    fun theSpinnerContrastsWithThePageBehindIt() {
        assertTrue(pullRefreshTint(Color.White).luminance() < 0.5f)
        assertTrue(pullRefreshTint(Color.Black).luminance() > 0.5f)
        // No known page colour: a mid grey that reads on white and on black.
        val unknown = pullRefreshTint(null)
        assertEquals(0.55f, unknown.red, 0.001f)
        assertEquals(unknown.red, unknown.green, 0.001f)
        assertEquals(unknown.red, unknown.blue, 0.001f)
    }
}
