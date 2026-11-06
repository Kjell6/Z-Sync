import Foundation
import UIKit
import WebKit

/// What a page reports to the open pages it belongs to.
@MainActor
protocol MiniBrowserPageHost: AnyObject {
    /// `target=_blank` or `window.open`. Returns the web view of the new page,
    /// built on WebKit's [configuration].
    func openPopup(from model: MiniBrowserModel, url: URL?, configuration: WKWebViewConfiguration) -> WKWebView?
    func openInNewPage(_ url: URL, from model: MiniBrowserModel)
    /// `window.close()`.
    func pageRequestedClose(_ model: MiniBrowserModel)
    func pageDidChange(_ model: MiniBrowserModel)
    func pageDidFinishLoading(_ model: MiniBrowserModel)
    func pageWasPinned(_ model: MiniBrowserModel, url: URL)
    /// Returns true when the page was out of sight and simply went to sleep.
    func pageProcessDidTerminate(_ model: MiniBrowserModel) -> Bool
}

/// One page open on this phone. Pages stay open like in any browser, and on
/// disk, until they are closed; with "Keep only chosen pages" on, only those the
/// user keeps do. Neither kind ever enters Zen's data.
@MainActor
@Observable
final class OpenPage: Identifiable {
    let id: String
    let model: MiniBrowserModel
    /// Opened from one of a space's own tabs: it stays only once the user
    /// browses away, so the list does not fill with copies of pinned tabs.
    let openedFromSpace: Bool
    /// The page a link opened this one from. Back returns there once this
    /// page's own history runs out.
    var openerID: String?
    /// When the page was saved to stay; nil until then.
    var keptAt: Date?
    var lastUsedAt: Date
    /// The page as it last looked, for the list and the address bar swipe.
    var shot: UIImage?
    /// The user stopped keeping this page, so it goes with the browser.
    @ObservationIgnored var released = false
    /// WebKit's history blob while the page has no web view.
    @ObservationIgnored var history: Any?

    var isKept: Bool { keptAt != nil }
    var url: URL? { model.pageURL }
    var title: String {
        guard let url else { return String(localized: "kept.new_page") }
        return PageTitle.headline(model.pageTitle, url: url)
    }

    init(
        id: String = UUID().uuidString,
        model: MiniBrowserModel,
        openedFromSpace: Bool = false,
        openerID: String? = nil,
        keptAt: Date? = nil,
        lastUsedAt: Date
    ) {
        self.id = id
        self.model = model
        self.openedFromSpace = openedFromSpace
        self.openerID = openerID
        self.keptAt = keptAt
        self.lastUsedAt = lastUsedAt
    }
}

/// How long a page stays once the user has left it. A month by default.
enum KeptPageLifetime: String, CaseIterable, Identifiable {
    case never
    case day
    case week
    case month

    var id: String { rawValue }

    var title: String {
        switch self {
        case .never: return String(localized: "settings.kept_lifetime.never")
        case .day: return String(localized: "settings.kept_lifetime.day")
        case .week: return String(localized: "settings.kept_lifetime.week")
        case .month: return String(localized: "settings.kept_lifetime.month")
        }
    }

    /// The short note in the overview.
    var hint: String {
        switch self {
        case .never: return String(localized: "kept.hint.never")
        case .day: return String(localized: "kept.hint.day")
        case .week: return String(localized: "kept.hint.week")
        case .month: return String(localized: "kept.hint.month")
        }
    }

    var interval: TimeInterval? {
        switch self {
        case .never: return nil
        case .day: return 86_400
        case .week: return 7 * 86_400
        case .month: return 30 * 86_400
        }
    }

    @MainActor
    static func stored(in preferences: PreferencesStoring) -> KeptPageLifetime {
        preferences.string(PreferenceKeys.keptPageLifetime, scope: .standard)
            .flatMap(KeptPageLifetime.init(rawValue:)) ?? .month
    }
}

/// What ending a browser session does with a page.
enum LeaveAction: Equatable {
    case update
    case keep
    case discard
}

enum KeptPagesPolicy {
    /// A kept page stays and takes its latest address. Any other page stays too,
    /// unless "Keep only chosen pages" is on. Even then a page is not kept
    /// when there is nothing worth reopening (no web address, a failed load),
    /// when it was just pinned into a space, when the user stopped keeping it
    /// during this visit, or when it is a space's tab that was opened and left
    /// without browsing away from it.
    static func onLeave(
        isKept: Bool,
        keepEveryPage: Bool,
        url: URL?,
        failed: Bool,
        pinnedHere: Bool,
        releasedHere: Bool,
        openedFromSpace: Bool,
        browsedAway: Bool
    ) -> LeaveAction {
        if isKept { return .update }
        guard keepEveryPage else { return .discard }
        guard let scheme = url?.scheme?.lowercased(), scheme == "https" || scheme == "http" else { return .discard }
        if failed || pinnedHere || releasedHere { return .discard }
        if openedFromSpace && !browsedAway { return .discard }
        return .keep
    }
}

/// Every page open on this phone, the one on screen and what stays on disk.
///
/// The pages live here and not in the browser view, so they outlast it, the way
/// tabs outlast a browser window. Pages that were never saved to stay go when
/// the browser closes, and the setting decides which ones those are. Only the most
/// recently used pages hold a web view; the others sleep with their history and
/// wake when they are shown. Kept pages live in Application Support as one JSON
/// file plus a picture and a history file per page, and go on sign-out.
@MainActor
@Observable
final class OpenPagesStore: MiniBrowserPageHost {
    static let shared: OpenPagesStore = {
        let store = OpenPagesStore(
            directory: FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
                .appendingPathComponent("OpenPages", isDirectory: true)
        )
        store.observeSystem()
        return store
    }()

    /// Pages with a live web view, the one on screen included. A live page
    /// comes back instantly with its scripts still running; each one costs a
    /// web content process.
    static let liveLimit = 4

    /// Oldest first; new pages join at the end, or right after their opener.
    private(set) var pages: [OpenPage] = []
    /// The page on screen; nil while the browser is closed.
    private(set) var activeID: String?

    /// Whether this account can write normal tabs. Follows the synced browser.
    @ObservationIgnored var allowsNormalTabs = false {
        didSet { pages.forEach { $0.model.allowsNormalTabs = allowsNormalTabs } }
    }

    @ObservationIgnored private let directory: URL
    @ObservationIgnored private let preferences: PreferencesStoring
    @ObservationIgnored private let now: () -> Date
    @ObservationIgnored private let makeModel: @MainActor (URL?) -> MiniBrowserModel
    @ObservationIgnored private var observers: [NSObjectProtocol] = []

    init(
        directory: URL,
        preferences: PreferencesStoring = AppServices.preferences,
        now: @escaping () -> Date = Date.init,
        makeModel: @escaping @MainActor (URL?) -> MiniBrowserModel = { MiniBrowserModel(initialURL: $0) }
    ) {
        self.directory = directory
        self.preferences = preferences
        self.now = now
        self.makeModel = makeModel
        pages = Self.read(from: directory).map { saved in
            let model = makeModel(saved.url)
            model.restore(title: saved.title)
            let page = OpenPage(id: saved.id, model: model, keptAt: saved.keptAt, lastUsedAt: saved.lastUsedAt)
            model.host = self
            return page
        }
        closeExpired()
    }

    // MARK: - Reading

    var active: OpenPage? {
        guard let activeID else { return nil }
        return page(activeID)
    }

    func page(_ id: String) -> OpenPage? {
        pages.first { $0.id == id }
    }

    /// The pages either side of the active one, for the address bar swipe.
    var neighbours: (previous: OpenPage?, next: OpenPage?) {
        guard let activeID, let index = pages.firstIndex(where: { $0.id == activeID }) else { return (nil, nil) }
        return (
            index > 0 ? pages[index - 1] : nil,
            index < pages.count - 1 ? pages[index + 1] : nil
        )
    }

    /// Keeping a page is a choice only with "Keep only chosen pages" on; the
    /// Keep controls exist for that mode alone.
    var keepsOnlyChosenPages: Bool {
        preferences.bool(PreferenceKeys.keepOnlyChosenPages, scope: .standard)
    }

    /// What the overview says will happen to the pages.
    var lifetimeHint: String {
        KeptPageLifetime.stored(in: preferences).hint
    }

    func canKeep(_ page: OpenPage) -> Bool {
        if page.isKept { return true }
        guard page.model.pageError == nil else { return false }
        return Self.isWebAddress(page.url)
    }

    // MARK: - Opening and switching

    /// Opens a new page and puts it on screen.
    @discardableResult
    func open(url: URL?, title: String? = nil, openedFromSpace: Bool = false) -> OpenPage {
        let model = makeModel(url)
        if let title { model.restore(title: title) }
        return insert(OpenPage(model: model, openedFromSpace: openedFromSpace, lastUsedAt: now()), after: nil)
    }

    private func insert(_ page: OpenPage, after opener: OpenPage?) -> OpenPage {
        page.model.host = self
        page.model.allowsNormalTabs = allowsNormalTabs
        if let opener, let index = pages.firstIndex(where: { $0.id == opener.id }) {
            pages.insert(page, at: index + 1)
        } else {
            pages.append(page)
        }
        activate(page.id)
        return page
    }

    /// Puts a page on screen and leaves the one that was there.
    func activate(_ id: String) {
        guard let page = page(id) else { return }
        if let current = active, current.id != id {
            leaveScreen(current)
        }
        page.lastUsedAt = now()
        activeID = id
        wake(page)
        trimLivePages()
        persist()
    }

    private func leaveScreen(_ page: OpenPage) {
        page.model.commitPendingPinSave()
        page.model.didLeaveScreen()
        page.lastUsedAt = now()
        saveHistory(of: page)
    }

    private func wake(_ page: OpenPage) {
        guard page.model.webView == nil else { return }
        page.model.wake(history: page.history ?? storedHistory(of: page))
        page.history = nil
    }

    /// Wakes the pages either side of the active one, so they can follow the
    /// finger as real pages rather than as pictures.
    func prepareNeighbours() {
        let around = neighbours
        for page in [around.previous, around.next].compactMap({ $0 }) {
            wake(page)
        }
    }

    private func putToSleep(_ page: OpenPage) {
        if let history = page.model.sleep() {
            page.history = history
            writeHistory(history, of: page)
        }
    }

    /// Keeps the most recently used pages live.
    private func trimLivePages(limit: Int = OpenPagesStore.liveLimit) {
        let live = pages
            .filter { $0.model.webView != nil && $0.id != activeID }
            .sorted { $0.lastUsedAt > $1.lastUsedAt }
        for page in live.dropFirst(max(0, limit - 1)) {
            putToSleep(page)
        }
    }

    /// Puts a page where another one is. The swipe follows the same order.
    func move(_ id: String, onto target: String) {
        guard id != target,
              let from = pages.firstIndex(where: { $0.id == id }),
              let to = pages.firstIndex(where: { $0.id == target }) else { return }
        pages.move(fromOffsets: IndexSet(integer: from), toOffset: to > from ? to + 1 : to)
        persist()
    }

    // MARK: - Closing

    /// Ends a page. Back on screen comes the page that opened it, otherwise
    /// its neighbour. Returns false when no page is left on screen.
    @discardableResult
    func close(_ id: String) -> Bool {
        guard let index = pages.firstIndex(where: { $0.id == id }) else { return activeID != nil }
        let page = pages[index]
        let wasActive = activeID == id
        page.model.commitPendingPinSave()
        page.model.sleep()
        pages.remove(at: index)
        removeFiles(of: page)
        for other in pages where other.openerID == id {
            other.openerID = page.openerID
        }
        if wasActive {
            activeID = nil
            let next = page.openerID.flatMap(self.page) ?? (pages.isEmpty ? nil : pages[min(index, pages.count - 1)])
            if let next {
                activate(next.id)
            }
        }
        persist()
        return activeID != nil
    }

    /// Ends every page. The browser, if it was open, closes with them.
    func closeAll() {
        for page in pages {
            page.model.commitPendingPinSave()
            page.model.sleep()
        }
        pages = []
        activeID = nil
        try? FileManager.default.removeItem(at: directory)
    }

    /// Pages that stay let go of their web views; the others go.
    func endBrowserSession() {
        let onScreen = activeID
        activeID = nil
        for page in pages {
            page.model.commitPendingPinSave()
            page.model.didLeaveScreen()
            // Only what was in use: the others keep counting down.
            if page.id == onScreen { page.lastUsedAt = now() }
            saveIfItStays(page)
            guard page.isKept else {
                page.model.sleep()
                removeFiles(of: page)
                continue
            }
            putToSleep(page)
        }
        pages.removeAll { !$0.isKept }
        // A page's way back ends with the page it led to.
        for page in pages where page.openerID.flatMap(self.page) == nil {
            page.openerID = nil
        }
        persist()
    }

    /// Pages past the chosen lifetime close by themselves. The page on
    /// screen is in use, so it is never one of them.
    func closeExpired() {
        guard let interval = KeptPageLifetime.stored(in: preferences).interval else { return }
        let deadline = now().addingTimeInterval(-interval)
        for page in pages where page.isKept && page.id != activeID && page.lastUsedAt <= deadline {
            close(page.id)
        }
    }

    // MARK: - Keeping

    /// Keep Page / Kept. Tapping again lets the page go with the browser.
    func toggleKeep(_ id: String) {
        guard let page = page(id) else { return }
        if page.isKept {
            release(page)
            AppServices.haptics.selectionChanged()
        } else if canKeep(page) {
            keep(page)
            AppServices.haptics.pinSucceeded()
        }
    }

    private func keep(_ page: OpenPage) {
        guard !page.isKept else { return }
        page.keptAt = now()
        page.released = false
        if let shot = page.shot { writeShot(shot, of: page) }
        saveHistory(of: page)
        persist()
    }

    private func release(_ page: OpenPage, byUser: Bool = true) {
        page.keptAt = nil
        if byUser { page.released = true }
        removeFiles(of: page)
        persist()
    }

    private func saveIfItStays(_ page: OpenPage) {
        let model = page.model
        let action = KeptPagesPolicy.onLeave(
            isKept: page.isKept,
            keepEveryPage: !keepsOnlyChosenPages,
            url: page.url,
            failed: model.pageError != nil,
            pinnedHere: PageTitle.sameDocument(model.pinnedURL, page.url),
            releasedHere: page.released,
            openedFromSpace: page.openedFromSpace,
            browsedAway: model.webView?.canGoBack ?? model.sleptWithBackHistory
        )
        if action == .keep { keep(page) }
    }

    // MARK: - Page reports

    func openPopup(from model: MiniBrowserModel, url: URL?, configuration: WKWebViewConfiguration) -> WKWebView? {
        let opener = pages.first { $0.model === model }
        let popup = makeModel(url ?? URL(string: "about:blank"))
        let webView = MiniBrowserWebDriver.attach(to: popup, configuration: configuration)
        let page = OpenPage(model: popup, openerID: opener?.id, lastUsedAt: now())
        _ = insert(page, after: opener)
        return webView
    }

    func openInNewPage(_ url: URL, from model: MiniBrowserModel) {
        let opener = pages.first { $0.model === model }
        let page = OpenPage(model: makeModel(url), openerID: opener?.id, lastUsedAt: now())
        _ = insert(page, after: opener)
    }

    func pageRequestedClose(_ model: MiniBrowserModel) {
        guard let page = pages.first(where: { $0.model === model }) else { return }
        close(page.id)
    }

    func pageDidChange(_ model: MiniBrowserModel) {
        guard pages.contains(where: { $0.model === model && $0.isKept }) else { return }
        persist()
    }

    /// A page that stays is saved as soon as it has loaded, not only when the
    /// browser closes: the app can end at any moment without saying so.
    func pageDidFinishLoading(_ model: MiniBrowserModel) {
        guard let page = pages.first(where: { $0.model === model }) else { return }
        saveIfItStays(page)
        saveHistory(of: page)
        guard page.id == activeID else { return }
        Task { await capture(page) }
    }

    func pageWasPinned(_ model: MiniBrowserModel, url: URL) {
        guard let page = pages.first(where: { $0.model === model }), page.isKept else { return }
        // Still pinned? A page that moved on stays what it was.
        guard PageTitle.sameDocument(url, page.url) else { return }
        if page.id == activeID {
            // Still on screen; it stays if the user browses on from here.
            release(page, byUser: false)
        } else {
            close(page.id)
        }
    }

    func pageProcessDidTerminate(_ model: MiniBrowserModel) -> Bool {
        guard let page = pages.first(where: { $0.model === model }), page.id != activeID else { return false }
        model.sleep()
        return true
    }

    // MARK: - Pictures

    /// Pictures the page as it looks now. Only the web view is drawn, so the
    /// bar and menus never end up in it, and it has to be on screen. The page
    /// keeps a small copy; the full picture comes back for the zoom into the
    /// overview and is not held on to.
    @discardableResult
    func capture(_ page: OpenPage) async -> UIImage? {
        guard let webView = page.model.webView, webView.window != nil,
              webView.bounds.width > 0, webView.bounds.height > 0 else { return nil }
        guard let image = try? await webView.takeSnapshot(configuration: WKSnapshotConfiguration()) else { return nil }
        let small = Self.shrunk(image)
        page.shot = small
        // The page may have been closed while the snapshot was on its way.
        if page.isKept, self.page(page.id) != nil { writeShot(small, of: page) }
        return image
    }

    private static func shrunk(_ image: UIImage) -> UIImage {
        guard image.size.width > shotWidth else { return image }
        let size = CGSize(width: shotWidth, height: image.size.height * shotWidth / image.size.width)
        return UIGraphicsImageRenderer(size: size).image { _ in
            image.draw(in: CGRect(origin: .zero, size: size))
        }
    }

    func loadShot(of page: OpenPage) async {
        guard page.shot == nil else { return }
        let file = fileURL(page.id, "jpg")
        let data = await Task.detached(priority: .utility) { try? Data(contentsOf: file) }.value
        if page.shot == nil, let data { page.shot = UIImage(data: data) }
    }

    private static let shotWidth = 240.0

    private func writeShot(_ image: UIImage, of page: OpenPage) {
        guard let data = image.jpegData(compressionQuality: 0.72) else { return }
        ensureDirectory()
        let file = fileURL(page.id, "jpg")
        Task.detached(priority: .utility) { try? data.write(to: file, options: .atomic) }
    }

    // MARK: - History

    private func saveHistory(of page: OpenPage) {
        // A sleeping page holds what its web view gave up.
        guard let history = page.model.webView?.interactionState ?? page.history else { return }
        page.history = history
        writeHistory(history, of: page)
    }

    private func writeHistory(_ history: Any, of page: OpenPage) {
        guard page.isKept, let data = history as? Data else { return }
        ensureDirectory()
        try? data.write(to: fileURL(page.id, "state"), options: .atomic)
    }

    private func storedHistory(of page: OpenPage) -> Any? {
        guard page.isKept else { return nil }
        return try? Data(contentsOf: fileURL(page.id, "state"))
    }

    // MARK: - System

    private func observeSystem() {
        let center = NotificationCenter.default
        observers.append(center.addObserver(
            forName: UIApplication.didEnterBackgroundNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated { self?.didEnterBackground() }
        })
        observers.append(center.addObserver(
            forName: UIApplication.didReceiveMemoryWarningNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated { self?.trimLivePages(limit: 1) }
        })
    }

    /// The app may not come back, so what would stay on close is saved now.
    private func didEnterBackground() {
        for page in pages {
            saveIfItStays(page)
            if page.id == activeID { page.lastUsedAt = now() }
            saveHistory(of: page)
        }
        persist()
    }

    // MARK: - Files

    private func fileURL(_ id: String, _ suffix: String) -> URL {
        directory.appendingPathComponent("\(id).\(suffix)")
    }

    private func removeFiles(of page: OpenPage) {
        try? FileManager.default.removeItem(at: fileURL(page.id, "jpg"))
        try? FileManager.default.removeItem(at: fileURL(page.id, "state"))
    }

    /// Never in a device backup: the pages are local by promise.
    private func ensureDirectory() {
        guard !FileManager.default.fileExists(atPath: directory.path) else { return }
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var target = directory
        try? target.setResourceValues(values)
    }

    private func persist() {
        let saved = pages.compactMap { page -> SavedPage? in
            guard let keptAt = page.keptAt, let url = page.url else { return nil }
            return SavedPage(id: page.id, url: url, title: page.model.pageTitle, keptAt: keptAt, lastUsedAt: page.lastUsedAt)
        }
        ensureDirectory()
        guard let data = try? JSONEncoder().encode(saved) else { return }
        // The list in memory still holds; the next write tries again.
        try? data.write(to: directory.appendingPathComponent(Self.fileName), options: .atomic)
    }

    private struct SavedPage: Codable {
        let id: String
        let url: URL
        let title: String
        let keptAt: Date
        let lastUsedAt: Date
    }

    private static let fileName = "pages.json"

    private static func read(from directory: URL) -> [SavedPage] {
        guard let data = try? Data(contentsOf: directory.appendingPathComponent(fileName)) else { return [] }
        // A damaged file loses the list, not the app.
        return (try? JSONDecoder().decode([SavedPage].self, from: data)) ?? []
    }

    private static func isWebAddress(_ url: URL?) -> Bool {
        guard let scheme = url?.scheme?.lowercased() else { return false }
        return scheme == "http" || scheme == "https"
    }
}
