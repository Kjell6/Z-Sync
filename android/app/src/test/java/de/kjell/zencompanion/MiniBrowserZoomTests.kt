package de.kjell.zencompanion

import de.kjell.zencompanion.ui.browser.MiniBrowserZoom
import de.kjell.zencompanion.ui.screens.PageZoomScript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniBrowserZoomTests {

    @Test
    fun stepsClampToHalfAndDouble() {
        assertEquals(1.1f, MiniBrowserZoom.clamped(1.0f + MiniBrowserZoom.step), 0.001f)
        assertEquals(2.0f, MiniBrowserZoom.clamped(9f), 0.001f)
        assertEquals(0.5f, MiniBrowserZoom.clamped(0.01f), 0.001f)
        assertFalse(MiniBrowserZoom.canZoomIn(2f))
        assertFalse(MiniBrowserZoom.canZoomOut(0.5f))
    }

    @Test
    fun labelsAreWholePercents() {
        assertEquals("100%", MiniBrowserZoom.percentLabel(1f))
        assertEquals("50%", MiniBrowserZoom.percentLabel(0.5f))
        assertEquals("110%", MiniBrowserZoom.percentLabel(1.1f))
    }

    @Test
    fun resetIsOnlyOfferedWhenZoomed() {
        assertFalse(MiniBrowserZoom.canReset(MiniBrowserZoom.default))
        assertTrue(MiniBrowserZoom.canReset(1.1f))
        assertTrue(MiniBrowserZoom.canReset(0.9f))
    }

    @Test
    fun scriptZoomsTheRootElement() {
        assertTrue(PageZoomScript.source(1.1f).contains("root.style.zoom = '1.10';"))
        assertTrue(PageZoomScript.source(0.5f).contains("root.style.zoom = '0.50';"))
        // Frames are already scaled with their parent's layout.
        assertTrue(PageZoomScript.source(1.1f).contains("window.self !== window.top"))
        // 100% takes the property off again instead of pinning it at 1.
        assertTrue(PageZoomScript.source(1f).contains("root.style.removeProperty('zoom');"))
        assertFalse(PageZoomScript.source(1f).contains("style.zoom ="))
    }
}
