import XCTest

@testable import ZenCompanion

@MainActor
final class ActivityModelTests: XCTestCase {
    private func entry(_ title: String, url: String) -> ZenCompanion.SyncedActivityService.HistoryEntry {
        ZenCompanion.SyncedActivityService.HistoryEntry(title: title, url: url, lastVisit: nil)
    }

    func testLoadPopulatesHistoryAndClearsLoading() async {
        let loader = FakeActivityLoader()
        loader.result = .success(ZenCompanion.SyncedActivityService.Activity(history: [
            entry("Example", url: "https://example.com"),
        ]))
        let model = ActivityModel(loader: loader)

        await model.load()

        XCTAssertFalse(model.loading)
        XCTAssertNil(model.loadError)
        XCTAssertEqual(model.filteredHistory.count, 1)
        XCTAssertEqual(model.filteredHistory.first?.url, "https://example.com")
    }

    func testLoadFailureSetsErrorAndLeavesHistoryEmpty() async {
        let loader = FakeActivityLoader()
        loader.result = .failure(URLError(.notConnectedToInternet))
        let model = ActivityModel(loader: loader)

        await model.load()

        XCTAssertFalse(model.loading)
        XCTAssertNotNil(model.loadError)
        XCTAssertTrue(model.filteredHistory.isEmpty)
    }

    func testFilterMatchesTitleOrURLCaseInsensitively() async {
        let loader = FakeActivityLoader()
        loader.result = .success(ZenCompanion.SyncedActivityService.Activity(history: [
            entry("Mozilla Blog", url: "https://blog.mozilla.org/post"),
            entry("Example", url: "https://example.com"),
            entry("Other", url: "https://mozilla.com/docs"),
        ]))
        let model = ActivityModel(loader: loader)
        await model.load()

        model.searchText = "blog"
        model.updateFiltered()
        XCTAssertEqual(model.filteredHistory.map(\.url), ["https://blog.mozilla.org/post"])

        model.searchText = "MOZILLA"
        model.updateFiltered()
        XCTAssertEqual(
            model.filteredHistory.map(\.url),
            ["https://blog.mozilla.org/post", "https://mozilla.com/docs"]
        )

        model.searchText = "   "
        model.updateFiltered()
        XCTAssertEqual(model.filteredHistory.count, 3)
    }
}

@MainActor
private final class FakeActivityLoader: ActivityLoading {
    var result: Result<ZenCompanion.SyncedActivityService.Activity, Error> = .success(
        ZenCompanion.SyncedActivityService.Activity(history: [])
    )

    func load() async throws -> ZenCompanion.SyncedActivityService.Activity {
        try result.get()
    }
}
