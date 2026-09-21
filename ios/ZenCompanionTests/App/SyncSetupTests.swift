import XCTest

@testable import ZenCompanion

/// Sidebar-sync detection: when to offer the setup hint.
final class SyncSetupTests: XCTestCase {
    private func space(pinned: [ZenItem] = []) -> ZenSpace {
        ZenSpace(id: "space-1", name: "Space", pinned: pinned)
    }

    private func tab(id: String = "tab-1") -> ZenTab {
        ZenTab(id: id, url: "https://example.com", title: "Example")
    }

    func testEmptySnapshotHasNothingSynced() {
        XCTAssertFalse(ZenSnapshot.empty.hasSyncedTabs)
    }

    func testSpacesWithoutPinsOrEssentialsHaveNothingSynced() {
        let snapshot = ZenSnapshot(spaces: [space()], fetchedAt: .distantPast)
        XCTAssertFalse(snapshot.hasSyncedTabs)
    }

    func testPinnedTabCountsAsSynced() {
        let snapshot = ZenSnapshot(
            spaces: [space(pinned: [.tab(tab())])],
            fetchedAt: .distantPast
        )
        XCTAssertTrue(snapshot.hasSyncedTabs)
    }

    func testEssentialsCountAsSynced() {
        let snapshot = ZenSnapshot(
            spaces: [space()],
            essentials: ["default": [tab()]],
            fetchedAt: .distantPast
        )
        XCTAssertTrue(snapshot.hasSyncedTabs)
    }

    func testNormalTabsCountAsSynced() {
        let snapshot = ZenSnapshot(
            spaces: [ZenSpace(id: "space-1", name: "Space", tabs: [.tab(tab())])],
            fetchedAt: .distantPast
        )
        XCTAssertTrue(snapshot.hasSyncedTabs)
    }

    func testEmptyEssentialBucketsDoNotCount() {
        let snapshot = ZenSnapshot(
            spaces: [space()],
            essentials: ["default": []],
            fetchedAt: .distantPast
        )
        XCTAssertFalse(snapshot.hasSyncedTabs)
    }
}
