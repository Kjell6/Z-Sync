package de.kjell.zencompanion

import de.kjell.zencompanion.sync.ZenSpaces
import de.kjell.zencompanion.ui.BrowserState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sidebar-sync detection: when to offer the setup hint. */
class SyncSetupTests {
    private fun space(
        id: String = "space-1",
        pinned: List<ZenSpaces.ZenItem> = emptyList(),
        tabs: List<ZenSpaces.ZenItem> = emptyList(),
    ) = ZenSpaces.ZenSpace(
        id = id,
        name = "Space",
        icon = null,
        containerGuid = null,
        theme = null,
        pinned = pinned,
        tabs = tabs,
    )

    private fun tab(id: String = "tab-1") =
        ZenSpaces.ZenTab(id = id, url = "https://example.com", title = "Example")

    @Test
    fun emptySnapshotHasNothingSynced() {
        assertFalse(ZenSpaces.ZenSnapshot.empty.hasSyncedTabs)
    }

    @Test
    fun spacesWithoutPinsOrEssentialsHaveNothingSynced() {
        val snapshot = ZenSpaces.ZenSnapshot(listOf(space()), emptyMap(), 0L)
        assertFalse(snapshot.hasSyncedTabs)
    }

    @Test
    fun pinnedTabCountsAsSynced() {
        val snapshot = ZenSpaces.ZenSnapshot(
            listOf(space(pinned = listOf(ZenSpaces.ZenItem.Tab(tab())))),
            emptyMap(),
            0L,
        )
        assertTrue(snapshot.hasSyncedTabs)
    }

    @Test
    fun essentialsCountAsSynced() {
        val snapshot = ZenSpaces.ZenSnapshot(
            listOf(space()),
            mapOf("default" to listOf(tab())),
            0L,
        )
        assertTrue(snapshot.hasSyncedTabs)
    }

    @Test
    fun normalTabsCountAsSynced() {
        val snapshot = ZenSpaces.ZenSnapshot(
            listOf(space(tabs = listOf(ZenSpaces.ZenItem.Tab(tab())))),
            emptyMap(),
            0L,
        )
        assertTrue(snapshot.hasSyncedTabs)
    }

    @Test
    fun emptyEssentialBucketsDoNotCount() {
        val snapshot = ZenSpaces.ZenSnapshot(
            listOf(space()),
            mapOf("default" to emptyList()),
            0L,
        )
        assertFalse(snapshot.hasSyncedTabs)
    }

    @Test
    fun setupHintShowsForSpacesWithoutSyncedTabs() {
        val state = BrowserState(snapshot = ZenSpaces.ZenSnapshot(listOf(space()), emptyMap(), 0L))
        assertTrue(state.showSyncSetupHint)
    }

    @Test
    fun setupHintHiddenAfterDismissal() {
        val state = BrowserState(
            snapshot = ZenSpaces.ZenSnapshot(listOf(space()), emptyMap(), 0L),
            syncSetupHintDismissed = true,
        )
        assertFalse(state.showSyncSetupHint)
    }

    @Test
    fun setupHintHiddenWithSyncedTabs() {
        val snapshot = ZenSpaces.ZenSnapshot(
            listOf(space(pinned = listOf(ZenSpaces.ZenItem.Tab(tab())))),
            emptyMap(),
            0L,
        )
        assertFalse(BrowserState(snapshot = snapshot).showSyncSetupHint)
    }

    @Test
    fun setupHintHiddenWithoutSpaces() {
        assertFalse(BrowserState(snapshot = ZenSpaces.ZenSnapshot.empty).showSyncSetupHint)
    }
}
