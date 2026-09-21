package de.kjell.zencompanion.data

import android.content.Context
import de.kjell.zencompanion.sync.ZenSpaces
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.json.JSONObject
import java.io.File

/** Cross-cutting events (ports of the Swift Notification.Name usages). */
object AppEvents {
    private val _signedOut = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val signedOut: SharedFlow<Unit> = _signedOut

    private val _snapshotStale = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val snapshotStale: SharedFlow<Unit> = _snapshotStale

    fun emitSignedOut() {
        _signedOut.tryEmit(Unit)
    }

    fun emitSnapshotStale() {
        _snapshotStale.tryEmit(Unit)
    }
}

/**
 * Application-wide context handle so service-layer code without UI access
 * can reach files/prefs (single-process app, mirrors the Swift singletons).
 */
object AppContextHolder {
    @Volatile
    lateinit var appContext: Context
        internal set

    /** True once [initialize] ran; unit tests without an Android Context stay memory-only. */
    internal val isInitialized: Boolean
        get() = ::appContext.isInitialized

    internal fun initialize(context: Context) {
        appContext = context.applicationContext
    }
}

/**
 * Port of `Shared/SpacesSyncService.swift` cache layer: one JSON file in the
 * app's private directory, plus `lastSpaceId` in plain prefs (not a secret).
 */
object SnapshotCache {
    const val PREFS_NAME = "zen_prefs"
    private const val CACHE_FILE = "spaces-cache.json"
    private const val KEY_LAST_SPACE = "lastSpaceId"

    private val contextOrNull: Context?
        get() = if (AppContextHolder.isInitialized) AppContextHolder.appContext else null

    /** Live cached snapshot for service-layer fallbacks and instant UI. */
    @Volatile
    private var memorySnapshot: ZenSpaces.ZenSnapshot? = null

    val cachedSnapshotShared: ZenSpaces.ZenSnapshot?
        get() = synchronized(this) {
            // Without an initialized context (pure-JVM tests) stay memory-only.
            memorySnapshot ?: contextOrNull?.let { cachedSnapshot(it) }?.also { memorySnapshot = it }
        }

    fun invalidateMemory() {
        synchronized(this) { memorySnapshot = null }
    }

    fun cache(snapshot: ZenSpaces.ZenSnapshot) {
        val context = contextOrNull
        if (context != null) {
            cache(context, snapshot)
        } else {
            synchronized(this) { memorySnapshot = snapshot }
        }
    }

    fun cache(context: Context, snapshot: ZenSpaces.ZenSnapshot) {
        runCatching {
            File(context.filesDir, CACHE_FILE).writeText(
                ZenSpaces.encodeSnapshot(snapshot).toString(),
                Charsets.UTF_8,
            )
        }
        synchronized(this) { memorySnapshot = snapshot }
    }

    fun cachedSnapshot(context: Context): ZenSpaces.ZenSnapshot? {
        val file = File(context.filesDir, CACHE_FILE)
        if (!file.exists()) return null
        return runCatching {
            ZenSpaces.decodeSnapshot(JSONObject(file.readText(Charsets.UTF_8)))
        }.getOrNull()
    }

    /** Removes cached preview data so tab titles/URLs never outlive sign-in. */
    fun deleteCachedSnapshot(context: Context? = contextOrNull) {
        if (context != null) {
            runCatching { File(context.filesDir, CACHE_FILE).delete() }
            lastSpacePrefs(context).edit().remove(KEY_LAST_SPACE).apply()
        }
        synchronized(this) { memorySnapshot = null }
    }

    /**
     * Inserts a pinned tab at the space root, or inside the folder with the
     * given id (any nesting depth); a normal tab always lands in the space's
     * normal (`tabs`) bucket and ignores [folderId]. Falls back to the root
     * when the folder does not exist. Mirrors the Swift
     * `SpacesSyncEdits.inserting`.
     */
    fun insertCachedTab(
        spaceId: String,
        tab: ZenSpaces.ZenTab,
        folderId: String?,
        fetchedAtMillis: Long,
        kind: SaveKind = SaveKind.PINNED,
    ) {
        val current = cachedSnapshotShared ?: return
        cache(
            SpacesSyncCacheOps.insertTab(current, spaceId, tab, folderId, fetchedAtMillis, kind),
        )
    }

    fun removeCachedTab(tabId: String, fetchedAtMillis: Long) {
        val current = cachedSnapshotShared ?: return
        cache(SpacesSyncCacheOps.removeTab(current, tabId, fetchedAtMillis))
    }

    fun expandCachedSplit(splitId: String, fetchedAtMillis: Long) {
        val current = cachedSnapshotShared ?: return
        cache(SpacesSyncCacheOps.expandSplit(current, splitId, fetchedAtMillis))
    }

    fun lastSpaceId(context: Context): String? =
        lastSpacePrefs(context).getString(KEY_LAST_SPACE, null)

    fun setLastSpaceId(context: Context, id: String?) {
        lastSpacePrefs(context).edit().putString(KEY_LAST_SPACE, id).apply()
    }

    fun lastSpacePrefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

internal object SpacesSyncCacheOps {
    fun appendTab(
        snapshot: ZenSpaces.ZenSnapshot,
        spaceId: String,
        tab: ZenSpaces.ZenTab,
        fetchedAtMillis: Long,
    ): ZenSpaces.ZenSnapshot = insertTab(snapshot, spaceId, tab, folderId = null, fetchedAtMillis)

    fun insertTab(
        snapshot: ZenSpaces.ZenSnapshot,
        spaceId: String,
        tab: ZenSpaces.ZenTab,
        folderId: String?,
        fetchedAtMillis: Long,
        kind: SaveKind = SaveKind.PINNED,
    ): ZenSpaces.ZenSnapshot = snapshot.copy(
        spaces = snapshot.spaces.map { space ->
            if (space.id == spaceId) {
                if (kind == SaveKind.NORMAL) {
                    // Normal tabs are never placed in a folder.
                    space.copy(tabs = space.tabs + listOf(ZenSpaces.ZenItem.Tab(tab)))
                } else {
                    space.copy(
                        pinned = insertIntoPinned(space.pinned, tab, folderId),
                    )
                }
            } else {
                space
            }
        },
        fetchedAtMillis = fetchedAtMillis,
    )

    private fun insertIntoPinned(
        pinned: List<ZenSpaces.ZenItem>,
        tab: ZenSpaces.ZenTab,
        folderId: String?,
    ): List<ZenSpaces.ZenItem> {
        if (folderId.isNullOrEmpty()) return pinned + listOf(ZenSpaces.ZenItem.Tab(tab))
        val next = pinned.toMutableList()
        if (insertRecursively(next, tab, folderId)) return next
        return pinned + listOf(ZenSpaces.ZenItem.Tab(tab))
    }

    private fun insertRecursively(
        items: MutableList<ZenSpaces.ZenItem>,
        tab: ZenSpaces.ZenTab,
        folderId: String,
    ): Boolean {
        for (index in items.indices) {
            val folder = (items[index] as? ZenSpaces.ZenItem.Folder)?.folder ?: continue
            if (folder.id == folderId) {
                items[index] = ZenSpaces.ZenItem.Folder(folder.copy(tabs = folder.tabs + listOf(tab)))
                return true
            }
            val subs = (folder.subfolders ?: emptyList()).toMutableList()
            if (insertRecursivelyIntoFolders(subs, tab, folderId)) {
                items[index] = ZenSpaces.ZenItem.Folder(folder.copy(subfolders = subs))
                return true
            }
        }
        return false
    }

    private fun insertRecursivelyIntoFolders(
        folders: MutableList<ZenSpaces.ZenFolder>,
        tab: ZenSpaces.ZenTab,
        folderId: String,
    ): Boolean {
        for (index in folders.indices) {
            if (folders[index].id == folderId) {
                folders[index] = folders[index].copy(tabs = folders[index].tabs + listOf(tab))
                return true
            }
            val subs = (folders[index].subfolders ?: emptyList()).toMutableList()
            if (insertRecursivelyIntoFolders(subs, tab, folderId)) {
                folders[index] = folders[index].copy(subfolders = subs)
                return true
            }
        }
        return false
    }

    fun removeTab(
        snapshot: ZenSpaces.ZenSnapshot,
        tabId: String,
        fetchedAtMillis: Long,
    ): ZenSpaces.ZenSnapshot = snapshot.copy(
        spaces = snapshot.spaces.map { space ->
            space.copy(
                pinned = removeFromItems(space.pinned, tabId),
                tabs = removeFromItems(space.tabs, tabId),
            )
        },
        essentials = snapshot.essentials.mapValues { (_, tabs) -> tabs.filterNot { it.id == tabId } },
        fetchedAtMillis = fetchedAtMillis,
    )

    private fun removeFromItems(
        items: List<ZenSpaces.ZenItem>,
        tabId: String,
    ): List<ZenSpaces.ZenItem> = items.flatMap { item ->
        when (item) {
            is ZenSpaces.ZenItem.Tab ->
                if (item.tab.id == tabId) emptyList() else listOf(item)
            is ZenSpaces.ZenItem.Folder -> listOf(
                ZenSpaces.ZenItem.Folder(removeTabFromFolder(item.folder, tabId)),
            )
            is ZenSpaces.ZenItem.Split -> {
                if (item.split.id == tabId) {
                    item.split.tabs.map { ZenSpaces.ZenItem.Tab(it) }
                } else {
                    val tabs = item.split.tabs.filterNot { it.id == tabId }
                    when {
                        tabs.size >= 2 -> listOf(
                            ZenSpaces.ZenItem.Split(item.split.copy(tabs = tabs)),
                        )
                        else -> tabs.map { ZenSpaces.ZenItem.Tab(it) }
                    }
                }
            }
        }
    }

    /** Removes a tab from a folder tree at any nesting depth. */
    private fun removeTabFromFolder(folder: ZenSpaces.ZenFolder, tabId: String): ZenSpaces.ZenFolder =
        folder.copy(
            tabs = folder.tabs.filterNot { it.id == tabId },
            subfolders = folder.subfolders?.map { removeTabFromFolder(it, tabId) },
        )

    fun expandSplit(
        snapshot: ZenSpaces.ZenSnapshot,
        splitId: String,
        fetchedAtMillis: Long,
    ): ZenSpaces.ZenSnapshot = snapshot.copy(
        spaces = snapshot.spaces.map { space ->
            space.copy(
                pinned = expandSplitIn(space.pinned, splitId),
                tabs = expandSplitIn(space.tabs, splitId),
            )
        },
        fetchedAtMillis = fetchedAtMillis,
    )

    private fun expandSplitIn(
        items: List<ZenSpaces.ZenItem>,
        splitId: String,
    ): List<ZenSpaces.ZenItem> = items.flatMap { item ->
        if (item is ZenSpaces.ZenItem.Split && item.split.id == splitId) {
            item.split.tabs.map { ZenSpaces.ZenItem.Tab(it) }
        } else {
            listOf(item)
        }
    }
}
