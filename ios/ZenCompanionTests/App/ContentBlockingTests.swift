import WebKit
import XCTest

@testable import ZenCompanion

final class BlockListTests: XCTestCase {
    private let sample = """
    [Adblock Plus 2.0]
    ! Version: 202610242150
    ! Title: EasyList
    ! Last modified: 24 Oct 2026 21:50 UTC
    ||ads.example^
    ||tracker.example^$third-party
    ||pixel.example^$script,image
    ||popup.example^$popup
    ||scam.example^$document
    ||one-site.example^$domain=news.example
    ||path.example/ads/
    ##.ad-banner
    @@||ads.example^$domain=shop.example|~other.example
    @@||cdn.ads.example^
    @@||own.tracker.example^$~third-party
    ||own.tracker.example^
    @@||safe.example^$document
    ||widget.example^
    @@||jetzt.de^$generichide
    """

    private lazy var list = BlockList.parse(sample)

    func testReadsTheHeader() {
        XCTAssertEqual(list.version, "202610242150")
        XCTAssertEqual(list.lastModified, "24 Oct 2026 21:50 UTC")
    }

    func testKeepsOnlyDomainRulesItCanApply() {
        XCTAssertEqual(list.anywhere, ["ads.example", "pixel.example", "own.tracker.example", "widget.example"])
        XCTAssertEqual(list.thirdParty, ["tracker.example"])
        XCTAssertEqual(list.ruleCount, 5)
    }

    func testBlocksTheDomainAndItsSubdomains() {
        XCTAssertTrue(list.blocks(requestHost: "ads.example", pageHost: "news.example"))
        XCTAssertTrue(list.blocks(requestHost: "eu.ads.example", pageHost: "news.example"))
        XCTAssertFalse(list.blocks(requestHost: "badads.example", pageHost: "news.example"))
        XCTAssertFalse(list.blocks(requestHost: "popup.example", pageHost: "news.example"))
        XCTAssertFalse(list.blocks(requestHost: "path.example", pageHost: "news.example"))
    }

    func testThirdPartyRulesSpareTheSiteItself() {
        XCTAssertTrue(list.blocks(requestHost: "tracker.example", pageHost: "news.example"))
        XCTAssertFalse(list.blocks(requestHost: "tracker.example", pageHost: "www.tracker.example"))
        XCTAssertFalse(list.blocks(requestHost: "tracker.example", pageHost: nil))
    }

    func testExceptionsUndoBlocks() {
        XCTAssertFalse(list.blocks(requestHost: "ads.example", pageHost: "www.shop.example"))
        XCTAssertTrue(list.blocks(requestHost: "ads.example", pageHost: "other.example"))
        XCTAssertFalse(list.blocks(requestHost: "cdn.ads.example", pageHost: "news.example"))
        XCTAssertFalse(list.blocks(requestHost: "own.tracker.example", pageHost: "app.tracker.example"))
        XCTAssertTrue(list.blocks(requestHost: "own.tracker.example", pageHost: "news.example"))
        XCTAssertFalse(list.blocks(requestHost: "widget.example", pageHost: "safe.example"))
    }

    func testHostsAreCaseAndDotInsensitive() {
        XCTAssertTrue(list.blocks(requestHost: "ADS.Example.", pageHost: "news.example"))
    }

    func testSameSiteHandlesShortSecondLevelDomains() {
        XCTAssertTrue(BlockList.isSameSite("cdn.bbc.co.uk", "www.bbc.co.uk"))
        XCTAssertFalse(BlockList.isSameSite("bbc.co.uk", "itv.co.uk"))
        XCTAssertTrue(BlockList.isSameSite("a.example.com", "b.example.com"))
        XCTAssertFalse(BlockList.isSameSite("example.com", "example.net"))
    }

    func testRuleListJSONIsValidAndOrdered() throws {
        let json = list.contentRuleListJSON()
        let rules = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(json.utf8)) as? [[String: Any]])
        let actions = rules.compactMap { ($0["action"] as? [String: Any])?["type"] as? String }
        XCTAssertEqual(actions.filter { $0 == "block" }.count, 5)
        // Every exception comes after the last block.
        let lastBlock = try XCTUnwrap(actions.lastIndex(of: "block"))
        let firstException = try XCTUnwrap(actions.firstIndex(of: "ignore-previous-rules"))
        XCTAssertGreaterThan(firstException, lastBlock)

        let filters = rules.compactMap { ($0["trigger"] as? [String: Any])?["url-filter"] as? String }
        let adsFilter = try XCTUnwrap(filters.first { $0.contains("ads\\.example") })
        let regex = try NSRegularExpression(pattern: adsFilter)
        func matches(_ url: String) -> Bool {
            regex.firstMatch(in: url, range: NSRange(url.startIndex..., in: url)) != nil
        }
        XCTAssertTrue(matches("https://ads.example/x.js"))
        XCTAssertTrue(matches("https://eu.ads.example:8443/x.js"))
        XCTAssertFalse(matches("https://badads.example/x.js"))
        XCTAssertFalse(matches("https://news.example/ads.example/"))

        let trackerFilter = #"^[^:]+://+([^:/]+\.)?tracker\.example[:/]"#
        let thirdParty = try XCTUnwrap(rules.first { ($0["trigger"] as? [String: Any])?["url-filter"] as? String == trackerFilter })
        XCTAssertEqual((thirdParty["trigger"] as? [String: Any])?["load-type"] as? [String], ["third-party"])
    }

    func testBundledListsParseAndFitWebKit() throws {
        for name in ["easylist", "easyprivacy"] {
            let url = try XCTUnwrap(Bundle.main.url(forResource: name, withExtension: "txt"), name)
            let parsed = BlockList.parse(try String(contentsOf: url, encoding: .utf8))
            XCTAssertGreaterThan(parsed.ruleCount, 10_000, name)
            XCTAssertNotNil(parsed.version, name)
            // WebKit refuses a rule list above 150,000 rules.
            XCTAssertLessThan(parsed.ruleCount + 1_000, 150_000, name)
        }
    }

    /// The only real check of the rule syntax: WebKit compiles both lists.
    @MainActor
    func testBundledListsCompileInWebKit() async throws {
        let store = try XCTUnwrap(WKContentRuleListStore.default())
        for name in ["easylist", "easyprivacy"] {
            let url = try XCTUnwrap(Bundle.main.url(forResource: name, withExtension: "txt"), name)
            let json = BlockList.parse(try String(contentsOf: url, encoding: .utf8)).contentRuleListJSON()
            let identifier = "zsync-test-\(name)"
            let started = Date()
            let compiled: WKContentRuleList? = await withCheckedContinuation { continuation in
                store.compileContentRuleList(forIdentifier: identifier, encodedContentRuleList: json) { list, error in
                    if let error { XCTFail("\(name): \(error)") }
                    continuation.resume(returning: list)
                }
            }
            XCTAssertNotNil(compiled, name)
            print("\(name) compiled in \(String(format: "%.1f", Date().timeIntervalSince(started))) s")
            await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
                store.removeContentRuleList(forIdentifier: identifier) { _ in continuation.resume() }
            }
        }
    }
}

@MainActor
final class ContentBlockingTests: XCTestCase {
    func testEverythingStartsOn() {
        let settings = ContentBlockingSettings()
        for category in BlockCategory.allCases {
            XCTAssertTrue(settings.isOn(category, host: "news.example"))
        }
    }

    func testSiteSwitchesTurnOffOneCategoryForOneSite() {
        let settings = ContentBlockingSettings().withSite(.cookieBanners, host: "www.News.example", on: false)
        XCTAssertFalse(settings.isOn(.cookieBanners, host: "news.example"))
        XCTAssertTrue(settings.isOn(.ads, host: "news.example"))
        XCTAssertTrue(settings.isOn(.cookieBanners, host: "other.example"))
        XCTAssertEqual(settings.cookieBannerSitesOff, ["news.example"])
        XCTAssertTrue(settings.withSite(.cookieBanners, host: "news.example", on: true).isOn(.cookieBanners, host: "news.example"))
    }

    func testASiteCannotTurnOnWhatSettingsTurnedOff() {
        let settings = ContentBlockingSettings().with(.ads, on: false)
        XCTAssertFalse(settings.withSite(.ads, host: "news.example", on: true).isOn(.ads, host: "news.example"))
    }

    func testAllOffForSiteOnlyCountsSwitchesThatAreOn() {
        var settings = ContentBlockingSettings().with(.cookieBanners, on: false)
        settings = settings.withSite(.ads, host: "news.example", on: false)
        XCTAssertFalse(settings.allOff(for: "news.example"))
        settings = settings.withSite(.trackers, host: "news.example", on: false)
        XCTAssertTrue(settings.allOff(for: "news.example"))
        XCTAssertFalse(ContentBlockingSettings(ads: false, trackers: false, cookieBanners: false).allOff(for: "news.example"))
    }

    func testSiteKeysDropWwwAndCase() {
        XCTAssertEqual(blockingSiteKey("WWW.News.Example."), "news.example")
        XCTAssertEqual(blockingSiteKey("en.m.wikipedia.org"), "en.m.wikipedia.org")
    }

    func testCardFollowsThePage() {
        let on = ContentBlockingSettings()
        XCTAssertEqual(BlockingCard.of(settings: on, host: "news.example", blockedCount: 15, showingOriginal: false), .blocked(15))
        XCTAssertEqual(BlockingCard.of(settings: on, host: "news.example", blockedCount: 0, showingOriginal: false), .hidden)
        XCTAssertEqual(BlockingCard.of(settings: on, host: nil, blockedCount: 4, showingOriginal: false), .hidden)
        XCTAssertEqual(BlockingCard.of(settings: on, host: "news.example", blockedCount: 0, showingOriginal: true), .showingOriginal)
        let off = BlockCategory.allCases.reduce(on) { $0.withSite($1, host: "www.news.example", on: false) }
        XCTAssertEqual(BlockingCard.of(settings: off, host: "www.news.example", blockedCount: 0, showingOriginal: false), .offForSite("news.example"))
    }

    func testListsAreDueWeeklyAndAfterTheClockMovesBack() {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        XCTAssertTrue(FilterListUpdates.isDue(lastUpdate: nil, now: now))
        XCTAssertFalse(FilterListUpdates.isDue(lastUpdate: now.addingTimeInterval(-FilterListUpdates.interval + 1), now: now))
        XCTAssertTrue(FilterListUpdates.isDue(lastUpdate: now.addingTimeInterval(-FilterListUpdates.interval), now: now))
        XCTAssertTrue(FilterListUpdates.isDue(lastUpdate: now.addingTimeInterval(60), now: now))
    }

    func testDownloadsMustBeRealFilterLists() {
        XCTAssertNil(FilterListUpdates.accept("<html>Service unavailable</html>"))
        XCTAssertNil(FilterListUpdates.accept("[Adblock Plus 2.0]\n||ads.example^\n"))
        var big = "[Adblock Plus 2.0]\n! Version: 1\n"
        for index in 0..<FilterListUpdates.minRules { big += "||ads\(index).example^\n" }
        XCTAssertNotNil(FilterListUpdates.accept(big))
    }

    func testConsentScriptSkipsTheSitesTurnedOff() {
        let script = consentScriptSource(bundle: "BUNDLE();", skip: ["b.example", "a.example", "x\"y"])
        XCTAssertTrue(script.contains(#"var skip = ["a.example","b.example","xy"];"#))
        XCTAssertTrue(script.contains("BUNDLE();"))
        XCTAssertTrue(script.trimmingCharacters(in: .whitespacesAndNewlines).hasSuffix("})();"))
    }

    func testCaptionUsesTheListDate() {
        XCTAssertEqual(FilterListUpdates.displayDate("24 Oct 2026 21:50 UTC"), "24 Oct 2026")
        XCTAssertNil(FilterListUpdates.displayDate("soon"))
    }

    func testOriginalPageTurnsEverythingOff() {
        let blocker = ContentBlocker(preferences: MemoryPreferences())
        let configuration = blocker.configuration(host: "news.example", original: true)
        XCTAssertEqual(configuration.ruleLists, [])
        XCTAssertNil(configuration.consentSkip)
    }
}

@MainActor
private final class MemoryPreferences: PreferencesStoring {
    private var strings: [String: String] = [:]
    private var bools: [String: Bool] = [:]

    func string(_ key: String, scope: PreferenceScope) -> String? { strings[key] }
    func bool(_ key: String, scope: PreferenceScope) -> Bool { bools[key] ?? false }
    func hasObject(_ key: String, scope: PreferenceScope) -> Bool { strings[key] != nil || bools[key] != nil }
    func setString(_ value: String?, _ key: String, scope: PreferenceScope) { strings[key] = value }
    func setBool(_ value: Bool, _ key: String, scope: PreferenceScope) { bools[key] = value }
    func remove(_ key: String, scope: PreferenceScope) {
        strings[key] = nil
        bools[key] = nil
    }
}
