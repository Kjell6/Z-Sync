import UIKit
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

    private func date(_ seconds: TimeInterval) -> Date { Date(timeIntervalSince1970: seconds) }

    func testOnlyWebPagesAreKept() {
        let store = makeStore()
        store.record(url("about:blank"), title: "")
        store.record(url("blob:https://example.com/1"), title: "")
        store.record(nil, title: "")
        XCTAssertTrue(store.visits.isEmpty)
    }

    func testAnAddressNeverKeepsItsPassword() {
        let store = makeStore()
        store.record(url("https://reader:secret@example.com/private"), title: "")
        XCTAssertEqual(store.visits.map(\.url), ["https://example.com/private"])
        XCTAssertEqual(store.visits.first?.displayAddress, "example.com/private")
    }

    func testAStoredEntryNeverKeepsItsPassword() {
        let stored = [
            BrowserVisit(url: "https://reader:secret@example.com/private", title: "Private", lastVisited: date(2), visits: 1),
            BrowserVisit(url: "about:blank", title: "", lastVisited: date(3), visits: 1),
        ]
        XCTAssertEqual(
            BrowserHistoryStore.cleanedHistory(stored),
            [BrowserVisit(url: "https://example.com/private", title: "Private", lastVisited: date(2), visits: 1)]
        )
    }

    func testTwoStoredEntriesOfOnePageBecomeOne() {
        let stored = [
            BrowserVisit(url: "https://reader:secret@example.com/a", title: "Old", lastVisited: date(1), visits: 2),
            BrowserVisit(url: "https://b.example", title: "B", lastVisited: date(2), visits: 1),
            BrowserVisit(url: "https://example.com/a", title: "", lastVisited: date(5), visits: 3),
        ]
        XCTAssertEqual(
            BrowserHistoryStore.cleanedHistory(stored),
            [
                BrowserVisit(url: "https://example.com/a", title: "Old", lastVisited: date(5), visits: 5),
                BrowserVisit(url: "https://b.example", title: "B", lastVisited: date(2), visits: 1),
            ]
        )
    }

    func testACleanListNeedsNoRewrite() {
        let clean = [BrowserVisit(url: "https://example.com/a", title: "A", lastVisited: date(1), visits: 1)]
        XCTAssertNil(BrowserHistoryStore.cleanedHistory(clean))
    }

    func testAnOlderFileLosesTheLoginsItSaved() throws {
        let file = makeFile()
        try FileManager.default.createDirectory(
            at: file.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )
        let stored = [
            BrowserVisit(url: "https://reader:secret@example.com/private", title: "Private", lastVisited: date(1), visits: 1)
        ]
        try JSONEncoder().encode(stored).write(to: file)
        let store = makeStore(file: file)
        XCTAssertEqual(store.visits.map(\.url), ["https://example.com/private"])
        XCTAssertFalse(try String(contentsOf: file, encoding: .utf8).contains("secret"))
    }

    func testACleanFileIsNotWrittenAgain() throws {
        let file = makeFile()
        try FileManager.default.createDirectory(
            at: file.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )
        let clean = [BrowserVisit(url: "https://example.com/a", title: "A", lastVisited: date(1), visits: 1)]
        try JSONEncoder().encode(clean).write(to: file)
        let written = try FileManager.default.attributesOfItem(atPath: file.path)[.modificationDate] as? Date
        let store = makeStore(file: file)
        XCTAssertEqual(store.visits.map(\.title), ["A"])
        let left = try FileManager.default.attributesOfItem(atPath: file.path)[.modificationDate] as? Date
        XCTAssertEqual(left, written)
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
            store.suggestions(for: "git", limit: 3).map(\.url),
            ["https://github.com/b", "https://github.com/a", "https://other.example/github"]
        )
        XCTAssertEqual(store.suggestions(for: "git").count, 2)
    }

    func testOnlyARealMatchIsSuggested() {
        let store = makeStore()
        store.record(url("https://github.com/login?next=rust"), title: "Sign in to GitHub")
        XCTAssertEqual(store.suggestions(for: "sign").count, 1)
        XCTAssertEqual(store.suggestions(for: "https://www.github").count, 1)
        XCTAssertEqual(store.suggestions(for: "log").count, 1)
        XCTAssertTrue(store.suggestions(for: "hub").isEmpty, "Not the middle of a word")
        XCTAssertTrue(store.suggestions(for: "rust").isEmpty, "Not the query string")
        XCTAssertTrue(store.suggestions(for: "g").isEmpty, "One letter says too little")
        XCTAssertTrue(store.suggestions(for: "sign python").isEmpty)
    }

    func testTheListNeverPassesEightRows() {
        let eight = (1...8).map(String.init)
        XCTAssertEqual(SearchSuggestions.fitting(eight, beside: 0).count, 8)
        XCTAssertEqual(SearchSuggestions.fitting(eight, beside: 2), Array(eight.prefix(6)))
        XCTAssertEqual(SearchSuggestions.fitting(["a"], beside: 2), ["a"])
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
        let model = MiniBrowserModel(initialURL: nil, preferences: FakePreferences(), history: store)
        model.updateCurrentURL(url("https://a.example/one"))
        model.recordVisit()
        model.recordVisit()
        XCTAssertEqual(store.visits.first?.visits, 1)
        model.updateCurrentURL(url("https://a.example/two"))
        XCTAssertEqual(store.visits.map(\.url), ["https://a.example/two", "https://a.example/one"])
    }

    func testANewPagesFirstLoadIsAVisitButWakingItIsNot() {
        let store = makeStore()
        let address = url("https://a.example/one")
        let model = MiniBrowserModel(initialURL: address, preferences: FakePreferences(), history: store)
        model.wake()
        model.updateCurrentURL(address)
        model.recordVisit()
        XCTAssertEqual(store.visits.first?.visits, 1)
        model.sleep()
        model.wake()
        model.updateCurrentURL(address)
        model.recordVisit()
        XCTAssertEqual(store.visits.first?.visits, 1)
    }

    func testAPageKeptFromAnEarlierRunIsNoNewVisit() {
        let store = makeStore()
        let address = url("https://a.example/one")
        let model = MiniBrowserModel(initialURL: address, preferences: FakePreferences(), history: store)
        model.markAddressAsVisited()
        model.wake()
        model.updateCurrentURL(address)
        model.recordVisit()
        XCTAssertTrue(store.visits.isEmpty)
    }

    func testGoingToTheBackgroundSavesWhatIsWaitingButNotARemovedList() {
        let file = makeFile()
        let store = BrowserHistoryStore(fileURL: file, saveDelay: .seconds(60))
        store.record(url("https://a.example"), title: "A")
        NotificationCenter.default.post(name: UIApplication.didEnterBackgroundNotification, object: nil)
        XCTAssertEqual(makeStore(file: file).visits.map(\.title), ["A"])
        store.removeAll()
        NotificationCenter.default.post(name: UIApplication.didEnterBackgroundNotification, object: nil)
        XCTAssertFalse(FileManager.default.fileExists(atPath: file.path))
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
