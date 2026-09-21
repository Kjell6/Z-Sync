package de.kjell.zencompanion.sync

import android.content.Context
import de.kjell.zencompanion.data.AccountStore
import de.kjell.zencompanion.data.AppEvents
import de.kjell.zencompanion.data.DemoCatalog
import de.kjell.zencompanion.data.SaveKind
import de.kjell.zencompanion.data.SnapshotCache
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * Port of `Shared/SpacesSyncService.swift`. Reads and writes Zen's Spaces
 * Sync collection; saving a link uploads one encrypted `tab` record that the
 * desktop browser turns into a real tab on its next sync.
 */
object SpacesSyncService {
    const val collection = "spaces"

    /** Firefox Sync collection holding the synced `prefs` map (one record). */
    const val PREFS_COLLECTION = "prefs"

    /** Desktop setting "Include unpinned tabs" (`zen.spaces-sync.normal-tabs`). */
    const val NORMAL_TABS_PREF_KEY = "zen.spaces-sync.normal-tabs"

    /**
     * Desktop setting "Enable container-specific essentials". Not in Zen's
     * synced-prefs list today, so callers must tolerate it being absent.
     */
    const val SEPARATE_ESSENTIALS_PREF_KEY = "zen.workspaces.separate-essentials"

    // MARK: - Read

    suspend fun loadSnapshot(context: Context): ZenSpaces.ZenSnapshot {
        if (AccountStore.isDemo(context)) {
            val cached = SnapshotCache.cachedSnapshotShared
            if (cached != null && cached.spaces.isNotEmpty()) return demoCapability(cached)
            val sample = demoCapability(DemoCatalog.snapshot)
            SnapshotCache.cache(sample)
            return sample
        }
        val client = AccountStore.connect(context)
        return loadSnapshot(client = client)
    }

    /**
     * Demo mode has no synced prefs record: the bundled catalog proves the
     * capability by containing normal tabs (or lacking them).
     */
    private fun demoCapability(snapshot: ZenSpaces.ZenSnapshot): ZenSpaces.ZenSnapshot =
        snapshot.copy(
            normalTabsCapability = if (snapshot.spaces.any { it.tabs.isNotEmpty() }) {
                ZenSpaces.NormalTabsCapability.ENABLED
            } else {
                ZenSpaces.NormalTabsCapability.DISABLED
            },
        )

    suspend fun loadSnapshot(client: SyncClient): ZenSpaces.ZenSnapshot = withContext(Dispatchers.IO) {
        val prefs = syncedPrefs(client)
        val spaceRecords = client.getRecords(collection)

        val spacesById = linkedMapOf<String, ZenSpaces.ZenSpaceRecord>()
        val tabsById = linkedMapOf<String, ZenSpaces.ZenTabRecord>()
        val foldersById = linkedMapOf<String, ZenSpaces.ZenFolderRecord>()
        val splitsById = linkedMapOf<String, ZenSpaces.ZenSplitRecord>()
        var layout: ZenSpaces.ZenLayoutRecord? = null
        var decryptFailures = 0
        var skippedKinds = 0
        var gatedNormalItems = 0
        var observedNormalItems = 0

        for (record in spaceRecords) {
            val id = record.optString("id")
            if (id.isEmpty()) continue
            val cleartext = try {
                client.decryptRecord(collection, record)
            } catch (_: Exception) {
                decryptFailures++
                continue
            }
            if ((cleartext.opt("deleted") as? Boolean) == true) continue
            when (val decoded = ZenSpaces.decode(id, cleartext)) {
                is ZenSpaces.DecodedRecord.Space -> spacesById[decoded.record.uuid] = decoded.record
                is ZenSpaces.DecodedRecord.Tab -> {
                    // Records linger server-side after the option is turned off
                    // (held back, not tombstoned). Hide them when the synced
                    // pref says the option is off.
                    val tabRecord = decoded.record
                    if (tabRecord.pinned == false) observedNormalItems++
                    if (prefs.normalTabs || tabRecord.pinned != false) {
                        tabsById[tabRecord.tabId] = tabRecord
                    } else {
                        gatedNormalItems++
                    }
                }
                is ZenSpaces.DecodedRecord.Folder -> foldersById[decoded.record.folderId] = decoded.record
                is ZenSpaces.DecodedRecord.Split -> {
                    // Split records carry the same opt-in flag (from their
                    // first member); gate them like normal tab records.
                    val splitRecord = decoded.record
                    if (splitRecord.pinned == false) observedNormalItems++
                    if (prefs.normalTabs || splitRecord.pinned != false) {
                        splitsById[splitRecord.splitId] = splitRecord
                    } else {
                        gatedNormalItems++
                    }
                }
                is ZenSpaces.DecodedRecord.Layout -> layout = decoded.record
                null -> skippedKinds++
            }
        }

        val order = layout?.spaces ?: spacesById.keys.toList()
        val seen = mutableSetOf<String>()
        val orderedIds = order.filter { spacesById.containsKey(it) && seen.add(it) }
        val unorderedIds = spacesById.keys.sorted().filter { !seen.contains(it) }

        val spaces = (orderedIds + unorderedIds).map { id ->
            makeSpace(spacesById.getValue(id), allTabs = tabsById, folders = foldersById, splits = splitsById)
        }

        val essentials = assembleEssentials(layout, tabsById)

        android.util.Log.i(
            "SpacesSync",
            loadLogSummary(
                recordCount = spaceRecords.size,
                spaces = spaces.size,
                tabs = tabsById.size,
                folders = foldersById.size,
                splits = splitsById.size,
                essentials = essentials.values.sumOf { it.size },
                normalTabsOn = prefs.normalTabs,
                separateEssentials = prefs.separateEssentials,
                decryptFailures = decryptFailures,
                skippedKinds = skippedKinds,
                gatedNormalItems = gatedNormalItems,
            ),
        )

        // A normal record observed in `spaces` proves the browser supports
        // normal-tab sync (records linger after the option is turned off), so
        // `absent` with observed normal items is honestly `disabled`.
        val capability = if (
            prefs.normalTabsCapability == ZenSpaces.NormalTabsCapability.ABSENT &&
            observedNormalItems > 0
        ) {
            ZenSpaces.NormalTabsCapability.DISABLED
        } else {
            prefs.normalTabsCapability
        }

        ZenSpaces.ZenSnapshot(
            spaces = spaces,
            essentials = essentials,
            fetchedAtMillis = System.currentTimeMillis(),
            separateEssentialsPref = prefs.separateEssentials,
            normalTabsCapability = capability,
        )
    }

    /** The Zen preferences this app consumes from the synced `prefs` record. */
    internal data class SyncedPrefs(
        /** "Include unpinned tabs" (`zen.spaces-sync.normal-tabs`), default true. */
        val normalTabs: Boolean = true,
        /**
         * "Enable container-specific essentials" when Zen synced it; null when
         * absent (Zen does not mark it for sync today).
         */
        val separateEssentials: Boolean? = null,
        /**
         * Tri-state write gate (`wire-prefs-normal-tabs-capability`); does not
         * change [normalTabs], which keeps the display read default.
         */
        val normalTabsCapability: ZenSpaces.NormalTabsCapability = ZenSpaces.NormalTabsCapability.ABSENT,
    )

    /**
     * Reads the synced `prefs` record once and parses every pref this app
     * consumes. Unreadable records keep the defaults: a present `pinned:false`
     * record still renders, and essentials fall back to inference.
     */
    internal fun syncedPrefs(client: SyncClient): SyncedPrefs {
        var prefs = SyncedPrefs()
        val records = try {
            client.getRecords(PREFS_COLLECTION)
        } catch (_: Exception) {
            return prefs
        }
        for (record in records) {
            val cleartext = try {
                client.decryptRecord(PREFS_COLLECTION, record)
            } catch (_: Exception) {
                continue
            }
            val values = cleartext.optJSONObject("value") ?: continue
            // Last key-bearing record wins; records without the key never
            // downgrade a capability a readable record already proved.
            val derived = deriveNormalTabsCapability(values)
            if (derived != ZenSpaces.NormalTabsCapability.ABSENT) {
                prefs = prefs.copy(normalTabsCapability = derived)
            }
            parsePrefBool(values.opt(NORMAL_TABS_PREF_KEY))?.let { prefs = prefs.copy(normalTabs = it) }
            parsePrefBool(values.opt(SEPARATE_ESSENTIALS_PREF_KEY))?.let { prefs = prefs.copy(separateEssentials = it) }
        }
        return prefs
    }

    /**
     * Derives the normal-tabs write capability from one decrypted prefs
     * `value` map. Null means no readable `value` object was seen; a present
     * key that does not parse true is [ZenSpaces.NormalTabsCapability.DISABLED].
     */
    internal fun deriveNormalTabsCapability(values: JSONObject?): ZenSpaces.NormalTabsCapability = when {
        values == null -> ZenSpaces.NormalTabsCapability.ABSENT
        !values.has(NORMAL_TABS_PREF_KEY) -> ZenSpaces.NormalTabsCapability.ABSENT
        parsePrefBool(values.opt(NORMAL_TABS_PREF_KEY)) == true -> ZenSpaces.NormalTabsCapability.ENABLED
        else -> ZenSpaces.NormalTabsCapability.DISABLED
    }

    /** Convenience for the normal-tabs flag alone (legacy call sites/tests). */
    internal fun normalTabsEnabled(client: SyncClient): Boolean = syncedPrefs(client).normalTabs

    /**
     * D2 target-folder matching (SPEC §3.5): a nil/empty request never
     * matches a folder, and a candidate matches only when its `data.folderId`
     * is a strict non-empty JSON string exactly equal to the requested id —
     * never via `optString`'s number/bool coercion.
     */
    internal fun isTargetFolder(folderId: String?, data: JSONObject): Boolean =
        !folderId.isNullOrEmpty() && ZenSpaces.optStringOrNull(data, "folderId") == folderId

    /**
     * One-line read summary matching the iOS fields: record count, decrypt
     * failures, ignored records, the per-kind buckets, the synced prefs and how
     * many normal items were gated by the normal-tabs pref.
     */
    internal fun loadLogSummary(
        recordCount: Int,
        spaces: Int,
        tabs: Int,
        folders: Int,
        splits: Int,
        essentials: Int,
        normalTabsOn: Boolean,
        separateEssentials: Boolean?,
        decryptFailures: Int,
        skippedKinds: Int,
        gatedNormalItems: Int,
    ): String =
        "spaces sync: $recordCount records, $decryptFailures decrypt failures, $skippedKinds ignored → " +
            "spaces: $spaces, tabs: $tabs, folders: $folders, splits: $splits, essentials: $essentials, " +
            "normal-tabs pref: $normalTabsOn, separate-essentials pref: $separateEssentials, " +
            "gated normal items: $gatedNormalItems"

    /** Tolerant bool parse for a synced pref value (JSON bool, number or string). */
    internal fun parsePrefBool(raw: Any?): Boolean? = when (raw) {
        is Boolean -> raw
        is Number -> raw.toInt() != 0
        is String -> when (raw.lowercase()) {
            "true", "1" -> true
            "false", "0" -> false
            else -> null
        }
        else -> null
    }

    /** Essentials grouped by container bucket ("default" for tabs without one). */
    internal fun assembleEssentials(
        layout: ZenSpaces.ZenLayoutRecord?,
        allTabs: Map<String, ZenSpaces.ZenTabRecord>,
    ): Map<String, List<ZenSpaces.ZenTab>> {
        val byBucket = linkedMapOf<String, MutableList<ZenSpaces.ZenTab>>()
        val placed = mutableSetOf<String>()
        for ((bucket, ids) in layout?.essentials ?: emptyMap()) {
            val tabs = ids.mapNotNull { id ->
                val record = allTabs[id] ?: return@mapNotNull null
                if (record.essential != true) return@mapNotNull null
                makeTab(record)
            }
            if (tabs.isNotEmpty()) {
                byBucket.getOrPut(bucket) { mutableListOf() }.addAll(tabs)
                placed.addAll(tabs.map { it.id })
            }
        }
        val orphans = allTabs.values
            .filter { it.essential == true && !placed.contains(it.tabId) }
            .mapNotNull { makeTab(it) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        if (orphans.isNotEmpty()) {
            byBucket.getOrPut("default") { mutableListOf() }.addAll(orphans)
        }
        return byBucket
    }

    internal fun makeSpace(
        record: ZenSpaces.ZenSpaceRecord,
        allTabs: Map<String, ZenSpaces.ZenTabRecord>,
        folders: Map<String, ZenSpaces.ZenFolderRecord>,
        splits: Map<String, ZenSpaces.ZenSplitRecord> = emptyMap(),
    ): ZenSpaces.ZenSpace {
        val items = buildItems(
            ids = record.children ?: emptyList(),
            allTabs = allTabs,
            folders = folders,
            splits = splits,
            topLevel = true,
            depth = 0,
        )
        // Partition by the record's wire `pinned` flag, keeping the synced
        // `children` order inside each bucket. Folders stay pinned; splits go
        // with their members (a split of normal tabs reports `pinned: false`).
        val pinnedItems = mutableListOf<ZenSpaces.ZenItem>()
        val normalItems = mutableListOf<ZenSpaces.ZenItem>()
        for (item in items) {
            val isNormal = when (item) {
                is ZenSpaces.ZenItem.Tab -> allTabs[item.tab.id]?.isNormalTab == true
                is ZenSpaces.ZenItem.Split -> splits[item.split.id]?.isNormalSplit == true
                else -> false
            }
            if (isNormal) normalItems.add(item) else pinnedItems.add(item)
        }
        // Also capture tabs assigned to this space that aren't yet in children.
        val placedTabIds = buildSet {
            for (item in items) {
                when (item) {
                    is ZenSpaces.ZenItem.Tab -> add(item.tab.id)
                    is ZenSpaces.ZenItem.Folder -> item.folder.tabs.forEach { add(it.id) }
                    is ZenSpaces.ZenItem.Split -> item.split.tabs.forEach { add(it.id) }
                }
            }
        }
        val unplacedRecords = allTabs.values.filter {
            it.workspaceUuid == record.uuid && it.essential != true &&
                it.folderId == null && !placedTabIds.contains(it.tabId) &&
                !de.kjell.zencompanion.favicon.FaviconResolver.isLocalURL(it.url)
        }
        fun sortedTabItems(records: List<ZenSpaces.ZenTabRecord>): List<ZenSpaces.ZenItem> =
            records
                .mapNotNull { makeTab(it) }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
                .map { ZenSpaces.ZenItem.Tab(it) }
        pinnedItems.addAll(sortedTabItems(unplacedRecords.filter { !it.isNormalTab }))
        normalItems.addAll(sortedTabItems(unplacedRecords.filter { it.isNormalTab }))

        return ZenSpaces.ZenSpace(
            id = record.uuid,
            name = record.name ?: "",
            icon = record.icon?.takeIf { it.isNotEmpty() },
            containerGuid = record.containerGuid,
            theme = record.theme,
            pinned = pinnedItems,
            tabs = normalItems,
        )
    }

    /** Walks child ids in synced order; top-level folder members are skipped.
     *  Folders nested under another folder render inside it, not here. */
    internal fun buildItems(
        ids: List<String>,
        allTabs: Map<String, ZenSpaces.ZenTabRecord>,
        folders: Map<String, ZenSpaces.ZenFolderRecord>,
        splits: Map<String, ZenSpaces.ZenSplitRecord> = emptyMap(),
        topLevel: Boolean,
        depth: Int,
    ): List<ZenSpaces.ZenItem> {
        if (depth >= 4) return emptyList()
        val items = mutableListOf<ZenSpaces.ZenItem>()
        for (id in ids) {
            val tabRecord = allTabs[id]
            if (tabRecord != null) {
                if (topLevel && tabRecord.folderId != null) continue
                makeTab(tabRecord)?.let { items.add(ZenSpaces.ZenItem.Tab(it)) }
            } else {
                val folderRecord = folders[id]
                if (folderRecord != null) {
                    if (topLevel) {
                        val parent = folderRecord.parentFolderId
                        if (!parent.isNullOrEmpty() && folders.containsKey(parent)) continue
                    }
                    items.add(ZenSpaces.ZenItem.Folder(makeFolderTree(folderRecord, allTabs, folders, splits, depth)))
                } else {
                    val splitRecord = splits[id]
                    if (splitRecord != null) {
                        val members = (splitRecord.tabs ?: emptyList())
                            .mapNotNull { allTabs[it] }
                            .mapNotNull { makeTab(it) }
                        if (members.size >= 2) {
                            items.add(
                                ZenSpaces.ZenItem.Split(
                                    ZenSpaces.ZenSplit(
                                        id = splitRecord.splitId,
                                        gridType = splitRecord.gridType,
                                        tabs = members,
                                    )
                                )
                            )
                        }
                    }
                }
            }
        }
        return items
    }

    /**
     * Builds a folder and its nested sub-folders. Children come from the
     * folder's `children` ids; folders only linked via `parentFolderId` and
     * tabs only linked via `folderId` are picked up too, so partial sync
     * payloads still render completely. Mirrors the Swift `makeFolderTree`.
     */
    private fun makeFolderTree(
        folder: ZenSpaces.ZenFolderRecord,
        allTabs: Map<String, ZenSpaces.ZenTabRecord>,
        folders: Map<String, ZenSpaces.ZenFolderRecord>,
        splits: Map<String, ZenSpaces.ZenSplitRecord>,
        depth: Int,
    ): ZenSpaces.ZenFolder {
        val tabs = mutableListOf<ZenSpaces.ZenTab>()
        val subfolders = mutableListOf<ZenSpaces.ZenFolder>()
        val placed = mutableSetOf<String>()

        if (depth < 4) {
            for (childId in folder.children ?: emptyList()) {
                val tabRecord = allTabs[childId]
                if (tabRecord != null) {
                    makeTab(tabRecord)?.let {
                        tabs.add(it)
                        placed.add(childId)
                    }
                } else {
                    val childFolder = folders[childId]
                    if (childFolder != null && childFolder.folderId != folder.folderId) {
                        subfolders.add(makeFolderTree(childFolder, allTabs, folders, splits, depth + 1))
                        placed.add(childId)
                    }
                }
            }
            // Sub-folders assigned only via parentFolderId.
            folders.values
                .filter { it.parentFolderId == folder.folderId && !placed.contains(it.folderId) }
                .sortedBy { it.folderId }
                .forEach { subfolders.add(makeFolderTree(it, allTabs, folders, splits, depth + 1)) }
            // Tabs assigned via folderId but missing from children.
            allTabs.values
                .filter { it.folderId == folder.folderId && !placed.contains(it.tabId) }
                .mapNotNull { makeTab(it) }
                .forEach { tabs.add(it) }
        }

        return ZenSpaces.ZenFolder(
            id = folder.folderId,
            name = folder.name ?: "",
            icon = folder.icon?.takeIf { it.isNotEmpty() },
            tabs = tabs,
            subfolders = subfolders.takeIf { it.isNotEmpty() },
        )
    }

    internal fun makeTab(record: ZenSpaces.ZenTabRecord): ZenSpaces.ZenTab? {
        val rawURL = record.url.trim()
        if (rawURL.isEmpty()) return null
        if (!hasScheme(rawURL)) return null
        return ZenSpaces.ZenTab(
            id = record.tabId,
            url = rawURL,
            title = record.title ?: "",
            iconURL = null,
            icon = record.icon,
            hasStaticIcon = record.hasStaticIcon,
        )
    }

    /** Mirrors Swift's `URL(string:)` scheme presence check closely enough for sync data. */
    internal fun hasScheme(urlString: String): Boolean = runCatching {
        val uri = URI(urlString)
        uri.scheme != null && uri.scheme.isNotEmpty()
    }.getOrDefault(false)

    // MARK: - Cache

    /** Loads cached data immediately, then refreshes over the network. */
    suspend fun refresh(context: Context): ZenSpaces.ZenSnapshot {
        val fresh = loadSnapshot(context)
        SnapshotCache.cache(fresh)
        return fresh
    }

    // MARK: - Write

    /**
     * Effective outcome of an upload: the record id, whether it was written
     * as a pinned or normal tab, and whether a requested normal write fell
     * back to pinned because the browser capability was not enabled.
     */
    data class AddTabOutcome(
        val recordId: String,
        val kind: SaveKind,
        val fellBackToPinned: Boolean = false,
    )

    /**
     * Uploads one encrypted `tab` record, appends it to the space's children,
     * and updates the local cache — the exact flow of the Swift version.
     * Normal writes are gated on the synced prefs capability and fall back to
     * pinned (the tab is never lost).
     */
    suspend fun addTab(
        context: Context,
        url: String,
        title: String,
        spaceId: String,
        folderId: String? = null,
        kind: SaveKind = SaveKind.PINNED,
    ): AddTabOutcome {
        if (AccountStore.isDemo(context)) {
            // Demo catalog contains normal tabs, so its capability is ENABLED.
            val recordId = addTabLocally(url = url, title = title, spaceId = spaceId, folderId = folderId, kind = kind)
            return AddTabOutcome(recordId = recordId, kind = kind, fellBackToPinned = false)
        }
        val client = AccountStore.connect(context)
        return addTab(client = client, url = url, title = title, spaceId = spaceId, folderId = folderId, kind = kind)
    }

    /**
     * Sample-data save: same cache rewrite as a live save, no Firefox Sync.
     * A normal save lands in the space's normal (`tabs`) bucket.
     */
    private fun addTabLocally(
        url: String,
        title: String,
        spaceId: String,
        folderId: String?,
        kind: SaveKind,
    ): String {
        if (SnapshotCache.cachedSnapshotShared == null) {
            SnapshotCache.cache(DemoCatalog.snapshot)
        }
        val recordId = ZenSpaces.newTabRecordId()
        val newTab = ZenSpaces.ZenTab(
            id = recordId,
            url = url,
            title = title,
            iconURL = null,
            icon = null,
            hasStaticIcon = false,
        )
        SnapshotCache.insertCachedTab(
            spaceId,
            tab = newTab,
            folderId = if (kind == SaveKind.PINNED) folderId else null,
            fetchedAtMillis = System.currentTimeMillis(),
            kind = kind,
        )
        AppEvents.emitSnapshotStale()
        return recordId
    }

    suspend fun addTab(
        client: SyncClient,
        url: String,
        title: String,
        spaceId: String,
        folderId: String? = null,
        kind: SaveKind = SaveKind.PINNED,
    ): AddTabOutcome =
        withContext(Dispatchers.IO) {
            var effectiveKind = kind
            var fellBackToPinned = false
            // Only a normal write needs the extra prefs read. A transient or
            // unreadable read counts as not enabled and pins instead.
            if (kind == SaveKind.NORMAL) {
                val capability = try {
                    syncedPrefs(client).normalTabsCapability
                } catch (_: Exception) {
                    ZenSpaces.NormalTabsCapability.ABSENT
                }
                if (capability != ZenSpaces.NormalTabsCapability.ENABLED) {
                    effectiveKind = SaveKind.PINNED
                    fellBackToPinned = true
                }
            }
            val effectiveFolder = if (effectiveKind == SaveKind.PINNED) folderId else null
            val recordId = if (SyncSafety.safeSyncEnabled) {
                addTabSafe(client, url, title, spaceId, effectiveFolder, effectiveKind)
            } else {
                addTabLegacy(client, url, title, spaceId, effectiveFolder, effectiveKind)
            }
            AddTabOutcome(recordId = recordId, kind = effectiveKind, fellBackToPinned = fellBackToPinned)
        }

    private fun addTabLegacy(
        client: SyncClient,
        url: String,
        title: String,
        spaceId: String,
        folderId: String?,
        kind: SaveKind,
    ): String =
        run {
            val spaceRecords = client.getRecords(collection)

            var targetSpaceData: JSONObject? = null
            var containerGuid: String? = null
            var targetFolderRecordId: String? = null
            var targetFolderData: JSONObject? = null

            for (record in spaceRecords) {
                val id = record.optString("id")
                try {
                    val cleartext = client.decryptRecord(collection, record)
                    if ((cleartext.opt("deleted") as? Boolean) == true) continue
                    val recordKind = cleartext.optString("kind")
                    val data = cleartext.optJSONObject("data") ?: continue
                    when {
                        recordKind == "space" && id == spaceId -> {
                            targetSpaceData = data
                            if (!data.isNull("containerGuid")) {
                                val guid = data.optString("containerGuid")
                                if (guid.isNotEmpty()) containerGuid = guid
                            }
                        }
                        // Normal tabs are never placed in a folder.
                        kind == SaveKind.PINNED && recordKind == "folder" && isTargetFolder(folderId, data) -> {
                            targetFolderData = data
                            targetFolderRecordId = id
                        }
                    }
                } catch (_: Exception) {
                    continue
                }
            }

            // If not found in live records, fall back to the cached snapshot.
            if (targetSpaceData == null) {
                SnapshotCache.cachedSnapshotShared?.space(spaceId)?.let { cached ->
                    containerGuid = cached.containerGuid
                    targetSpaceData = JSONObject()
                        .put("uuid", cached.id)
                        .put("name", cached.name)
                        .put("icon", cached.icon ?: "")
                        .put(
                            "children",
                            JSONArray().also { arr -> (cached.pinned + cached.tabs).forEach { arr.put(it.id) } },
                        )
                }
            }

            // The folder must belong to the target space; otherwise fall back
            // to the space root so the tab never lands in an unrelated folder.
            if (targetFolderData != null && targetFolderData!!.optString("workspaceUuid") != spaceId) {
                targetFolderData = null
                targetFolderRecordId = null
            }

            val recordId = ZenSpaces.newTabRecordId()
            val tabData = JSONObject()
                .put("tabId", recordId)
                .put("url", url)
                .put("title", title)
                .put("icon", JSONObject.NULL)
                .put("essential", false)
                .put("pinned", kind == SaveKind.PINNED)
                .put("workspaceUuid", spaceId)
                .put("hasStaticIcon", false)
                .put(
                    "folderId",
                    if (kind == SaveKind.PINNED && targetFolderData != null && !folderId.isNullOrEmpty()) {
                        folderId
                    } else {
                        JSONObject.NULL
                    },
                )
                .put("staticLabel", JSONObject.NULL)
            if (!containerGuid.isNullOrEmpty()) {
                tabData.put("containerGuid", containerGuid!!)
                tabData.put("defaultContainer", false)
            } else {
                tabData.put("containerGuid", JSONObject.NULL)
                tabData.put("defaultContainer", true)
            }

            val tabCleartext = JSONObject()
                .put("id", recordId)
                .put("kind", "tab")
                .put("data", tabData)

            // 1. Upload the new tab record.
            client.putRecord(collection = collection, id = recordId, obj = tabCleartext)

            // 2. Update the parent record's children so Zen Desktop places the
            //    tab: the folder record when a folder was chosen, otherwise the
            //    space record itself.
            if (targetFolderData != null && targetFolderRecordId != null) {
                val fData = targetFolderData!!
                val childrenArr = fData.optJSONArray("children")
                val children = mutableListOf<String>()
                if (childrenArr != null) {
                    for (i in 0 until childrenArr.length()) {
                        (childrenArr.opt(i) as? String)?.let { children.add(it) }
                    }
                }
                if (!children.contains(recordId)) {
                    children.add(recordId)
                }
                val updatedChildren = JSONArray()
                children.forEach { updatedChildren.put(it) }
                fData.put("children", updatedChildren)
                client.putRecord(
                    collection = collection,
                    id = targetFolderRecordId!!,
                    obj = JSONObject()
                        .put("id", targetFolderRecordId!!)
                        .put("kind", "folder")
                        .put("data", fData),
                )
            } else {
                targetSpaceData?.let { sData ->
                    val childrenArr = sData.optJSONArray("children")
                    val children = mutableListOf<String>()
                    if (childrenArr != null) {
                        for (i in 0 until childrenArr.length()) {
                            (childrenArr.opt(i) as? String)?.let { children.add(it) }
                        }
                    }
                    if (!children.contains(recordId)) {
                        children.add(recordId)
                    }
                    val updatedChildren = JSONArray()
                    children.forEach { updatedChildren.put(it) }
                    sData.put("children", updatedChildren)
                    client.putRecord(
                        collection = collection,
                        id = spaceId,
                        obj = JSONObject()
                            .put("id", spaceId)
                            .put("kind", "space")
                            .put("data", sData),
                    )
                }
            }

            // 3. Update the local cache immediately.
            SnapshotCache.cachedSnapshotShared?.let { cached ->
                val newTab = ZenSpaces.ZenTab(
                    id = recordId,
                    url = url,
                    title = title,
                    iconURL = null,
                    icon = null,
                    hasStaticIcon = false,
                )
                SnapshotCache.insertCachedTab(
                    spaceId,
                    tab = newTab,
                    folderId = if (kind == SaveKind.PINNED && targetFolderData != null) folderId else null,
                    fetchedAtMillis = System.currentTimeMillis(),
                    kind = kind,
                )
            }

            AppEvents.emitSnapshotStale()
            recordId
        }

    suspend fun deleteTab(context: Context, id: String) {
        if (AccountStore.isDemo(context)) {
            SnapshotCache.removeCachedTab(id, fetchedAtMillis = System.currentTimeMillis())
            AppEvents.emitSnapshotStale()
            return
        }
        val client = AccountStore.connect(context)
        deleteTab(client = client, context = context, id = id)
    }

    suspend fun deleteTab(client: SyncClient, context: Context? = null, id: String) {
        val kind = withContext(Dispatchers.IO) {
            if (SyncSafety.safeSyncEnabled) {
                deleteTabSafe(client, id)
            } else {
                val incoming = decryptedCollection(client)
                val resolved = incoming.firstOrNull { it.id == id }?.kind
                if (resolved == "split") {
                    unsplit(client, splitId = id, incoming = incoming)
                } else {
                    tombstoneTab(client, tabId = id, incoming = incoming)
                }
                resolved
            }
        }

        if (context != null) {
            if (kind == "split") {
                SnapshotCache.expandCachedSplit(id, fetchedAtMillis = System.currentTimeMillis())
            } else {
                SnapshotCache.removeCachedTab(id, fetchedAtMillis = System.currentTimeMillis())
            }
        }
        AppEvents.emitSnapshotStale()
    }

    private data class IncomingCleartext(val id: String, val cleartext: JSONObject) {
        val kind: String get() = cleartext.optString("kind")
        val data: JSONObject get() = cleartext.optJSONObject("data") ?: JSONObject()
    }

    private fun decryptedCollection(client: SyncClient): List<IncomingCleartext> =
        decryptedFrom(client, client.getRecords(collection))

    private fun decryptedFrom(client: SyncClient, records: List<JSONObject>): List<IncomingCleartext> {
        val out = mutableListOf<IncomingCleartext>()
        for (rec in records) {
            val recId = rec.optString("id")
            if (recId.isEmpty()) continue
            val cleartext = try {
                client.decryptRecord(collection, rec)
            } catch (_: Exception) {
                continue
            }
            if ((cleartext.opt("deleted") as? Boolean) == true) continue
            out.add(IncomingCleartext(recId, cleartext))
        }
        return out
    }

    // MARK: - Conflict-safe writes (SPEC §7.2)

    private class AddTabPlan(val batch: List<JSONObject>, val folderResolved: Boolean)

    /**
     * Conflict-safe addTab: one consistent read, one conditional POST with the
     * new tab plus the rewritten parent. On 412 re-read, recompute the
     * order-preserving union and retry once; a second 412 is a conflict.
     */
    private fun addTabSafe(
        client: SyncClient,
        url: String,
        title: String,
        spaceId: String,
        folderId: String?,
        kind: SaveKind,
    ): String {
        val recordId = ZenSpaces.newTabRecordId()
        var read = client.getCollectionWithMetadata(collection)
        var plan = planAddTab(client, read.records, recordId, url, title, spaceId, folderId, kind)
        var outcome = client.postRecords(collection, plan.batch, read.lastModified)
        if (outcome is PostOutcome.PreconditionFailed) {
            read = client.getCollectionWithMetadata(collection)
            plan = planAddTab(client, read.records, recordId, url, title, spaceId, folderId, kind)
            outcome = client.postRecords(collection, plan.batch, read.lastModified)
        }
        when (outcome) {
            is PostOutcome.Applied -> Unit
            is PostOutcome.PreconditionFailed ->
                throw SyncError.Conflict("add tab '$recordId': conditional write lost the race twice")
            is PostOutcome.PartialFailure ->
                throw SyncError.Conflict("add tab '$recordId': batch rejected (${failureSummary(outcome)})")
        }

        // Cache and notification only after the server accepted the batch.
        SnapshotCache.cachedSnapshotShared?.let { _ ->
            val newTab = ZenSpaces.ZenTab(
                id = recordId,
                url = url,
                title = title,
                iconURL = null,
                icon = null,
                hasStaticIcon = false,
            )
            SnapshotCache.insertCachedTab(
                spaceId,
                tab = newTab,
                folderId = if (kind == SaveKind.PINNED && plan.folderResolved) folderId else null,
                fetchedAtMillis = System.currentTimeMillis(),
                kind = kind,
            )
        }
        AppEvents.emitSnapshotStale()
        return recordId
    }

    /**
     * Resolves the target space/folder from one consistent read (with the
     * cached-snapshot fallback) and builds the atomic batch: the fresh tab
     * record plus the parent with [recordId] unioned into its children.
     */
    private fun planAddTab(
        client: SyncClient,
        records: List<JSONObject>,
        recordId: String,
        url: String,
        title: String,
        spaceId: String,
        folderId: String?,
        kind: SaveKind,
    ): AddTabPlan {
        var targetSpaceData: JSONObject? = null
        var containerGuid: String? = null
        var targetFolderRecordId: String? = null
        var targetFolderData: JSONObject? = null

        for (rec in decryptedFrom(client, records)) {
            val data = rec.data
            when {
                rec.kind == "space" && rec.id == spaceId -> {
                    targetSpaceData = data
                    if (!data.isNull("containerGuid")) {
                        val guid = data.optString("containerGuid")
                        if (guid.isNotEmpty()) containerGuid = guid
                    }
                }
                // Normal tabs are never placed in a folder.
                kind == SaveKind.PINNED && rec.kind == "folder" && isTargetFolder(folderId, data) -> {
                    targetFolderData = data
                    targetFolderRecordId = rec.id
                }
            }
        }

        // If not found in live records, fall back to the cached snapshot.
        if (targetSpaceData == null) {
            SnapshotCache.cachedSnapshotShared?.space(spaceId)?.let { cached ->
                containerGuid = cached.containerGuid
                targetSpaceData = JSONObject()
                    .put("uuid", cached.id)
                    .put("name", cached.name)
                    .put("icon", cached.icon ?: "")
                    .put(
                        "children",
                        JSONArray().also { arr -> (cached.pinned + cached.tabs).forEach { arr.put(it.id) } },
                    )
            }
        }

        // The folder must belong to the target space; otherwise fall back to
        // the space root so the tab never lands in an unrelated folder.
        if (targetFolderData != null && targetFolderData.optString("workspaceUuid") != spaceId) {
            targetFolderData = null
            targetFolderRecordId = null
        }

        val tabData = JSONObject()
            .put("tabId", recordId)
            .put("url", url)
            .put("title", title)
            .put("icon", JSONObject.NULL)
            .put("essential", false)
            .put("pinned", kind == SaveKind.PINNED)
            .put("workspaceUuid", spaceId)
            .put("hasStaticIcon", false)
            .put(
                "folderId",
                if (kind == SaveKind.PINNED && targetFolderData != null && !folderId.isNullOrEmpty()) {
                    folderId
                } else {
                    JSONObject.NULL
                },
            )
            .put("staticLabel", JSONObject.NULL)
        if (!containerGuid.isNullOrEmpty()) {
            tabData.put("containerGuid", containerGuid!!)
            tabData.put("defaultContainer", false)
        } else {
            tabData.put("containerGuid", JSONObject.NULL)
            tabData.put("defaultContainer", true)
        }

        val batch = mutableListOf(
            JSONObject()
                .put("id", recordId)
                .put("kind", "tab")
                .put("data", tabData),
        )
        val folder = targetFolderData
        val space = targetSpaceData
        if (folder != null && targetFolderRecordId != null) {
            folder.put(
                "children",
                jsonArray(SpacesSyncEdits.union(stringList(folder.optJSONArray("children")), listOf(recordId))),
            )
            batch += JSONObject()
                .put("id", targetFolderRecordId)
                .put("kind", "folder")
                .put("data", folder)
        } else if (space != null) {
            space.put(
                "children",
                jsonArray(SpacesSyncEdits.union(stringList(space.optJSONArray("children")), listOf(recordId))),
            )
            batch += JSONObject()
                .put("id", spaceId)
                .put("kind", "space")
                .put("data", space)
        }
        return AddTabPlan(batch, targetFolderData != null)
    }

    /**
     * Conflict-safe delete: one consistent read, then one conditional POST
     * carrying the complete change set (tombstones plus rewritten parents,
     * splits, and layout essentials). On 412 re-read and recompute the
     * semantic edits from the fresh state, so unrelated concurrent edits
     * survive; retry once.
     */
    private fun deleteTabSafe(client: SyncClient, id: String): String? {
        var read = client.getCollectionWithMetadata(collection)
        var incoming = decryptedFrom(client, read.records)
        val kind = incoming.firstOrNull { it.id == id }?.kind
        val fallbackMembers = if (kind == "split") {
            stringList(incoming.firstOrNull { it.id == id }?.data?.optJSONArray("tabs"))
        } else {
            emptyList()
        }
        fun batchFor(list: List<IncomingCleartext>): List<JSONObject> =
            if (kind == "split") {
                unsplitBatch(id, fallbackMembers, list)
            } else {
                tombstoneBatch(id, list)
            }

        var outcome = client.postRecords(collection, batchFor(incoming), read.lastModified)
        if (outcome is PostOutcome.PreconditionFailed) {
            read = client.getCollectionWithMetadata(collection)
            incoming = decryptedFrom(client, read.records)
            outcome = client.postRecords(collection, batchFor(incoming), read.lastModified)
        }
        when (outcome) {
            is PostOutcome.Applied -> Unit
            is PostOutcome.PreconditionFailed ->
                throw SyncError.Conflict("delete '$id': conditional write lost the race twice")
            is PostOutcome.PartialFailure ->
                throw SyncError.Conflict("delete '$id': batch rejected (${failureSummary(outcome)})")
        }
        return kind
    }

    private fun failureSummary(outcome: PostOutcome.PartialFailure): String =
        "failed=${outcome.failed.keys}, missing=${outcome.missingIds}"

    /** Tombstone + every semantic rewrite caused by removing [tabId]. */
    private fun tombstoneBatch(tabId: String, incoming: List<IncomingCleartext>): List<JSONObject> {
        val batch = mutableListOf(tombstoneRecord(tabId))
        val collapsing = mutableListOf<Pair<String, List<String>>>()
        for (rec in incoming) {
            if (rec.id == tabId || rec.kind != "split") continue
            val tabs = stringList(rec.data.optJSONArray("tabs"))
            if (!tabs.contains(tabId)) continue
            val remaining = tabs.filter { it != tabId }
            if (remaining.size < 2) {
                collapsing.add(rec.id to remaining)
            } else {
                rec.data.put("tabs", jsonArray(remaining))
                batch += JSONObject().put("id", rec.id).put("kind", "split").put("data", rec.data)
            }
        }
        for ((splitId, _) in collapsing) {
            batch += tombstoneRecord(splitId)
        }
        val collapsed = collapsing.associate { it.first to it.second }
        for (rec in incoming) {
            if (rec.id == tabId) continue
            when (rec.kind) {
                "space", "folder" -> {
                    var children = stringList(rec.data.optJSONArray("children"))
                    var changed = false
                    if (children.contains(tabId)) {
                        children = SpacesSyncEdits.removing(tabId, children)
                        changed = true
                    }
                    for ((splitId, remaining) in collapsed) {
                        if (children.contains(splitId)) {
                            children = SpacesSyncEdits.replacing(splitId, remaining, children)
                            changed = true
                        }
                    }
                    if (changed) {
                        rec.data.put("children", jsonArray(children))
                        batch += JSONObject().put("id", rec.id).put("kind", rec.kind).put("data", rec.data)
                    }
                }
                "layout" -> {
                    val essentials = rec.data.optJSONObject("essentials") ?: continue
                    var changed = false
                    for (bucket in essentials.keys().asSequence().toList()) {
                        val ids = stringList(essentials.optJSONArray(bucket))
                        if (!ids.contains(tabId)) continue
                        essentials.put(bucket, jsonArray(ids.filter { it != tabId }))
                        changed = true
                    }
                    if (changed) {
                        rec.data.put("essentials", essentials)
                        batch += JSONObject().put("id", rec.id).put("kind", "layout").put("data", rec.data)
                    }
                }
            }
        }
        return batch
    }

    /**
     * Tombstone the split and splice its members at its position in every
     * parent, as one batch. On a retry the fresh split's members win over the
     * ones captured before the conflict.
     */
    private fun unsplitBatch(
        splitId: String,
        fallbackMembers: List<String>,
        incoming: List<IncomingCleartext>,
    ): List<JSONObject> {
        val members = incoming.firstOrNull { it.id == splitId }
            ?.let { stringList(it.data.optJSONArray("tabs")) }
            ?: fallbackMembers
        val batch = mutableListOf(tombstoneRecord(splitId))
        for (rec in incoming) {
            if (rec.id == splitId) continue
            if (rec.kind != "space" && rec.kind != "folder") continue
            val children = stringList(rec.data.optJSONArray("children"))
            if (!children.contains(splitId)) continue
            rec.data.put("children", jsonArray(SpacesSyncEdits.replacing(splitId, members, children)))
            batch += JSONObject().put("id", rec.id).put("kind", rec.kind).put("data", rec.data)
        }
        return batch
    }

    private fun tombstoneRecord(id: String): JSONObject =
        JSONObject().put("id", id).put("deleted", true)

    private fun unsplit(client: SyncClient, splitId: String, incoming: List<IncomingCleartext>) {
        val members = stringList(incoming.firstOrNull { it.id == splitId }?.data?.optJSONArray("tabs"))
        client.putTombstone(collection, splitId)
        replaceChild(client, oldId = splitId, replacements = members, incoming = incoming)
    }

    private fun tombstoneTab(client: SyncClient, tabId: String, incoming: List<IncomingCleartext>) {
        client.putTombstone(collection, tabId)
        val collapsing = mutableListOf<Pair<String, List<String>>>()
        for (rec in incoming) {
            if (rec.id == tabId) continue
            val data = rec.data
            when (rec.kind) {
                "space", "folder" -> {
                    val children = stringList(data.optJSONArray("children"))
                    if (!children.contains(tabId)) continue
                    data.put("children", jsonArray(SpacesSyncEdits.removing(tabId, children)))
                    putCleartext(client, rec.id, rec.kind, data)
                }
                "split" -> {
                    val tabs = stringList(data.optJSONArray("tabs"))
                    if (!tabs.contains(tabId)) continue
                    val remaining = tabs.filter { it != tabId }
                    if (remaining.size < 2) {
                        collapsing.add(rec.id to remaining)
                    } else {
                        data.put("tabs", jsonArray(remaining))
                        putCleartext(client, rec.id, "split", data)
                    }
                }
                "layout" -> {
                    val essentials = data.optJSONObject("essentials") ?: continue
                    var changed = false
                    val keys = essentials.keys().asSequence().toList()
                    for (bucket in keys) {
                        val ids = stringList(essentials.optJSONArray(bucket))
                        if (!ids.contains(tabId)) continue
                        essentials.put(bucket, jsonArray(ids.filter { it != tabId }))
                        changed = true
                    }
                    if (changed) {
                        data.put("essentials", essentials)
                        putCleartext(client, rec.id, "layout", data)
                    }
                }
            }
        }
        for ((splitId, remaining) in collapsing) {
            client.putTombstone(collection, splitId)
            replaceChild(client, oldId = splitId, replacements = remaining, incoming = incoming)
        }
    }

    private fun replaceChild(
        client: SyncClient,
        oldId: String,
        replacements: List<String>,
        incoming: List<IncomingCleartext>,
    ) {
        for (rec in incoming) {
            if (rec.id == oldId) continue
            if (rec.kind != "space" && rec.kind != "folder") continue
            val data = rec.data
            val children = stringList(data.optJSONArray("children"))
            if (!children.contains(oldId)) continue
            data.put("children", jsonArray(SpacesSyncEdits.replacing(oldId, replacements, children)))
            putCleartext(client, rec.id, rec.kind, data)
        }
    }

    private fun putCleartext(client: SyncClient, id: String, kind: String, data: JSONObject) {
        client.putRecord(
            collection = collection,
            id = id,
            obj = JSONObject().put("id", id).put("kind", kind).put("data", data),
        )
    }

    private fun stringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            (arr.opt(i) as? String)?.let { out.add(it) }
        }
        return out
    }

    private fun jsonArray(values: List<String>): JSONArray {
        val arr = JSONArray()
        values.forEach { arr.put(it) }
        return arr
    }
}

/** Pure list rewrites shared with cache tests. */
internal object SpacesSyncEdits {
    fun removing(id: String, children: List<String>): List<String> = children.filter { it != id }

    /**
     * Order-preserving set union: existing order first, then first-seen
     * additions. Used to merge a new child into a concurrently rewritten
     * `children` list without dropping the other writer's entries.
     */
    fun union(existing: List<String>, additions: List<String>): List<String> {
        val out = existing.toMutableList()
        val seen = existing.toMutableSet()
        for (addition in additions) {
            if (addition.isNotEmpty() && seen.add(addition)) out.add(addition)
        }
        return out
    }

    fun replacing(oldId: String, replacements: List<String>, children: List<String>): List<String> {
        val out = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (child in children) {
            if (child == oldId) {
                for (replacement in replacements) {
                    if (replacement != oldId && seen.add(replacement)) out.add(replacement)
                }
            } else if (seen.add(child)) {
                out.add(child)
            }
        }
        return out
    }
}
