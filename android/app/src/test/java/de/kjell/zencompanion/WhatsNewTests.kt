package de.kjell.zencompanion

import de.kjell.zencompanion.data.WhatsNewController
import de.kjell.zencompanion.data.WhatsNewRelease
import de.kjell.zencompanion.data.WhatsNewRepository
import de.kjell.zencompanion.data.firstLaunchValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsNewTests {
    private class MemoryWhatsNewRepository(
        var version: String? = null,
        var firstLaunch: String? = null,
    ) : WhatsNewRepository {
        override fun shownVersion(): String? = version

        override fun setShownVersion(version: String) {
            this.version = version
        }

        override fun firstLaunchVersion(): String? = firstLaunch
    }

    @Test
    fun freshInstallOfTheReleaseSkipsTheNotes() {
        val repository = MemoryWhatsNewRepository(firstLaunch = firstLaunchValue(usedBefore = false, "1.6"))
        assertFalse(WhatsNewController(repository, "1.6").shouldPresent(isDemo = false))
    }

    @Test
    fun updateFromAnEarlierVersionShowsTheNotes() {
        val repository = MemoryWhatsNewRepository(firstLaunch = firstLaunchValue(usedBefore = true, "1.6"))
        assertEquals(WhatsNewRepository.UPDATED_FROM_EARLIER_VERSION, repository.firstLaunch)
        assertTrue(WhatsNewController(repository, "1.6").shouldPresent(isDemo = false))
    }

    @Test
    fun readerWhoStartedOnTheReleaseGetsTheNextOne() {
        val repository = MemoryWhatsNewRepository(firstLaunch = "1.6")
        assertTrue(
            WhatsNewController(repository, "1.7", releaseVersion = "1.7").shouldPresent(isDemo = false),
        )
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
    fun heroUsesTheSingleHeroImage() {
        assertEquals(R.drawable.whats_new_hero, WhatsNewRelease.heroImageRes)
    }
}

class OnboardingTests {
    @Test
    fun freshInstallShowsTheOnboardingUntilCompleted() {
        val fresh = de.kjell.zencompanion.data.firstLaunchValue(usedBefore = false, "1.6")
        assertTrue(de.kjell.zencompanion.data.shouldShowOnboarding(fresh, completed = false))
        assertFalse(de.kjell.zencompanion.data.shouldShowOnboarding(fresh, completed = true))
    }

    @Test
    fun updateFromAnEarlierVersionSkipsTheOnboarding() {
        val updated = de.kjell.zencompanion.data.firstLaunchValue(usedBefore = true, "1.6")
        assertFalse(de.kjell.zencompanion.data.shouldShowOnboarding(updated, completed = false))
    }

    @Test
    fun unrecordedFirstLaunchStaysQuiet() {
        assertFalse(de.kjell.zencompanion.data.shouldShowOnboarding(null, completed = false))
    }
}
