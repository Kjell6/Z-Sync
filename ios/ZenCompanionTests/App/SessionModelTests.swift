import XCTest

@testable import ZenCompanion

@MainActor
final class SessionModelTests: XCTestCase {
    private func snapshot(isDemo: Bool = false) -> ZenCompanion.AccountSnapshot {
        ZenCompanion.AccountSnapshot(
            email: "a@b.c",
            uid: isDemo ? "demo" : "u1",
            sessionTokenHex: "aa",
            kBHex: "bb",
            isDemo: isDemo
        )
    }

    func testLoadReadsAccountAndRepublishesForShareExtension() {
        let accounts = FakeAccountSession()
        accounts.stored = snapshot()
        let model = SessionModel(
            accounts: accounts,
            spaces: FakeSessionSpaces(),
            preferences: FakePreferences(),
            haptics: FakeSessionHaptics()
        )

        model.load()

        XCTAssertEqual(model.account?.uid, "u1")
        XCTAssertEqual(accounts.republishCount, 1)
    }

    func testCompleteSignInPersistsAndEntersSession() {
        let accounts = FakeAccountSession()
        let model = SessionModel(
            accounts: accounts,
            spaces: FakeSessionSpaces(),
            preferences: FakePreferences(),
            haptics: FakeSessionHaptics()
        )

        model.completeSignIn(snapshot())

        XCTAssertEqual(model.account?.uid, "u1")
        XCTAssertEqual(accounts.stored?.uid, "u1")
        XCTAssertNil(model.saveError)
    }

    func testCompleteSignInSaveFailureKeepsSessionNil() {
        let accounts = FakeAccountSession()
        accounts.saveError = NSError(domain: "test", code: 1)
        let model = SessionModel(
            accounts: accounts,
            spaces: FakeSessionSpaces(),
            preferences: FakePreferences(),
            haptics: FakeSessionHaptics()
        )

        model.completeSignIn(snapshot())

        XCTAssertNil(model.account)
        XCTAssertNotNil(model.saveError)
        XCTAssertNil(accounts.stored)
    }

    func testSignedOutClearsAccountAndSyncSetupHint() {
        let prefs = FakePreferences()
        prefs.setBool(true, PreferenceKeys.didDismissSyncSetupHint, scope: .standard)
        let model = SessionModel(
            accounts: FakeAccountSession(),
            spaces: FakeSessionSpaces(),
            preferences: prefs,
            haptics: FakeSessionHaptics()
        )
        model.account = snapshot()

        model.signedOut()

        XCTAssertNil(model.account)
        XCTAssertFalse(prefs.bool(PreferenceKeys.didDismissSyncSetupHint, scope: .standard))
    }

    func testFiveDemoTapsWithinWindowEnterDemo() {
        let accounts = FakeAccountSession()
        let spaces = FakeSessionSpaces()
        let haptics = FakeSessionHaptics()
        var current = Date(timeIntervalSince1970: 1_000)
        let model = SessionModel(
            accounts: accounts,
            spaces: spaces,
            preferences: FakePreferences(),
            haptics: haptics,
            now: { current }
        )

        for _ in 0..<4 {
            model.registerDemoTap()
            current.addTimeInterval(0.2)
        }
        XCTAssertNil(model.account)

        model.registerDemoTap()

        XCTAssertEqual(model.account?.uid, DemoCatalog.account.uid)
        XCTAssertTrue(model.account?.isDemo == true)
        XCTAssertEqual(haptics.mediumImpacts, 1)
        XCTAssertEqual(spaces.cached?.spaces.count, DemoCatalog.snapshot.spaces.count)
        XCTAssertEqual(accounts.stored?.uid, DemoCatalog.account.uid)
    }

    func testDemoTapWindowResetsAfterGap() {
        let accounts = FakeAccountSession()
        var current = Date(timeIntervalSince1970: 1_000)
        let model = SessionModel(
            accounts: accounts,
            spaces: FakeSessionSpaces(),
            preferences: FakePreferences(),
            haptics: FakeSessionHaptics(),
            now: { current }
        )

        for _ in 0..<4 {
            model.registerDemoTap()
            current.addTimeInterval(0.1)
        }
        current.addTimeInterval(2.1)
        for _ in 0..<4 {
            model.registerDemoTap()
            current.addTimeInterval(0.1)
        }

        XCTAssertNil(model.account, "a gap over 2s must restart the five-tap count")
    }

    func testEnterDemoClearsPreviousCache() {
        let spaces = FakeSessionSpaces()
        spaces.cached = ZenCompanion.ZenSnapshot(
            spaces: [ZenCompanion.ZenSpace(id: "old", name: "Old")],
            fetchedAt: Date(timeIntervalSince1970: 0)
        )
        let model = SessionModel(
            accounts: FakeAccountSession(),
            spaces: spaces,
            preferences: FakePreferences(),
            haptics: FakeSessionHaptics()
        )

        model.enterDemo()

        XCTAssertNotEqual(spaces.cached?.spaces.first?.id, "old")
        XCTAssertEqual(model.account?.uid, DemoCatalog.account.uid)
    }
}

@MainActor
private final class FakeAccountSession: AccountSessioning {
    var stored: ZenCompanion.AccountSnapshot?
    var saveError: Error?
    private(set) var republishCount = 0

    func load() -> ZenCompanion.AccountSnapshot? { stored }

    func save(_ snapshot: ZenCompanion.AccountSnapshot) throws {
        if let saveError { throw saveError }
        stored = snapshot
    }

    func republishForShareExtension() { republishCount += 1 }
}

@MainActor
private final class FakeSessionSpaces: SpacesRepository {
    var cached: ZenCompanion.ZenSnapshot?

    func cachedSnapshot() -> ZenCompanion.ZenSnapshot? { cached }
    func cache(_ snapshot: ZenCompanion.ZenSnapshot) { cached = snapshot }
    func refresh() async throws -> ZenCompanion.ZenSnapshot {
        cached ?? ZenCompanion.ZenSnapshot(spaces: [], fetchedAt: Date())
    }
    func deleteCachedSnapshot() { cached = nil }
    func deleteTab(id: String) async throws {}
}

@MainActor
private final class FakeSessionHaptics: HapticsPlaying {
    private(set) var mediumImpacts = 0
    func pinSucceeded() {}
    func selectionChanged() {}
    func mediumImpact() { mediumImpacts += 1 }
}
