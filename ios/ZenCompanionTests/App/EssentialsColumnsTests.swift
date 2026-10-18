import XCTest

@testable import ZenCompanion

/// The essentials-per-row choice: the adaptive default keeps the pre-setting
/// behavior, a fixed choice caps the row at its number (and never exceeds the
/// item count, so a short row fills the width instead of leaving a gap).
final class EssentialsColumnsTests: XCTestCase {
    func testAutomaticMatchesTheAdaptiveDefault() {
        let expected: [Int: Int] = [1: 1, 2: 2, 3: 3, 4: 4, 5: 3, 6: 3, 7: 4, 8: 4, 12: 4]
        for (count, columns) in expected {
            XCTAssertEqual(
                EssentialsColumns.automatic.columnCount(forItemCount: count),
                columns,
                "automatic with \(count) essentials"
            )
        }
    }

    func testFixedChoiceCapsAtTheItemCount() {
        XCTAssertEqual(EssentialsColumns.five.columnCount(forItemCount: 3), 3)
        XCTAssertEqual(EssentialsColumns.five.columnCount(forItemCount: 8), 5)
        XCTAssertEqual(EssentialsColumns.two.columnCount(forItemCount: 5), 2)
        XCTAssertEqual(EssentialsColumns.one.columnCount(forItemCount: 9), 1)
    }

    func testEveryChoiceUsesAtLeastOneColumn() {
        for choice in EssentialsColumns.allCases {
            XCTAssertEqual(choice.columnCount(forItemCount: 0), 1)
        }
    }

    func testRawValuesSurviveTheRoundTrip() {
        for choice in EssentialsColumns.allCases {
            XCTAssertEqual(EssentialsColumns(rawValue: choice.rawValue), choice)
        }
    }
}

@MainActor
final class EssentialsColumnsPersistenceTests: XCTestCase {
    private func makeModel(_ preferences: FakePreferences) -> SettingsModel {
        SettingsModel(
            searchEngine: FakeColumnsSearchEngine(),
            preferences: preferences,
            signerOut: FakeColumnsSignerOut()
        )
    }

    func testDefaultsToAutomatic() {
        XCTAssertEqual(makeModel(FakePreferences()).essentialsColumns, .automatic)
    }

    func testAppGroupWinsThenStandard() {
        let appGroupWins = FakePreferences()
        appGroupWins.setString(EssentialsColumns.three.rawValue, PreferenceKeys.essentialsColumns, scope: .standard)
        appGroupWins.setString(EssentialsColumns.five.rawValue, PreferenceKeys.essentialsColumns, scope: .appGroup)
        XCTAssertEqual(makeModel(appGroupWins).essentialsColumns, .five)

        let standardOnly = FakePreferences()
        standardOnly.setString(EssentialsColumns.two.rawValue, PreferenceKeys.essentialsColumns, scope: .standard)
        XCTAssertEqual(makeModel(standardOnly).essentialsColumns, .two)
    }

    func testIgnoresUnknownStoredValue() {
        let prefs = FakePreferences()
        prefs.setString("eleven", PreferenceKeys.essentialsColumns, scope: .appGroup)
        XCTAssertEqual(makeModel(prefs).essentialsColumns, .automatic)
    }

    func testWritesBothStores() {
        let prefs = FakePreferences()
        let model = makeModel(prefs)

        model.essentialsColumns = .four

        XCTAssertEqual(prefs.string(PreferenceKeys.essentialsColumns, scope: .appGroup), "4")
        XCTAssertEqual(prefs.string(PreferenceKeys.essentialsColumns, scope: .standard), "4")
    }
}

@MainActor
private final class FakeColumnsSearchEngine: SearchEngineProviding {
    nonisolated init() {}
    var current: ZenCompanion.SearchEngine = .duckDuckGo
    var custom: [ZenCompanion.SearchEngine] = []
}

@MainActor
private final class FakeColumnsSignerOut: SessionSigningOut {
    nonisolated init() {}
    func signOut() {}
}
