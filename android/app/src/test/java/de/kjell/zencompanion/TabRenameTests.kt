package de.kjell.zencompanion

import de.kjell.zencompanion.data.SpacesSyncCacheOps
import de.kjell.zencompanion.sync.SpacesSyncService
import de.kjell.zencompanion.sync.ZenSpaces
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TabRenameTests {
    @Test
    fun displayTitlePrefersStaticLabel() {
        val tab = ZenSpaces.ZenTab(
            id = "t1",
            url = "https://example.com/path",
            title = "Page title",
            staticLabel = "Work",
        )
        assertEquals("Work", tab.displayTitle)
    }

    @Test
    fun displayTitleFallsBackToPageTitle() {
        val tab = ZenSpaces.ZenTab(id = "t1", url = "https://example.com/path", title = "Page title")
        assertEquals("Page title", tab.displayTitle)
    }

    @Test
    fun displayTitleFallsBackToHostWhenTitleEmpty() {
        val tab = ZenSpaces.ZenTab(id = "t1", url = "https://example.com/path", title = "  ")
        assertEquals("example.com", tab.displayTitle)
    }

    @Test
    fun displayTitleIgnoresWhitespaceOnlyStaticLabel() {
        val tab = ZenSpaces.ZenTab(
            id = "t1",
            url = "https://example.com/path",
            title = "Page title",
            staticLabel = "   ",
        )
        assertEquals("Page title", tab.displayTitle)
    }

    @Test
    fun makeTabCopiesStaticLabel() {
        val record = ZenSpaces.ZenTabRecord(
            tabId = "t1",
            url = "https://example.com",
            title = "Page",
            icon = null,
            containerGuid = null,
            essential = false,
            workspaceUuid = "space-1",
            folderId = null,
            staticLabel = "Work",
            hasStaticIcon = null,
            defaultContainer = null,
        )
        val tab = SpacesSyncService.makeTab(record)!!
        assertEquals("Work", tab.staticLabel)
        assertEquals("Work", tab.displayTitle)
    }

    @Test
    fun makeTabDropsWhitespaceOnlyStaticLabel() {
        val record = ZenSpaces.ZenTabRecord(
            tabId = "t1",
            url = "https://example.com",
            title = "Page",
            icon = null,
            containerGuid = null,
            essential = false,
            workspaceUuid = "space-1",
            folderId = null,
            staticLabel = "  ",
            hasStaticIcon = null,
            defaultContainer = null,
        )
        val tab = SpacesSyncService.makeTab(record)!!
        assertNull(tab.staticLabel)
        assertEquals("Page", tab.displayTitle)
    }

    @Test
    fun cacheRenameUpdatesPinnedFolderSplitAndEssentials() {
        val tab = ZenSpaces.ZenTab(id = "t1", url = "https://a.example", title = "A")
        val nested = ZenSpaces.ZenTab(id = "t2", url = "https://b.example", title = "B")
        val member = ZenSpaces.ZenTab(id = "t3", url = "https://c.example", title = "C")
        val snapshot = ZenSpaces.ZenSnapshot(
            spaces = listOf(
                ZenSpaces.ZenSpace(
                    id = "s1",
                    name = "Space",
                    icon = null,
                    containerGuid = null,
                    theme = null,
                    pinned = listOf(
                        ZenSpaces.ZenItem.Tab(tab),
                        ZenSpaces.ZenItem.Folder(
                            ZenSpaces.ZenFolder(id = "f1", name = "Folder", icon = null, tabs = listOf(nested)),
                        ),
                        ZenSpaces.ZenItem.Split(ZenSpaces.ZenSplit(id = "split-1", gridType = null, tabs = listOf(member))),
                    ),
                ),
            ),
            essentials = mapOf("default" to listOf(tab)),
            fetchedAtMillis = 0L,
        )

        val renamed = SpacesSyncCacheOps.renameTab(snapshot, "t1", "Work", 1L)
        assertEquals("Work", pinnedTab(renamed, "t1")?.staticLabel)
        assertEquals("Work", renamed.essentials["default"]?.first()?.staticLabel)
        assertNull(pinnedTab(renamed, "t2")?.staticLabel)
        assertNull(splitMember(renamed, "t3")?.staticLabel)

        val nestedRenamed = SpacesSyncCacheOps.renameTab(snapshot, "t2", "Docs", 1L)
        assertEquals("Docs", folderTab(nestedRenamed, "t2")?.staticLabel)
    }

    private fun pinnedTab(snapshot: ZenSpaces.ZenSnapshot, id: String): ZenSpaces.ZenTab? =
        snapshot.spaces.first().pinned.filterIsInstance<ZenSpaces.ZenItem.Tab>()
            .map { it.tab }
            .firstOrNull { it.id == id }

    private fun folderTab(snapshot: ZenSpaces.ZenSnapshot, id: String): ZenSpaces.ZenTab? =
        snapshot.spaces.first().pinned.filterIsInstance<ZenSpaces.ZenItem.Folder>()
            .flatMap { it.folder.tabs }
            .firstOrNull { it.id == id }

    private fun splitMember(snapshot: ZenSpaces.ZenSnapshot, id: String): ZenSpaces.ZenTab? =
        snapshot.spaces.first().pinned.filterIsInstance<ZenSpaces.ZenItem.Split>()
            .flatMap { it.split.tabs }
            .firstOrNull { it.id == id }
}
