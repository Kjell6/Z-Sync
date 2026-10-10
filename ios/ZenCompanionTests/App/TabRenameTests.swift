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

    private func splitMember(_ snapshot: ZenSnapshot, id: String) -> ZenTab? {
        snapshot.spaces.first?.pinned.compactMap { item -> ZenTab? in
            if case .split(let split) = item {
                return split.tabs.first { $0.id == id }
            }
            return nil
        }.first
    }
}
