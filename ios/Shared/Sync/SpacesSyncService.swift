import Foundation
import os

/// Reads and writes Zen's Spaces Sync collection (`spaces`), introduced by
/// zen-browser/desktop PRs #13598 (spaces/containers) and #13984
/// (tabs/folders/splits). Saving a link means uploading one encrypted `tab`
/// record; the desktop browser creates the real tab in that space on its next
/// sync. No device registration, no send-tab commands.
///
/// Note on regular tabs: pinned and essential tabs always sync through the
/// spaces engine. Since Zen PR #15250, normal (unpinned) tabs can sync too
/// when the opt-in `zen.spaces-sync.normal-tabs` pref is on; those records
/// carry `pinned: false` and a real `workspaceUuid`. The wire flag is the
/// source of truth here — never infer pinning from `children` membership.
enum SpacesSyncService {
    static let collection = "spaces"

    /// Firefox Sync collection holding the synced `prefs` map (one record).
    static let prefsCollection = "prefs"
    /// Desktop setting "Include unpinned tabs" (`zen.spaces-sync.normal-tabs`).
    static let normalTabsPrefKey = "zen.spaces-sync.normal-tabs"
    /// Desktop setting "Enable container-specific essentials". Not in Zen's
    /// synced-prefs list today, so callers must tolerate it being absent.
    static let separateEssentialsPrefKey = "zen.workspaces.separate-essentials"

    /// Test seam: when non-nil it wins over the AppGroup default. Production
    /// always reads the stored switch.
    static var safeSyncEnabledOverride: Bool?

    /// Local switch for the conflict-safe write path (SPEC §7.2, §8). Read at
    /// call time; default ON. OFF restores the legacy sequential unconditional
    /// PUTs byte-for-byte.
    static var safeSyncEnabled: Bool {
        if let override = safeSyncEnabledOverride { return override }
        return AppGroup.defaults.object(forKey: "safeSyncEnabled") as? Bool ?? true
    }

    private static let log = Logger(subsystem: "de.kjell.zencompanion", category: "sync")

    // MARK: - Read

    static func loadSnapshot() async throws -> ZenSnapshot {
        if AccountStore.isDemo {
            var cached = cachedSnapshot() ?? DemoCatalog.snapshot
            if !cached.spaces.isEmpty && cached.normalTabsCapability != .absent {
                return cached
            }
            cached.normalTabsCapability = normalizedDemoCapability(in: cached)
            cache(cached)
            return cached
        }
        let client = try await AccountStore.connect()
        return try await loadSnapshot(client: client)
    }

    static func loadSnapshot(client: SyncClient) async throws -> ZenSnapshot {
        async let prefsRead = syncedPrefs(client: client)
        let spaceRecords = try await client.getRecords(collection: collection)
        let prefs = await prefsRead

        var spacesById: [String: ZenSpaceRecord] = [:]
        var tabsById: [String: ZenTabRecord] = [:]
        var foldersById: [String: ZenFolderRecord] = [:]
        var splitsById: [String: ZenSplitRecord] = [:]
        var layout: ZenLayoutRecord?
        var decryptFailures = 0
        var skippedKinds = 0
        var gatedNormalItems = 0
        /// Any `pinned:false` tab/split seen in `spaces`, held back or not:
        /// the secondary capability signal (SPEC §7).
        var observedNormalItems = 0

        for record in spaceRecords {
            guard let id = record["id"] as? String else {
                log.warning("spaces sync: record without id skipped")
                continue
            }
            let cleartext: [String: Any]
            do {
                cleartext = try await client.decryptRecord(collection: collection, record: record)
            } catch {
                decryptFailures += 1
                log.error("spaces sync: decrypt failed for \(id, privacy: .public): \(String(describing: error), privacy: .public)")
                continue
            }
            if (cleartext["deleted"] as? Bool) == true { continue }
            switch ZenSpacesDecoder.decode(id: id, cleartext: cleartext) {
            case .space(let space):
                spacesById[space.uuid] = space
            case .tab(let tab):
                if tab.isNormalTab { observedNormalItems += 1 }
                // Records linger on the server after the option is turned off
                // (held back, not tombstoned). Hide them when the synced pref
                // says the option is off.
                if tab.isNormalTab && !prefs.normalTabs {
                    gatedNormalItems += 1
                    continue
                }
                tabsById[tab.tabId] = tab
            case .folder(let folder):
                foldersById[folder.folderId] = folder
            case .split(let split):
                if split.isNormalSplit { observedNormalItems += 1 }
                // Split records carry the same opt-in flag (from their first
                // member); gate them like normal tab records.
                if split.isNormalSplit && !prefs.normalTabs {
                    gatedNormalItems += 1
                    continue
                }
                splitsById[split.splitId] = split
            case .layout(let parsed):
                layout = parsed
            case nil:
                skippedKinds += 1
                let kind = cleartext["kind"] as? String ?? "?"
                log.warning("spaces sync: skipped record \(id, privacy: .public) (kind \(kind, privacy: .public)): unknown kind or malformed data")
            }
        }

        let order = layout?.spaces ?? Array(spacesById.keys)
        var seen = Set<String>()
        let orderedIds = order.filter { spacesById[$0] != nil && seen.insert($0).inserted }
        let unorderedIds = spacesById.keys.sorted().filter { !seen.contains($0) }

        let spaces = (orderedIds + unorderedIds).map { id -> ZenSpace in
            makeSpace(from: spacesById[id]!, allTabs: tabsById, folders: foldersById, splits: splitsById)
        }

        let essentials = assembleEssentials(layout: layout, allTabs: tabsById)

        log.info("spaces sync: \(spaceRecords.count, privacy: .public) records, \(decryptFailures, privacy: .public) decrypt failures, \(skippedKinds, privacy: .public) ignored → spaces: \(spaces.count, privacy: .public), tabs: \(tabsById.count, privacy: .public), folders: \(foldersById.count, privacy: .public), splits: \(splitsById.count, privacy: .public), essentials: \(essentials.values.map(\.count).reduce(0, +), privacy: .public), normal-tabs pref: \(prefs.normalTabs, privacy: .public), separate-essentials pref: \(String(describing: prefs.separateEssentials), privacy: .public), gated normal items: \(gatedNormalItems, privacy: .public)")

        return ZenSnapshot(
            spaces: spaces,
            essentials: essentials,
            separateEssentialsPref: prefs.separateEssentials,
            normalTabsCapability: Self.effectiveCapability(
                prefs.normalTabsCapability,
                observedNormalItems: observedNormalItems > 0
            ),
            fetchedAt: Date()
        )
    }

    /// Secondary capability signal (SPEC §7): a `pinned:false` tab/split seen
    /// in `spaces` proves browser support even when the prefs record is
    /// unreadable or the key is missing. It can only upgrade `absent` to
    /// `disabled` — never to `enabled`.
    static func effectiveCapability(
        _ capability: NormalTabsCapability,
        observedNormalItems: Bool
    ) -> NormalTabsCapability {
        if capability == .absent && observedNormalItems { return .disabled }
        return capability
    }

    /// Derives the write-gating capability from the decrypted prefs value
    /// map (fixture `wire-prefs-normal-tabs-capability`). The key's presence
    /// is the version-support signal even when its value is `null`.
    static func normalTabsCapability(prefsRecordPresent: Bool, values: [String: Any]?) -> NormalTabsCapability {
        guard prefsRecordPresent, let values, values.index(forKey: normalTabsPrefKey) != nil else { return .absent }
        return prefBool(values[normalTabsPrefKey]) == true ? .enabled : .disabled
    }

    /// The Zen preferences this app consumes from the synced `prefs` record.
    struct SyncedPrefs: Equatable {
        /// "Include unpinned tabs" (`zen.spaces-sync.normal-tabs`), default true.
        var normalTabs: Bool = true
        /// "Enable container-specific essentials" when Zen synced it; nil when
        /// absent (Zen does not mark it for sync today).
        var separateEssentials: Bool?
        /// Derived write gate (SPEC §7): `enabled` only when the record and
        /// the key are present and the value parses true.
        var normalTabsCapability: NormalTabsCapability = .absent
    }

    /// Reads the synced `prefs` record once and parses every pref this app
    /// consumes. Unreadable records keep the defaults: a present
    /// `pinned:false` record still renders, and essentials fall back to
    /// inference.
    static func syncedPrefs(client: SyncClient) async -> SyncedPrefs {
        var prefs = SyncedPrefs()
        guard let records = try? await client.getRecords(collection: prefsCollection) else { return prefs }
        for record in records {
            guard let cleartext = try? await client.decryptRecord(collection: prefsCollection, record: record),
                  let values = cleartext["value"] as? [String: Any] else { continue }
            prefs.normalTabsCapability = normalTabsCapability(prefsRecordPresent: true, values: values)
            if let raw = values[normalTabsPrefKey], let parsed = prefBool(raw) {
                prefs.normalTabs = parsed
            }
            if let raw = values[separateEssentialsPrefKey], let parsed = prefBool(raw) {
                prefs.separateEssentials = parsed
            }
        }
        return prefs
    }

    /// Convenience for the normal-tabs flag alone (legacy call sites/tests).
    static func normalTabsEnabled(client: SyncClient) async -> Bool {
        await syncedPrefs(client: client).normalTabs
    }

    /// Tolerant bool parse for a synced pref value (JSON bool, number or string).
    static func prefBool(_ raw: Any?) -> Bool? {
        switch raw {
        case let value as Bool: return value
        case let value as NSNumber: return value.boolValue
        case let value as String:
            switch value.lowercased() {
            case "true", "1": return true
            case "false", "0": return false
            default: return nil
            }
        default: return nil
        }
    }

    /// Essential tabs grouped by container bucket ("default" for tabs without
    /// a container), ordered by the layout record. Tabs the layout does not
    /// mention (missing or stale layout) land in "default" so they still show.
    static func assembleEssentials(
        layout: ZenLayoutRecord?,
        allTabs: [String: ZenTabRecord]
    ) -> [String: [ZenTab]] {
        var byBucket: [String: [ZenTab]] = [:]
        var placed = Set<String>()
        for (bucket, ids) in layout?.essentials ?? [:] {
            let tabs = ids.compactMap { id -> ZenTab? in
                guard let record = allTabs[id], record.essential == true else { return nil }
                return makeTab(record)
            }
            if !tabs.isEmpty {
                byBucket[bucket] = tabs
                placed.formUnion(tabs.map(\.id))
            }
        }
        let orphans = allTabs.values
            .filter { $0.essential == true && !placed.contains($0.tabId) }
            .compactMap(makeTab)
            .sorted { $0.title.localizedCaseInsensitiveCompare($1.title) == .orderedAscending }
        if !orphans.isEmpty {
            byBucket["default", default: []].append(contentsOf: orphans)
        }
        return byBucket
    }

    static func makeSpace(
        from record: ZenSpaceRecord,
        allTabs: [String: ZenTabRecord],
        folders: [String: ZenFolderRecord],
        splits: [String: ZenSplitRecord] = [:]
    ) -> ZenSpace {
        let items = buildItems(
            ids: record.children ?? [],
            allTabs: allTabs,
            folders: folders,
            splits: splits,
            topLevel: true,
            depth: 0
        )
        // Partition by the record's wire `pinned` flag, keeping the synced
        // `children` order inside each bucket. Folders stay pinned; splits go
        // with their members (a split of normal tabs reports `pinned: false`).
        var pinnedItems: [ZenItem] = []
        var normalItems: [ZenItem] = []
        for item in items {
            switch item {
            case .tab(let tab) where allTabs[tab.id]?.isNormalTab == true:
                normalItems.append(item)
            case .split(let split) where splits[split.id]?.isNormalSplit == true:
                normalItems.append(item)
            default:
                pinnedItems.append(item)
            }
        }
        // Also capture tabs assigned to this space that aren't yet listed in children
        // (e.g. freshly shared tabs from mobile before desktop syncs back the updated children array)
        let placedTabIds = Set(items.flatMap { item -> [String] in
            switch item {
            case .tab(let t): return [t.id]
            case .folder(let f): return f.tabs.map(\.id)
            case .split(let s): return s.tabs.map(\.id)
            }
        })
        let unplacedRecords = allTabs.values.filter {
            $0.workspaceUuid == record.uuid && $0.essential != true && $0.folderId == nil
                && !placedTabIds.contains($0.tabId)
                && !FaviconResolver.isLocalURL($0.url)
        }
        func sortedTabItems(_ records: [ZenTabRecord]) -> [ZenItem] {
            records
                .compactMap(makeTab)
                .sorted { $0.title.localizedCaseInsensitiveCompare($1.title) == .orderedAscending }
                .map { ZenItem.tab($0) }
        }
        pinnedItems.append(contentsOf: sortedTabItems(unplacedRecords.filter { !$0.isNormalTab }))
        normalItems.append(contentsOf: sortedTabItems(unplacedRecords.filter { $0.isNormalTab }))

        return ZenSpace(
            id: record.uuid,
            name: record.name ?? "",
            icon: record.icon.flatMap { $0.isEmpty ? nil : $0 },
            containerGuid: record.containerGuid,
            theme: record.theme,
            pinned: pinnedItems,
            tabs: normalItems
        )
    }

    /// Walks a child-id sequence in synced order. Top-level tabs that live in
    /// a folder are skipped (the folder renders them); folder entries expand
    /// into full folder trees; split-group ids resolve to their member tabs
    /// as a `.split` item.
    static func buildItems(
        ids: [String],
        allTabs: [String: ZenTabRecord],
        folders: [String: ZenFolderRecord],
        splits: [String: ZenSplitRecord] = [:],
        topLevel: Bool,
        depth: Int
    ) -> [ZenItem] {
        guard depth < 4 else { return [] }
        var items: [ZenItem] = []
        for id in ids {
            if let record = allTabs[id] {
                if topLevel && record.folderId != nil { continue }
                if let tab = makeTab(record) { items.append(.tab(tab)) }
            } else if let folder = folders[id] {
                // Folders nested under another folder render inside it, not here.
                if topLevel, let parent = folder.parentFolderId, !parent.isEmpty, folders[parent] != nil {
                    continue
                }
                items.append(.folder(makeFolderTree(
                    folder,
                    allTabs: allTabs,
                    folders: folders,
                    splits: splits,
                    depth: depth
                )))
            } else if let split = splits[id] {
                let members = (split.tabs ?? []).compactMap { allTabs[$0] }.compactMap(makeTab)
                if members.count >= 2 {
                    items.append(.split(ZenSplit(
                        id: split.splitId,
                        gridType: split.gridType,
                        tabs: members
                    )))
                }
            }
        }
        return items
    }

    /// Builds a folder and its nested sub-folders. Children come from the
    /// folder's `children` ids; folders only linked via `parentFolderId` and
    /// tabs only linked via `folderId` are picked up too, so partial sync
    /// payloads still render completely.
    private static func makeFolderTree(
        _ folder: ZenFolderRecord,
        allTabs: [String: ZenTabRecord],
        folders: [String: ZenFolderRecord],
        splits: [String: ZenSplitRecord],
        depth: Int
    ) -> ZenFolder {
        var tabs: [ZenTab] = []
        var subfolders: [ZenFolder] = []
        var placed = Set<String>()

        if depth < 4 {
            for childId in folder.children ?? [] {
                if let tabRecord = allTabs[childId] {
                    guard let tab = makeTab(tabRecord) else { continue }
                    tabs.append(tab)
                    placed.insert(childId)
                } else if let childFolder = folders[childId], childFolder.folderId != folder.folderId {
                    subfolders.append(makeFolderTree(
                        childFolder,
                        allTabs: allTabs,
                        folders: folders,
                        splits: splits,
                        depth: depth + 1
                    ))
                    placed.insert(childId)
                }
            }
            // Sub-folders assigned only via parentFolderId.
            let viaParent = folders.values
                .filter { $0.parentFolderId == folder.folderId && !placed.contains($0.folderId) }
                .sorted { $0.folderId < $1.folderId }
            for childFolder in viaParent {
                subfolders.append(makeFolderTree(
                    childFolder,
                    allTabs: allTabs,
                    folders: folders,
                    splits: splits,
                    depth: depth + 1
                ))
            }
            // Tabs assigned via folderId but missing from children.
            let extraTabs = allTabs.values
                .filter { $0.folderId == folder.folderId && !placed.contains($0.tabId) }
                .compactMap(makeTab)
            tabs.append(contentsOf: extraTabs)
        }

        return ZenFolder(
            id: folder.folderId,
            name: folder.name ?? "",
            icon: folder.icon.flatMap { $0.isEmpty ? nil : $0 },
            tabs: tabs,
            subfolders: subfolders.isEmpty ? nil : subfolders
        )
    }

    static func makeTab(_ record: ZenTabRecord) -> ZenTab? {
        let rawURL = record.url.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !rawURL.isEmpty, URL(string: rawURL)?.scheme != nil else { return nil }
        return ZenTab(
            id: record.tabId,
            url: rawURL,
            title: record.title ?? "",
            iconURL: nil,
            icon: record.icon,
            hasStaticIcon: record.hasStaticIcon
        )
    }

    // MARK: - Write

    /// D2 target-folder predicate (shared/contract/SPEC.md §3.5): a folder
    /// record only matches a requested target when the requested id is
    /// non-nil and non-empty AND the record's `data.folderId` is a non-empty
    /// JSON string exactly equal to it. A folder record missing/`null`/
    /// empty/non-string `folderId` therefore never matches — neither a nil
    /// target nor a named one (fixture `wire-folder-missing-folderid`).
    static func isTargetFolder(folderId: String?, data: [String: Any]) -> Bool {
        guard let folderId, !folderId.isEmpty else { return false }
        guard let candidate = data["folderId"] as? String, !candidate.isEmpty else { return false }
        return candidate == folderId
    }

    @discardableResult
    static func addTab(
        url: URL,
        title: String,
        to spaceId: String,
        folderId: String? = nil,
        kind: SaveKind = .pinned
    ) async throws -> AddTabOutcome {
        if AccountStore.isDemo {
            return addTabLocally(url: url, title: title, to: spaceId, folderId: folderId, kind: kind)
        }
        let client = try await AccountStore.connect()
        return try await addTab(client: client, url: url, title: title, to: spaceId, folderId: folderId, kind: kind)
    }

    @discardableResult
    static func addTab(
        client: SyncClient,
        url: URL,
        title: String,
        to spaceId: String,
        folderId: String? = nil,
        kind requestedKind: SaveKind = .pinned
    ) async throws -> AddTabOutcome {
        // Live safety gate (SPEC §7): a normal write re-reads the synced prefs
        // and falls back to pinned unless the capability is `enabled`. A
        // transient/unreadable prefs read counts as not enabled. Pinned writes
        // never pay for this extra read.
        var kind = requestedKind
        var fellBack = false
        if kind == .normal, await syncedPrefs(client: client).normalTabsCapability != .enabled {
            kind = .pinned
            fellBack = true
        }
        let recordId: String
        if safeSyncEnabled {
            recordId = try await addTabConflictSafe(
                client: client,
                url: url,
                title: title,
                to: spaceId,
                folderId: folderId,
                kind: kind
            )
        } else {
            recordId = try await addTabLegacy(
                client: client,
                url: url,
                title: title,
                to: spaceId,
                folderId: folderId,
                kind: kind
            )
        }
        return AddTabOutcome(recordId: recordId, kind: kind, fellBackToPinned: fellBack)
    }

    /// Legacy sequential write path (safe-sync OFF).
    private static func addTabLegacy(
        client: SyncClient,
        url: URL,
        title: String,
        to spaceId: String,
        folderId: String?,
        kind: SaveKind
    ) async throws -> String {
        let spaceRecords = try await client.getRecords(collection: collection)
        let target = await resolveAddTarget(
            client: client,
            records: spaceRecords,
            spaceId: spaceId,
            folderId: folderId,
            kind: kind
        )
        let targetSpaceData = target.spaceData
        let containerGuid = target.containerGuid
        let targetFolderRecordId = target.folderRecordId
        let targetFolderData = target.folderData

        let recordId = UUID().uuidString.lowercased()
        let tabCleartext = makeTabCleartext(
            recordId: recordId,
            url: url,
            title: title,
            spaceId: spaceId,
            containerGuid: containerGuid,
            folderId: targetFolderData != nil ? folderId : nil,
            kind: kind
        )

        // 1. Upload the new tab record
        try await client.putRecord(collection: collection, id: recordId, object: tabCleartext)

        // 2. Update the parent record's children so Zen Desktop places the
        //    tab: the folder record when a folder was chosen, otherwise the
        //    space record itself.
        if let folderData = targetFolderData, let folderRecordId = targetFolderRecordId {
            var fData = folderData
            fData["children"] = SpacesSyncEdits.attaching(
                recordId,
                to: fData["children"] as? [String] ?? [],
                kind: kind,
                pinnedIds: []
            )
            try await putCleartext(client: client, id: folderRecordId, kind: "folder", data: fData)
        } else if var sData = targetSpaceData {
            sData["children"] = SpacesSyncEdits.attaching(
                recordId,
                to: sData["children"] as? [String] ?? [],
                kind: kind,
                pinnedIds: cachedPinnedIds(in: spaceId)
            )
            let spaceCleartext: [String: Any] = [
                "id": spaceId,
                "kind": "space",
                "data": sData
            ]
            try await client.putRecord(collection: collection, id: spaceId, object: spaceCleartext)
        }

        // 3. Update the local cache immediately
        cacheAddedTab(
            recordId: recordId,
            url: url,
            title: title,
            spaceId: spaceId,
            folderId: targetFolderData != nil ? folderId : nil,
            kind: kind
        )

        NotificationCenter.default.post(name: .zenCompanionSnapshotStale, object: nil)
        return recordId
    }

    /// Conflict-safe add (SPEC §7.2): consistent read with the collection
    /// timestamp, then one atomic conditional POST carrying the fresh tab and
    /// the rewritten parent. A 412 re-reads, recomputes the children attach
    /// against fresh server state, and retries once.
    private static func addTabConflictSafe(
        client: SyncClient,
        url: URL,
        title: String,
        to spaceId: String,
        folderId: String?,
        kind: SaveKind
    ) async throws -> String {
        let recordId = UUID().uuidString.lowercased()
        for attempt in 0..<2 {
            let (records, lastModified) = try await client.getCollectionWithMetadata(collection: collection)
            let target = await resolveAddTarget(
                client: client,
                records: records,
                spaceId: spaceId,
                folderId: folderId,
                kind: kind
            )
            let attachedFolderId = target.folderData != nil ? folderId : nil

            var writes = [SyncWriteRecord(
                id: recordId,
                cleartext: makeTabCleartext(
                    recordId: recordId,
                    url: url,
                    title: title,
                    spaceId: spaceId,
                    containerGuid: target.containerGuid,
                    folderId: attachedFolderId,
                    kind: kind
                )
            )]

            if let folderData = target.folderData, let folderRecordId = target.folderRecordId {
                var data = folderData
                data["children"] = SpacesSyncEdits.attaching(
                    recordId,
                    to: data["children"] as? [String] ?? [],
                    kind: kind,
                    pinnedIds: []
                )
                writes.append(SyncWriteRecord(
                    id: folderRecordId,
                    cleartext: ["id": folderRecordId, "kind": "folder", "data": data]
                ))
            } else if let spaceData = target.spaceData {
                var data = spaceData
                data["children"] = SpacesSyncEdits.attaching(
                    recordId,
                    to: data["children"] as? [String] ?? [],
                    kind: kind,
                    pinnedIds: cachedPinnedIds(in: spaceId)
                )
                writes.append(SyncWriteRecord(
                    id: spaceId,
                    cleartext: ["id": spaceId, "kind": "space", "data": data]
                ))
            }

            let outcome = try await client.postRecords(
                collection: collection,
                records: writes,
                ifUnmodifiedSince: lastModified
            )
            switch outcome {
            case .applied:
                cacheAddedTab(
                    recordId: recordId,
                    url: url,
                    title: title,
                    spaceId: spaceId,
                    folderId: attachedFolderId,
                    kind: kind
                )
                NotificationCenter.default.post(name: .zenCompanionSnapshotStale, object: nil)
                return recordId
            case .preconditionFailed:
                if attempt == 1 { throw SyncError.conflict }
            case .partialFailure:
                throw SyncError.conflict
            }
        }
        throw SyncError.conflict
    }

    /// Resolves the space (and optional folder) record a new tab attaches to,
    /// falling back to the cached snapshot when the live collection lacks the
    /// space. Shared by the legacy and conflict-safe add paths. A normal tab
    /// never targets a folder (the contract ignores `folderId` for
    /// `pinned:false` records).
    private static func resolveAddTarget(
        client: SyncClient,
        records: [[String: Any]],
        spaceId: String,
        folderId: String?,
        kind: SaveKind
    ) async -> (
        spaceData: [String: Any]?,
        containerGuid: String?,
        folderRecordId: String?,
        folderData: [String: Any]?
    ) {
        var targetSpaceData: [String: Any]?
        var containerGuid: String?
        var targetFolderRecordId: String?
        var targetFolderData: [String: Any]?

        let requestedFolderId = kind == .normal ? nil : folderId
        for record in records {
            guard let id = record["id"] as? String else { continue }
            if let cleartext = try? await client.decryptRecord(collection: collection, record: record),
               (cleartext["deleted"] as? Bool) != true,
               let data = cleartext["data"] as? [String: Any] {
                switch cleartext["kind"] as? String {
                case "space" where id == spaceId:
                    targetSpaceData = data
                    containerGuid = data["containerGuid"] as? String
                case "folder" where isTargetFolder(folderId: requestedFolderId, data: data):
                    targetFolderData = data
                    targetFolderRecordId = id
                default:
                    break
                }
            }
            if targetSpaceData != nil && (requestedFolderId == nil || targetFolderData != nil) { break }
        }

        // If not found in live records, check cached snapshot as fallback
        if targetSpaceData == nil, let cached = cachedSnapshot()?.space(id: spaceId) {
            containerGuid = cached.containerGuid
            targetSpaceData = [
                "uuid": cached.id,
                "name": cached.name,
                "icon": cached.icon ?? "",
                "children": (cached.pinned + cached.tabs).map(\.id)
            ]
        }

        // The folder must belong to the target space; otherwise fall back to
        // the space root so the tab never lands in an unrelated folder.
        if let folderData = targetFolderData,
           (folderData["workspaceUuid"] as? String) != spaceId {
            targetFolderData = nil
            targetFolderRecordId = nil
        }

        return (targetSpaceData, containerGuid, targetFolderRecordId, targetFolderData)
    }

    private static func makeTabCleartext(
        recordId: String,
        url: URL,
        title: String,
        spaceId: String,
        containerGuid: String?,
        folderId: String?,
        kind: SaveKind
    ) -> [String: Any] {
        var tabData: [String: Any] = [
            "tabId": recordId,
            "url": url.absoluteString,
            "title": title,
            "icon": NSNull(),
            "essential": false,
            "pinned": kind == .pinned,
            "workspaceUuid": spaceId,
            "hasStaticIcon": false,
            "folderId": (kind == .normal ? nil : folderId) ?? NSNull(),
            "staticLabel": NSNull()
        ]
        if let guid = containerGuid, !guid.isEmpty {
            tabData["containerGuid"] = guid
            tabData["defaultContainer"] = false
        } else {
            tabData["containerGuid"] = NSNull()
            tabData["defaultContainer"] = true
        }
        return ["id": recordId, "kind": "tab", "data": tabData]
    }

    private static func cacheAddedTab(
        recordId: String,
        url: URL,
        title: String,
        spaceId: String,
        folderId: String?,
        kind: SaveKind
    ) {
        guard var cached = cachedSnapshot() else { return }
        let newTab = ZenTab(
            id: recordId,
            url: url.absoluteString,
            title: title,
            iconURL: nil,
            icon: nil,
            hasStaticIcon: false
        )
        if let idx = cached.spaces.firstIndex(where: { $0.id == spaceId }) {
            if kind == .pinned {
                cached.spaces[idx].pinned = SpacesSyncEdits.inserting(
                    newTab,
                    folderId: folderId,
                    into: cached.spaces[idx].pinned
                )
            } else {
                cached.spaces[idx].tabs.insert(.tab(newTab), at: 0)
            }
        }
        cached.fetchedAt = Date()
        cache(cached)
    }

    /// Pinned sidebar ids from the last snapshot, used so a normal save can
    /// land at the front of the open-tabs region instead of after every tab.
    private static func cachedPinnedIds(in spaceId: String) -> Set<String> {
        Set(cachedSnapshot()?.space(id: spaceId)?.pinned.map(\.id) ?? [])
    }

    /// Deletes a pinned tab, or unsplits a split group, by uploading a Weave
    /// tombstone and rewriting parent `children` lists. Matches Zen desktop
    /// `ZenSpacesSync.sys.mjs` (`record.deleted = true` on the encrypted
    /// cleartext) and `ZenSpacesSyncApplier.#deleteTabs` / `#deleteSplits`.
    static func deleteTab(id: String) async throws {
        if AccountStore.isDemo {
            if let cached = cachedSnapshot() {
                cache(SpacesSyncEdits.remove(id: id, from: cached))
            }
            NotificationCenter.default.post(name: .zenCompanionSnapshotStale, object: nil)
            return
        }
        let client = try await AccountStore.connect()
        try await deleteTab(client: client, id: id)
    }

    static func deleteTab(client: SyncClient, id: String) async throws {
        if safeSyncEnabled {
            try await deleteTabConflictSafe(client: client, id: id)
        } else {
            let incoming = try await decryptedCollection(client: client)
            let kind = incoming.first { $0.id == id }?.kind

            if kind == "split" {
                try await unsplit(client: client, splitId: id, incoming: incoming)
                if let cached = cachedSnapshot() {
                    cache(SpacesSyncEdits.expandSplit(splitId: id, in: cached))
                }
            } else {
                try await tombstoneTab(client: client, tabId: id, incoming: incoming)
                if let cached = cachedSnapshot() {
                    cache(SpacesSyncEdits.remove(id: id, from: cached))
                }
            }
        }

        NotificationCenter.default.post(name: .zenCompanionSnapshotStale, object: nil)
    }

    /// Conflict-safe delete: one consistent read and one atomic conditional
    /// POST carrying the complete change set (primary tombstone, collapsing
    /// split tombstones, parent splices and layout bucket rewrites). A 412
    /// re-reads and recomputes the same semantic edits against fresh server
    /// state, retrying once.
    private static func deleteTabConflictSafe(client: SyncClient, id: String) async throws {
        for attempt in 0..<2 {
            let (records, lastModified) = try await client.getCollectionWithMetadata(collection: collection)
            let incoming = await decryptedCleartexts(client: client, records: records)
            let changeSet = deleteChangeSet(id: id, incoming: incoming)

            let outcome = try await client.postRecords(
                collection: collection,
                records: changeSet.writes,
                ifUnmodifiedSince: lastModified
            )
            switch outcome {
            case .applied:
                if let cached = cachedSnapshot() {
                    cache(changeSet.wasSplit
                        ? SpacesSyncEdits.expandSplit(splitId: id, in: cached)
                        : SpacesSyncEdits.remove(id: id, from: cached))
                }
                return
            case .preconditionFailed:
                if attempt == 1 { throw SyncError.conflict }
            case .partialFailure:
                throw SyncError.conflict
            }
        }
        throw SyncError.conflict
    }

    private struct DeleteChangeSet {
        let writes: [SyncWriteRecord]
        let wasSplit: Bool
    }

    private static func deleteChangeSet(id: String, incoming: [IncomingCleartext]) -> DeleteChangeSet {
        if incoming.first(where: { $0.id == id })?.kind == "split" {
            return DeleteChangeSet(writes: unsplitChangeSet(splitId: id, incoming: incoming), wasSplit: true)
        }
        return DeleteChangeSet(writes: tombstoneChangeSet(tabId: id, incoming: incoming), wasSplit: false)
    }

    /// Tombstone + rewrite change set for deleting one tab id. Split members
    /// that drop below two members collapse: their tombstone and the parent
    /// splice are part of the same set, so one POST applies them atomically.
    private static func tombstoneChangeSet(tabId: String, incoming: [IncomingCleartext]) -> [SyncWriteRecord] {
        var writes = [tombstoneWrite(id: tabId)]

        var collapsedRemaining: [String: [String]] = [:]
        for rec in incoming where rec.id != tabId && rec.kind == "split" {
            guard let tabs = rec.data["tabs"] as? [String], tabs.contains(tabId) else { continue }
            let remaining = tabs.filter { $0 != tabId }
            if remaining.count < 2 {
                collapsedRemaining[rec.id] = remaining
                writes.append(tombstoneWrite(id: rec.id))
            } else {
                var data = rec.data
                data["tabs"] = remaining
                writes.append(rewrittenWrite(id: rec.id, kind: "split", data: data))
            }
        }

        for rec in incoming where rec.id != tabId && rec.kind != "split" {
            switch rec.kind {
            case "space", "folder":
                let children = rec.data["children"] as? [String] ?? []
                let touchesTab = children.contains(tabId)
                let touchesCollapsedSplit = collapsedRemaining.keys.contains { children.contains($0) }
                guard touchesTab || touchesCollapsedSplit else { continue }
                var next = children.filter { $0 != tabId }
                for (splitId, remaining) in collapsedRemaining {
                    next = SpacesSyncEdits.replacing(splitId, with: remaining, in: next)
                }
                var data = rec.data
                data["children"] = next
                writes.append(rewrittenWrite(id: rec.id, kind: rec.kind ?? "", data: data))
            case "layout":
                guard var essentials = rec.data["essentials"] as? [String: Any] else { continue }
                var changed = false
                for (bucket, raw) in essentials {
                    guard let ids = raw as? [String], ids.contains(tabId) else { continue }
                    essentials[bucket] = ids.filter { $0 != tabId }
                    changed = true
                }
                guard changed else { continue }
                var data = rec.data
                data["essentials"] = essentials
                writes.append(rewrittenWrite(id: rec.id, kind: "layout", data: data))
            default:
                continue
            }
        }

        return writes
    }

    /// Tombstone the split + splice its members into every parent `children`.
    private static func unsplitChangeSet(splitId: String, incoming: [IncomingCleartext]) -> [SyncWriteRecord] {
        let members = incoming.first { $0.id == splitId }?.data["tabs"] as? [String] ?? []
        var writes = [tombstoneWrite(id: splitId)]
        for rec in incoming where rec.id != splitId && (rec.kind == "space" || rec.kind == "folder") {
            guard let children = rec.data["children"] as? [String], children.contains(splitId) else { continue }
            var data = rec.data
            data["children"] = SpacesSyncEdits.replacing(splitId, with: members, in: children)
            writes.append(rewrittenWrite(id: rec.id, kind: rec.kind ?? "", data: data))
        }
        return writes
    }

    private static func tombstoneWrite(id: String) -> SyncWriteRecord {
        SyncWriteRecord(id: id, cleartext: ["id": id, "deleted": true])
    }

    private static func rewrittenWrite(id: String, kind: String, data: [String: Any]) -> SyncWriteRecord {
        SyncWriteRecord(id: id, cleartext: ["id": id, "kind": kind, "data": data])
    }

    private struct IncomingCleartext {
        let id: String
        let cleartext: [String: Any]
        var kind: String? { cleartext["kind"] as? String }
        var data: [String: Any] { cleartext["data"] as? [String: Any] ?? [:] }
    }

    private static func decryptedCollection(client: SyncClient) async throws -> [IncomingCleartext] {
        let records = try await client.getRecords(collection: collection)
        return await decryptedCleartexts(client: client, records: records)
    }

    private static func decryptedCleartexts(client: SyncClient, records: [[String: Any]]) async -> [IncomingCleartext] {
        var out: [IncomingCleartext] = []
        for rec in records {
            guard let recId = rec["id"] as? String else { continue }
            guard let cleartext = try? await client.decryptRecord(collection: collection, record: rec) else {
                continue
            }
            if (cleartext["deleted"] as? Bool) == true { continue }
            out.append(IncomingCleartext(id: recId, cleartext: cleartext))
        }
        return out
    }

    /// Tombstone the split record and splice its member tab ids into the
    /// parent space/folder `children`. Member tab records stay; desktop
    /// `#deleteSplits` then calls `removeGroup`.
    private static func unsplit(
        client: SyncClient,
        splitId: String,
        incoming: [IncomingCleartext]
    ) async throws {
        let members = incoming.first { $0.id == splitId }?.data["tabs"] as? [String] ?? []
        try await client.putTombstone(collection: collection, id: splitId)
        try await replaceChild(
            client: client,
            oldId: splitId,
            with: members,
            incoming: incoming
        )
    }

    private static func tombstoneTab(
        client: SyncClient,
        tabId: String,
        incoming: [IncomingCleartext]
    ) async throws {
        try await client.putTombstone(collection: collection, id: tabId)

        var collapsingSplits: [(id: String, remaining: [String])] = []
        for rec in incoming {
            guard rec.id != tabId else { continue }
            var data = rec.data
            switch rec.kind {
            case "space", "folder":
                guard let children = data["children"] as? [String], children.contains(tabId) else { continue }
                data["children"] = SpacesSyncEdits.removing(tabId, from: children)
                try await putCleartext(client: client, id: rec.id, kind: rec.kind ?? "", data: data)
            case "split":
                guard let tabs = data["tabs"] as? [String], tabs.contains(tabId) else { continue }
                let remaining = tabs.filter { $0 != tabId }
                if remaining.count < 2 {
                    collapsingSplits.append((rec.id, remaining))
                } else {
                    data["tabs"] = remaining
                    try await putCleartext(client: client, id: rec.id, kind: "split", data: data)
                }
            case "layout":
                guard var essentials = data["essentials"] as? [String: Any] else { continue }
                var changed = false
                for (bucket, raw) in essentials {
                    guard let ids = raw as? [String], ids.contains(tabId) else { continue }
                    essentials[bucket] = ids.filter { $0 != tabId }
                    changed = true
                }
                if changed {
                    data["essentials"] = essentials
                    try await putCleartext(client: client, id: rec.id, kind: "layout", data: data)
                }
            default:
                break
            }
        }

        for split in collapsingSplits {
            try await client.putTombstone(collection: collection, id: split.id)
            try await replaceChild(
                client: client,
                oldId: split.id,
                with: split.remaining,
                incoming: incoming
            )
        }
    }

    private static func replaceChild(
        client: SyncClient,
        oldId: String,
        with replacements: [String],
        incoming: [IncomingCleartext]
    ) async throws {
        for rec in incoming {
            guard rec.id != oldId else { continue }
            guard rec.kind == "space" || rec.kind == "folder" else { continue }
            var data = rec.data
            guard let children = data["children"] as? [String], children.contains(oldId) else { continue }
            data["children"] = SpacesSyncEdits.replacing(oldId, with: replacements, in: children)
            try await putCleartext(client: client, id: rec.id, kind: rec.kind ?? "", data: data)
        }
    }

    private static func putCleartext(
        client: SyncClient,
        id: String,
        kind: String,
        data: [String: Any]
    ) async throws {
        try await client.putRecord(
            collection: collection,
            id: id,
            object: ["id": id, "kind": kind, "data": data]
        )
    }

    // MARK: - Cache (app group; lets the share extension start instantly)

    // D1: this local snapshot cache is device-local and NON-CONTRACTUAL
    // (shared/contract/SPEC.md §8). No fixture covers it, cross-platform
    // compatibility is not required, and its shape may change without a
    // contract version bump as long as each platform still migrates its own
    // old caches. The Swift enum encoding below (`{"tab": {"_0": …}}`) is an
    // implementation detail, unlike the wire records.

    private static var cacheURL: URL {
        AppGroup.container.appendingPathComponent("spaces-cache.json")
    }

    static func cache(_ snapshot: ZenSnapshot) {
        guard let data = try? JSONEncoder().encode(snapshot) else { return }
        var url = cacheURL
        do {
            // Device-local only (SPEC §8): keep tab data out of iCloud backups.
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            try url.setResourceValues(values)
        } catch {}
        try? data.write(to: cacheURL, options: [.atomic, .completeFileProtection])
        AppGroup.defaults.set(snapshot.fetchedAt.timeIntervalSince1970, forKey: "spacesCacheTime")
    }

    static func cachedSnapshot() -> ZenSnapshot? {
        guard let data = try? Data(contentsOf: cacheURL),
              let snapshot = try? JSONDecoder().decode(ZenSnapshot.self, from: data)
        else { return nil }
        return snapshot
    }

    /// Removes the cached preview data and its timestamp. Called on sign-out
    /// so tab titles/URLs never outlive the signed-in state.
    static func deleteCachedSnapshot() {
        try? FileManager.default.removeItem(at: cacheURL)
        AppGroup.defaults.removeObject(forKey: "spacesCacheTime")
        AppGroup.defaults.removeObject(forKey: "lastSpaceId")
    }

    /// Loads cached data immediately, then refreshes over the network.
    @discardableResult
    static func refresh() async throws -> ZenSnapshot {
        let fresh = try await loadSnapshot()
        cache(fresh)
        return fresh
    }

    /// Sample-data pin: same cache rewrite as a live pin, no Firefox Sync.
    /// Demo data always carries normal tabs, so the capability reports
    /// `.enabled` and a normal save is honored.
    private static func addTabLocally(
        url: URL,
        title: String,
        to spaceId: String,
        folderId: String?,
        kind requestedKind: SaveKind
    ) -> AddTabOutcome {
        var kind = requestedKind
        var fellBack = false
        if kind == .normal, normalizedDemoCapability(in: cachedSnapshot() ?? DemoCatalog.snapshot) != .enabled {
            kind = .pinned
            fellBack = true
        }
        let recordId = UUID().uuidString.lowercased()
        var cached = cachedSnapshot() ?? DemoCatalog.snapshot
        cached.normalTabsCapability = normalizedDemoCapability(in: cached)
        let newTab = ZenTab(
            id: recordId,
            url: url.absoluteString,
            title: title,
            iconURL: nil,
            icon: nil,
            hasStaticIcon: false
        )
        if let idx = cached.spaces.firstIndex(where: { $0.id == spaceId }) {
            if kind == .pinned {
                cached.spaces[idx].pinned = SpacesSyncEdits.inserting(
                    newTab,
                    folderId: folderId,
                    into: cached.spaces[idx].pinned
                )
            } else {
                cached.spaces[idx].tabs.insert(.tab(newTab), at: 0)
            }
        }
        cached.fetchedAt = Date()
        cache(cached)
        NotificationCenter.default.post(name: .zenCompanionSnapshotStale, object: nil)
        return AddTabOutcome(recordId: recordId, kind: kind, fellBackToPinned: fellBack)
    }

    /// Demo capability: `.enabled` when the snapshot (or the catalog) ships
    /// normal tabs, otherwise `.disabled` (never `absent` — demo mode is
    /// self-contained).
    private static func normalizedDemoCapability(in snapshot: ZenSnapshot) -> NormalTabsCapability {
        if snapshot.normalTabsCapability != .absent {
            return snapshot.normalTabsCapability
        }
        let source = snapshot.spaces.isEmpty ? DemoCatalog.snapshot : snapshot
        return source.spaces.contains { !$0.tabs.isEmpty } ? .enabled : .disabled
    }
}

    /// Pure list/cache edits for delete + unsplit. Kept off the network path
    /// so tests can cover the same rewrite desktop applies on the next sync.
    enum SpacesSyncEdits {
        /// Inserts a pinned tab at the space root, or inside the folder with
        /// the given id (any nesting depth). Falls back to the root when the
        /// folder does not exist.
        static func inserting(
            _ tab: ZenTab,
            folderId: String?,
            into pinned: [ZenItem]
        ) -> [ZenItem] {
            guard let folderId, !folderId.isEmpty else { return pinned + [.tab(tab)] }
            var next = pinned
            guard insertRecursively(tab, folderId: folderId, into: &next) else {
                return pinned + [.tab(tab)]
            }
            return next
        }

        private static func insertRecursively(
            _ tab: ZenTab,
            folderId: String,
            into items: inout [ZenItem]
        ) -> Bool {
            for index in items.indices {
                guard case .folder(var folder) = items[index] else { continue }
                if folder.id == folderId {
                    folder.tabs.append(tab)
                    items[index] = .folder(folder)
                    return true
                }
                var nested = folder.subfolders ?? []
                if insertRecursively(tab, folderId: folderId, into: &nested) {
                    folder.subfolders = nested
                    items[index] = .folder(folder)
                    return true
                }
            }
            return false
        }

        private static func insertRecursively(
            _ tab: ZenTab,
            folderId: String,
            into folders: inout [ZenFolder]
        ) -> Bool {
            for index in folders.indices {
                if folders[index].id == folderId {
                    folders[index].tabs.append(tab)
                    return true
                }
                var nested = folders[index].subfolders ?? []
                if insertRecursively(tab, folderId: folderId, into: &nested) {
                    folders[index].subfolders = nested
                    return true
                }
            }
            return false
        }

    /// Drop `id` from a child-id list.
    static func removing(_ id: String, from children: [String]) -> [String] {
        children.filter { $0 != id }
    }

    /// Order-preserving set union: existing order first, then additions that
    /// are not already present. Concurrent remote edits keep their order and
    /// the local addition is appended once.
    static func union(_ children: [String], _ additions: [String]) -> [String] {
        var out: [String] = []
        var seen = Set<String>()
        for id in children + additions where seen.insert(id).inserted {
            out.append(id)
        }
        return out
    }

    /// Pins append (`union`); a normal tab is inserted just before the first
    /// child that is not a known pinned item, so it shows at the top of the
    /// open-tabs list. Folder children have no pinned/unpinned split, so
    /// `pinnedIds` is empty and a normal insert there prepends.
    static func attaching(
        _ recordId: String,
        to children: [String],
        kind: SaveKind,
        pinnedIds: Set<String>
    ) -> [String] {
        if kind == .normal {
            return insertingAtFrontOfUnpinned([recordId], into: children, pinnedIds: pinnedIds)
        }
        return union(children, [recordId])
    }

    /// Inserts `additions` immediately before the first child that is not in
    /// `pinnedIds`. Already-present ids are skipped. If every child is pinned
    /// (or the list is empty), additions are appended — still the start of
    /// the (empty) open-tabs region.
    static func insertingAtFrontOfUnpinned(
        _ additions: [String],
        into children: [String],
        pinnedIds: Set<String>
    ) -> [String] {
        var seen = Set(children)
        let fresh = additions.filter { seen.insert($0).inserted }
        guard !fresh.isEmpty else { return children }
        let index = children.firstIndex { !pinnedIds.contains($0) } ?? children.endIndex
        var out = children
        out.insert(contentsOf: fresh, at: index)
        return out
    }

    /// Replace `oldId` with `replacements` (deduped, in order) in a child list.
    static func replacing(_ oldId: String, with replacements: [String], in children: [String]) -> [String] {
        var out: [String] = []
        var seen = Set<String>()
        for child in children {
            if child == oldId {
                for replacement in replacements where replacement != oldId && seen.insert(replacement).inserted {
                    out.append(replacement)
                }
            } else if seen.insert(child).inserted {
                out.append(child)
            }
        }
        return out
    }

    /// Local cache after unsplitting: the group becomes its member tabs.
    static func expandSplit(splitId: String, in snapshot: ZenSnapshot) -> ZenSnapshot {
        var next = snapshot
        next.spaces = next.spaces.map { space in
            var copy = space
            copy.pinned = expandingSplit(splitId: splitId, in: copy.pinned)
            copy.tabs = expandingSplit(splitId: splitId, in: copy.tabs)
            return copy
        }
        next.fetchedAt = Date()
        return next
    }

    private static func expandingSplit(splitId: String, in items: [ZenItem]) -> [ZenItem] {
        items.flatMap { item in
            if case .split(let split) = item, split.id == splitId {
                return split.tabs.map { ZenItem.tab($0) }
            }
            return [item]
        }
    }

    /// Local cache after deleting a tab (or collapsing a split that lost a member).
    static func remove(id: String, from snapshot: ZenSnapshot) -> ZenSnapshot {
        var next = snapshot
        next.spaces = next.spaces.map { space in
            var copy = space
            copy.pinned = removing(id: id, from: copy.pinned)
            copy.tabs = removing(id: id, from: copy.tabs)
            return copy
        }
        next.essentials = next.essentials.mapValues { $0.filter { $0.id != id } }
        next.fetchedAt = Date()
        return next
    }

    private static func removing(id: String, from items: [ZenItem]) -> [ZenItem] {
        items.flatMap { item -> [ZenItem] in
            switch item {
            case .tab(let tab):
                return tab.id == id ? [] : [item]
            case .split(let split):
                if split.id == id {
                    return split.tabs.map { .tab($0) }
                }
                let tabs = split.tabs.filter { $0.id != id }
                if tabs.count >= 2 {
                    return [.split(ZenSplit(id: split.id, gridType: split.gridType, tabs: tabs))]
                }
                return tabs.map { .tab($0) }
            case .folder(let folder):
                let nextFolder = removing(id, from: folder)
                return [.folder(nextFolder)]
            }
        }
    }

    /// Removes a tab from a folder tree at any nesting depth.
    private static func removing(_ id: String, from folder: ZenFolder) -> ZenFolder {
        var next = folder
        next.tabs.removeAll { $0.id == id }
        next.subfolders = next.subfolders?.map { removing(id, from: $0) }
        return next
    }
}

extension Notification.Name {
    static let zenCompanionSnapshotStale = Notification.Name("zenCompanionSnapshotStale")
}
