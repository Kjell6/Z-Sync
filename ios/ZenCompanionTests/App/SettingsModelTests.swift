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

    // MARK: - Toolbar placement

    func testToolbarPlacementDefaultsToTop() {
        XCTAssertEqual(makeModel(FakePreferences()).toolbarPlacement, .top)
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
        XCTAssertEqual(makeModel(prefs).toolbarPlacement, .top)
    }

    func testSettingToolbarPlacementPersistsToBothStores() {
        let prefs = FakePreferences()
        let model = makeModel(prefs)

        model.toolbarPlacement = .bottom

        XCTAssertEqual(prefs.string(PreferenceKeys.toolbarPlacement, scope: .appGroup), "bottom")
        XCTAssertEqual(prefs.string(PreferenceKeys.toolbarPlacement, scope: .standard), "bottom")
    }

    func testSettingToolbarPlacementToSameValueDoesNotWrite() {
        let prefs = FakePreferences()
        let model = makeModel(prefs)

        model.toolbarPlacement = .top

        XCTAssertFalse(prefs.hasObject(PreferenceKeys.toolbarPlacement, scope: .appGroup))
        XCTAssertFalse(prefs.hasObject(PreferenceKeys.toolbarPlacement, scope: .standard))
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
