package de.kjell.zencompanion

import de.kjell.zencompanion.data.SpacesSyncCacheOps
import de.kjell.zencompanion.sync.SpacesSyncService
import de.kjell.zencompanion.sync.ZenSpaces
import de.kjell.zencompanion.ui.components.PinDestinationModel
import de.kjell.zencompanion.ui.components.TabMove
import de.kjell.zencompanion.ui.components.TabPin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun moveIsOfferedOnlyOnRenamablePinnedRows() {
        assertFalse(TabMove.offersMove(renames = true, isNormalTab = true))
        assertTrue(TabMove.offersMove(renames = true, isNormalTab = false))
        assertFalse(TabMove.offersMove(renames = false, isNormalTab = false))
    }

    @Test
    fun pinIsOfferedOnlyOnNormalTabs() {
        assertTrue(TabPin.offersPin(isNormalTab = true))
        assertFalse(TabPin.offersPin(isNormalTab = false))
    }

    @Test
    fun moveDestinationsMarkCurrentPlace() {
        val folders = listOf(
            PinDestinationModel.FolderEntry("outer", "Outer", 0),
            PinDestinationModel.FolderEntry("inner", "Inner", 1),
        )
        val atRoot = TabMove.destinations("Space", folders, null)
        assertEquals(listOf(null, "outer", "inner"), atRoot.map { it.folderId })
        assertTrue(atRoot[0].isCurrent)
        assertEquals(1, atRoot[2].depth)
        assertFalse(atRoot[2].isCurrent)
        assertFalse(TabMove.shouldWrite(null, ""))
        assertFalse(TabMove.shouldWrite(null, null))
        assertTrue(TabMove.shouldWrite(null, "outer"))

        val nested = TabMove.destinations("Space", folders, "inner")
        assertTrue(nested[2].isCurrent)
        assertFalse(nested[0].isCurrent)
        assertFalse(TabMove.shouldWrite("inner", "inner"))
        assertTrue(TabMove.shouldWrite("inner", null))
    }

    @Test
    fun cacheMovePlacesPinnedTabInNestedFolderAndBack() {
        val tab = ZenSpaces.ZenTab(id = "t1", url = "https://a.example", title = "Page", staticLabel = "Keep")
        val inner = ZenSpaces.ZenFolder(id = "inner", name = "Inner", icon = null, tabs = emptyList())
        val outer = ZenSpaces.ZenFolder(
            id = "outer",
            name = "Outer",
            icon = null,
            tabs = emptyList(),
            subfolders = listOf(inner),
        )
        val snapshot = ZenSpaces.ZenSnapshot(
            spaces = listOf(
                ZenSpaces.ZenSpace(
                    id = "s1",
                    name = "Space",
                    icon = null,
                    containerGuid = null,
                    theme = null,
                    pinned = listOf(ZenSpaces.ZenItem.Tab(tab), ZenSpaces.ZenItem.Folder(outer)),
                ),
            ),
            essentials = emptyMap(),
            fetchedAtMillis = 0L,
        )

        val moved = SpacesSyncCacheOps.moveTab(snapshot, "t1", "inner", "s1", 5L)
        assertNull(pinnedTab(moved, "t1"))
        assertEquals("Keep", nestedTab(moved, "inner", "t1")?.staticLabel)
        assertEquals(5L, moved.fetchedAtMillis)

        val rooted = SpacesSyncCacheOps.moveTab(moved, "t1", null, "s1", 9L)
        assertEquals("Keep", pinnedTab(rooted, "t1")?.staticLabel)
        assertNull(nestedTab(rooted, "inner", "t1"))
    }

    @Test
    fun cacheMoveLeavesNormalTabAndSamePlace() {
        val normal = ZenSpaces.ZenTab(id = "n1", url = "https://n.example", title = "Normal")
        val pinned = ZenSpaces.ZenTab(id = "t1", url = "https://a.example", title = "Page", staticLabel = "Keep")
        val snapshot = ZenSpaces.ZenSnapshot(
            spaces = listOf(
                ZenSpaces.ZenSpace(
                    id = "s1",
                    name = "Space",
                    icon = null,
                    containerGuid = null,
                    theme = null,
                    pinned = listOf(
                        ZenSpaces.ZenItem.Folder(
                            ZenSpaces.ZenFolder(id = "f1", name = "Folder", icon = null, tabs = listOf(pinned)),
                        ),
                    ),
                    tabs = listOf(ZenSpaces.ZenItem.Tab(normal)),
                ),
            ),
            essentials = emptyMap(),
            fetchedAtMillis = 0L,
        )

        val normalMoved = SpacesSyncCacheOps.moveTab(snapshot, "n1", "f1", "s1", 5L)
        assertEquals(0L, normalMoved.fetchedAtMillis)
        assertEquals("n1", normalMoved.spaces.first().tabs.first().id)
        assertNull(folderTab(normalMoved, "n1"))

        val same = SpacesSyncCacheOps.moveTab(snapshot, "t1", "f1", "s1", 5L)
        assertEquals(0L, same.fetchedAtMillis)
        assertEquals("Keep", folderTab(same, "t1")?.staticLabel)
    }

    @Test
    fun cachePinMovesNormalTabToPinnedRootKeepingFields() {
        val normal = ZenSpaces.ZenTab(id = "n1", url = "https://n.example", title = "Normal", staticLabel = "Keep")
        val pinned = ZenSpaces.ZenTab(id = "t1", url = "https://a.example", title = "Pinned")
        val snapshot = ZenSpaces.ZenSnapshot(
            spaces = listOf(
                ZenSpaces.ZenSpace(
                    id = "s1",
                    name = "Space",
                    icon = null,
                    containerGuid = null,
                    theme = null,
                    pinned = listOf(ZenSpaces.ZenItem.Tab(pinned)),
                    tabs = listOf(ZenSpaces.ZenItem.Tab(normal)),
                ),
            ),
            essentials = emptyMap(),
            fetchedAtMillis = 0L,
        )

        val pinnedSnapshot = SpacesSyncCacheOps.pinTab(snapshot, "n1", "s1", 5L)
        assertTrue(pinnedSnapshot.spaces.first().tabs.isEmpty())
        assertEquals(listOf("t1", "n1"), pinnedSnapshot.spaces.first().pinned.map { it.id })
        assertEquals("Keep", pinnedTab(pinnedSnapshot, "n1")?.staticLabel)
        assertEquals("https://n.example", pinnedTab(pinnedSnapshot, "n1")?.url)
        assertEquals(5L, pinnedSnapshot.fetchedAtMillis)

        // A tab that is not in the normal bucket (already pinned) is untouched.
        val same = SpacesSyncCacheOps.pinTab(snapshot, "t1", "s1", 5L)
        assertEquals(0L, same.fetchedAtMillis)
        assertEquals(listOf("n1"), same.spaces.first().tabs.map { it.id })
    }

    private fun pinnedTab(snapshot: ZenSpaces.ZenSnapshot, id: String): ZenSpaces.ZenTab? =
        snapshot.spaces.first().pinned.filterIsInstance<ZenSpaces.ZenItem.Tab>()
            .map { it.tab }
            .firstOrNull { it.id == id }

    private fun folderTab(snapshot: ZenSpaces.ZenSnapshot, id: String): ZenSpaces.ZenTab? =
        snapshot.spaces.first().pinned.filterIsInstance<ZenSpaces.ZenItem.Folder>()
            .flatMap { it.folder.tabs }
            .firstOrNull { it.id == id }

    private fun nestedTab(snapshot: ZenSpaces.ZenSnapshot, folderId: String, tabId: String): ZenSpaces.ZenTab? {
        fun search(folders: List<ZenSpaces.ZenFolder>): ZenSpaces.ZenTab? {
            for (folder in folders) {
                if (folder.id == folderId) return folder.tabs.firstOrNull { it.id == tabId }
                search(folder.subfolders.orEmpty())?.let { return it }
            }
            return null
        }
        val folders = snapshot.spaces.first().pinned.filterIsInstance<ZenSpaces.ZenItem.Folder>().map { it.folder }
        return search(folders)
    }

    private fun splitMember(snapshot: ZenSpaces.ZenSnapshot, id: String): ZenSpaces.ZenTab? =
        snapshot.spaces.first().pinned.filterIsInstance<ZenSpaces.ZenItem.Split>()
            .flatMap { it.split.tabs }
            .firstOrNull { it.id == id }
}
