import XCTest

@testable import ZenCompanion

@MainActor
final class BrowserHistoryTests: XCTestCase {
    private func makeStore(file: URL? = nil) -> BrowserHistoryStore {
        let url = file ?? makeFile()
        return BrowserHistoryStore(fileURL: url, saveDelay: .zero)
    }

    private func makeFile() -> URL {
        let file = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString)
            .appendingPathComponent("history.json")
        addTeardownBlock { try? FileManager.default.removeItem(at: file.deletingLastPathComponent()) }
        return file
    }

    private func url(_ text: String) -> URL { URL(string: text)! }

    func testOnlyWebPagesAreKept() {
        let store = makeStore()
        store.record(url("about:blank"), title: "")
        store.record(url("blob:https://example.com/1"), title: "")
        store.record(nil, title: "")
        XCTAssertTrue(store.visits.isEmpty)
    }

    func testAJumpInsideAPageIsThePage() {
        let store = makeStore()
        store.record(url("https://example.com/a#one"), title: "A")
        store.record(url("https://example.com/a#two"), title: "A")
        XCTAssertEqual(store.visits.map(\.url), ["https://example.com/a"])
        XCTAssertEqual(store.visits.first?.visits, 2)
    }

    func testAReturnMovesThePageToTheTopAndKeepsItsTitle() {
        let store = makeStore()
        store.record(url("https://a.example"), title: "A")
        store.record(url("https://b.example"), title: "B")
        store.record(url("https://a.example"), title: "")
        XCTAssertEqual(store.visits.map(\.url), ["https://a.example", "https://b.example"])
        XCTAssertEqual(store.visits.first?.title, "A")
    }

    func testTheOldestPagesGoFirst() {
        let store = makeStore()
        for index in 0...BrowserHistoryStore.maxEntries {
            store.record(url("https://example.com/\(index)"), title: "")
        }
        XCTAssertEqual(store.visits.count, BrowserHistoryStore.maxEntries)
        XCTAssertFalse(store.visits.contains { $0.url == "https://example.com/0" })
    }

    func testSearchNeedsEveryWordInTitleOrAddress() {
        let store = makeStore()
        store.record(url("https://news.example/rust"), title: "Hello Rust")
        store.record(url("https://news.example/go"), title: "Hello Go")
        XCTAssertEqual(store.search("hello RUST").map(\.title), ["Hello Rust"])
        XCTAssertEqual(store.search("").count, 2)
        XCTAssertTrue(store.search("python").isEmpty)
    }

    func testSuggestionsPutTheSiteNameFirstThenTheMostVisited() {
        let store = makeStore()
        store.record(url("https://other.example/github"), title: "About GitHub")
        store.record(url("https://github.com/a"), title: "A")
        store.record(url("https://github.com/b"), title: "B")
        store.record(url("https://github.com/b"), title: "B")
        XCTAssertEqual(
            store.suggestions(for: "git").map(\.url),
            ["https://github.com/b", "https://github.com/a", "https://other.example/github"]
        )
        XCTAssertEqual(store.suggestions(for: "git", limit: 1).count, 1)
    }

    func testSuggestionsLeaveOutTheOpenPageAndBlankText() {
        let store = makeStore()
        store.record(url("https://example.com/"), title: "Example")
        XCTAssertTrue(store.suggestions(for: "example", excluding: url("https://example.com/")).isEmpty)
        XCTAssertTrue(store.suggestions(for: "   ").isEmpty)
    }

    func testTheShownAddressTellsPagesOfASiteApart() {
        let visit = BrowserVisit(url: "https://www.example.com/a/b?c=1", title: "", lastVisited: .now, visits: 1)
        XCTAssertEqual(visit.displayAddress, "example.com/a/b?c=1")
        let home = BrowserVisit(url: "https://example.com/", title: "", lastVisited: .now, visits: 1)
        XCTAssertEqual(home.displayAddress, "example.com")
    }

    func testALateTitleReplacesTheOldOneButEmptyDoesNot() {
        let store = makeStore()
        store.record(url("https://a.example"), title: "Old")
        store.updateTitle("", of: "https://a.example/")
        store.updateTitle("New", of: store.visits[0].url)
        XCTAssertEqual(store.visits[0].title, "New")
        store.updateTitle("", of: store.visits[0].url)
        XCTAssertEqual(store.visits[0].title, "New")
    }

    func testAReloadIsNoNewVisitButANewAddressIs() {
        let store = makeStore()
        let model = MiniBrowserModel(initialURL: nil, history: store)
        model.updateCurrentURL(url("https://a.example/one"))
        model.recordVisit()
        model.recordVisit()
        XCTAssertEqual(store.visits.first?.visits, 1)
        model.updateCurrentURL(url("https://a.example/two"))
        XCTAssertEqual(store.visits.map(\.url), ["https://a.example/two", "https://a.example/one"])
    }

    func testRemovingOnePageLeavesTheOthers() {
        let store = makeStore()
        store.record(url("https://a.example"), title: "A")
        store.record(url("https://b.example"), title: "B")
        store.remove(store.visits[0])
        XCTAssertEqual(store.visits.map(\.title), ["A"])
    }

    func testHistorySurvivesARelaunchStaysOutOfBackupsAndGoesWithRemoveAll() throws {
        let file = makeFile()
        let store = makeStore(file: file)
        store.record(url("https://example.com"), title: "Example")
        store.saveNow()
        XCTAssertEqual(makeStore(file: file).visits.map(\.title), ["Example"])
        XCTAssertEqual(try file.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup, true)
        store.removeAll()
        XCTAssertFalse(FileManager.default.fileExists(atPath: file.path))
        XCTAssertTrue(makeStore(file: file).visits.isEmpty)
    }
}
