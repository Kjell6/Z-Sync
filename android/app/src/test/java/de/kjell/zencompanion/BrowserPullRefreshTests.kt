package de.kjell.zencompanion

import de.kjell.zencompanion.ui.screens.childCanScrollUp
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
