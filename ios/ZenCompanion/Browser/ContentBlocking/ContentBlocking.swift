import Foundation
import Observation
import WebKit

/// What the quick browser can block. Each has its own switch.
enum BlockCategory: String, CaseIterable, Sendable {
    case ads
    case trackers
    case cookieBanners = "cookie_banners"
}

/// The key a site's own switches are stored under: the host without `www.`.
func blockingSiteKey(_ host: String) -> String {
    var key = host.lowercased()
    while key.hasSuffix(".") { key.removeLast() }
    if key.hasPrefix("www.") { key.removeFirst(4) }
    return key
}

/// The three switches in Settings, and the sites where one of them is turned
/// off from the browser menu. A site can only turn off what Settings has on.
struct ContentBlockingSettings: Equatable, Sendable {
    var ads = true
    var trackers = true
    var cookieBanners = true
    var offForSites: [BlockCategory: Set<String>] = [:]

    func isOn(_ category: BlockCategory) -> Bool {
        switch category {
        case .ads: return ads
        case .trackers: return trackers
        case .cookieBanners: return cookieBanners
        }
    }

    /// On in Settings and not turned off for the site of `host`.
    func isOn(_ category: BlockCategory, host: String?) -> Bool {
        guard isOn(category) else { return false }
        guard let host else { return true }
        return !(offForSites[category] ?? []).contains(blockingSiteKey(host))
    }

    func with(_ category: BlockCategory, on: Bool) -> ContentBlockingSettings {
        var copy = self
        switch category {
        case .ads: copy.ads = on
        case .trackers: copy.trackers = on
        case .cookieBanners: copy.cookieBanners = on
        }
        return copy
    }

    func withSite(_ category: BlockCategory, host: String, on: Bool) -> ContentBlockingSettings {
        var copy = self
        let site = blockingSiteKey(host)
        var sites = copy.offForSites[category] ?? []
        if on { sites.remove(site) } else { sites.insert(site) }
        copy.offForSites[category] = sites
        return copy
    }

    /// Every switch that is on in Settings is off for this site.
    func allOff(for host: String) -> Bool {
        let active = BlockCategory.allCases.filter { isOn($0) }
        return !active.isEmpty && !active.contains { isOn($0, host: host) }
    }

    var cookieBannerSitesOff: Set<String> { offForSites[.cookieBanners] ?? [] }
}

/// What the card at the top of the browser menu says.
enum BlockingCard: Equatable {
    /// Something was blocked on this page.
    case blocked(Int)
    /// The page was reloaded once with blocking off.
    case showingOriginal
    /// Every switch that is on in Settings is off for this site.
    case offForSite(String)
    case hidden

    static func of(settings: ContentBlockingSettings, host: String?, blockedCount: Int, showingOriginal: Bool) -> BlockingCard {
        guard let host, !host.isEmpty else { return .hidden }
        if showingOriginal { return .showingOriginal }
        if settings.allOff(for: host) { return .offForSite(blockingSiteKey(host)) }
        if blockedCount > 0 { return .blocked(blockedCount) }
        return .hidden
    }
}

/// One of the two filter lists, as shipped and as downloaded.
enum FilterList: String, CaseIterable, Sendable {
    case easylist
    case easyprivacy

    var url: URL {
        switch self {
        case .easylist: return URL(string: "https://easylist.to/easylist/easylist.txt")!
        case .easyprivacy: return URL(string: "https://easylist.to/easylist/easyprivacy.txt")!
        }
    }

    var category: BlockCategory {
        switch self {
        case .easylist: return .ads
        case .easyprivacy: return .trackers
        }
    }
}

/// When to download fresh lists, and which downloads to trust.
enum FilterListUpdates {
    static let interval: TimeInterval = 7 * 24 * 60 * 60
    /// Well above the real lists, so a broken server can't fill the phone.
    static let maxBytes = 16 * 1024 * 1024
    /// Both real lists have tens of thousands; far fewer means a bad download.
    static let minRules = 1_000

    /// A clock set back (or a date in the future) counts as due.
    static func isDue(lastUpdate: Date?, now: Date) -> Bool {
        guard let lastUpdate else { return true }
        return now.timeIntervalSince(lastUpdate) >= interval || lastUpdate > now
    }

    /// The parsed list, or nil when the download isn't a usable filter list.
    static func accept(_ text: String) -> BlockList? {
        guard text.hasPrefix("[Adblock") else { return nil }
        let list = BlockList.parse(text)
        return list.ruleCount >= minRules ? list : nil
    }

    /// `24 Oct 2026 21:50 UTC` from the list header becomes `24 Oct 2026`.
    static func displayDate(_ lastModified: String) -> String? {
        let parts = lastModified.split(separator: " ").prefix(3)
        return parts.count == 3 ? parts.joined(separator: " ") : nil
    }
}

/// Wraps the autoconsent bundle so it does nothing on the sites in `skip`.
/// The check uses the top page's host, which frames read from `ancestorOrigins`.
func consentScriptSource(bundle: String, skip: Set<String>) -> String {
    let sites = skip.sorted()
        .map { "\"" + $0.replacingOccurrences(of: "\\", with: "").replacingOccurrences(of: "\"", with: "") + "\"" }
        .joined(separator: ",")
    return """
    (function () {
      var skip = [\(sites)];
      var host = location.hostname;
      try {
        var a = location.ancestorOrigins;
        if (a && a.length) host = new URL(a[a.length - 1]).hostname;
      } catch (e) {}
      host = host.toLowerCase().replace(/^www\\./, '');
      if (skip.indexOf(host) !== -1) return;
    \(bundle)
    })();
    """
}

/// What one web view should have: which rule lists, and which sites the
/// cookie banner script skips (nil: no script at all).
struct BlockingConfiguration: Equatable {
    var ruleLists: [BlockCategory]
    var consentSkip: Set<String>?
}

/// The app's content blocker: the switches, the parsed lists and their
/// compiled WebKit rule lists. Lists load and compile in the background at
/// app start; until then nothing is blocked, which only matters for a page
/// opened in the first moments after the first launch.
@MainActor
@Observable
final class ContentBlocker {
    static let shared = ContentBlocker()

    private(set) var settings = ContentBlockingSettings()
    /// False until the setup question was answered.
    private(set) var asked = true
    /// The ad list's own date, for the caption in Settings.
    private(set) var lastModified: String?

    @ObservationIgnored private var lists: [BlockCategory: BlockList] = [:]
    @ObservationIgnored private var ruleLists: [BlockCategory: WKContentRuleList] = [:]
    @ObservationIgnored private var consentBundle: String?
    @ObservationIgnored private let preferences: PreferencesStoring
    @ObservationIgnored private var started = false

    init(preferences: PreferencesStoring = AppServices.preferences) {
        self.preferences = preferences
    }

    func start() {
        guard !started else { return }
        started = true
        settings = loadSettings()
        asked = preferences.bool(Keys.asked, scope: .standard)
        consentBundle = Bundle.main.url(forResource: "autoconsent", withExtension: "js")
            .flatMap { try? String(contentsOf: $0, encoding: .utf8) }
        Task {
            await loadLists()
            let last = preferences.string(Keys.updatedAt, scope: .standard).flatMap(Double.init).map(Date.init(timeIntervalSince1970:))
            // Nothing to keep current while both list switches are off.
            let listsInUse = settings.ads || settings.trackers
            if listsInUse, FilterListUpdates.isDue(lastUpdate: last, now: Date()), await Self.downloadLists() {
                preferences.setString(String(Date().timeIntervalSince1970), Keys.updatedAt, scope: .standard)
                await loadLists()
            }
        }
    }

    func update(_ settings: ContentBlockingSettings) {
        self.settings = settings
        saveSettings(settings)
    }

    /// The answer to the setup question: the three switches, saved at once.
    func answerSetup(_ settings: ContentBlockingSettings) {
        update(settings)
        preferences.setBool(true, Keys.asked, scope: .standard)
        asked = true
    }

    /// True when a resource the page loaded is on an active list. Used for the
    /// count on iOS, where WebKit doesn't report what it blocked.
    func blocks(_ url: URL, pageHost: String?) -> Bool {
        guard let host = url.host, ["http", "https"].contains(url.scheme?.lowercased() ?? "") else { return false }
        return lists.contains { entry in
            settings.isOn(entry.key, host: pageHost) && entry.value.blocks(requestHost: host, pageHost: pageHost)
        }
    }

    /// What a web view needs for a page on `host`; `original` turns blocking off.
    func configuration(host: String?, original: Bool) -> BlockingConfiguration {
        if original { return BlockingConfiguration(ruleLists: [], consentSkip: nil) }
        let wanted = [BlockCategory.ads, .trackers].filter { ruleLists[$0] != nil && settings.isOn($0, host: host) }
        let skip = settings.isOn(.cookieBanners) && consentBundle != nil ? settings.cookieBannerSitesOff : nil
        return BlockingConfiguration(ruleLists: wanted, consentSkip: skip)
    }

    /// Puts a configuration on a web view's content controller. The caller
    /// adds its own scripts afterwards, because this clears them.
    func apply(_ configuration: BlockingConfiguration, to controller: WKUserContentController) {
        controller.removeAllContentRuleLists()
        for category in configuration.ruleLists {
            if let list = ruleLists[category] { controller.add(list) }
        }
        controller.removeAllUserScripts()
        if let skip = configuration.consentSkip, let consentBundle {
            controller.addUserScript(WKUserScript(
                source: consentScriptSource(bundle: consentBundle, skip: skip),
                injectionTime: .atDocumentStart,
                forMainFrameOnly: false,
                in: .page
            ))
        }
    }

    // MARK: - Lists

    private func loadLists() async {
        var loaded: [BlockCategory: BlockList] = [:]
        var compiled: [BlockCategory: WKContentRuleList] = [:]
        for list in FilterList.allCases {
            guard let parsed = await Task.detached(priority: .utility, operation: { Self.readList(list) }).value else { continue }
            loaded[list.category] = parsed
            if let ruleList = await Self.ruleList(for: list, parsed) {
                compiled[list.category] = ruleList
            }
        }
        lists = loaded
        ruleLists = compiled
        lastModified = loaded[.ads]?.lastModified
    }

    /// The downloaded copy when it is usable, otherwise the one in the app.
    private nonisolated static func readList(_ list: FilterList) -> BlockList? {
        if let text = try? String(contentsOf: downloadedFile(list), encoding: .utf8), let parsed = FilterListUpdates.accept(text) {
            return parsed
        }
        guard let url = Bundle.main.url(forResource: list.rawValue, withExtension: "txt"),
              let text = try? String(contentsOf: url, encoding: .utf8) else { return nil }
        return BlockList.parse(text)
    }

    /// Looks up the compiled list for this version, compiling it when needed.
    /// Older versions are removed so the store doesn't grow every week.
    private static func ruleList(for list: FilterList, _ parsed: BlockList) async -> WKContentRuleList? {
        guard let store = WKContentRuleListStore.default() else { return nil }
        let prefix = "zsync-\(list.rawValue)-"
        let identifier = prefix + (parsed.version ?? "bundled")
        if let existing = await store.lookUp(identifier) {
            return existing
        }
        let json = await Task.detached(priority: .utility) { parsed.contentRuleListJSON() }.value
        let compiled = await store.compile(identifier, json)
        if compiled != nil {
            for old in await store.availableIdentifiers() where old.hasPrefix(prefix) && old != identifier {
                await store.remove(old)
            }
        }
        return compiled
    }

    /// Fetches both lists; keeps the old files unless both downloads are good.
    private nonisolated static func downloadLists() async -> Bool {
        var fetched: [FilterList: String] = [:]
        for list in FilterList.allCases {
            var request = URLRequest(url: list.url, timeoutInterval: 30)
            request.cachePolicy = .reloadIgnoringLocalCacheData
            guard let (data, response) = try? await URLSession.shared.data(for: request),
                  (response as? HTTPURLResponse)?.statusCode == 200,
                  data.count <= FilterListUpdates.maxBytes,
                  let text = String(data: data, encoding: .utf8),
                  FilterListUpdates.accept(text) != nil else { return false }
            fetched[list] = text
        }
        let folder = downloadedFile(.easylist).deletingLastPathComponent()
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        for (list, text) in fetched {
            try? text.write(to: downloadedFile(list), atomically: true, encoding: .utf8)
        }
        return true
    }

    private nonisolated static func downloadedFile(_ list: FilterList) -> URL {
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return support.appendingPathComponent("content-blocking", isDirectory: true)
            .appendingPathComponent("\(list.rawValue).txt")
    }

    // MARK: - Settings storage

    private enum Keys {
        static let asked = "content_blocking_setup_asked"
        static let updatedAt = "content_blocking_lists_updated_at"
        static func on(_ category: BlockCategory) -> String { "content_blocking_\(category.rawValue)" }
        static func sites(_ category: BlockCategory) -> String { "content_blocking_off_for_sites_\(category.rawValue)" }
    }

    private func loadSettings() -> ContentBlockingSettings {
        var loaded = ContentBlockingSettings()
        for category in BlockCategory.allCases {
            let key = Keys.on(category)
            let on = preferences.hasObject(key, scope: .standard) ? preferences.bool(key, scope: .standard) : true
            loaded = loaded.with(category, on: on)
            let sites = preferences.string(Keys.sites(category), scope: .standard)?
                .split(separator: "\n").map(String.init) ?? []
            loaded.offForSites[category] = Set(sites)
        }
        return loaded
    }

    private func saveSettings(_ settings: ContentBlockingSettings) {
        for category in BlockCategory.allCases {
            preferences.setBool(settings.isOn(category), Keys.on(category), scope: .standard)
            let sites = (settings.offForSites[category] ?? []).sorted().joined(separator: "\n")
            preferences.setString(sites, Keys.sites(category), scope: .standard)
        }
    }
}

/// Completion-handler calls wrapped for async use; errors only mean "no list".
private extension WKContentRuleListStore {
    func lookUp(_ identifier: String) async -> WKContentRuleList? {
        await withCheckedContinuation { continuation in
            lookUpContentRuleList(forIdentifier: identifier) { list, _ in continuation.resume(returning: list) }
        }
    }

    func compile(_ identifier: String, _ json: String) async -> WKContentRuleList? {
        await withCheckedContinuation { continuation in
            compileContentRuleList(forIdentifier: identifier, encodedContentRuleList: json) { list, error in
                if let error { NSLog("Content rule list %@ failed to compile: %@", identifier, error.localizedDescription) }
                continuation.resume(returning: list)
            }
        }
    }

    func remove(_ identifier: String) async {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            removeContentRuleList(forIdentifier: identifier) { _ in continuation.resume() }
        }
    }

    func availableIdentifiers() async -> [String] {
        await withCheckedContinuation { continuation in
            getAvailableContentRuleListIdentifiers { continuation.resume(returning: $0 ?? []) }
        }
    }
}
