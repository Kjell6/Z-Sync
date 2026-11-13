import XCTest

@testable import ZenCompanion

@MainActor
final class SettingsModelTests: XCTestCase {
    private func makeModel(_ preferences: FakePreferences) -> SettingsModel {
        SettingsModel(
            searchEngine: FakeSettingsSearchEngine(),
            preferences: preferences,
            signerOut: FakeSignerOut()
        )
    }

    // MARK: - Open into search

    func testOpenIntoSearchIsOffByDefaultAndPersists() {
        let prefs = FakePreferences()
        let model = makeModel(prefs)
        XCTAssertFalse(model.openIntoSearch)

        model.openIntoSearch = true

        XCTAssertTrue(prefs.bool(PreferenceKeys.openIntoSearch, scope: .standard))
        XCTAssertTrue(makeModel(prefs).openIntoSearch)
    }

    // MARK: - Toolbar placement

    func testToolbarPlacementDefaultsToBottom() {
        XCTAssertEqual(makeModel(FakePreferences()).toolbarPlacement, .bottom)
    }

    func testToolbarPlacementPrefersAppGroupThenStandard() {
        let appGroupWins = FakePreferences()
        appGroupWins.setString(ToolbarPlacement.bottom.rawValue, PreferenceKeys.toolbarPlacement, scope: .standard)
        appGroupWins.setString(ToolbarPlacement.top.rawValue, PreferenceKeys.toolbarPlacement, scope: .appGroup)
        XCTAssertEqual(makeModel(appGroupWins).toolbarPlacement, .top)

        let standardOnly = FakePreferences()
        standardOnly.setString(ToolbarPlacement.bottom.rawValue, PreferenceKeys.toolbarPlacement, scope: .standard)
        XCTAssertEqual(makeModel(standardOnly).toolbarPlacement, .bottom)
    }

    func testToolbarPlacementIgnoresUnknownStoredValue() {
        let prefs = FakePreferences()
        prefs.setString("sideways", PreferenceKeys.toolbarPlacement, scope: .appGroup)
        XCTAssertEqual(makeModel(prefs).toolbarPlacement, .bottom)
    }

    func testSettingToolbarPlacementPersistsToBothStores() {
        let prefs = FakePreferences()
        let model = makeModel(prefs)

        model.toolbarPlacement = .top

        XCTAssertEqual(prefs.string(PreferenceKeys.toolbarPlacement, scope: .appGroup), "top")
        XCTAssertEqual(prefs.string(PreferenceKeys.toolbarPlacement, scope: .standard), "top")
    }

    func testSettingToolbarPlacementToSameValueDoesNotWrite() {
        let prefs = FakePreferences()
        let model = makeModel(prefs)

        model.toolbarPlacement = .bottom

        XCTAssertFalse(prefs.hasObject(PreferenceKeys.toolbarPlacement, scope: .appGroup))
        XCTAssertFalse(prefs.hasObject(PreferenceKeys.toolbarPlacement, scope: .standard))
    }

    // MARK: - Signed-out access

    func testSignedOutHidesTheRowsThatNeedAnAccount() {
        let access = SettingsAccess(isSignedIn: false)

        XCTAssertFalse(access.showsAccountEmail)
        XCTAssertFalse(access.showsSignOut)
        XCTAssertFalse(access.showsSaveKind)
        XCTAssertFalse(access.showsEssentialsColumns)
    }

    func testSignedInShowsTheAccountRows() {
        let access = SettingsAccess(isSignedIn: true)

        XCTAssertTrue(access.showsAccountEmail)
        XCTAssertTrue(access.showsSignOut)
        XCTAssertTrue(access.showsSaveKind)
        XCTAssertTrue(access.showsEssentialsColumns)
    }
}

@MainActor
private final class FakeSettingsSearchEngine: SearchEngineProviding {
    nonisolated init() {}

    var current: ZenCompanion.SearchEngine = .duckDuckGo
    var custom: [ZenCompanion.SearchEngine] = []
}

@MainActor
private final class FakeSignerOut: SessionSigningOut {
    nonisolated init() {}

    private(set) var signOutCount = 0

    func signOut() { signOutCount += 1 }
}
