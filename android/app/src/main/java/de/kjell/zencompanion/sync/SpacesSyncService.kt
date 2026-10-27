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
     * Desktop setting "Enable container-specific essentials". Zen 1.23+
     * registers it for sync; older versions leave it absent.
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
        return AccountStore.withClient(context) { loadSnapshot(client = it) }
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

        // Without a layout record every space falls through to the sorted
        // list below, so the order matches iOS on every launch.
        val order = layout?.spaces ?: emptyList()
        val seen = mutableSetOf<String>()
        val orderedIds = order.filter { spacesById.containsKey(it) && seen.add(it) }
        val unorderedIds = spacesById.keys.sorted().filter { !seen.contains(it) }

        val spaces = (orderedIds + unorderedIds).map { id ->
            makeSpace(spacesById.getValue(id), allTabs = tabsById, folders = foldersById, splits = splitsById)
        }

        val essentials = assembleEssentials(layout, tabsById)

        // Records came back but none decrypted into a space: that's a key
        // problem, not an empty account. Fail so the cached spaces stay.
        if (spaces.isEmpty() && decryptFailures > 0) {
            throw SyncError.Crypto("spaces: $decryptFailures records failed to decrypt")
        }

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
         * "Enable container-specific essentials". Null when the browser does
         * not sync the pref (Zen 1.22 and older).
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
            separateEssentialsPref(values)?.let { prefs = prefs.copy(separateEssentials = it) }
        }
        return prefs
    }

    /**
     * Resolves `zen.workspaces.separate-essentials` from one synced prefs value
     * map (fixture `wire-prefs-separate-essentials`). Zen 1.23+ registers the
     * pref for sync. Firefox's prefs engine writes `null` for a pref the user
     * never changed, and Zen's default is `true` (container-specific), so a
     * present key with a missing or unreadable value means container-specific.
     * A missing key means the browser does not sync the pref at all; callers
     * then infer from the layout buckets.
     */
    internal fun separateEssentialsPref(values: JSONObject?): Boolean? {
        if (values == null || !values.has(SEPARATE_ESSENTIALS_PREF_KEY)) return null
        return parsePrefBool(values.opt(SEPARATE_ESSENTIALS_PREF_KEY)) ?: true
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

    /**
     * Deterministic tab-title order for tabs that arrive outside `children`
     * order: fixed-locale case folding plus the tab id as tie-breaker, the
     * same rule as the Swift `byStableTitle`. Map iteration order (network
     * arrival order on some builds) must not leak into equal titles, and the
     * device locale must not pick the order.
     */
    internal fun compareByStableTitle(a: ZenSpaces.ZenTab, b: ZenSpaces.ZenTab): Int {
        val la = a.title.lowercase(java.util.Locale.ROOT)
        val lb = b.title.lowercase(java.util.Locale.ROOT)
        if (la != lb) return la.compareTo(lb)
        return a.id.compareTo(b.id)
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
        // A tab whose folder record is missing has nowhere else to render.
        val unplacedRecords = allTabs.values.filter {
            it.workspaceUuid == record.uuid && it.essential != true &&
                (it.folderId == null || !folders.containsKey(it.folderId)) &&
                !placedTabIds.contains(it.tabId) &&
                !de.kjell.zencompanion.favicon.FaviconResolver.isLocalURL(it.url)
        }
        fun sortedTabItems(records: List<ZenSpaces.ZenTabRecord>): List<ZenSpaces.ZenItem> =
            records
                .mapNotNull { makeTab(it) }
                .sortedWith { a, b -> compareByStableTitle(a, b) }
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
                // A folder member renders inside its folder, if that folder exists.
                if (topLevel && tabRecord.folderId != null && folders.containsKey(tabRecord.folderId)) continue
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
                val splitRecord = splits[childId]
                if (tabRecord != null) {
                    makeTab(tabRecord)?.let {
                        tabs.add(it)
                        placed.add(childId)
                    }
                } else if (splitRecord != null) {
                    // Folders have no split rows: keep the members in the
                    // split's place instead of dropping them to the end.
                    for (memberId in splitRecord.tabs ?: emptyList()) {
                        if (placed.contains(memberId)) continue
                        val member = allTabs[memberId]?.let { makeTab(it) } ?: continue
                        tabs.add(member)
                        placed.add(memberId)
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
                .sortedWith { a, b -> compareByStableTitle(a, b) }
                .forEach { tabs.add(it) }
        }

        return ZenSpaces.ZenFolder(
            id = folder.folderId,
            name = folder.name ?: "",
            icon = folder.icon?.takeIf { it.isNotEmpty() },
            tabs = tabs,
            subfolders = subfolders.takeIf { it.isNotEmpty() },
            live = folder.live,
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
            staticLabel = normalizedStaticLabel(record.staticLabel),
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
        val started = AccountStore.currentGeneration
        val fresh = loadSnapshot(context)
        // A sign-out during the fetch already deleted the cache; don't refill it.
        if (!AccountStore.isCurrent(started)) throw SyncError.NotSignedIn
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
        return AccountStore.withClient(context) {
            addTab(client = it, url = url, title = title, spaceId = spaceId, folderId = folderId, kind = kind)
        }
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
            ensureWritable(client)
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

    /** Highest Zen Spaces engine version this contract describes (SPEC §7.5). */
    const val SUPPORTED_SPACES_ENGINE_VERSION = 3

    /**
     * SPEC §7.5 write gate over the plain-JSON `meta/global` payload (fixture
     * `wire-meta-global-write-gate`). An absent record allows writes; anything
     * this app cannot vouch for blocks them.
     */
    fun metaGlobalAllowsWrites(payload: String?): Boolean {
        if (payload == null) return true
        val meta = try {
            JSONObject(payload)
        } catch (_: org.json.JSONException) {
            return false
        }
        fun number(raw: Any?): Double? = when (raw) {
            is Boolean -> null
            is Number -> raw.toDouble()
            else -> null
        }
        if (meta.has("storageVersion") && number(meta.opt("storageVersion")) != 5.0) return false
        if (!meta.has("engines")) return true
        val engines = meta.opt("engines") as? JSONObject ?: return false
        if (!engines.has("spaces")) return true
        val spaces = engines.opt("spaces") as? JSONObject ?: return false
        val version = number(spaces.opt("version")) ?: return false
        return version <= SUPPORTED_SPACES_ENGINE_VERSION
    }

    private fun ensureWritable(client: SyncClient) {
        if (!metaGlobalAllowsWrites(client.metaGlobalPayload())) throw SyncError.UnsupportedSyncVersion
    }

    /**
     * The space (and optional folder) a new tab attaches to, as full decrypted
     * cleartexts so a rewrite keeps every field it doesn't edit.
     */
    private class AddTarget(
        val spaceId: String,
        val space: JSONObject,
        val folderRecordId: String?,
        val folder: JSONObject?,
    ) {
        val containerGuid: String?
            get() {
                val data = space.optJSONObject("data") ?: return null
                if (data.isNull("containerGuid")) return null
                return data.optString("containerGuid").ifEmpty { null }
            }

        /** The folder (when resolved) or the space, with [recordId] unioned into `children`. */
        fun parentWrite(recordId: String): JSONObject {
            val (id, record) = if (folder != null && folderRecordId != null) {
                folderRecordId to folder
            } else {
                spaceId to space
            }
            val data = JSONObject((record.optJSONObject("data") ?: JSONObject()).toString())
            data.put(
                "children",
                jsonArray(SpacesSyncEdits.union(stringList(data.optJSONArray("children")), listOf(recordId))),
            )
            return rewritten(record, id, data)
        }
    }

    /**
     * Resolves the target space/folder from one read. The parent must come
     * from the server: a missing or deleted space throws
     * [SyncError.TargetMissing], an undecryptable one [SyncError.IncompleteRead]
     * (SPEC §7.5). Normal tabs never target a folder.
     */
    private fun resolveAddTarget(
        client: SyncClient,
        records: List<JSONObject>,
        spaceId: String,
        folderId: String?,
        kind: SaveKind,
    ): AddTarget {
        var space: JSONObject? = null
        var spaceUndecryptable = false
        var folder: JSONObject? = null
        var folderRecordId: String? = null
        for (record in records) {
            val id = record.optString("id")
            if (id.isEmpty()) continue
            val cleartext = try {
                client.decryptRecord(collection, record)
            } catch (_: Exception) {
                if (id == spaceId) spaceUndecryptable = true
                continue
            }
            if ((cleartext.opt("deleted") as? Boolean) == true) continue
            val data = cleartext.optJSONObject("data") ?: continue
            val recordKind = cleartext.optString("kind")
            when {
                recordKind == "space" && id == spaceId -> space = cleartext
                kind == SaveKind.PINNED && recordKind == "folder" && isTargetFolder(folderId, data) -> {
                    folder = cleartext
                    folderRecordId = id
                }
            }
        }
        val resolvedSpace = space
            ?: throw if (spaceUndecryptable) SyncError.IncompleteRead else SyncError.TargetMissing
        // The folder must belong to the target space; otherwise fall back to
        // the space root so the tab never lands in an unrelated folder.
        if (folder != null && folder.optJSONObject("data")?.optString("workspaceUuid") != spaceId) {
            folder = null
            folderRecordId = null
        }
        return AddTarget(spaceId, resolvedSpace, folderRecordId, folder)
    }

    private fun tabCleartext(
        recordId: String,
        url: String,
        title: String,
        spaceId: String,
        target: AddTarget,
        folderId: String?,
        kind: SaveKind,
    ): JSONObject {
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
                if (kind == SaveKind.PINNED && target.folder != null && !folderId.isNullOrEmpty()) {
                    folderId
                } else {
                    JSONObject.NULL
                },
            )
            .put("staticLabel", JSONObject.NULL)
        val containerGuid = target.containerGuid
        if (containerGuid != null) {
            tabData.put("containerGuid", containerGuid)
            tabData.put("defaultContainer", false)
        } else {
            tabData.put("containerGuid", JSONObject.NULL)
            tabData.put("defaultContainer", true)
        }
        return JSONObject()
            .put("id", recordId)
            .put("kind", "tab")
            .put("data", tabData)
    }

    private fun cacheAddedTab(
        recordId: String,
        url: String,
        title: String,
        spaceId: String,
        folderId: String?,
        kind: SaveKind,
    ) {
        if (SnapshotCache.cachedSnapshotShared == null) return
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
            folderId = folderId,
            fetchedAtMillis = System.currentTimeMillis(),
            kind = kind,
        )
    }

    private fun addTabLegacy(
        client: SyncClient,
        url: String,
        title: String,
        spaceId: String,
        folderId: String?,
        kind: SaveKind,
    ): String {
        val target = resolveAddTarget(client, client.getRecords(collection), spaceId, folderId, kind)
        val recordId = ZenSpaces.newTabRecordId()

        // 1. Upload the new tab record.
        client.putRecord(
            collection = collection,
            id = recordId,
            obj = tabCleartext(recordId, url, title, spaceId, target, folderId, kind),
        )

        // 2. Update the parent record's children so Zen Desktop places the
        //    tab: the folder record when a folder was chosen, otherwise the
        //    space record itself.
        val parent = target.parentWrite(recordId)
        client.putRecord(collection = collection, id = parent.getString("id"), obj = parent)

        // 3. Update the local cache immediately.
        cacheAddedTab(recordId, url, title, spaceId, if (target.folder != null) folderId else null, kind)
        AppEvents.emitSnapshotStale()
        return recordId
    }

    suspend fun deleteTab(context: Context, id: String) {
        if (AccountStore.isDemo(context)) {
            SnapshotCache.removeCachedTab(id, fetchedAtMillis = System.currentTimeMillis())
            AppEvents.emitSnapshotStale()
            return
        }
        AccountStore.withClient(context) { deleteTab(client = it, context = context, id = id) }
    }

    suspend fun deleteTab(client: SyncClient, context: Context? = null, id: String) {
        val kind = withContext(Dispatchers.IO) {
            ensureWritable(client)
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

    /**
     * Writes `staticLabel` on an existing tab record. Empty/whitespace
     * clears the custom name so the page title shows again.
     */
    suspend fun renameTab(context: Context, id: String, label: String) {
        val normalized = normalizedStaticLabel(label)
        if (AccountStore.isDemo(context)) {
            SnapshotCache.renameCachedTab(id, normalized, System.currentTimeMillis())
            AppEvents.emitSnapshotStale()
            return
        }
        AccountStore.withClient(context) { renameTab(client = it, id = id, label = label) }
    }

    suspend fun renameTab(client: SyncClient, id: String, label: String) {
        val normalized = normalizedStaticLabel(label)
        withContext(Dispatchers.IO) {
            ensureWritable(client)
            if (SyncSafety.safeSyncEnabled) {
                renameTabSafe(client, id, normalized)
            } else {
                renameTabLegacy(client, id, normalized)
            }
        }
        SnapshotCache.renameCachedTab(id, normalized, System.currentTimeMillis())
        AppEvents.emitSnapshotStale()
    }

    private fun renameTabSafe(client: SyncClient, id: String, label: String?) {
        var read = client.getCollectionWithMetadata(collection)
        var incoming = decryptedFrom(client, read.records)
        fun batchFor(list: List<IncomingCleartext>): List<JSONObject> {
            val write = renameWrite(id, label, list)
                ?: throw SyncError.Conflict("rename '$id': tab record not found")
            return listOf(write)
        }
        var outcome = client.postRecords(collection, batchFor(incoming), read.lastModified)
        if (outcome is PostOutcome.PreconditionFailed || outcome is PostOutcome.PartialFailure) {
            read = client.getCollectionWithMetadata(collection)
            incoming = decryptedFrom(client, read.records)
            outcome = client.postRecords(collection, batchFor(incoming), read.lastModified)
        }
        when (outcome) {
            is PostOutcome.Applied -> Unit
            is PostOutcome.PreconditionFailed ->
                throw SyncError.Conflict("rename '$id': conditional write lost the race twice")
            is PostOutcome.PartialFailure ->
                throw SyncError.Conflict("rename '$id': batch rejected (${failureSummary(outcome)})")
        }
    }

    private fun renameTabLegacy(client: SyncClient, id: String, label: String?) {
        val incoming = decryptedCollection(client)
        val write = renameWrite(id, label, incoming)
            ?: throw SyncError.Conflict("rename '$id': tab record not found")
        client.putRecord(collection = collection, id = id, obj = write)
    }

    private fun renameWrite(id: String, label: String?, incoming: List<IncomingCleartext>): JSONObject? {
        val rec = incoming.firstOrNull { it.id == id } ?: return null
        if (rec.kind != "tab") return null
        val data = JSONObject(rec.data.toString())
        if (label == null) data.put("staticLabel", JSONObject.NULL) else data.put("staticLabel", label)
        return rewritten(rec, data)
    }

    /**
     * Moves a pinned tab into a folder of the same space, or back to the space
     * root when [folderId] is null. Sets or clears `folderId` on the tab and
     * rewrites `children` on the old and new parent. A normal tab is left
     * untouched. The current place is not written.
     */
    suspend fun moveTab(context: Context, id: String, spaceId: String, folderId: String?) {
        val target = normalizeFolderId(folderId)
        if (AccountStore.isDemo(context)) {
            SnapshotCache.moveCachedTab(id, target, spaceId, System.currentTimeMillis())
            AppEvents.emitSnapshotStale()
            return
        }
        AccountStore.withClient(context) { moveTab(client = it, id = id, spaceId = spaceId, folderId = target) }
    }

    suspend fun moveTab(client: SyncClient, id: String, spaceId: String, folderId: String?) {
        val target = normalizeFolderId(folderId)
        val wrote = withContext(Dispatchers.IO) {
            ensureWritable(client)
            if (SyncSafety.safeSyncEnabled) {
                moveTabSafe(client, id, spaceId, target)
            } else {
                moveTabLegacy(client, id, spaceId, target)
            }
        }
        if (wrote) SnapshotCache.moveCachedTab(id, target, spaceId, System.currentTimeMillis())
        // Reload even when nothing was written: the screen already moved the
        // tab, and a target the server resolved to the root must snap back.
        AppEvents.emitSnapshotStale()
    }

    /**
     * Turns a normal (unpinned) tab into a pinned tab at its space root: sets
     * `pinned`, clears `folderId`, and moves the id out of any folder's
     * `children` into the space's. Every other field is kept. A tab that is
     * already pinned is left untouched (the action is only offered on normal
     * tabs).
     */
    suspend fun pinTab(context: Context, id: String, spaceId: String) {
        if (AccountStore.isDemo(context)) {
            SnapshotCache.pinCachedTab(id, spaceId, System.currentTimeMillis())
            AppEvents.emitSnapshotStale()
            return
        }
        AccountStore.withClient(context) { pinTab(client = it, id = id, spaceId = spaceId) }
    }

    suspend fun pinTab(client: SyncClient, id: String, spaceId: String) {
        val wrote = withContext(Dispatchers.IO) {
            ensureWritable(client)
            if (SyncSafety.safeSyncEnabled) {
                pinTabSafe(client, id, spaceId)
            } else {
                pinTabLegacy(client, id, spaceId)
            }
        }
        if (wrote) SnapshotCache.pinCachedTab(id, spaceId, System.currentTimeMillis())
        // Reload even when nothing was written: the screen already moved the
        // row, and a tab the server would not pin must snap back.
        AppEvents.emitSnapshotStale()
    }

    /**
     * Conflict-safe pin: one consistent read, then a conditional POST of the
     * tab plus the parent `children` rewrites. A 412 re-reads and reapplies
     * only this pin, so concurrent edits to other fields survive. Retry once.
     */
    private fun pinTabSafe(client: SyncClient, id: String, spaceId: String): Boolean {
        var read = client.getCollectionWithMetadata(collection)
        var incoming = decryptedFrom(client, read.records)
        var attempted = false
        // True once the server stored part of the previous batch; the next
        // plan then completes the missing records instead of no-op'ing.
        var repairs = false
        fun batchFor(list: List<IncomingCleartext>): List<JSONObject> =
            when (val plan = pinPlan(id, spaceId, list, repairs)) {
                MovePlan.Missing -> throw SyncError.Conflict("pin '$id': tab record not found")
                MovePlan.Unchanged -> emptyList()
                is MovePlan.Writes -> plan.records
            }
        var batch = batchFor(incoming)
        if (batch.isEmpty()) return false
        attempted = true
        var outcome = client.postRecords(collection, batch, read.lastModified)
        if (outcome is PostOutcome.PreconditionFailed || outcome is PostOutcome.PartialFailure) {
            repairs = outcome is PostOutcome.PartialFailure
            read = client.getCollectionWithMetadata(collection)
            incoming = decryptedFrom(client, read.records)
            batch = batchFor(incoming)
            outcome = if (batch.isEmpty()) {
                return attempted
            } else {
                client.postRecords(collection, batch, read.lastModified)
            }
        }
        when (outcome) {
            is PostOutcome.Applied -> return true
            is PostOutcome.PreconditionFailed ->
                throw SyncError.Conflict("pin '$id': conditional write lost the race twice")
            is PostOutcome.PartialFailure ->
                throw SyncError.Conflict("pin '$id': batch rejected (${failureSummary(outcome)})")
        }
    }

    /** Legacy pin: the same edit as the safe path, via unconditional PUTs. Mirrors [moveTabLegacy]. */
    private fun pinTabLegacy(client: SyncClient, id: String, spaceId: String): Boolean {
        val incoming = decryptedCollection(client)
        when (val plan = pinPlan(id, spaceId, incoming)) {
            MovePlan.Missing -> throw SyncError.Conflict("pin '$id': tab record not found")
            MovePlan.Unchanged -> return false
            is MovePlan.Writes -> {
                for (record in plan.records) {
                    client.putRecord(collection = collection, id = record.getString("id"), obj = record)
                }
                return true
            }
        }
    }

    /**
     * The tab's `pinned` flag, its cleared `folderId`, and the parent
     * `children` rewrites that move the id out of any folder and into the
     * space record. A tab that is already pinned is left untouched (the
     * action is only offered on normal tabs); the one exception is the
     * retry after a partial POST, which knows this batch stored some records
     * and completes the ones the server rejected ([repair]).
     */
    private fun pinPlan(
        id: String,
        spaceId: String,
        incoming: List<IncomingCleartext>,
        repair: Boolean = false,
    ): MovePlan {
        val tab = incoming.firstOrNull { it.id == id } ?: return MovePlan.Missing
        if (tab.kind != "tab") return MovePlan.Missing
        val owner = ZenSpaces.optStringOrNull(tab.data, "workspaceUuid")
        if (owner != null && owner != spaceId) return MovePlan.Unchanged
        val newParent = parentRecord(null, spaceId, incoming)
        val alreadyPinned = !isNormalTab(tab.data)
        if (alreadyPinned && !repair) return MovePlan.Unchanged

        val writes = mutableListOf<JSONObject>()
        if (!alreadyPinned) {
            val tabData = JSONObject(tab.data.toString())
            tabData.put("pinned", true)
            tabData.put("folderId", JSONObject.NULL)
            writes.add(rewritten(tab, tabData))
        }

        // A normal tab usually already sits in the space `children`, so the
        // order-preserving union is then a no-op and only the tab is written.
        incoming.filter { it.id != newParent?.id }.forEach { parent ->
            childrenWrite(parent) { SpacesSyncEdits.removing(id, it) }?.let { writes.add(it) }
        }
        newParent?.let { parent ->
            childrenWrite(parent) { SpacesSyncEdits.union(it, listOf(id)) }?.let { writes.add(it) }
        }
        if (writes.isEmpty()) return MovePlan.Unchanged
        return MovePlan.Writes(writes)
    }

    /**
     * Conflict-safe move: one consistent read, then a conditional POST of the
     * tab plus the old and new parent. A 412 re-reads and reapplies only this
     * move, so concurrent edits to other fields survive. Retry once.
     */
    private fun moveTabSafe(
        client: SyncClient,
        id: String,
        spaceId: String,
        folderId: String?,
    ): Boolean {
        var read = client.getCollectionWithMetadata(collection)
        var incoming = decryptedFrom(client, read.records)
        var attempted = false
        // True once the server stored part of the previous batch; the next
        // plan then completes the missing records instead of no-op'ing.
        var repairs = false
        fun batchFor(list: List<IncomingCleartext>): List<JSONObject> =
            when (val plan = movePlan(id, folderId, spaceId, list, repairs)) {
                MovePlan.Missing -> throw SyncError.Conflict("move '$id': tab record not found")
                MovePlan.Unchanged -> emptyList()
                is MovePlan.Writes -> plan.records
            }
        var batch = batchFor(incoming)
        if (batch.isEmpty()) return false
        attempted = true
        var outcome = client.postRecords(collection, batch, read.lastModified)
        if (outcome is PostOutcome.PreconditionFailed || outcome is PostOutcome.PartialFailure) {
            repairs = outcome is PostOutcome.PartialFailure
            read = client.getCollectionWithMetadata(collection)
            incoming = decryptedFrom(client, read.records)
            batch = batchFor(incoming)
            outcome = if (batch.isEmpty()) {
                return attempted
            } else {
                client.postRecords(collection, batch, read.lastModified)
            }
        }
        when (outcome) {
            is PostOutcome.Applied -> return true
            is PostOutcome.PreconditionFailed ->
                throw SyncError.Conflict("move '$id': conditional write lost the race twice")
            is PostOutcome.PartialFailure ->
                throw SyncError.Conflict("move '$id': batch rejected (${failureSummary(outcome)})")
        }
    }

    /** Legacy move: the same edit as the safe path, via unconditional PUTs. Mirrors [renameTabLegacy]. */
    private fun moveTabLegacy(
        client: SyncClient,
        id: String,
        spaceId: String,
        folderId: String?,
    ): Boolean {
        val incoming = decryptedCollection(client)
        when (val plan = movePlan(id, folderId, spaceId, incoming)) {
            MovePlan.Missing -> throw SyncError.Conflict("move '$id': tab record not found")
            MovePlan.Unchanged -> return false
            is MovePlan.Writes -> {
                for (record in plan.records) {
                    client.putRecord(collection = collection, id = record.getString("id"), obj = record)
                }
                return true
            }
        }
    }

    private sealed class MovePlan {
        data object Missing : MovePlan()
        data object Unchanged : MovePlan()
        data class Writes(val records: List<JSONObject>) : MovePlan()
    }

    /**
     * Tab `folderId` plus the old and new parent's `children`. A folder that
     * does not belong to [spaceId] falls back to the space root (SPEC §3.5),
     * so a tab never lands in another space's folder. An already-current
     * place produces no writes; the one exception is the retry after a
     * partial POST, which knows this batch stored some records and completes
     * the ones the server rejected ([repair]).
     */
    private fun movePlan(
        id: String,
        folderId: String?,
        spaceId: String,
        incoming: List<IncomingCleartext>,
        repair: Boolean = false,
    ): MovePlan {
        val tab = incoming.firstOrNull { it.id == id } ?: return MovePlan.Missing
        if (tab.kind != "tab") return MovePlan.Missing
        if (isNormalTab(tab.data)) return MovePlan.Unchanged
        val owner = ZenSpaces.optStringOrNull(tab.data, "workspaceUuid")
        if (owner != null && owner != spaceId) return MovePlan.Unchanged

        val current = currentFolderId(tab.data)
        val target = resolveMoveFolder(folderId, spaceId, incoming)
        val targetFolderId = target?.folderId
        val newParent = parentRecord(targetFolderId, spaceId, incoming)
        val completing = current == targetFolderId
        if (completing && !repair) return MovePlan.Unchanged

        val writes = mutableListOf<JSONObject>()
        if (!completing) {
            val tabData = JSONObject(tab.data.toString())
            tabData.put("folderId", targetFolderId ?: JSONObject.NULL)
            writes.add(rewritten(tab, tabData))
        }

        // Take the tab out of every parent that lists it, as delete does. Looking
        // the old folder up by workspaceUuid misses folders without one (optional,
        // SPEC §3.5), and the tab would then show in both places.
        incoming.filter { it.id != newParent?.id }.forEach { parent ->
            childrenWrite(parent) { SpacesSyncEdits.removing(id, it) }?.let { writes.add(it) }
        }
        newParent?.let { parent ->
            childrenWrite(parent) { children ->
                SpacesSyncEdits.union(SpacesSyncEdits.removing(id, children), listOf(id))
            }?.let { writes.add(it) }
        }
        if (writes.isEmpty()) return MovePlan.Unchanged
        return MovePlan.Writes(writes)
    }

    /** The folder record for a same-space target, or null when the move lands on the space root. */
    private fun resolveMoveFolder(
        folderId: String?,
        spaceId: String,
        incoming: List<IncomingCleartext>,
    ): ResolvedFolder? {
        val requested = normalizeFolderId(folderId) ?: return null
        val match = incoming.firstOrNull { rec ->
            rec.kind == "folder" &&
                isTargetFolder(requested, rec.data) &&
                rec.data.optString("workspaceUuid") == spaceId
        } ?: return null
        val matched = currentFolderId(match.data) ?: return null
        return ResolvedFolder(match, matched)
    }

    private class ResolvedFolder(val record: IncomingCleartext, val folderId: String)

    /** Folder parent when [folderId] is set, otherwise the space record. */
    private fun parentRecord(
        folderId: String?,
        spaceId: String,
        incoming: List<IncomingCleartext>,
    ): IncomingCleartext? {
        if (folderId != null) {
            return incoming.firstOrNull { rec ->
                rec.kind == "folder" &&
                    isTargetFolder(folderId, rec.data) &&
                    rec.data.optString("workspaceUuid") == spaceId
            }
        }
        return incoming.firstOrNull { it.id == spaceId && it.kind == "space" }
    }

    private fun childrenWrite(
        record: IncomingCleartext,
        transform: (List<String>) -> List<String>,
    ): JSONObject? {
        if (record.kind != "space" && record.kind != "folder") return null
        val children = stringList(record.data.optJSONArray("children"))
        val next = transform(children)
        if (next == children) return null
        val data = JSONObject(record.data.toString())
        data.put("children", jsonArray(next))
        return rewritten(record, data)
    }

    private fun isNormalTab(data: JSONObject): Boolean = ZenSpaces.parseFlag(data, "pinned") == false

    private fun currentFolderId(data: JSONObject): String? =
        ZenSpaces.optStringOrNull(data, "folderId")?.takeIf { it.isNotEmpty() }

    private fun normalizeFolderId(folderId: String?): String? = folderId?.takeIf { it.isNotEmpty() }

    internal fun normalizedStaticLabel(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        return trimmed.ifEmpty { null }
    }

    private data class IncomingCleartext(val id: String, val cleartext: JSONObject) {
        val kind: String get() = cleartext.optString("kind")
        val data: JSONObject get() = cleartext.optJSONObject("data") ?: JSONObject()
    }

    private fun decryptedCollection(client: SyncClient): List<IncomingCleartext> =
        decryptedFrom(client, client.getRecords(collection))

    /**
     * Every live record of a delete's planning read. A delete rewrites every
     * parent that references the removed id, so one undecryptable record
     * refuses the whole write (SPEC §7.5).
     */
    private fun decryptedFrom(client: SyncClient, records: List<JSONObject>): List<IncomingCleartext> {
        val out = mutableListOf<IncomingCleartext>()
        for (rec in records) {
            val recId = rec.optString("id")
            if (recId.isEmpty()) continue
            val cleartext = try {
                client.decryptRecord(collection, rec)
            } catch (_: Exception) {
                throw SyncError.IncompleteRead
            }
            if ((cleartext.opt("deleted") as? Boolean) == true) continue
            out.add(IncomingCleartext(recId, cleartext))
        }
        return out
    }

    // MARK: - Conflict-safe writes (SPEC §7.2)

    /**
     * Conflict-safe addTab: one consistent read, one conditional POST with the
     * new tab plus the rewritten parent. A 412 or a partial outcome re-reads,
     * recomputes the order-preserving union and retries once with the same
     * record id, so the retry is idempotent; a second failure is a conflict.
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
        var lastFailure: PostOutcome = PostOutcome.Applied
        for (attempt in 0 until 2) {
            val read = client.getCollectionWithMetadata(collection)
            val target = resolveAddTarget(client, read.records, spaceId, folderId, kind)
            val batch = listOf(
                tabCleartext(recordId, url, title, spaceId, target, folderId, kind),
                target.parentWrite(recordId),
            )
            when (val outcome = client.postRecords(collection, batch, read.lastModified)) {
                is PostOutcome.Applied -> {
                    // Cache and notification only after the server accepted the batch.
                    cacheAddedTab(recordId, url, title, spaceId, if (target.folder != null) folderId else null, kind)
                    AppEvents.emitSnapshotStale()
                    return recordId
                }
                else -> lastFailure = outcome
            }
        }
        throw SyncError.Conflict("add tab '$recordId': ${failureSummary(lastFailure)}")
    }

    /**
     * Conflict-safe delete: one consistent read, then one conditional POST
     * carrying the complete change set (tombstones plus rewritten parents,
     * splits, and layout essentials). A 412 or a partial outcome re-reads and
     * recomputes the semantic edits from the fresh state, so unrelated
     * concurrent edits survive; retry once.
     */
    private fun deleteTabSafe(client: SyncClient, id: String): String? {
        var kind: String? = null
        var fallbackMembers = emptyList<String>()
        var lastFailure: PostOutcome = PostOutcome.Applied
        for (attempt in 0 until 2) {
            val read = client.getCollectionWithMetadata(collection)
            val incoming = decryptedFrom(client, read.records)
            if (attempt == 0) {
                kind = incoming.firstOrNull { it.id == id }?.kind
                if (kind == "split") {
                    fallbackMembers = stringList(incoming.firstOrNull { it.id == id }?.data?.optJSONArray("tabs"))
                }
            }
            val batch = if (kind == "split") {
                unsplitBatch(id, fallbackMembers, incoming)
            } else {
                tombstoneBatch(id, incoming)
            }
            when (val outcome = client.postRecords(collection, batch, read.lastModified)) {
                is PostOutcome.Applied -> return kind
                else -> lastFailure = outcome
            }
        }
        throw SyncError.Conflict("delete '$id': ${failureSummary(lastFailure)}")
    }

    private fun failureSummary(outcome: PostOutcome): String = when (outcome) {
        is PostOutcome.PreconditionFailed -> "conditional write lost the race twice"
        is PostOutcome.PartialFailure -> "batch rejected (failed=${outcome.failed.keys}, missing=${outcome.missingIds})"
        is PostOutcome.Applied -> "applied"
    }

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
                batch += rewritten(rec, rec.data.put("tabs", jsonArray(remaining)))
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
                        batch += rewritten(rec, rec.data.put("children", jsonArray(children)))
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
                        batch += rewritten(rec, rec.data.put("essentials", essentials))
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
            batch += rewritten(rec, rec.data.put("children", jsonArray(SpacesSyncEdits.replacing(splitId, members, children))))
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
                    putCleartext(client, rec, data.put("children", jsonArray(SpacesSyncEdits.removing(tabId, children))))
                }
                "split" -> {
                    val tabs = stringList(data.optJSONArray("tabs"))
                    if (!tabs.contains(tabId)) continue
                    val remaining = tabs.filter { it != tabId }
                    if (remaining.size < 2) {
                        collapsing.add(rec.id to remaining)
                    } else {
                        putCleartext(client, rec, data.put("tabs", jsonArray(remaining)))
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
                        putCleartext(client, rec, data.put("essentials", essentials))
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
            putCleartext(client, rec, data.put("children", jsonArray(SpacesSyncEdits.replacing(oldId, replacements, children))))
        }
    }

    private fun putCleartext(client: SyncClient, rec: IncomingCleartext, data: JSONObject) {
        client.putRecord(collection = collection, id = rec.id, obj = rewritten(rec, data))
    }

    private fun rewritten(rec: IncomingCleartext, data: JSONObject): JSONObject =
        rewritten(rec.cleartext, rec.id, data)

    /**
     * A decrypted record with new `data`, keeping every other top-level field
     * it carried (SPEC §7.5).
     */
    private fun rewritten(cleartext: JSONObject, id: String, data: JSONObject): JSONObject =
        JSONObject(cleartext.toString()).put("id", id).put("data", data)

    /** String entries of a raw JSON list, in order; anything else is dropped (SPEC §3.1). */
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
