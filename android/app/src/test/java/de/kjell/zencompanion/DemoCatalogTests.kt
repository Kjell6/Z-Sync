package de.kjell.zencompanion

import de.kjell.zencompanion.data.AccountStore
import de.kjell.zencompanion.data.DemoCatalog
import de.kjell.zencompanion.sync.ZenSpaces
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ports of `DemoCatalogTests.swift`. */
class DemoCatalogTests {
    @Test
    fun snapshotCoversCoreSurfaces() {
        val snap = DemoCatalog.snapshot
        assertTrue(snap.spaces.size >= 3)
        assertTrue(snap.spaces.any { it.pinned.isNotEmpty() })
        assertTrue(snap.spaces.any { it.tabs.isNotEmpty() })
        assertTrue(
            snap.spaces.any { space ->
                space.pinned.any { it is ZenSpaces.ZenItem.Folder }
            },
        )
        assertTrue(
            snap.spaces.any { space ->
                space.pinned.any { it is ZenSpaces.ZenItem.Split }
            },
        )
        assertEquals(snap.spaces.size, snap.essentials.size)
        assertTrue(
            snap.spaces.any { space ->
                space.pinned.any { item ->
                    item is ZenSpaces.ZenItem.Folder &&
                        !(item.folder.subfolders ?: emptyList()).isEmpty()
                }
            },
        )
    }

    @Test
    fun activityHasHistory() {
        assertFalse(DemoCatalog.activity.history.isEmpty())
    }

    @Test
    fun legacyAccountDecodesWithoutDemoFlag() {
        val json = JSONObject(
            """{"email":"a@b.c","uid":"u","sessionTokenHex":"aa","kBHex":"bb"}""",
        )
        val snap = AccountStore.AccountSnapshot.fromJSON(json)!!
        assertFalse(snap.isDemo)
        assertEquals("a@b.c", snap.email)
    }

    @Test
    fun demoAccountRoundTrips() {
        val snap = AccountStore.AccountSnapshot.fromJSON(DemoCatalog.account.toJSON())!!
        assertTrue(snap.isDemo)
        assertEquals("demo", snap.uid)
    }

    @Test
    fun snapshotCacheKeepsNestedFolders() {
        val encoded = ZenSpaces.encodeSnapshot(DemoCatalog.snapshot)
        val decoded = ZenSpaces.decodeSnapshot(encoded)!!
        val nested = decoded.spaces
            .flatMap { it.pinned }
            .filterIsInstance<ZenSpaces.ZenItem.Folder>()
            .flatMap { it.folder.subfolders ?: emptyList() }
        assertTrue(nested.any { it.id == "demo-folder-specs" })
        assertEquals(3, decoded.essentials.size)
    }
}
