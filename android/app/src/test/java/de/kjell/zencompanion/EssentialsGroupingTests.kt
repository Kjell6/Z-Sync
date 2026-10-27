package de.kjell.zencompanion

import de.kjell.zencompanion.sync.ZenSpaces
import de.kjell.zencompanion.ui.screens.essentialsGridsMatch
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Container-specific essentials: the synced Zen pref wins, a pref the browser
 * does not sync falls back to wire-bucket inference, and the per-space lookup
 * mirrors Zen Desktop's `_shouldShowTab` rules.
 */
class EssentialsGroupingTests {
    private fun tab(id: String) = ZenSpaces.ZenTab(id = id, url = "https://$id.example", title = id)

    private fun space(id: String, containerGuid: String? = null) = ZenSpaces.ZenSpace(
        id = id,
        name = id,
        icon = null,
        containerGuid = containerGuid,
        theme = null,
        pinned = emptyList(),
    )

    private fun snapshot(
        spaces: List<ZenSpaces.ZenSpace>,
        essentials: Map<String, List<ZenSpaces.ZenTab>>,
        separatePref: Boolean? = null,
    ) = ZenSpaces.ZenSnapshot(spaces, essentials, 0L, separatePref)

    private fun ids(tabs: List<ZenSpaces.ZenTab>): List<String> = tabs.map { it.id }

    // MARK: Inference

    @Test
    fun allDefaultBucketsInferShared() {
        val snap = snapshot(
            spaces = listOf(space("work", "container-work")),
            essentials = mapOf("default" to listOf(tab("a"), tab("b"))),
        )
        assertFalse(snap.isContainerSpecific)
        assertEquals(
            "all-default essentials must show on container spaces too",
            listOf("a", "b"),
            ids(snap.essentialsFor(space("work", "container-work"))),
        )
    }

    @Test
    fun emptyEssentialsInferShared() {
        val snap = snapshot(spaces = listOf(space("s")), essentials = emptyMap())
        assertFalse(snap.isContainerSpecific)
        assertTrue(snap.essentialsFor(space("s")).isEmpty())
    }

    @Test
    fun containerBucketsInferContainerSpecific() {
        val snap = snapshot(
            spaces = listOf(space("work", "container-work")),
            essentials = mapOf("container-work" to listOf(tab("a"))),
        )
        assertTrue(snap.isContainerSpecific)
        assertEquals(listOf("a"), ids(snap.essentialsFor(space("work", "container-work"))))
    }

    // MARK: Synced pref

    @Test
    fun syncedPrefWinsOverInference() {
        val separate = snapshot(
            spaces = listOf(space("work", "container-work")),
            essentials = mapOf("container-work" to listOf(tab("a"))),
            separatePref = true,
        )
        assertTrue(separate.isContainerSpecific)

        val shared = snapshot(
            spaces = listOf(space("work", "container-work")),
            essentials = mapOf("container-work" to listOf(tab("a"))),
            separatePref = false,
        )
        assertFalse(shared.isContainerSpecific)
        assertEquals(
            "pref off shows every essential on every space",
            listOf("a"),
            ids(shared.essentialsFor(space("work", "container-work"))),
        )
    }

    // MARK: Per-space lookup

    @Test
    fun sharedUnionKeepsDefaultFirstAndDeduplicates() {
        val snap = snapshot(
            spaces = listOf(space("s")),
            essentials = mapOf(
                "b" to listOf(tab("t3")),
                "default" to listOf(tab("t1"), tab("t2")),
            ),
        )
        assertEquals(listOf("t1", "t2", "t3"), ids(snap.essentialsFor(space("s"))))
    }

    // MARK: URL de-duplication across buckets

    @Test
    fun sharedMergeDeduplicatesSameUrlAcrossBuckets() {
        val snap = snapshot(
            spaces = listOf(space("s")),
            essentials = mapOf(
                "default" to listOf(ZenSpaces.ZenTab("t1", "https://dup.example/", "dup")),
                "work" to listOf(ZenSpaces.ZenTab("t2", "https://dup.example/", "dup")),
            ),
        )
        assertEquals(
            "the first bucket wins; the per-container copy is dropped",
            listOf("t1"),
            ids(snap.essentialsFor(space("s"))),
        )
    }

    @Test
    fun sharedMergeKeepsSameUrlWithinOneBucket() {
        val snap = snapshot(
            spaces = listOf(space("s")),
            essentials = mapOf(
                "default" to listOf(
                    ZenSpaces.ZenTab("t1", "https://dup.example/", "dup"),
                    ZenSpaces.ZenTab("t2", "https://dup.example/", "dup"),
                ),
            ),
        )
        assertEquals(
            "a genuine duplicate inside one bucket is real Zen data and stays visible",
            listOf("t1", "t2"),
            ids(snap.essentialsFor(space("s"))),
        )
    }

    @Test
    fun sharedMergeKeepsDistinctUrlsInBucketOrder() {
        val snap = snapshot(
            spaces = listOf(space("s")),
            essentials = mapOf(
                "work" to listOf(tab("w")),
                "alpha" to listOf(tab("a")),
                "default" to listOf(tab("d")),
            ),
        )
        assertEquals(listOf("d", "a", "w"), ids(snap.essentialsFor(space("s"))))
    }

    @Test
    fun containerSpaceReadsOnlyItsOwnBucket() {
        val snap = snapshot(
            spaces = listOf(space("work", "container-work"), space("plain")),
            essentials = mapOf(
                "default" to listOf(tab("d")),
                "container-work" to listOf(tab("w")),
            ),
        )
        assertEquals(listOf("w"), ids(snap.essentialsFor(space("work", "container-work"))))
    }

    @Test
    fun defaultSpaceGetsDefaultPlusOrphanContainerBuckets() {
        val snap = snapshot(
            spaces = listOf(space("plain"), space("work", "container-work")),
            essentials = mapOf(
                "default" to listOf(tab("d")),
                "container-work" to listOf(tab("w")),
                "container-orphan" to listOf(tab("o")),
            ),
        )
        assertEquals(
            "orphan containers stay reachable from a container-less space",
            listOf("d", "o"),
            ids(snap.essentialsFor(space("plain"))),
        )
    }

    @Test
    fun gridsMatchComparesEffectiveTabLists() {
        assertTrue(essentialsGridsMatch(listOf(tab("a")), listOf(tab("a"))))
        assertFalse(essentialsGridsMatch(listOf(tab("a")), listOf(tab("b"))))
        assertFalse(essentialsGridsMatch(listOf(tab("a")), emptyList()))
        assertTrue(essentialsGridsMatch(emptyList(), emptyList()))
    }

    // MARK: Cache and storage

    @Test
    fun snapshotCacheRoundTripsSeparatePref() {
        val snap = snapshot(
            spaces = listOf(space("s")),
            essentials = mapOf("default" to listOf(tab("a"))),
            separatePref = false,
        )
        val decoded = ZenSpaces.decodeSnapshot(ZenSpaces.encodeSnapshot(snap))
        assertEquals(false, decoded?.separateEssentialsPref)
        assertFalse(decoded!!.isContainerSpecific)
    }

    @Test
    fun legacyCacheWithoutPrefDecodesAndInfers() {
        val root = JSONObject(
            """
            {"spaces":[{"id":"s","name":"S","pinned":[],"tabs":[]}],
             "essentials":{"default":[{"id":"a","url":"https://a.example","title":"a"}]},
             "fetchedAt":0.0}
            """.trimIndent(),
        )
        val decoded = ZenSpaces.decodeSnapshot(root)
        assertNull(decoded?.separateEssentialsPref)
        assertFalse(decoded!!.isContainerSpecific)
    }
}
