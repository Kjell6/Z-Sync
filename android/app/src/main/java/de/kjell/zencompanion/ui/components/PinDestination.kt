package de.kjell.zencompanion.ui.components

import de.kjell.zencompanion.sync.ZenSpaces

/**
 * A pin destination: a space plus an optional folder inside that space.
 * Null/empty [folderId] means the space root. Mirrors the Swift
 * `PinDestination` / `PinDestinationModel`.
 */
data class PinDestination(
    val spaceId: String,
    val folderId: String? = null,
) {
    val hasFolder: Boolean
        get() = !folderId.isNullOrEmpty()

    companion object {
        fun of(space: ZenSpaces.ZenSpace) = PinDestination(spaceId = space.id)
    }
}

object PinDestinationModel {
    /** One folder row for the menu: name plus nesting depth for indentation. */
    data class FolderEntry(
        val folderId: String,
        val name: String,
        val depth: Int,
    )

    fun foldersIn(space: ZenSpaces.ZenSpace): List<ZenSpaces.ZenFolder> =
        space.pinned.mapNotNull { (it as? ZenSpaces.ZenItem.Folder)?.folder }

    /**
     * Depth-first walk of the space's folder tree: outer folders first,
     * nested folders after their parent. Cycles are guarded by the visited
     * set.
     */
    fun folderEntries(space: ZenSpaces.ZenSpace): List<FolderEntry> {
        val out = mutableListOf<FolderEntry>()
        val visited = mutableSetOf<String>()

        fun walk(folders: List<ZenSpaces.ZenFolder>, depth: Int) {
            for (folder in folders) {
                if (!visited.add(folder.id)) continue
                out.add(FolderEntry(folder.id, folder.name, depth))
                walk(folder.subfolders ?: emptyList(), depth + 1)
            }
        }
        walk(foldersIn(space), depth = 0)
        return out
    }

    /** Human-readable label: space name for the root, folder name otherwise. */
    fun displayName(destination: PinDestination, space: ZenSpaces.ZenSpace?): String {
        if (space == null) return destination.folderId ?: ""
        val folderId = destination.folderId
        if (folderId.isNullOrEmpty()) return space.name
        return folderName(folderId, space) ?: folderId
    }

    fun folderName(folderId: String, space: ZenSpaces.ZenSpace): String? {
        fun search(folders: List<ZenSpaces.ZenFolder>): ZenSpaces.ZenFolder? {
            for (folder in folders) {
                if (folder.id == folderId) return folder
                search(folder.subfolders ?: emptyList())?.let { return it }
            }
            return null
        }
        return search(foldersIn(space))?.name
    }
}

/**
 * Where a pinned tab may be moved inside its own space. Normal tabs never
 * carry a `folderId`, and rows that do not rename (splits) do not move.
 * Mirrors the Swift `TabMove`.
 */
object TabMove {
    data class Destination(
        /** Null is the space root. */
        val folderId: String?,
        val name: String,
        val depth: Int,
        val isCurrent: Boolean,
    )

    /** Move is offered only on rows that already rename, and never on a normal tab. */
    fun offersMove(renames: Boolean, isNormalTab: Boolean): Boolean = renames && !isNormalTab

    fun normalize(folderId: String?): String? = folderId?.takeIf { it.isNotEmpty() }

    fun samePlace(currentFolderId: String?, selectedFolderId: String?): Boolean =
        normalize(currentFolderId) == normalize(selectedFolderId)

    /** Selecting the current place must not write. */
    fun shouldWrite(currentFolderId: String?, selectedFolderId: String?): Boolean =
        !samePlace(currentFolderId, selectedFolderId)

    /** Space root first, then this space's folders depth-first (nested included). */
    fun destinations(
        spaceName: String,
        folders: List<PinDestinationModel.FolderEntry>,
        currentFolderId: String?,
    ): List<Destination> {
        val current = normalize(currentFolderId)
        val rows = mutableListOf(
            Destination(folderId = null, name = spaceName, depth = 0, isCurrent = current == null),
        )
        for (folder in folders) {
            rows.add(
                Destination(
                    folderId = folder.folderId,
                    name = folder.name,
                    depth = folder.depth,
                    isCurrent = folder.folderId == current,
                ),
            )
        }
        return rows
    }
}
