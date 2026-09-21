package de.kjell.zencompanion

import de.kjell.zencompanion.sync.SyncedActivityService
import de.kjell.zencompanion.ui.sheets.filterHistory
import org.junit.Assert.assertEquals
import org.junit.Test

class ActivityFilterTests {
    private fun entry(title: String, url: String) = SyncedActivityService.HistoryEntry(
        title = title,
        url = url,
        lastVisit = null,
    )

    @Test
    fun emptyQueryReturnsAllEntries() {
        val entries = listOf(
            entry("Example", "https://example.com"),
            entry("Mozilla", "https://mozilla.org"),
        )
        assertEquals(entries, filterHistory(entries, ""))
    }

    @Test
    fun filterMatchesTitleOrUrlCaseInsensitively() {
        val entries = listOf(
            entry("Mozilla Blog", "https://blog.mozilla.org/post"),
            entry("Example", "https://example.com"),
            entry("Other", "https://mozilla.com/docs"),
        )
        assertEquals(
            listOf("https://blog.mozilla.org/post"),
            filterHistory(entries, "blog").map { it.url },
        )
        assertEquals(
            listOf("https://blog.mozilla.org/post", "https://mozilla.com/docs"),
            filterHistory(entries, "MOZILLA").map { it.url },
        )
    }
}
