import XCTest

@testable import ZenCompanion

@MainActor
final class OpenPagesTests: XCTestCase {
    private let a = URL(string: "https://a.example/")!
    private let b = URL(string: "https://b.example/")!
    private let c = URL(string: "https://c.example/")!

    private func tempDirectory() -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: url) }
        return url
    }

    private func store(
        directory: URL? = nil,
        preferences: FakePreferences = FakePreferences(),
        now: @escaping () -> Date = Date.init
    ) -> OpenPagesStore {
        OpenPagesStore(
            directory: directory ?? tempDirectory(),
            preferences: preferences,
            now: now,
            makeModel: { MiniBrowserModel(initialURL: $0, preferences: preferences) }
        )
    }

    private func choosing() -> FakePreferences {
        let preferences = FakePreferences()
        preferences.setBool(true, PreferenceKeys.keepOnlyChosenPages, scope: .standard)
        return preferences
    }

    // MARK: - Like any browser

    func testKeptPageLosesItsWayBackWhenItsOpenerGoesWithTheBrowser() {
        let pages = store(preferences: choosing())
        let opener = pages.open(url: a, title: "A")
        let linked = pages.open(url: b, title: "B")
        linked.openerID = opener.id
        pages.toggleKeep(linked.id)

        pages.endBrowserSession()

        XCTAssertEqual(pages.pages.map(\.id), [linked.id])
        XCTAssertNil(linked.openerID)
    }

    func testLoadedPageSurvivesTheAppEndingWithoutWarning() {
        let directory = tempDirectory()
        let pages = store(directory: directory)
        let loaded = pages.open(url: a, title: "A")

        pages.pageDidFinishLoading(loaded.model)

        XCTAssertEqual(store(directory: directory).pages.map(\.url), [a])
    }

    func testLoadedPageIsNotSavedWhenOnlyChosenPagesStay() {
        let directory = tempDirectory()
        let pages = store(directory: directory, preferences: choosing())
        let loaded = pages.open(url: a, title: "A")

        pages.pageDidFinishLoading(loaded.model)

        XCTAssertTrue(store(directory: directory).pages.isEmpty)
    }

    func testPagesStayWhenTheBrowserCloses() {
        let directory = tempDirectory()
        let pages = store(directory: directory)
        let page = pages.open(url: a, title: "A")

        pages.endBrowserSession()

        XCTAssertEqual(pages.pages.map(\.id), [page.id])
        XCTAssertNil(pages.activeID)
        XCTAssertNil(page.model.webView, "A closed browser holds no web views")
        XCTAssertEqual(store(directory: directory).pages.map(\.url), [a])
    }

    func testAnUntouchedTabFromASpaceDoesNotStay() {
        let pages = store()
        pages.open(url: a, openedFromSpace: true)
        pages.endBrowserSession()
        XCTAssertTrue(pages.pages.isEmpty)
    }

    func testAnEmptyNewPageDoesNotStay() {
        let pages = store()
        pages.open(url: nil)
        pages.endBrowserSession()
        XCTAssertTrue(pages.pages.isEmpty)
    }

    func testPagesCloseAfterAMonthByDefault() {
        var clock = Date(timeIntervalSince1970: 1_000_000)
        let pages = store(now: { clock })
        pages.open(url: a)
        pages.endBrowserSession()

        clock += 29 * 86_400
        pages.closeExpired()
        XCTAssertEqual(pages.pages.count, 1)

        clock += 86_400
        pages.closeExpired()
        XCTAssertTrue(pages.pages.isEmpty)
    }

    func testClosingTheBrowserOnlyRefreshesThePageOnScreen() {
        var clock = Date(timeIntervalSince1970: 1_000_000)
        let pages = store(now: { clock })
        let idle = pages.open(url: a)
        let used = pages.open(url: b)

        clock += 20 * 86_400
        pages.endBrowserSession()
        XCTAssertEqual(idle.lastUsedAt, Date(timeIntervalSince1970: 1_000_000))
        XCTAssertEqual(used.lastUsedAt, clock)

        clock += 10 * 86_400
        pages.closeExpired()
        XCTAssertEqual(pages.pages.map(\.id), [used.id], "An idle page runs out even if the browser is in use")
    }

    func testAPageThatWasPinnedStaysOnceTheUserBrowsesOn() {
        let pages = store()
        let page = pages.open(url: a)
        pages.endBrowserSession()
        pages.activate(page.id)

        pages.pageWasPinned(page.model, url: a)
        XCTAssertFalse(page.isKept, "Pinned, it belongs to the space")

        page.model.updateCurrentURL(b)
        pages.endBrowserSession()
        XCTAssertEqual(pages.pages.map(\.id), [page.id])
    }

    func testTheOverviewNamesTheChosenPeriod() {
        XCTAssertEqual(store().lifetimeHint, String(localized: "kept.hint.month"))

        let never = FakePreferences()
        never.setString(KeptPageLifetime.never.rawValue, PreferenceKeys.keptPageLifetime, scope: .standard)
        XCTAssertEqual(store(preferences: never).lifetimeHint, String(localized: "kept.hint.never"))
    }

    // MARK: - Keep only chosen pages

    func testOnlyChosenPagesStay() {
        let pages = store(preferences: choosing())
        let kept = pages.open(url: a)
        pages.open(url: b)
        pages.toggleKeep(kept.id)

        pages.endBrowserSession()

        XCTAssertEqual(pages.pages.map(\.id), [kept.id])
    }

    func testChosenPagesSurviveARestartAndOthersDoNot() {
        let directory = tempDirectory()
        let preferences = choosing()
        let pages = store(directory: directory, preferences: preferences)
        let kept = pages.open(url: a, title: "A")
        pages.open(url: b, title: "B")
        pages.toggleKeep(kept.id)

        let reopened = store(directory: directory, preferences: preferences)

        XCTAssertEqual(reopened.pages.map(\.id), [kept.id])
        XCTAssertEqual(reopened.pages.first?.url, a)
        XCTAssertEqual(reopened.pages.first?.model.pageTitle, "A")
        XCTAssertNil(reopened.activeID)
    }

    func testTappingKeptAgainLetsThePageGo() {
        let directory = tempDirectory()
        let preferences = choosing()
        let pages = store(directory: directory, preferences: preferences)
        let page = pages.open(url: a)
        pages.toggleKeep(page.id)
        pages.toggleKeep(page.id)

        XCTAssertFalse(page.isKept)
        XCTAssertTrue(store(directory: directory, preferences: preferences).pages.isEmpty)
    }

    func testANewPageCannotBeKept() {
        let pages = store(preferences: choosing())
        let page = pages.open(url: nil)
        XCTAssertFalse(pages.canKeep(page))
        pages.toggleKeep(page.id)
        XCTAssertFalse(page.isKept)
    }

    func testLeavePolicy() {
        func action(
            isKept: Bool = false,
            keepEvery: Bool = true,
            url: URL? = URL(string: "https://a.example/"),
            failed: Bool = false,
            pinned: Bool = false,
            released: Bool = false,
            fromSpace: Bool = false,
            browsedAway: Bool = false
        ) -> LeaveAction {
            KeptPagesPolicy.onLeave(
                isKept: isKept, keepEveryPage: keepEvery, url: url, failed: failed, pinnedHere: pinned,
                releasedHere: released, openedFromSpace: fromSpace, browsedAway: browsedAway
            )
        }
        XCTAssertEqual(action(isKept: true, keepEvery: false), .update)
        XCTAssertEqual(action(keepEvery: false), .discard)
        XCTAssertEqual(action(), .keep)
        XCTAssertEqual(action(url: URL(string: "about:blank")), .discard)
        XCTAssertEqual(action(url: nil), .discard)
        XCTAssertEqual(action(failed: true), .discard)
        XCTAssertEqual(action(pinned: true), .discard)
        XCTAssertEqual(action(released: true), .discard)
        XCTAssertEqual(action(fromSpace: true), .discard)
        XCTAssertEqual(action(fromSpace: true, browsedAway: true), .keep)
    }

    // MARK: - Switching and closing

    func testNeighboursFollowTheListOrder() {
        let pages = store()
        let first = pages.open(url: a)
        let second = pages.open(url: b)
        let third = pages.open(url: c)

        pages.activate(second.id)

        XCTAssertEqual(pages.neighbours.previous?.id, first.id)
        XCTAssertEqual(pages.neighbours.next?.id, third.id)
    }

    func testAMovedPageKeepsItsPlaceAcrossARestart() {
        let directory = tempDirectory()
        let pages = store(directory: directory)
        let first = pages.open(url: a)
        let second = pages.open(url: b)
        let third = pages.open(url: c)
        [first, second, third].forEach { pages.toggleKeep($0.id) }

        pages.move(first.id, onto: third.id)
        XCTAssertEqual(pages.pages.map(\.id), [second.id, third.id, first.id])

        pages.move(third.id, onto: second.id)
        XCTAssertEqual(pages.pages.map(\.id), [third.id, second.id, first.id])
        XCTAssertEqual(store(directory: directory).pages.map(\.id), [third.id, second.id, first.id])
    }

    func testClosingTheActivePageShowsItsOpenerFirst() {
        let pages = store()
        let opener = pages.open(url: a)
        let other = pages.open(url: b)
        pages.activate(opener.id)
        pages.openInNewPage(c, from: opener.model)

        XCTAssertEqual(pages.pages.map(\.url), [a, c, b], "A link's page sits right after its opener")
        XCTAssertEqual(pages.active?.openerID, opener.id)

        pages.close(pages.activeID!)

        XCTAssertEqual(pages.activeID, opener.id)
        XCTAssertEqual(pages.pages.map(\.id), [opener.id, other.id])
    }

    func testClosingTheLastPageLeavesNothingOnScreen() {
        let pages = store()
        let page = pages.open(url: a)
        XCTAssertFalse(pages.close(page.id))
        XCTAssertNil(pages.activeID)
    }

    func testOnlyTheMostRecentPagesStayLive() {
        var clock = Date(timeIntervalSince1970: 1_000)
        let pages = store(now: {
            clock += 1
            return clock
        })
        let oldest = pages.open(url: a)
        let others = (0..<OpenPagesStore.liveLimit).map { pages.open(url: URL(string: "https://\($0).example/")!) }

        XCTAssertNil(oldest.model.webView)
        XCTAssertEqual(pages.pages.filter { $0.model.webView != nil }.count, OpenPagesStore.liveLimit)
        XCTAssertNotNil(others.last?.model.webView)

        pages.activate(oldest.id)
        XCTAssertNotNil(oldest.model.webView, "A sleeping page wakes when it is shown")
    }

    func testAPageKeepsItsAddressWhileAsleep() {
        let model = MiniBrowserModel(initialURL: a, preferences: FakePreferences())
        model.restore(title: "A")
        model.wake()
        model.sleep()
        XCTAssertNil(model.webView)
        XCTAssertEqual(model.pageURL, a)
        XCTAssertEqual(model.pageTitle, "A")
    }

    func testPinningAKeptPageTakesItOutOfTheList() {
        let directory = tempDirectory()
        let pages = store(directory: directory)
        let page = pages.open(url: a)
        pages.toggleKeep(page.id)

        pages.pageWasPinned(page.model, url: a)

        XCTAssertFalse(page.isKept)
        XCTAssertTrue(store(directory: directory).pages.isEmpty)
    }

    func testAPinOfAnEarlierAddressLeavesAMovedPageKept() {
        let pages = store()
        let page = pages.open(url: a)
        pages.toggleKeep(page.id)
        page.model.updateCurrentURL(b)

        pages.pageWasPinned(page.model, url: a)

        XCTAssertTrue(page.isKept)
    }

    // MARK: - Closing by themselves

    func testKeptPagesCloseAfterTheChosenPeriod() {
        let preferences = FakePreferences()
        preferences.setString(KeptPageLifetime.day.rawValue, PreferenceKeys.keptPageLifetime, scope: .standard)
        var clock = Date(timeIntervalSince1970: 1_000_000)
        let directory = tempDirectory()
        let pages = store(directory: directory, preferences: preferences, now: { clock })
        let old = pages.open(url: a)
        pages.toggleKeep(old.id)
        pages.endBrowserSession()

        clock += 86_400 - 1
        pages.closeExpired()
        XCTAssertEqual(pages.pages.count, 1)

        clock += 1
        pages.closeExpired()
        XCTAssertTrue(pages.pages.isEmpty)
        XCTAssertTrue(store(directory: directory, preferences: preferences, now: { clock }).pages.isEmpty)
    }

    func testThePageOnScreenNeverExpires() {
        let preferences = FakePreferences()
        preferences.setString(KeptPageLifetime.day.rawValue, PreferenceKeys.keptPageLifetime, scope: .standard)
        var clock = Date(timeIntervalSince1970: 1_000_000)
        let pages = store(preferences: preferences, now: { clock })
        let page = pages.open(url: a)
        pages.toggleKeep(page.id)

        clock += 10 * 86_400
        pages.closeExpired()

        XCTAssertEqual(pages.activeID, page.id)
    }

    func testPagesStayForeverWhenAskedTo() {
        let preferences = FakePreferences()
        preferences.setString(KeptPageLifetime.never.rawValue, PreferenceKeys.keptPageLifetime, scope: .standard)
        var clock = Date(timeIntervalSince1970: 1_000_000)
        let pages = store(preferences: preferences, now: { clock })
        let page = pages.open(url: a)
        pages.endBrowserSession()

        clock += 365 * 86_400
        pages.closeExpired()

        XCTAssertEqual(pages.pages.map(\.id), [page.id])
    }

    func testCloseAllLeavesNothingOnDisk() {
        let directory = tempDirectory()
        let pages = store(directory: directory)
        let page = pages.open(url: a)
        pages.toggleKeep(page.id)

        pages.closeAll()

        XCTAssertTrue(pages.pages.isEmpty)
        XCTAssertFalse(FileManager.default.fileExists(atPath: directory.path))
    }
}
