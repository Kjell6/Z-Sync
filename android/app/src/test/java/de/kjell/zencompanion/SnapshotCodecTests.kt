package de.kjell.zencompanion

import de.kjell.zencompanion.data.SaveKind
import de.kjell.zencompanion.sync.ZenSpaces
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cache codec (mirrors Swift behavior). */
class SnapshotCodecTests {
    @Test
    fun snapshotEncodeDecodeRoundTrip() {
        val space = ZenSpaces.ZenSpace(
            id = "s1",
            name = "Work",
            icon = "💼",
            containerGuid = null,
            theme = ZenSpaces.ZenSpaceTheme(
                type = "gradient",
                dots = listOf(
                    ZenSpaces.ZenThemeDot(color = ZenSpaces.ZenColorValue.fromHex("#f76f53")!!),
                    ZenSpaces.ZenThemeDot(color = ZenSpaces.ZenColorValue.fromHex("#ffd9a0")!!, isCustom = true),
                ),
                opacity = 0.5,
                texture = 0.2,
                lightness = null,
            ),
            pinned = listOf(
                ZenSpaces.ZenItem.Tab(ZenSpaces.ZenTab(id = "t1", url = "https://a.de", title = "A")),
                ZenSpaces.ZenItem.Folder(
                    ZenSpaces.ZenFolder(
                        id = "f1", name = "Docs", icon = null,
                        tabs = listOf(ZenSpaces.ZenTab(id = "t2", url = "https://b.de", title = "B")),
                        subfolders = listOf(
                            ZenSpaces.ZenFolder(
                                id = "f2", name = "Specs", icon = "page",
                                tabs = listOf(ZenSpaces.ZenTab(id = "t3", url = "https://c.de", title = "C")),
                            ),
                        ),
                    )
                ),
            ),
        )
        val snapshot = ZenSpaces.ZenSnapshot(
            spaces = listOf(space),
            essentials = mapOf("default" to listOf(ZenSpaces.ZenTab("t4", "https://d.de", "D"))),
            fetchedAtMillis = 1_700_000_000_000L,
        )

        val encoded = ZenSpaces.encodeSnapshot(snapshot)
        val decoded = ZenSpaces.decodeSnapshot(JSONObject(encoded.toString()))!!

        assertEquals(1, decoded.spaces.size)
        val s = decoded.spaces[0]
        assertEquals("Work", s.name)
        assertEquals(2, s.theme?.dots?.size)
        assertEquals("#f76f53", s.themeColors.first())
        assertEquals(2, s.pinned.size)
        assertTrue(s.pinned[0] is ZenSpaces.ZenItem.Tab)
        assertTrue(s.pinned[1] is ZenSpaces.ZenItem.Folder)
        val folder = (s.pinned[1] as ZenSpaces.ZenItem.Folder).folder
        assertEquals("Docs", folder.name)
        assertEquals("Specs", folder.subfolders?.single()?.name)
        assertEquals("t3", folder.subfolders?.single()?.tabs?.single()?.id)
        assertEquals(1, decoded.essentials["default"]?.size)
    }

    @Test
    fun childrenReplaceSplicesMembersInPlace() {
        assertEquals(
            listOf("x", "tab-a", "tab-b", "y"),
            de.kjell.zencompanion.sync.SpacesSyncEdits.replacing(
                "split-1",
                listOf("tab-a", "tab-b"),
                listOf("x", "split-1", "y"),
            ),
        )
        assertEquals(
            listOf("tab-b"),
            de.kjell.zencompanion.sync.SpacesSyncEdits.removing("tab-a", listOf("tab-a", "tab-b")),
        )
    }

    @Test
    fun cacheExpandSplitKeepsMemberTabs() {
        val a = ZenSpaces.ZenTab(id = "tab-a", url = "https://a.de", title = "A")
        val b = ZenSpaces.ZenTab(id = "tab-b", url = "https://b.de", title = "B")
        val space = ZenSpaces.ZenSpace(
            id = "s1",
            name = "Work",
            icon = null,
            containerGuid = null,
            theme = null,
            pinned = listOf(
                ZenSpaces.ZenItem.Split(ZenSpaces.ZenSplit(id = "split-1", gridType = "vsep", tabs = listOf(a, b))),
                ZenSpaces.ZenItem.Tab(ZenSpaces.ZenTab(id = "tab-c", url = "https://c.de", title = "C")),
            ),
        )
        val snap = de.kjell.zencompanion.data.SpacesSyncCacheOps.expandSplit(
            ZenSpaces.ZenSnapshot(spaces = listOf(space), essentials = emptyMap(), fetchedAtMillis = 0L),
            "split-1",
            1L,
        )
        assertEquals(listOf("tab-a", "tab-b", "tab-c"), snap.spaces[0].pinned.map { it.id })
    }

    @Test
    fun cacheRemoveTabFromFolderAndCollapsesSplit() {
        val a = ZenSpaces.ZenTab(id = "tab-a", url = "https://a.de", title = "A")
        val b = ZenSpaces.ZenTab(id = "tab-b", url = "https://b.de", title = "B")
        val space = ZenSpaces.ZenSpace(
            id = "s1",
            name = "Work",
            icon = null,
            containerGuid = null,
            theme = null,
            pinned = listOf(
                ZenSpaces.ZenItem.Folder(ZenSpaces.ZenFolder(id = "f1", name = "Docs", icon = null, tabs = listOf(a))),
                ZenSpaces.ZenItem.Split(ZenSpaces.ZenSplit(id = "split-1", gridType = null, tabs = listOf(a, b))),
            ),
        )
        val snap = de.kjell.zencompanion.data.SpacesSyncCacheOps.removeTab(
            ZenSpaces.ZenSnapshot(
                spaces = listOf(space),
                essentials = mapOf("default" to listOf(a)),
                fetchedAtMillis = 0L,
            ),
            "tab-a",
            1L,
        )
        assertEquals(2, snap.spaces[0].pinned.size)
        val folder = snap.spaces[0].pinned[0] as ZenSpaces.ZenItem.Folder
        assertTrue(folder.folder.tabs.isEmpty())
        val leftover = snap.spaces[0].pinned[1] as ZenSpaces.ZenItem.Tab
        assertEquals("tab-b", leftover.tab.id)
        assertTrue(snap.essentials["default"].orEmpty().isEmpty())
    }

    /** Legacy caches written before essentials existed keep decoding. */
    @Test
    fun legacySnapshotWithoutEssentialsDecodes() {
        val json = """
            {"spaces":[{"id":"s1","name":"Work","icon":"💼","themeColors":[],"themeOpacity":null,"pinned":[]}],"fetchedAt":7200000.0}
        """.trimIndent()
        val snapshot = ZenSpaces.decodeSnapshot(JSONObject(json))!!
        assertEquals(1, snapshot.spaces.size)
        assertTrue(snapshot.essentials.isEmpty())
    }

    /** Legacy caches written before the capability field existed: ABSENT. */
    @Test
    fun legacySnapshotWithoutCapabilityDecodesAsAbsent() {
        val json = """
            {"spaces":[{"id":"s1","name":"Work","pinned":[],"tabs":[]}],"essentials":{},"fetchedAt":0.0}
        """.trimIndent()
        val snapshot = ZenSpaces.decodeSnapshot(JSONObject(json))!!
        assertEquals(ZenSpaces.NormalTabsCapability.ABSENT, snapshot.normalTabsCapability)
    }

    /** The new field participates in the cache round-trip. */
    @Test
    fun capabilityRoundTripsThroughCache() {
        val snapshot = ZenSpaces.ZenSnapshot(
            spaces = emptyList(),
            essentials = emptyMap(),
            fetchedAtMillis = 0L,
            normalTabsCapability = ZenSpaces.NormalTabsCapability.ENABLED,
        )
        val decoded = ZenSpaces.decodeSnapshot(ZenSpaces.encodeSnapshot(snapshot))!!
        assertEquals(ZenSpaces.NormalTabsCapability.ENABLED, decoded.normalTabsCapability)
    }

    /** A normal optimistic insert lands in the `tabs` bucket, never a folder. */
    @Test
    fun normalInsertGoesToTabsBucketAndIgnoresFolder() {
        val space = ZenSpaces.ZenSpace(
            id = "s1", name = "Work", icon = null, containerGuid = null, theme = null,
            pinned = emptyList(),
            tabs = emptyList(),
        )
        val snapshot = ZenSpaces.ZenSnapshot(listOf(space), emptyMap(), 0L)
        val updated = de.kjell.zencompanion.data.SpacesSyncCacheOps.insertTab(
            snapshot,
            spaceId = "s1",
            tab = ZenSpaces.ZenTab("n1", "https://n.de", "N"),
            folderId = "f1",
            fetchedAtMillis = 1L,
            kind = SaveKind.NORMAL,
        )
        assertTrue(updated.spaces[0].pinned.isEmpty())
        assertEquals(listOf("n1"), updated.spaces[0].tabs.map { it.id })
    }

    @Test
    fun normalTabsRoundTripThroughCache() {
        val space = ZenSpaces.ZenSpace(
            id = "s1", name = "Work", icon = null, containerGuid = null, theme = null,
            pinned = listOf(ZenSpaces.ZenItem.Tab(ZenSpaces.ZenTab("p", "https://p.de", "P"))),
            tabs = listOf(ZenSpaces.ZenItem.Tab(ZenSpaces.ZenTab("n", "https://n.de", "N"))),
        )
        val encoded = ZenSpaces.encodeSnapshot(
            ZenSpaces.ZenSnapshot(listOf(space), emptyMap(), 0L),
        )
        val decoded = ZenSpaces.decodeSnapshot(encoded)!!
        assertEquals(listOf("p"), decoded.spaces[0].pinned.map { it.id })
        assertEquals(listOf("n"), decoded.spaces[0].tabs.map { it.id })
    }

    @Test
    fun cacheRemoveTabClearsNormalTabs() {
        val space = ZenSpaces.ZenSpace(
            id = "s1", name = "Work", icon = null, containerGuid = null, theme = null,
            pinned = emptyList(),
            tabs = listOf(ZenSpaces.ZenItem.Tab(ZenSpaces.ZenTab("n", "https://n.de", "N"))),
        )
        val snap = de.kjell.zencompanion.data.SpacesSyncCacheOps.removeTab(
            ZenSpaces.ZenSnapshot(listOf(space), emptyMap(), 0L), "n", 1L,
        )
        assertTrue(snap.spaces[0].tabs.isEmpty())
    }

    @Test
    fun cacheExpandNormalSplitKeepsMemberTabs() {
        val space = ZenSpaces.ZenSpace(
            id = "s1", name = "Work", icon = null, containerGuid = null, theme = null,
            pinned = listOf(ZenSpaces.ZenItem.Tab(ZenSpaces.ZenTab("p1", "https://p1.de", "P1"))),
            tabs = listOf(
                ZenSpaces.ZenItem.Split(
                    ZenSpaces.ZenSplit(
                        id = "split-n",
                        gridType = "vsep",
                        tabs = listOf(
                            ZenSpaces.ZenTab("n1", "https://n1.de", "N1"),
                            ZenSpaces.ZenTab("n2", "https://n2.de", "N2"),
                        ),
                    ),
                ),
            ),
        )
        val snap = de.kjell.zencompanion.data.SpacesSyncCacheOps.expandSplit(
            ZenSpaces.ZenSnapshot(listOf(space), emptyMap(), 0L), "split-n", 1L,
        )
        assertEquals(listOf("p1"), snap.spaces[0].pinned.map { it.id })
        assertEquals(listOf("n1", "n2"), snap.spaces[0].tabs.map { it.id })
    }

    /**
     * D1 pin: the local cache is device-local and non-contractual (SPEC §8).
     * Android writes the discriminated shape `{"type":"tab","tab":{…}}`
     * while iOS Codable writes `{"tab":{"_0":{…}}}` — this test pins the
     * Android shape so nobody "fixes" it towards iOS without bumping the
     * contract version.
     */
    @Test
    fun localCacheKeepsAndroidDiscriminatedShape() {
        val space = ZenSpaces.ZenSpace(
            id = "s1", name = "Work", icon = null, containerGuid = null, theme = null,
            pinned = listOf(
                ZenSpaces.ZenItem.Tab(ZenSpaces.ZenTab("t1", "https://a.de", "A")),
                ZenSpaces.ZenItem.Folder(
                    ZenSpaces.ZenFolder(
                        id = "f1", name = "Docs", icon = null,
                        tabs = listOf(ZenSpaces.ZenTab("t2", "https://b.de", "B")),
                    )
                ),
                ZenSpaces.ZenItem.Split(
                    ZenSpaces.ZenSplit(
                        id = "sp1", gridType = "vsep",
                        tabs = listOf(
                            ZenSpaces.ZenTab("t3", "https://c.de", "C"),
                            ZenSpaces.ZenTab("t4", "https://d.de", "D"),
                        ),
                    )
                ),
            ),
        )
        val encoded = ZenSpaces.encodeSnapshot(
            ZenSpaces.ZenSnapshot(listOf(space), emptyMap(), 0L),
        )
        val entries = encoded.getJSONArray("spaces").getJSONObject(0).getJSONArray("pinned")

        val tabEntry = entries.getJSONObject(0)
        assertEquals("tab", tabEntry.getString("type"))
        assertEquals("t1", tabEntry.getJSONObject("tab").getString("id"))
        assertFalse("iOS enum shape must not appear", tabEntry.has("_0"))

        val folderEntry = entries.getJSONObject(1)
        assertEquals("folder", folderEntry.getString("type"))
        assertEquals("f1", folderEntry.getJSONObject("folder").getString("id"))
        assertFalse(folderEntry.has("_0"))

        val splitEntry = entries.getJSONObject(2)
        assertEquals("split", splitEntry.getString("type"))
        assertEquals("sp1", splitEntry.getJSONObject("split").getString("id"))
        assertFalse(splitEntry.has("_0"))

        // And the shape round-trips through the decoder.
        val decoded = ZenSpaces.decodeSnapshot(encoded)!!
        assertEquals(listOf("t1", "f1", "sp1"), decoded.spaces[0].pinned.map { it.id })
    }
}
