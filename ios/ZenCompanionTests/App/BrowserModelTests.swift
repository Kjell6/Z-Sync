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

    /// A reload asked for mid-fetch (e.g. a write's stale signal) runs after
    /// the current fetch instead of being dropped.
    func testReloadDuringReloadRunsAgainAfterward() async {
        let repo = FakeSpacesRepository()
        repo.cached = snapshot(["a"])
        repo.refreshResult = .success(snapshot(["a"]))
        repo.blockRefresh = true
        let model = makeModel(repo)

        let inFlight = Task { await model.reload() }
        while !repo.didBlockRefresh { await Task.yield() }
        await model.reload()
        XCTAssertEqual(repo.refreshCount, 1)

        repo.blockRefresh = false
        repo.releaseRefresh()
        await inFlight.value

        XCTAssertEqual(repo.refreshCount, 2, "the queued reload must run once the first finishes")
    }

    /// A stale signal that lands during a quiet (foreground) reload must run
    /// once that fetch finishes too: `reloadQuietly` holds `reloading` during
    /// its fetch, so the queue must be drained there as well, not only in
    /// `reload()`.
    func testStaleSignalDuringQuietReloadRunsAfterward() async {
        let repo = FakeSpacesRepository()
        repo.cached = snapshot(["a"])
        repo.refreshResult = .success(snapshot(["a"]))
        repo.blockRefresh = true
        let model = makeModel(repo)

        // Foreground refresh with cached spaces on screen: the quiet path.
        let quiet = Task { await model.reloadQuietly() }
        while !repo.didBlockRefresh { await Task.yield() }
        // A write's stale signal lands mid-fetch.
        await model.reload()
        XCTAssertEqual(repo.refreshCount, 1)

        repo.blockRefresh = false
        repo.releaseRefresh()
        await quiet.value

        XCTAssertEqual(repo.refreshCount, 2, "the queued reload must run once the quiet fetch finishes")
    }

    /// An interrupted fetch holds `reloading` through its 700 ms retry pause
    /// (like Android), so a reload arriving inside the pause queues and runs
    /// after the retry instead of fetching alongside it.
    func testInterruptedRetryDoesNotFetchAlongsideANewerReload() async {
        let repo = FakeSpacesRepository()
        repo.cached = snapshot(["a"])
        repo.refreshResult = .failure(URLError(.cancelled))
        let model = makeModel(repo)

        // Fetch 1 is interrupted: the model pauses 700 ms before retrying.
        let interrupted = Task { await model.reload() }
        while model.loadError == nil { await Task.yield() }

        // A newer reload arrives inside the pause and blocks mid-fetch.
        repo.refreshResult = .success(snapshot(["a"]))
        repo.blockRefresh = true
        let newer = Task { await model.reload() }

        // Let the pause expire so the retry path runs; the gate keeps the
        // newer fetch in flight until then.
        try? await Task.sleep(for: .milliseconds(1_500))
        repo.blockRefresh = false
        repo.releaseRefresh()
        await interrupted.value
        await newer.value

        XCTAssertEqual(repo.refreshCount, 3, "the newer reload must still run")
        XCTAssertEqual(
            repo.maxConcurrentRefreshes, 1,
            "the interrupted retry must queue behind the newer reload, not fetch alongside it"
        )
    }

    /// A failed quiet (foreground) refresh surfaces in the status line like
    /// every other refresh failure, and a later success clears it.
    func testQuietReloadFailureSurfacesAndClearsInStatusLine() async {
        let repo = FakeSpacesRepository()
        repo.refreshResult = .failure(URLError(.timedOut))
        let model = makeModel(repo)
        model.snapshot = snapshot(["a"])

        await model.reloadQuietly()

        XCTAssertNotNil(model.loadError, "a failed foreground refresh must be visible in the dock line")

        repo.refreshResult = .success(snapshot(["a", "b"]))
        await model.reloadQuietly()

        XCTAssertNil(model.loadError, "a successful foreground refresh must clear the line")
    }

    /// A failed delete says so in the status line instead of failing silently.
    func testFailedDeleteShowsMessage() async {
        let repo = FakeSpacesRepository()
        repo.deleteError = ZenCompanion.SyncError.conflict
        let model = makeModel(repo)

        await model.deleteTab(id: "t1")

        XCTAssertEqual(
            model.deleteError,
            "\(String(localized: "home.delete_failed")) \(String(localized: "error.conflict"))"
        )
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

    // MARK: - Signed out

    func testSignedOutNeverReadsOrRefreshesTheAccount() async {
        let repo = FakeSpacesRepository()
        repo.cached = snapshot(["a"])
        repo.refreshResult = .success(snapshot(["a"]))
        let favicons = FakeFaviconPrefetcher()
        let model = BrowserModel(
            account: nil,
            repository: repo,
            preferences: FakePreferences(),
            favicons: favicons
        )

        await model.initialLoad()
        await model.manualRefresh()
        await model.sceneBecameActive()

        XCTAssertFalse(model.isSignedIn)
        XCTAssertTrue(model.snapshot.spaces.isEmpty)
        XCTAssertEqual(repo.refreshCount, 0, "a signed-out screen must not fetch the account's spaces")
        XCTAssertTrue(favicons.prefetchedSnapshots.isEmpty)
    }

    func testSignedOutOpenIntoSearchStillAsksForANewTab() async {
        let model = BrowserModel(
            account: nil,
            repository: FakeSpacesRepository(),
            preferences: searchPreferences(),
            favicons: FakeFaviconPrefetcher()
        )

        await model.initialLoad()

        XCTAssertTrue(model.searchOnOpenPending, "Open into search applies without an account")
    }

    func testSigningOutDropsTheSpacesOnScreen() async {
        let repo = FakeSpacesRepository()
        repo.cached = snapshot(["a"])
        repo.refreshResult = .success(snapshot(["a"]))
        let model = BrowserModel(
            account: account(),
            repository: repo,
            preferences: FakePreferences(),
            favicons: FakeFaviconPrefetcher()
        )
        await model.initialLoad()
        XCTAssertEqual(model.snapshot.spaces.map(\.id), ["a"])

        model.accountChanged(isSignedIn: false, isDemo: false)

        XCTAssertFalse(model.isSignedIn)
        XCTAssertTrue(model.snapshot.spaces.isEmpty)
        XCTAssertNil(model.loadError)
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
    // MARK: - Toolbar placement

    func testToolbarPlacementDefaultsToBottomAndPrefersAppGroup() {
        XCTAssertEqual(makeModel(FakeSpacesRepository()).toolbarPlacement, .bottom)

        let appGroupWins = FakePreferences()
        appGroupWins.setString(ToolbarPlacement.bottom.rawValue, PreferenceKeys.toolbarPlacement, scope: .standard)
        appGroupWins.setString(ToolbarPlacement.top.rawValue, PreferenceKeys.toolbarPlacement, scope: .appGroup)
        XCTAssertEqual(
            makeModel(FakeSpacesRepository(), preferences: appGroupWins).toolbarPlacement,
            .top
        )

        let standardOnly = FakePreferences()
        standardOnly.setString(ToolbarPlacement.bottom.rawValue, PreferenceKeys.toolbarPlacement, scope: .standard)
        XCTAssertEqual(
            makeModel(FakeSpacesRepository(), preferences: standardOnly).toolbarPlacement,
            .bottom
        )
    }

    func testToolbarPlacementIgnoresUnknownStoredValue() {
        let prefs = FakePreferences()
        prefs.setString("sideways", PreferenceKeys.toolbarPlacement, scope: .appGroup)

        XCTAssertEqual(makeModel(FakeSpacesRepository(), preferences: prefs).toolbarPlacement, .bottom)
    }

    func testSetToolbarPlacementMirrorsChoiceWithoutPersisting() {
        let prefs = FakePreferences()
        let model = makeModel(FakeSpacesRepository(), preferences: prefs)

        model.setToolbarPlacement(.top)

        XCTAssertEqual(model.toolbarPlacement, .top)
        // Persistence is SettingsModel's job; the browser model only mirrors.
        XCTAssertFalse(prefs.hasObject(PreferenceKeys.toolbarPlacement, scope: .appGroup))
        XCTAssertFalse(prefs.hasObject(PreferenceKeys.toolbarPlacement, scope: .standard))
    }

    // MARK: - Open into search

    private func searchPreferences(enabled: Bool = true) -> FakePreferences {
        let prefs = FakePreferences()
        prefs.setBool(enabled, PreferenceKeys.openIntoSearch, scope: .standard)
        return prefs
    }

    func testLaunchWithCachedSpacesOpensSearchWhenEnabled() async {
        let repo = FakeSpacesRepository()
        repo.cached = snapshot(["a"])
        repo.refreshResult = .success(snapshot(["a"]))
        let model = makeModel(repo, preferences: searchPreferences())

        await model.initialLoad()

        XCTAssertTrue(model.searchOnOpenPending)
    }

    func testLaunchStaysOnSpacesWhenSettingIsOff() async {
        let repo = FakeSpacesRepository()
        repo.cached = snapshot(["a"])
        repo.refreshResult = .success(snapshot(["a"]))
        let model = makeModel(repo, preferences: searchPreferences(enabled: false))

        await model.initialLoad()
        model.sceneEnteredBackground()
        await model.sceneBecameActive()

        XCTAssertFalse(model.searchOnOpenPending)
    }

    func testFirstLaunchWithoutCacheShowsSpacesThenSearchesOnNextOpen() async {
        let repo = FakeSpacesRepository()
        repo.refreshResult = .success(snapshot(["a"]))
        let model = makeModel(repo, preferences: searchPreferences())

        await model.initialLoad()
        XCTAssertFalse(model.searchOnOpenPending)

        // Control Center or a system alert: active again without backgrounding.
        await model.sceneBecameActive()
        XCTAssertFalse(model.searchOnOpenPending)

        model.sceneEnteredBackground()
        await model.sceneBecameActive()
        XCTAssertTrue(model.searchOnOpenPending)
    }

    func testSearchOpensOncePerTripToTheForeground() async {
        let repo = FakeSpacesRepository()
        repo.cached = snapshot(["a"])
        repo.refreshResult = .success(snapshot(["a"]))
        let model = makeModel(repo, preferences: searchPreferences())

        // scenePhase can report active before the cached spaces load.
        await model.sceneBecameActive()
        await model.initialLoad()
        XCTAssertTrue(model.searchOnOpenPending)
        model.consumeSearchOnOpen()

        await model.sceneBecameActive()
        XCTAssertFalse(model.searchOnOpenPending)
    }

    // MARK: - Rename

    func testRenameTabUpdatesSnapshotBeforeWriteCompletes() async {
        let repo = FakeSpacesRepository()
        repo.blockRename = true
        let model = makeModel(repo)
        model.snapshot = ZenCompanion.ZenSnapshot(
            spaces: [
                ZenCompanion.ZenSpace(
                    id: "s1",
                    name: "One",
                    pinned: [.tab(ZenCompanion.ZenTab(id: "t1", url: "https://a.example", title: "Old"))]
                ),
            ],
            fetchedAt: Date(timeIntervalSince1970: 0)
        )

        let task = Task { await model.renameTab(id: "t1", label: "Work") }
        while !repo.didBlockRename { await Task.yield() }

        // The write is still awaiting the server, but the name is already shown.
        XCTAssertEqual(pinnedTab(model.snapshot, id: "t1")?.staticLabel, "Work")
        XCTAssertEqual(repo.renamedTabs.count, 1)

        repo.releaseRename()
        await task.value
    }

    func testRenameTabNormalizesWhitespaceIntoSnapshotButForwardsRawLabel() async {
        let repo = FakeSpacesRepository()
        let model = makeModel(repo)
        model.snapshot = ZenCompanion.ZenSnapshot(
            spaces: [
                ZenCompanion.ZenSpace(
                    id: "s1",
                    name: "One",
                    pinned: [.tab(ZenCompanion.ZenTab(id: "t1", url: "https://a.example", title: "Old"))]
                ),
            ],
            fetchedAt: Date(timeIntervalSince1970: 0)
        )

        await model.renameTab(id: "t1", label: "  Work  ")

        XCTAssertEqual(pinnedTab(model.snapshot, id: "t1")?.staticLabel, "Work")
        XCTAssertEqual(repo.renamedTabs.first?.label, "  Work  ")
    }

    func testRenameTabWhitespaceOnlyClearsLabel() async {
        let repo = FakeSpacesRepository()
        let model = makeModel(repo)
        model.snapshot = ZenCompanion.ZenSnapshot(
            spaces: [
                ZenCompanion.ZenSpace(
                    id: "s1",
                    name: "One",
                    pinned: [.tab(ZenCompanion.ZenTab(
                        id: "t1",
                        url: "https://a.example",
                        title: "Old",
                        staticLabel: "Work"
                    ))]
                ),
            ],
            fetchedAt: Date(timeIntervalSince1970: 0)
        )

        await model.renameTab(id: "t1", label: "   ")

        XCTAssertNil(pinnedTab(model.snapshot, id: "t1")?.staticLabel)
    }

    // MARK: - Pin

    func testPinTabMovesRowToPinnedSectionAndForwardsWrite() async {
        let repo = FakeSpacesRepository()
        let model = makeModel(repo)
        model.snapshot = ZenCompanion.ZenSnapshot(
            spaces: [
                ZenCompanion.ZenSpace(
                    id: "s1",
                    name: "One",
                    tabs: [.tab(ZenCompanion.ZenTab(
                        id: "n1",
                        url: "https://n.example",
                        title: "Normal",
                        staticLabel: "Keep"
                    ))]
                ),
            ],
            fetchedAt: Date(timeIntervalSince1970: 0)
        )

        await model.pinTab(id: "n1", in: "s1")

        let space = model.snapshot.spaces.first
        XCTAssertEqual(space?.tabs.map(\.id), [])
        XCTAssertEqual(space?.pinned.map(\.id), ["n1"])
        XCTAssertEqual(pinnedTab(model.snapshot, id: "n1")?.staticLabel, "Keep")
        XCTAssertEqual(repo.pinnedTabs.count, 1)
        XCTAssertEqual(repo.pinnedTabs.first?.id, "n1")
        XCTAssertEqual(repo.pinnedTabs.first?.spaceId, "s1")
    }

    func testPinTabReloadsAndSnapsBackWhenWriteFails() async {
        let repo = FakeSpacesRepository()
        let original = ZenCompanion.ZenSnapshot(
            spaces: [
                ZenCompanion.ZenSpace(
                    id: "s1",
                    name: "One",
                    tabs: [.tab(ZenCompanion.ZenTab(id: "n1", url: "https://n.example", title: "Normal"))]
                ),
            ],
            fetchedAt: Date(timeIntervalSince1970: 0)
        )
        repo.refreshResult = .success(original)
        repo.pinError = URLError(.badServerResponse)
        let model = makeModel(repo)
        model.snapshot = original

        await model.pinTab(id: "n1", in: "s1")

        XCTAssertEqual(repo.pinnedTabs.count, 1)
        XCTAssertEqual(repo.refreshCount, 1, "a failed pin must reload so the row snaps back")
        XCTAssertEqual(model.snapshot.spaces.first?.tabs.map(\.id), ["n1"])
        XCTAssertTrue(model.snapshot.spaces.first?.pinned.isEmpty ?? false)
    }

    private func pinnedTab(_ snapshot: ZenCompanion.ZenSnapshot, id: String) -> ZenCompanion.ZenTab? {
        snapshot.spaces.first?.pinned.compactMap { item -> ZenCompanion.ZenTab? in
            if case .tab(let tab) = item { return tab }
            return nil
        }.first { $0.id == id }
    }
}

// MARK: - Fakes

@MainActor
private final class FakeSpacesRepository: SpacesRepository {
    var cached: ZenCompanion.ZenSnapshot?
    var refreshResult: Result<ZenCompanion.ZenSnapshot, Error> = .failure(URLError(.badServerResponse))
    private(set) var refreshCount = 0
    /// Highest number of refreshes in flight at once — pins that queued
    /// reloads never fetch alongside the one that queued them.
    private(set) var maxConcurrentRefreshes = 0
    private var inFlightRefreshes = 0
    private(set) var deletedTabIds: [String] = []
    private(set) var renamedTabs: [(id: String, label: String)] = []
    private(set) var pinnedTabs: [(id: String, spaceId: String)] = []
    var blockRefresh = false
    private(set) var didBlockRefresh = false
    private var refreshWaiters: [CheckedContinuation<Void, Never>] = []
    var blockRename = false
    private(set) var didBlockRename = false
    private var renameContinuation: CheckedContinuation<Void, Never>?
    /// When set, `pinTab` records the call and then throws it, so the model's
    /// failure rollback can be exercised.
    var pinError: Error?

    func cachedSnapshot() -> ZenCompanion.ZenSnapshot? { cached }

    func cache(_ snapshot: ZenCompanion.ZenSnapshot) { cached = snapshot }

    func refresh() async throws -> ZenCompanion.ZenSnapshot {
        refreshCount += 1
        inFlightRefreshes += 1
        maxConcurrentRefreshes = max(maxConcurrentRefreshes, inFlightRefreshes)
        defer { inFlightRefreshes -= 1 }
        if blockRefresh {
            await withCheckedContinuation { continuation in
                refreshWaiters.append(continuation)
                didBlockRefresh = true
            }
        }
        return try refreshResult.get()
    }

    func deleteCachedSnapshot() { cached = nil }

    var deleteError: Error?

    func deleteTab(id: String) async throws {
        deletedTabIds.append(id)
        if let deleteError { throw deleteError }
    }

    func renameTab(id: String, label: String) async throws {
        renamedTabs.append((id, label))
        if blockRename {
            await withCheckedContinuation { continuation in
                renameContinuation = continuation
                didBlockRename = true
            }
        }
    }

    func moveTab(id: String, toFolder folderId: String?, in spaceId: String) async throws {}

    func pinTab(id: String, in spaceId: String) async throws {
        pinnedTabs.append((id, spaceId))
        if let pinError { throw pinError }
    }

    func releaseRefresh() {
        let waiters = refreshWaiters
        refreshWaiters = []
        waiters.forEach { $0.resume() }
    }

    func releaseRename() {
        renameContinuation?.resume()
        renameContinuation = nil
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
