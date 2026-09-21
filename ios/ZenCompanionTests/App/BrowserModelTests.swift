import XCTest

@testable import ZenCompanion

@MainActor
final class BrowserModelTests: XCTestCase {
    private func space(_ id: String) -> ZenCompanion.ZenSpace {
        ZenCompanion.ZenSpace(id: id, name: "Space \(id)")
    }

    private func snapshot(_ ids: [String]) -> ZenCompanion.ZenSnapshot {
        ZenCompanion.ZenSnapshot(spaces: ids.map(space), fetchedAt: Date(timeIntervalSince1970: 0))
    }

    private func account(isDemo: Bool = false) -> ZenCompanion.AccountSnapshot {
        ZenCompanion.AccountSnapshot(email: "a@b.c", uid: "u1", sessionTokenHex: "aa", kBHex: "bb", isDemo: isDemo)
    }

    private func makeModel(
        _ repository: FakeSpacesRepository,
        preferences: FakePreferences = FakePreferences()
    ) -> BrowserModel {
        BrowserModel(
            account: account(),
            repository: repository,
            preferences: preferences,
            favicons: FakeFaviconPrefetcher()
        )
    }

    // MARK: - Last-space restore

    func testInitialLoadRestoresLastSpaceById() async {
        let prefs = FakePreferences()
        prefs.setString("b", PreferenceKeys.lastSpaceId, scope: .appGroup)
        let repo = FakeSpacesRepository()
        repo.cached = snapshot(["a", "b", "c"])
        repo.refreshResult = .success(snapshot(["a", "b", "c"]))

        let model = makeModel(repo, preferences: prefs)
        await model.initialLoad()

        XCTAssertEqual(model.selectedIndex, 1)
        XCTAssertEqual(model.snapshot.spaces.map(\.id), ["a", "b", "c"])
    }

    func testInitialLoadClampsWhenPersistedIdVanishes() async {
        let prefs = FakePreferences()
        prefs.setString("gone", PreferenceKeys.lastSpaceId, scope: .appGroup)
        let repo = FakeSpacesRepository()
        repo.cached = snapshot(["a", "b"])
        repo.refreshResult = .success(snapshot(["a", "b"]))

        let model = makeModel(repo, preferences: prefs)
        model.selectedIndex = 9
        await model.initialLoad()

        XCTAssertEqual(model.selectedIndex, 1, "a vanished persisted id must clamp numerically")
    }

    func testSelectionPersistsToAppGroup() {
        let prefs = FakePreferences()
        let repo = FakeSpacesRepository()
        repo.cached = snapshot(["a", "b", "c"])
        let model = makeModel(repo, preferences: prefs)

        model.snapshot = snapshot(["a", "b", "c"])
        model.selectedIndex = 2
        model.persistSelectedSpace()

        XCTAssertEqual(prefs.string(PreferenceKeys.lastSpaceId, scope: .appGroup), "c")
        XCTAssertNil(prefs.string(PreferenceKeys.lastSpaceId, scope: .standard))
    }

    // MARK: - Latch rules

    func testSelectionWhileSettledFollowsImmediately() {
        let model = makeModel(FakeSpacesRepository())
        model.snapshot = snapshot(["a", "b", "c"])
        model.selectedIndex = 2

        model.selectedIndexChangedFromPager()

        XCTAssertEqual(model.displayedIndex, 2)
        XCTAssertFalse(model.swipeLatched)
    }

    func testGestureAdoptsOnlyFirstFlipUntilSettle() {
        let model = makeModel(FakeSpacesRepository())
        model.snapshot = snapshot(["a", "b", "c"])

        model.pagerSettledChanged(false)
        XCTAssertFalse(model.swipeLatched)

        model.selectedIndex = 1
        model.selectedIndexChangedFromPager()
        XCTAssertEqual(model.displayedIndex, 1)
        XCTAssertTrue(model.swipeLatched)

        model.selectedIndex = 2
        model.selectedIndexChangedFromPager()
        XCTAssertEqual(model.displayedIndex, 1, "further flips within one gesture must be ignored")

        model.pagerSettledChanged(true)
        XCTAssertEqual(model.displayedIndex, 2, "settle must sync the final page exactly once")
        XCTAssertFalse(model.swipeLatched)
    }

    func testNewGestureSegmentRearmsLatch() {
        let model = makeModel(FakeSpacesRepository())
        model.snapshot = snapshot(["a", "b", "c"])

        model.pagerSettledChanged(false)
        model.selectedIndex = 1
        model.selectedIndexChangedFromPager()
        XCTAssertTrue(model.swipeLatched)

        // Lift into glide starts a new segment: the latch rearms.
        model.pagerSettledChanged(false)
        XCTAssertFalse(model.swipeLatched)

        model.selectedIndex = 2
        model.selectedIndexChangedFromPager()
        XCTAssertEqual(model.displayedIndex, 2)
    }

    // MARK: - Loading

    func testManualRefreshWaitsForInFlightReload() async {
        let repo = FakeSpacesRepository()
        repo.cached = snapshot(["a"])
        repo.refreshResult = .success(snapshot(["a"]))
        repo.blockRefresh = true
        let model = makeModel(repo)

        let inFlight = Task { await model.reload() }
        while !repo.didBlockRefresh { await Task.yield() }
        XCTAssertEqual(repo.refreshCount, 1)

        let manual = Task { await model.manualRefresh() }
        await Task.yield()
        await Task.yield()
        XCTAssertEqual(repo.refreshCount, 1, "manual refresh must wait instead of doing nothing")

        repo.blockRefresh = false
        repo.releaseRefresh()
        await inFlight.value
        await manual.value

        XCTAssertEqual(repo.refreshCount, 2, "manual refresh must hit the network again after the wait")
    }

    func testZeroSpacesClearsError() async {
        let repo = FakeSpacesRepository()
        repo.refreshResult = .failure(URLError(.timedOut))
        let model = makeModel(repo)

        await model.reload()
        XCTAssertNotNil(model.loadError)

        repo.refreshResult = .success(snapshot([]))
        await model.reload()

        XCTAssertNil(model.loadError)
        XCTAssertTrue(model.zeroSpaces)
        XCTAssertTrue(model.snapshot.spaces.isEmpty)
    }

    // MARK: - In-app review

    private func snapshotWithPinnedTab() -> ZenCompanion.ZenSnapshot {
        let tab = ZenCompanion.ZenTab(id: "tab-1", url: "https://example.com", title: "Example")
        let space = ZenCompanion.ZenSpace(id: "a", name: "Space a", pinned: [.tab(tab)])
        return ZenCompanion.ZenSnapshot(spaces: [space], fetchedAt: Date(timeIntervalSince1970: 0))
    }

    func testReviewPromptFiresOnceWhenSyncedTabsAreVisible() {
        let prefs = FakePreferences()
        let model = makeModel(FakeSpacesRepository(), preferences: prefs)
        model.snapshot = snapshotWithPinnedTab()

        model.requestReviewIfEligible()
        XCTAssertTrue(model.reviewPromptPending)
        XCTAssertTrue(prefs.bool(PreferenceKeys.didRequestReview, scope: .standard))

        model.consumeReviewPrompt()
        XCTAssertFalse(model.reviewPromptPending)

        model.requestReviewIfEligible()
        XCTAssertFalse(model.reviewPromptPending)
    }

    func testReviewPromptSkippedWithoutSyncedTabs() {
        let model = makeModel(FakeSpacesRepository())
        model.snapshot = snapshot(["a"])

        model.requestReviewIfEligible()
        XCTAssertFalse(model.reviewPromptPending)
        XCTAssertFalse(model.snapshot.hasSyncedTabs)
    }

    func testReviewPromptSkippedWhileShareTipIsShowing() {
        let model = makeModel(FakeSpacesRepository())
        model.snapshot = snapshotWithPinnedTab()
        model.showShareTip = true

        model.requestReviewIfEligible()
        XCTAssertFalse(model.reviewPromptPending)
    }

    func testReviewPromptSkippedInDemo() {
        let model = BrowserModel(
            account: account(isDemo: true),
            repository: FakeSpacesRepository(),
            preferences: FakePreferences(),
            favicons: FakeFaviconPrefetcher()
        )
        model.snapshot = snapshotWithPinnedTab()

        model.requestReviewIfEligible()
        XCTAssertFalse(model.reviewPromptPending)
    }

    func testReviewPromptAfterShareTipDismiss() {
        let model = makeModel(FakeSpacesRepository())
        model.snapshot = snapshotWithPinnedTab()
        model.showShareTip = true
        model.requestReviewIfEligible()
        XCTAssertFalse(model.reviewPromptPending)

        model.dismissShareTip()
        XCTAssertFalse(model.showShareTip)
        model.requestReviewIfEligible()
        XCTAssertTrue(model.reviewPromptPending)
    }

    func testConsiderReviewPromptArmsWithoutFiringImmediately() {
        let prefs = FakePreferences()
        let model = makeModel(FakeSpacesRepository(), preferences: prefs)
        model.snapshot = snapshotWithPinnedTab()

        model.considerReviewPrompt()
        XCTAssertTrue(prefs.bool(PreferenceKeys.didArmReviewPrompt, scope: .standard))
        XCTAssertFalse(model.reviewPromptPending)
        XCTAssertFalse(prefs.bool(PreferenceKeys.didRequestReview, scope: .standard))
    }
}

// MARK: - Fakes

@MainActor
private final class FakeSpacesRepository: SpacesRepository {
    var cached: ZenCompanion.ZenSnapshot?
    var refreshResult: Result<ZenCompanion.ZenSnapshot, Error> = .failure(URLError(.badServerResponse))
    private(set) var refreshCount = 0
    private(set) var deletedTabIds: [String] = []
    var blockRefresh = false
    private(set) var didBlockRefresh = false
    private var refreshContinuation: CheckedContinuation<Void, Never>?

    func cachedSnapshot() -> ZenCompanion.ZenSnapshot? { cached }

    func cache(_ snapshot: ZenCompanion.ZenSnapshot) { cached = snapshot }

    func refresh() async throws -> ZenCompanion.ZenSnapshot {
        refreshCount += 1
        if blockRefresh {
            await withCheckedContinuation { continuation in
                refreshContinuation = continuation
                didBlockRefresh = true
            }
        }
        return try refreshResult.get()
    }

    func deleteCachedSnapshot() { cached = nil }

    func deleteTab(id: String) async throws { deletedTabIds.append(id) }

    func releaseRefresh() {
        refreshContinuation?.resume()
        refreshContinuation = nil
    }
}

@MainActor
final class FakePreferences: PreferencesStoring {
    nonisolated init() {}

    private var strings: [String: String] = [:]
    private var bools: [String: Bool] = [:]
    private var objects: Set<String> = []

    private func scoped(_ key: String, _ scope: PreferenceScope) -> String {
        "\(scope == .standard ? "standard" : "appGroup").\(key)"
    }

    func string(_ key: String, scope: PreferenceScope) -> String? {
        strings[scoped(key, scope)]
    }

    func bool(_ key: String, scope: PreferenceScope) -> Bool {
        bools[scoped(key, scope)] ?? false
    }

    func hasObject(_ key: String, scope: PreferenceScope) -> Bool {
        objects.contains(scoped(key, scope))
    }

    func setString(_ value: String?, _ key: String, scope: PreferenceScope) {
        let id = scoped(key, scope)
        strings[id] = value
        if value != nil { objects.insert(id) } else { objects.remove(id) }
    }

    func setBool(_ value: Bool, _ key: String, scope: PreferenceScope) {
        let id = scoped(key, scope)
        bools[id] = value
        objects.insert(id)
    }

    func remove(_ key: String, scope: PreferenceScope) {
        strings[scoped(key, scope)] = nil
        bools[scoped(key, scope)] = nil
        objects.remove(scoped(key, scope))
    }
}

@MainActor
private final class FakeFaviconPrefetcher: FaviconPrefetching {
    private(set) var prefetchedSnapshots: [ZenCompanion.ZenSnapshot] = []

    func prefetch(from snapshot: ZenCompanion.ZenSnapshot) {
        prefetchedSnapshots.append(snapshot)
    }
}
