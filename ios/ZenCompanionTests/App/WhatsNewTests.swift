import UIKit
import XCTest

@testable import ZenCompanion

@MainActor
final class WhatsNewTests: XCTestCase {
    private func makeStore(
        _ preferences: FakePreferences = FakePreferences(),
        version: String = "1.6",
        release: WhatsNewRelease = .latest
    ) -> WhatsNewStore {
        WhatsNewStore(preferences: preferences, currentVersion: version, release: release)
    }

    // MARK: - Once per release

    func testReleaseTargetsVersion16() {
        XCTAssertEqual(WhatsNewRelease.latest.version, "1.6")
    }

    func testPresentsOnceForTheReleaseVersion() {
        let preferences = FakePreferences()
        let store = makeStore(preferences)

        XCTAssertTrue(store.shouldPresent)

        store.markPresented()

        XCTAssertFalse(store.shouldPresent)
        XCTAssertFalse(makeStore(preferences).shouldPresent)
    }

    func testDoesNotPresentBeforeTheReleaseVersion() {
        XCTAssertFalse(makeStore(FakePreferences(), version: "1.5").shouldPresent)
        XCTAssertFalse(makeStore(FakePreferences(), version: "1.6.1").shouldPresent)
        XCTAssertTrue(makeStore(FakePreferences(), version: "1.6").shouldPresent)
    }

    func testPresentsAgainForANewerRelease() {
        let preferences = FakePreferences()
        makeStore(preferences, version: "1.6").markPresented()

        XCTAssertFalse(makeStore(preferences, version: "1.6").shouldPresent)
        XCTAssertFalse(makeStore(preferences, version: "1.7").shouldPresent)

        let nextRelease = WhatsNewRelease(
            version: "1.7",
            eyebrow: WhatsNewRelease.latest.eyebrow,
            title: WhatsNewRelease.latest.title,
            subtitle: WhatsNewRelease.latest.subtitle,
            heroCards: WhatsNewRelease.latest.heroCards,
            heroAccessibilityLabel: WhatsNewRelease.latest.heroAccessibilityLabel,
            featuresHeading: WhatsNewRelease.latest.featuresHeading,
            items: WhatsNewRelease.latest.items
        )
        XCTAssertTrue(makeStore(preferences, version: "1.7", release: nextRelease).shouldPresent)
    }

    func testMarkingTwiceKeepsTheSameVersion() {
        let preferences = FakePreferences()
        let store = makeStore(preferences)

        store.markPresented()
        store.markPresented()

        XCTAssertEqual(
            preferences.string(PreferenceKeys.whatsNewShownVersion, scope: .standard),
            "1.6"
        )
    }

    // MARK: - Release notes model

    func testReleaseHasCompleteHeaderContent() {
        let release = WhatsNewRelease.latest

        XCTAssertEqual(release.eyebrow, "Z-SYNC 1.6")
        XCTAssertEqual(release.title, "Meet the new browser")
        XCTAssertEqual(
            release.subtitle,
            "Rebuilt from the ground up, with real tabs that stay open."
        )
        XCTAssertEqual(release.featuresHeading, "AND EVERYTHING A BROWSER NEEDS")
        XCTAssertFalse(release.heroAccessibilityLabel.isEmpty)
    }

    func testHeroShowsTheThreeScreenshotsInOrder() {
        let cards = WhatsNewRelease.latest.heroCards
        XCTAssertEqual([cards.leading, cards.center, cards.trailing], ["WhatsNewSearch", "WhatsNewDeck", "WhatsNewPage"])
        // A missing asset renders as an empty card, so check every name.
        for name in [cards.leading, cards.center, cards.trailing] {
            XCTAssertNotNil(UIImage(named: name), "missing hero asset \(name)")
        }
    }

    func testItemsHaveUniqueIdsAndCompleteCopy() {
        let items = WhatsNewRelease.latest.items

        XCTAssertFalse(items.isEmpty)
        XCTAssertEqual(Set(items.map(\.id)).count, items.count, "item ids must be unique")
        for item in items {
            XCTAssertFalse(item.id.isEmpty)
            XCTAssertFalse(item.title.isEmpty, "\(item.id) needs a title")
        }
    }

    func testPillsCoverTheNewBrowserInOrder() {
        XCTAssertEqual(
            WhatsNewRelease.latest.items.map(\.title),
            ["History", "Ad blocking", "Search suggestions", "Downloads", "New bottom bar", "and more"]
        )
    }

    func testPillCopyStaysShort() {
        // Pills are one short phrase each; anything longer belongs in a
        // sentence, not a pill.
        for item in WhatsNewRelease.latest.items {
            XCTAssertLessThanOrEqual(item.title.count, 24, "\(item.id) is too long for a pill")
        }
    }
}
