import XCTest

@testable import ZenCompanion

final class TabRenameTests: XCTestCase {
    func testDisplayTitlePrefersStaticLabel() {
        let tab = ZenTab(
            id: "t1",
            url: "https://example.com/path",
            title: "Page title",
            staticLabel: "Work"
        )
        XCTAssertEqual(tab.displayTitle, "Work")
    }

    func testDisplayTitleFallsBackToPageTitle() {
        let tab = ZenTab(id: "t1", url: "https://example.com/path", title: "Page title")
        XCTAssertEqual(tab.displayTitle, "Page title")
    }

    func testDisplayTitleFallsBackToHostWhenTitleEmpty() {
        let tab = ZenTab(id: "t1", url: "https://example.com/path", title: "  ")
        XCTAssertEqual(tab.displayTitle, "example.com")
    }

    func testDisplayTitleIgnoresWhitespaceOnlyStaticLabel() {
        let tab = ZenTab(
            id: "t1",
            url: "https://example.com/path",
            title: "Page title",
            staticLabel: "   "
        )
        XCTAssertEqual(tab.displayTitle, "Page title")
    }

    func testMakeTabCopiesStaticLabel() {
        let record = ZenTabRecord(
            tabId: "t1",
            url: "https://example.com",
            title: "Page",
            staticLabel: "Work"
        )
        XCTAssertEqual(SpacesSyncService.makeTab(record)?.staticLabel, "Work")
        XCTAssertEqual(SpacesSyncService.makeTab(record)?.displayTitle, "Work")
    }

    func testSnapshotRoundTripsStaticLabelAndMissingKey() throws {
        let tab = ZenTab(id: "t1", url: "https://a.example", title: "A", staticLabel: "Work")
        let decoded = try JSONDecoder().decode(ZenTab.self, from: try JSONEncoder().encode(tab))
        XCTAssertEqual(decoded.staticLabel, "Work")

        let legacy = """
        {"id":"t1","url":"https://a.example","title":"A"}
        """.data(using: .utf8)!
        let fromLegacy = try JSONDecoder().decode(ZenTab.self, from: legacy)
        XCTAssertNil(fromLegacy.staticLabel)
        XCTAssertEqual(fromLegacy.displayTitle, "A")
    }

    func testMakeTabDropsWhitespaceOnlyStaticLabel() {
        let record = ZenTabRecord(
            tabId: "t1",
            url: "https://example.com",
            title: "Page",
            staticLabel: "  "
        )
        XCTAssertNil(SpacesSyncService.makeTab(record)?.staticLabel)
        XCTAssertEqual(SpacesSyncService.makeTab(record)?.displayTitle, "Page")
    }

    func testCacheRenameUpdatesPinnedFolderSplitAndEssentials() {
        let tab = ZenTab(id: "t1", url: "https://a.example", title: "A")
        let nested = ZenTab(id: "t2", url: "https://b.example", title: "B")
        let member = ZenTab(id: "t3", url: "https://c.example", title: "C")
        let snapshot = ZenSnapshot(
            spaces: [
                ZenSpace(
                    id: "s1",
                    name: "Space",
                    pinned: [
                        .tab(tab),
                        .folder(ZenFolder(
                            id: "f1",
                            name: "Folder",
                            tabs: [nested]
                        )),
                        .split(ZenSplit(id: "split-1", tabs: [member])),
                    ]
                ),
            ],
            essentials: ["default": [tab]],
            fetchedAt: Date(timeIntervalSince1970: 0)
        )

        let renamed = SpacesSyncEdits.renaming(id: "t1", staticLabel: "Work", in: snapshot)
        XCTAssertEqual(pinnedTab(renamed, id: "t1")?.staticLabel, "Work")
        XCTAssertEqual(renamed.essentials["default"]?.first?.staticLabel, "Work")
        XCTAssertNil(pinnedTab(renamed, id: "t2")?.staticLabel)
        XCTAssertNil(splitMember(renamed, id: "t3")?.staticLabel)

        let nestedRenamed = SpacesSyncEdits.renaming(id: "t2", staticLabel: "Docs", in: snapshot)
        XCTAssertEqual(folderTab(nestedRenamed, id: "t2")?.staticLabel, "Docs")
    }

    func testMoveIsOfferedOnlyOnRenamablePinnedRows() {
        XCTAssertFalse(TabMove.offersMove(renames: true, isNormalTab: true))
        XCTAssertTrue(TabMove.offersMove(renames: true, isNormalTab: false))
        XCTAssertFalse(TabMove.offersMove(renames: false, isNormalTab: false))
    }

    func testPinIsOfferedOnlyOnNormalTabs() {
        XCTAssertTrue(TabPin.offersPin(isNormalTab: true))
        XCTAssertFalse(TabPin.offersPin(isNormalTab: false))
    }

    func testMoveDestinationsMarkCurrentPlace() {
        let folders = [
            PinDestinationModel.FolderEntry(folderId: "outer", name: "Outer", depth: 0),
            PinDestinationModel.FolderEntry(folderId: "inner", name: "Inner", depth: 1),
        ]
        let atRoot = TabMove.destinations(spaceName: "Space", folders: folders, currentFolderId: nil)
        XCTAssertEqual(atRoot.map(\.folderId), [nil, "outer", "inner"])
        XCTAssertTrue(atRoot[0].isCurrent)
        XCTAssertEqual(atRoot[2].depth, 1)
        XCTAssertFalse(atRoot[2].isCurrent)
        XCTAssertFalse(TabMove.shouldWrite(currentFolderId: nil, selectedFolderId: ""))
        XCTAssertFalse(TabMove.shouldWrite(currentFolderId: nil, selectedFolderId: nil))
        XCTAssertTrue(TabMove.shouldWrite(currentFolderId: nil, selectedFolderId: "outer"))

        let nested = TabMove.destinations(spaceName: "Space", folders: folders, currentFolderId: "inner")
        XCTAssertTrue(nested[2].isCurrent)
        XCTAssertFalse(nested[0].isCurrent)
        XCTAssertFalse(TabMove.shouldWrite(currentFolderId: "inner", selectedFolderId: "inner"))
        XCTAssertTrue(TabMove.shouldWrite(currentFolderId: "inner", selectedFolderId: nil))
    }

    func testCacheMovePlacesPinnedTabInNestedFolderAndBack() {
        let tab = ZenTab(id: "t1", url: "https://a.example", title: "Page", staticLabel: "Keep")
        let inner = ZenFolder(id: "inner", name: "Inner")
        let outer = ZenFolder(id: "outer", name: "Outer", subfolders: [inner])
        let snapshot = ZenSnapshot(
            spaces: [ZenSpace(id: "s1", name: "Space", pinned: [.tab(tab), .folder(outer)])],
            fetchedAt: Date(timeIntervalSince1970: 0)
        )

        let moved = SpacesSyncEdits.moving(id: "t1", folderId: "inner", in: "s1", snapshot: snapshot)
        XCTAssertNil(pinnedTab(moved, id: "t1"))
        XCTAssertEqual(nestedTab(moved, folderId: "inner", tabId: "t1")?.staticLabel, "Keep")
        XCTAssertNotEqual(moved.fetchedAt, snapshot.fetchedAt)

        let rooted = SpacesSyncEdits.moving(id: "t1", folderId: nil, in: "s1", snapshot: moved)
        XCTAssertEqual(pinnedTab(rooted, id: "t1")?.staticLabel, "Keep")
        XCTAssertNil(nestedTab(rooted, folderId: "inner", tabId: "t1"))
    }

    func testCacheMoveLeavesNormalTabAndSamePlace() {
        let normal = ZenTab(id: "n1", url: "https://n.example", title: "Normal")
        let pinned = ZenTab(id: "t1", url: "https://a.example", title: "Page", staticLabel: "Keep")
        let snapshot = ZenSnapshot(
            spaces: [
                ZenSpace(
                    id: "s1",
                    name: "Space",
                    pinned: [.folder(ZenFolder(id: "f1", name: "Folder", tabs: [pinned]))],
                    tabs: [.tab(normal)]
                ),
            ],
            fetchedAt: Date(timeIntervalSince1970: 0)
        )

        let normalMoved = SpacesSyncEdits.moving(id: "n1", folderId: "f1", in: "s1", snapshot: snapshot)
        XCTAssertEqual(normalMoved.fetchedAt, snapshot.fetchedAt)
        XCTAssertEqual(normalMoved.spaces.first?.tabs.first?.id, "n1")
        XCTAssertNil(folderTab(normalMoved, id: "n1"))

        let same = SpacesSyncEdits.moving(id: "t1", folderId: "f1", in: "s1", snapshot: snapshot)
        XCTAssertEqual(same.fetchedAt, snapshot.fetchedAt)
        XCTAssertEqual(folderTab(same, id: "t1")?.staticLabel, "Keep")
    }

    func testCachePinMovesNormalTabToPinnedRootKeepingFields() {
        let normal = ZenTab(id: "n1", url: "https://n.example", title: "Normal", staticLabel: "Keep")
        let pinned = ZenTab(id: "t1", url: "https://a.example", title: "Pinned")
        let snapshot = ZenSnapshot(
            spaces: [
                ZenSpace(id: "s1", name: "Space", pinned: [.tab(pinned)], tabs: [.tab(normal)]),
            ],
            fetchedAt: Date(timeIntervalSince1970: 0)
        )

        let pinnedSnapshot = SpacesSyncEdits.pinning(id: "n1", in: "s1", snapshot: snapshot)
        XCTAssertEqual(pinnedSnapshot.spaces.first?.tabs.map(\.id), [])
        XCTAssertEqual(pinnedSnapshot.spaces.first?.pinned.map(\.id), ["t1", "n1"])
        XCTAssertEqual(pinnedTab(pinnedSnapshot, id: "n1")?.staticLabel, "Keep")
        XCTAssertEqual(pinnedTab(pinnedSnapshot, id: "n1")?.url, "https://n.example")
        XCTAssertNotEqual(pinnedSnapshot.fetchedAt, snapshot.fetchedAt)

        // A tab that is not in the normal bucket (already pinned) is untouched.
        let same = SpacesSyncEdits.pinning(id: "t1", in: "s1", snapshot: snapshot)
        XCTAssertEqual(same.fetchedAt, snapshot.fetchedAt)
        XCTAssertEqual(same.spaces.first?.tabs.map(\.id), ["n1"])
    }

    private func pinnedTab(_ snapshot: ZenSnapshot, id: String) -> ZenTab? {
        snapshot.spaces.first?.pinned.compactMap { item -> ZenTab? in
            if case .tab(let tab) = item, tab.id == id { return tab }
            return nil
        }.first
    }

    private func folderTab(_ snapshot: ZenSnapshot, id: String) -> ZenTab? {
        snapshot.spaces.first?.pinned.compactMap { item -> ZenTab? in
            if case .folder(let folder) = item {
                return folder.tabs.first { $0.id == id }
            }
            return nil
        }.first
    }

    private func nestedTab(_ snapshot: ZenSnapshot, folderId: String, tabId: String) -> ZenTab? {
        func search(_ folders: [ZenFolder]) -> ZenTab? {
            for folder in folders {
                if folder.id == folderId { return folder.tabs.first { $0.id == tabId } }
                if let found = search(folder.subfolders ?? []) { return found }
            }
            return nil
        }
        let folders = snapshot.spaces.first?.pinned.compactMap { item -> ZenFolder? in
            if case .folder(let folder) = item { return folder }
            return nil
        } ?? []
        return search(folders)
    }

    private func splitMember(_ snapshot: ZenSnapshot, id: String) -> ZenTab? {
        snapshot.spaces.first?.pinned.compactMap { item -> ZenTab? in
            if case .split(let split) = item {
                return split.tabs.first { $0.id == id }
            }
            return nil
        }.first
    }
}
