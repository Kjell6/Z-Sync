package de.kjell.zencompanion

import de.kjell.zencompanion.data.BlockCategory
import de.kjell.zencompanion.data.ContentBlockingSettings
import de.kjell.zencompanion.data.FilterListUpdates
import de.kjell.zencompanion.data.blockingSiteKey
import de.kjell.zencompanion.ui.browser.BlockingCard
import de.kjell.zencompanion.ui.browser.consentScriptSource
import de.kjell.zencompanion.ui.sheets.listDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentBlockingTests {
    @Test
    fun everythingStartsOn() {
        val settings = ContentBlockingSettings()
        BlockCategory.entries.forEach { assertTrue(settings.isOn(it, "news.example")) }
    }

    @Test
    fun siteSwitchesTurnOffOneCategoryForOneSite() {
        val settings = ContentBlockingSettings().withSite(BlockCategory.COOKIE_BANNERS, "www.News.example", on = false)
        assertFalse(settings.isOn(BlockCategory.COOKIE_BANNERS, "news.example"))
        assertTrue(settings.isOn(BlockCategory.ADS, "news.example"))
        assertTrue(settings.isOn(BlockCategory.COOKIE_BANNERS, "other.example"))
        assertEquals(setOf("news.example"), settings.cookieBannerSitesOff())

        val back = settings.withSite(BlockCategory.COOKIE_BANNERS, "news.example", on = true)
        assertTrue(back.isOn(BlockCategory.COOKIE_BANNERS, "news.example"))
    }

    @Test
    fun aSiteCannotTurnOnWhatSettingsTurnedOff() {
        val settings = ContentBlockingSettings().with(BlockCategory.ADS, false)
        assertFalse(settings.isOn(BlockCategory.ADS, "news.example"))
        assertFalse(settings.withSite(BlockCategory.ADS, "news.example", on = true).isOn(BlockCategory.ADS, "news.example"))
    }

    @Test
    fun allOffForSiteOnlyCountsSwitchesThatAreOn() {
        var settings = ContentBlockingSettings().with(BlockCategory.COOKIE_BANNERS, false)
        settings = settings.withSite(BlockCategory.ADS, "news.example", false)
        assertFalse(settings.allOffFor("news.example"))
        settings = settings.withSite(BlockCategory.TRACKERS, "news.example", false)
        assertTrue(settings.allOffFor("news.example"))

        val nothingOn = ContentBlockingSettings(ads = false, trackers = false, cookieBanners = false)
        assertFalse(nothingOn.allOffFor("news.example"))
    }

    @Test
    fun siteKeysDropWwwAndCase() {
        assertEquals("news.example", blockingSiteKey("WWW.News.Example."))
        assertEquals("en.m.wikipedia.org", blockingSiteKey("en.m.wikipedia.org"))
    }

    @Test
    fun cardFollowsThePage() {
        val on = ContentBlockingSettings()
        assertEquals(BlockingCard.Blocked(15), BlockingCard.of(on, "news.example", 15, showingOriginal = false))
        assertEquals(BlockingCard.Hidden, BlockingCard.of(on, "news.example", 0, showingOriginal = false))
        assertEquals(BlockingCard.Hidden, BlockingCard.of(on, null, 4, showingOriginal = false))
        assertEquals(BlockingCard.ShowingOriginal, BlockingCard.of(on, "news.example", 0, showingOriginal = true))

        val off = BlockCategory.entries.fold(on) { s, c -> s.withSite(c, "www.news.example", false) }
        assertEquals(BlockingCard.OffForSite("news.example"), BlockingCard.of(off, "www.news.example", 0, showingOriginal = false))
    }

    @Test
    fun listsAreDueWeeklyAndAfterTheClockMovesBack() {
        val now = 1_800_000_000_000L
        assertTrue(FilterListUpdates.isDue(0, now))
        assertFalse(FilterListUpdates.isDue(now - FilterListUpdates.INTERVAL_MS + 1, now))
        assertTrue(FilterListUpdates.isDue(now - FilterListUpdates.INTERVAL_MS, now))
        assertTrue(FilterListUpdates.isDue(now + 60_000, now))
    }

    @Test
    fun downloadsMustBeRealFilterLists() {
        assertNull(FilterListUpdates.accept("<html>Service unavailable</html>"))
        assertNull(FilterListUpdates.accept("[Adblock Plus 2.0]\n||ads.example^\n"))
        val big = buildString {
            append("[Adblock Plus 2.0]\n! Version: 1\n")
            repeat(FilterListUpdates.MIN_RULES) { append("||ads$it.example^\n") }
        }
        assertNotNull(FilterListUpdates.accept(big))
    }

    @Test
    fun consentScriptSkipsTheSitesTurnedOff() {
        val script = consentScriptSource("BUNDLE();", setOf("b.example", "a.example", "x\"y"))
        assertTrue(script.contains("""var skip = ["a.example","b.example","xy"];"""))
        assertTrue(script.contains("BUNDLE();"))
        assertTrue(script.trim().endsWith("})();"))
    }

    @Test
    fun captionUsesTheListDate() {
        assertEquals("24 Oct 2026", listDate("24 Oct 2026 21:50 UTC"))
        assertNull(listDate("soon"))
    }
}
