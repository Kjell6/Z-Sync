package de.kjell.zencompanion

import de.kjell.zencompanion.data.BrowserVisit
import de.kjell.zencompanion.data.SearchSuggestions
import de.kjell.zencompanion.data.historyAddress
import de.kjell.zencompanion.data.searchVisits
import de.kjell.zencompanion.data.visitSuggestions
import de.kjell.zencompanion.data.withVisit
import de.kjell.zencompanion.data.withVisitTitle
import de.kjell.zencompanion.ui.screens.dayOf
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserHistoryTests {
    private fun List<BrowserVisit>.with(url: String, title: String = "") = withVisit(this, url, title, now = 1L)

    @Test
    fun onlyWebPagesAreKept() {
        assertNull(historyAddress("about:blank"))
        assertNull(historyAddress("blob:https://example.com/1"))
        assertNull(historyAddress(null))
        val visits = emptyList<BrowserVisit>()
        assertSame(visits, withVisit(visits, "data:text/plain,hi", "", 1L))
    }

    @Test
    fun aJumpInsideAPageIsThePage() {
        val visits = emptyList<BrowserVisit>().with("https://example.com/a#one").with("https://example.com/a#two")
        assertEquals(listOf("https://example.com/a"), visits.map { it.url })
        assertEquals(2, visits.first().visits)
    }

    @Test
    fun aReturnMovesThePageToTheTopAndKeepsItsTitle() {
        val visits = emptyList<BrowserVisit>()
            .with("https://a.example", "A")
            .with("https://b.example", "B")
            .with("https://a.example")
        assertEquals(listOf("https://a.example", "https://b.example"), visits.map { it.url })
        assertEquals("A", visits.first().title)
    }

    @Test
    fun theOldestPagesGoFirst() {
        var visits = emptyList<BrowserVisit>()
        repeat(4) { visits = withVisit(visits, "https://example.com/$it", "", 1L, limit = 3) }
        assertEquals(3, visits.size)
        assertTrue(visits.none { it.url.endsWith("/0") })
    }

    @Test
    fun searchNeedsEveryWordInTitleOrAddress() {
        val visits = emptyList<BrowserVisit>()
            .with("https://news.example/rust", "Hello Rust")
            .with("https://news.example/go", "Hello Go")
        assertEquals(listOf("Hello Rust"), searchVisits(visits, "hello RUST").map { it.title })
        assertEquals(2, searchVisits(visits, "").size)
        assertTrue(searchVisits(visits, "python").isEmpty())
    }

    @Test
    fun suggestionsPutTheSiteNameFirstThenTheMostVisited() {
        val visits = emptyList<BrowserVisit>()
            .with("https://other.example/github", "About GitHub")
            .with("https://github.com/a", "A")
            .with("https://github.com/b", "B")
            .with("https://github.com/b", "B")
        assertEquals(
            listOf("https://github.com/b", "https://github.com/a", "https://other.example/github"),
            visitSuggestions(visits, "git", limit = 3).map { it.url },
        )
        assertEquals(2, visitSuggestions(visits, "git").size)
    }

    @Test
    fun onlyARealMatchIsSuggested() {
        val visits = emptyList<BrowserVisit>().with("https://github.com/login?next=rust", "Sign in to GitHub")
        assertEquals(1, visitSuggestions(visits, "sign").size)
        assertEquals(1, visitSuggestions(visits, "https://www.github").size)
        assertEquals(1, visitSuggestions(visits, "log").size)
        assertTrue(visitSuggestions(visits, "hub").isEmpty())
        assertTrue(visitSuggestions(visits, "rust").isEmpty())
        assertTrue(visitSuggestions(visits, "g").isEmpty())
        assertTrue(visitSuggestions(visits, "sign python").isEmpty())
    }

    @Test
    fun theListNeverPassesEightRows() {
        val eight = (1..8).map(Int::toString)
        assertEquals(8, SearchSuggestions.fitting(eight, 0).size)
        assertEquals(eight.take(6), SearchSuggestions.fitting(eight, 2))
        assertEquals(listOf("a"), SearchSuggestions.fitting(listOf("a"), 2))
    }

    @Test
    fun suggestionsLeaveOutTheOpenPageAndBlankText() {
        val visits = emptyList<BrowserVisit>().with("https://example.com/", "Example")
        assertTrue(visitSuggestions(visits, "example", current = "https://example.com/").isEmpty())
        assertTrue(visitSuggestions(visits, "   ").isEmpty())
    }

    @Test
    fun aLateTitleReplacesTheOldOneButEmptyDoesNot() {
        val visits = emptyList<BrowserVisit>().with("https://a.example", "Old")
        assertEquals("New", withVisitTitle(visits, "https://a.example", "New").first().title)
        assertSame(visits, withVisitTitle(visits, "https://a.example", ""))
        assertSame(visits, withVisitTitle(visits, "https://b.example", "New"))
    }

    @Test
    fun aHostDropsItsWww() {
        assertEquals("example.com", BrowserVisit("https://www.example.com/a?b", "", 1L, 1).host)
    }

    @Test
    fun theShownAddressTellsPagesOfASiteApart() {
        assertEquals("example.com/a?b=1", BrowserVisit("https://www.example.com/a?b=1", "", 1L, 1).displayAddress)
        assertEquals("example.com", BrowserVisit("https://example.com/", "", 1L, 1).displayAddress)
    }

    @Test
    fun visitsOfOneDayShareAGroup() {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        val morning = 1_800_000_000_000L
        assertEquals(dayOf(morning, calendar), dayOf(morning + 60_000L, calendar))
        assertNotEquals(dayOf(morning, calendar), dayOf(morning + 24 * 60 * 60_000L, calendar))
    }
}
