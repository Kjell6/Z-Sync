package de.kjell.zencompanion

import de.kjell.zencompanion.ui.screens.DesktopViewportScript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Same script as iOS `DesktopViewportScript`: see `DesktopViewportTests.swift`. */
class DesktopViewportScriptTests {

    @Test
    fun desktopWidthMatchesChromeAndSafari() {
        assertEquals(980, DesktopViewportScript.WIDTH)
    }

    @Test
    fun scriptRewritesViewportTagsToTheDesktopWidth() {
        val source = DesktopViewportScript.source
        assertTrue(source.contains("var wanted = 'width=980';"))
        assertTrue(source.contains("meta[name=\"viewport\" i]"))
        // Later tags too, as single-page apps swap them on navigation.
        assertTrue(source.contains("new MutationObserver"))
        // A replaced head arrives as one node with the tag inside it.
        assertTrue(source.contains("node.querySelector('meta')"))
    }

    @Test
    fun scriptLeavesFramesAlone() {
        assertTrue(DesktopViewportScript.source.contains("if (window.self !== window.top) return;"))
    }
}
