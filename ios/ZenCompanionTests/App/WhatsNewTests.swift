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

    // MARK: - Only for readers who used an earlier version

    func testFreshInstallOfTheReleaseSkipsTheNotes() {
        let preferences = FakePreferences()
        WhatsNewStore.recordFirstLaunch(preferences: preferences, currentVersion: "1.6", hasAccount: { false })

        XCTAssertEqual(preferences.string(PreferenceKeys.firstLaunchVersion, scope: .standard), "1.6")
        XCTAssertFalse(makeStore(preferences).shouldPresent)
    }

    func testUpdateFromAnEarlierVersionShowsTheNotes() {
        let preferences = FakePreferences()
        preferences.setString("1.5", PreferenceKeys.whatsNewShownVersion, scope: .standard)
        WhatsNewStore.recordFirstLaunch(preferences: preferences, currentVersion: "1.6", hasAccount: { false })

        XCTAssertEqual(
            preferences.string(PreferenceKeys.firstLaunchVersion, scope: .standard),
            PreferenceKeys.updatedFromEarlierVersion
        )
        XCTAssertTrue(makeStore(preferences).shouldPresent)
    }

    func testAppGroupFootprintOrStoredAccountCountsAsEarlierUse() {
        let fromCache = FakePreferences()
        fromCache.setString("space-1", PreferenceKeys.lastSpaceId, scope: .appGroup)
        WhatsNewStore.recordFirstLaunch(preferences: fromCache, currentVersion: "1.6", hasAccount: { false })
        XCTAssertTrue(makeStore(fromCache).shouldPresent)

        let fromAccount = FakePreferences()
        WhatsNewStore.recordFirstLaunch(preferences: fromAccount, currentVersion: "1.6", hasAccount: { true })
        XCTAssertTrue(makeStore(fromAccount).shouldPresent)
    }

    func testFirstLaunchIsRecordedOnlyOnce() {
        let preferences = FakePreferences()
        WhatsNewStore.recordFirstLaunch(preferences: preferences, currentVersion: "1.6", hasAccount: { false })
        // Later launches write preferences of their own; that is not an update.
        preferences.setBool(true, PreferenceKeys.didShowShareExtensionTip, scope: .standard)
        WhatsNewStore.recordFirstLaunch(preferences: preferences, currentVersion: "1.6", hasAccount: { true })

        XCTAssertEqual(preferences.string(PreferenceKeys.firstLaunchVersion, scope: .standard), "1.6")
        XCTAssertFalse(makeStore(preferences).shouldPresent)
    }

    func testReaderWhoStartedOnTheReleaseGetsTheNextOne() {
        let preferences = FakePreferences()
        WhatsNewStore.recordFirstLaunch(preferences: preferences, currentVersion: "1.6", hasAccount: { false })
        let nextRelease = WhatsNewRelease(
            version: "1.7",
            title: WhatsNewRelease.latest.title,
            subtitle: WhatsNewRelease.latest.subtitle,
            heroImage: WhatsNewRelease.latest.heroImage,
            heroAccessibilityLabel: WhatsNewRelease.latest.heroAccessibilityLabel,
            featuresHeading: WhatsNewRelease.latest.featuresHeading,
            items: WhatsNewRelease.latest.items
        )
        XCTAssertTrue(makeStore(preferences, version: "1.7", release: nextRelease).shouldPresent)
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
            title: WhatsNewRelease.latest.title,
            subtitle: WhatsNewRelease.latest.subtitle,
            heroImage: WhatsNewRelease.latest.heroImage,
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

        XCTAssertEqual(release.title, "Meet the new Quick Browser")
        XCTAssertEqual(
            release.subtitle,
            "Rebuilt from the ground up, with real tabs that stay open."
        )
        XCTAssertEqual(release.featuresHeading, "WITH EVERYTHING A BROWSER NEEDS")
        XCTAssertFalse(release.heroAccessibilityLabel.isEmpty)
    }

    func testHeroImageShipsWithTheApp() {
        let name = WhatsNewRelease.latest.heroImage
        XCTAssertEqual(name, "WhatsNewHero")
        // A missing asset renders as nothing, so check it is bundled.
        XCTAssertNotNil(UIImage(named: name), "missing hero asset \(name)")
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

@MainActor
final class OnboardingStoreTests: XCTestCase {
    func testFreshInstallShowsTheOnboardingUntilCompleted() {
        let preferences = FakePreferences()
        WhatsNewStore.recordFirstLaunch(preferences: preferences, currentVersion: "1.6", hasAccount: { false })
        let store = OnboardingStore(preferences: preferences)

        XCTAssertTrue(store.shouldPresent)
        store.markCompleted()
        XCTAssertFalse(store.shouldPresent)
        XCTAssertFalse(OnboardingStore(preferences: preferences).shouldPresent)
    }

    func testUpdateFromAnEarlierVersionSkipsTheOnboarding() {
        let preferences = FakePreferences()
        preferences.setString("1.5", PreferenceKeys.whatsNewShownVersion, scope: .standard)
        WhatsNewStore.recordFirstLaunch(preferences: preferences, currentVersion: "1.6", hasAccount: { false })

        XCTAssertFalse(OnboardingStore(preferences: preferences).shouldPresent)
    }

    func testUnrecordedFirstLaunchStaysQuiet() {
        XCTAssertFalse(OnboardingStore(preferences: FakePreferences()).shouldPresent)
    }
}
