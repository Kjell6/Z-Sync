package de.kjell.zencompanion

import de.kjell.zencompanion.data.WhatsNewController
import de.kjell.zencompanion.data.WhatsNewRelease
import de.kjell.zencompanion.data.WhatsNewRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsNewTests {
    private class MemoryWhatsNewRepository(var version: String? = null) : WhatsNewRepository {
        override fun shownVersion(): String? = version

        override fun setShownVersion(version: String) {
            this.version = version
        }
    }

    @Test
    fun releaseTargetsVersion16() {
        assertEquals("1.6", WhatsNewRelease.version)
    }

    @Test
    fun presentsOnceForTheReleaseVersion() {
        val repository = MemoryWhatsNewRepository()
        val controller = WhatsNewController(repository, "1.6")

        assertTrue(controller.shouldPresent(isDemo = false))

        controller.markPresented()

        assertFalse(controller.shouldPresent(isDemo = false))
        assertFalse(WhatsNewController(repository, "1.6").shouldPresent(isDemo = false))
    }

    @Test
    fun doesNotPresentBeforeTheReleaseVersion() {
        assertFalse(WhatsNewController(MemoryWhatsNewRepository(), "1.5").shouldPresent(isDemo = false))
        assertFalse(WhatsNewController(MemoryWhatsNewRepository(), "1.6.1").shouldPresent(isDemo = false))
        assertTrue(WhatsNewController(MemoryWhatsNewRepository(), "1.6").shouldPresent(isDemo = false))
    }

    @Test
    fun presentsAgainForANewerRelease() {
        val repository = MemoryWhatsNewRepository()
        WhatsNewController(repository, "1.6").markPresented()

        assertFalse(WhatsNewController(repository, "1.6").shouldPresent(isDemo = false))
        assertFalse(WhatsNewController(repository, "1.7").shouldPresent(isDemo = false))
        assertTrue(
            WhatsNewController(repository, "1.7", releaseVersion = "1.7")
                .shouldPresent(isDemo = false),
        )
    }

    @Test
    fun neverMarksTheSheetInDemoMode() {
        val repository = MemoryWhatsNewRepository()
        val controller = WhatsNewController(repository, "1.6")

        assertFalse(controller.shouldPresent(isDemo = true))
        assertNull(repository.version)
    }

    @Test
    fun markingTwiceKeepsTheSameVersion() {
        val repository = MemoryWhatsNewRepository()
        val controller = WhatsNewController(repository, "1.6")

        controller.markPresented()
        controller.markPresented()

        assertEquals("1.6", repository.version)
    }

    @Test
    fun releaseHasHeaderResources() {
        assertNotEquals(0, WhatsNewRelease.eyebrowRes)
        assertNotEquals(0, WhatsNewRelease.headlineRes)
        assertNotEquals(0, WhatsNewRelease.subtitleRes)
        assertNotEquals(0, WhatsNewRelease.featuresHeadingRes)
        assertNotEquals(0, WhatsNewRelease.heroDescriptionRes)
    }

    @Test
    fun releaseItemsHaveUniqueIdsAndResources() {
        val items = WhatsNewRelease.items

        assertTrue(items.isNotEmpty())
        assertEquals(items.size, items.map { it.id }.toSet().size)
        items.forEach { item ->
            assertTrue(item.id.isNotBlank())
            assertTrue("${item.id} needs a title", item.titleRes != 0)
        }
    }

    @Test
    fun pillsCoverTheNewBrowserInOrder() {
        assertEquals(
            listOf("history", "ad-blocking", "suggestions", "downloads", "bottom-bar", "more"),
            WhatsNewRelease.items.map { it.id },
        )
    }

    @Test
    fun heroShowsTheThreeScreenshotsInOrder() {
        val cards = WhatsNewRelease.heroCards
        assertEquals(
            listOf(R.drawable.whats_new_search, R.drawable.whats_new_deck, R.drawable.whats_new_page),
            listOf(cards.leading, cards.center, cards.trailing),
        )
    }
}
