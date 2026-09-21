package de.kjell.zencompanion

import de.kjell.zencompanion.sync.ZenSpaces
import de.kjell.zencompanion.ui.components.PinDestination
import de.kjell.zencompanion.ui.components.PinDestinationModel
import de.kjell.zencompanion.ui.screens.parseCssColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PinDestinationPickerModelTests {

    private fun folder(
        id: String,
        name: String = id,
        subfolders: List<ZenSpaces.ZenFolder> = emptyList(),
    ): ZenSpaces.ZenFolder = ZenSpaces.ZenFolder(
        id = id,
        name = name,
        icon = null,
        tabs = emptyList(),
        subfolders = subfolders.takeIf { it.isNotEmpty() },
    )

    private fun space(vararg folders: ZenSpaces.ZenFolder): ZenSpaces.ZenSpace = ZenSpaces.ZenSpace(
        id = "space",
        name = "Space",
        icon = null,
        containerGuid = null,
        theme = null,
        pinned = folders.map { ZenSpaces.ZenItem.Folder(it) },
    )

    @Test
    fun folderEntriesWalkDepthFirstInOrderAndGuardCycles() {
        val cycleBackToRoot = folder("f1")
        val nested = folder("nested", subfolders = listOf(cycleBackToRoot))
        val root = folder("f1", subfolders = listOf(folder("child"), nested))

        val entries = PinDestinationModel.folderEntries(space(root, folder("f2")))

        assertEquals(listOf("f1", "child", "nested", "f2"), entries.map { it.folderId })
        assertEquals(listOf(0, 1, 1, 0), entries.map { it.depth })
    }

    @Test
    fun displayNameFallsBackThroughFolderNameIdAndSpace() {
        val nested = folder("folder-2", name = "Nested")
        val root = folder("folder-1", name = "Root", subfolders = listOf(nested))
        val space = space(root)

        assertEquals("Root", PinDestinationModel.displayName(PinDestination("space", "folder-1"), space))
        assertEquals("Nested", PinDestinationModel.displayName(PinDestination("space", "folder-2"), space))
        assertEquals("Space", PinDestinationModel.displayName(PinDestination("space"), space))
        assertEquals("missing", PinDestinationModel.displayName(PinDestination("space", "missing"), space))
        assertEquals("folder-9", PinDestinationModel.displayName(PinDestination("space", "folder-9"), null))
        assertEquals("", PinDestinationModel.displayName(PinDestination("space"), null))
    }

    @Test
    fun parseCssColorHandlesHexRgbAndInvalidInput() {
        val red = parseCssColor("#FF0000")!!
        assertEquals(1f, red.red, 0.01f)
        assertEquals(0f, red.green, 0.01f)
        assertEquals(0f, red.blue, 0.01f)
        assertEquals(1f, red.alpha, 0.01f)

        val short = parseCssColor("#abc")!!
        assertEquals(170f / 255f, short.red, 0.01f)
        assertEquals(187f / 255f, short.green, 0.01f)
        assertEquals(204f / 255f, short.blue, 0.01f)

        val alphaHex = parseCssColor("#FF000080")!!
        assertEquals(128f / 255f, alphaHex.alpha, 0.02f)

        val rgb = parseCssColor("rgb(255, 0, 0)")!!
        assertEquals(1f, rgb.red, 0.01f)

        val rgba = parseCssColor("rgba(255, 0, 0, 0.5)")!!
        assertEquals(0.5f, rgba.alpha, 0.01f)

        assertNull(parseCssColor("transparent"))
        assertNull(parseCssColor("null"))
        assertNull(parseCssColor("nonsense"))
        assertNull(parseCssColor("rgba(255, 0, 0, 0.04)"))
    }
}
