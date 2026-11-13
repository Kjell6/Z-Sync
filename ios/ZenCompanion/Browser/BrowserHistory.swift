import Foundation
import Observation
import UIKit

/// One address the reader has been to. A second visit updates the entry, so
/// the list holds each page once, at the time it was last open.
struct BrowserVisit: Codable, Equatable, Identifiable {
    let url: String
    var title: String
    var lastVisited: Date
    var visits: Int

    var id: String { url }

    var host: String {
        let host = URL(string: url)?.host ?? url
        return host.hasPrefix("www.") ? String(host.dropFirst(4)) : host
    }

    /// The address without its scheme, so two pages of a site can be told apart.
    var displayAddress: String {
        var text = url
        if let range = text.range(of: "://") { text = String(text[range.upperBound...]) }
        if text.hasPrefix("www.") { text = String(text.dropFirst(4)) }
        return text.hasSuffix("/") ? String(text.dropLast()) : text
    }
}

/// The pages opened in the browser, on this phone only: it never enters Zen's
/// data, stays out of device backups, and goes on sign-out.
///
/// Android port: `BrowserHistory` in `data/BrowserHistory.kt`.
@MainActor
@Observable
final class BrowserHistoryStore {
    static let shared = BrowserHistoryStore()
    static let maxEntries = 5_000

    /// Newest first.
    private(set) var visits: [BrowserVisit]

    @ObservationIgnored private let fileURL: URL
    @ObservationIgnored private let saveDelay: Duration
    @ObservationIgnored private var saveTask: Task<Void, Never>?
    @ObservationIgnored private var backgroundObserver: NSObjectProtocol?

    init(fileURL: URL = BrowserHistoryStore.defaultFileURL, saveDelay: Duration = .seconds(1)) {
        self.fileURL = fileURL
        self.saveDelay = saveDelay
        let decoder = JSONDecoder()
        let stored = (try? Data(contentsOf: fileURL)).flatMap { try? decoder.decode([BrowserVisit].self, from: $0) } ?? []
        // Older builds saved the login a page was opened with: the list is
        // cleaned before it is shown, and the file is written again below so it
        // no longer holds the logins either.
        let cleaned = Self.cleanedHistory(stored)
        visits = cleaned ?? stored
        // The app may be ended while in the background, before the save is due.
        backgroundObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.didEnterBackgroundNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated { self?.saveNow() }
        }
        if let cleaned {
            // Nothing waits to be saved here: the loaded list is what goes.
            write(cleaned)
        }
    }

    /// The address history keeps, or nil for what is not a web page. A jump
    /// inside a page is the same page.
    nonisolated static func address(of url: URL?) -> String? {
        guard let url,
              let scheme = url.scheme?.lowercased(), scheme == "http" || scheme == "https",
              var components = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return nil }
        components.fragment = nil
        // `user:pass@` is a login typed into the address, not part of the page.
        components.user = nil
        components.password = nil
        return components.url?.absoluteString
    }

    /// Stored history as it should read, or nil when it already does: every
    /// address goes through `address(of:)`, so a login an older build saved
    /// goes, a page that is not one goes, and two entries that end up at the
    /// same page become one. The caller writes a cleaned list back, so the old
    /// file loses the logins too.
    nonisolated static func cleanedHistory(_ visits: [BrowserVisit]) -> [BrowserVisit]? {
        var cleaned: [BrowserVisit] = []
        for visit in visits {
            guard let address = address(of: URL(string: visit.url)) else { continue }
            guard let at = cleaned.firstIndex(where: { $0.url == address }) else {
                cleaned.append(
                    BrowserVisit(url: address, title: visit.title, lastVisited: visit.lastVisited, visits: visit.visits)
                )
                continue
            }
            let kept = cleaned[at]
            // The newer of the two names the page, unless it arrived without a title.
            let visitIsNewer = visit.lastVisited >= kept.lastVisited
            let newer = visitIsNewer ? visit : kept
            let older = visitIsNewer ? kept : visit
            cleaned[at] = BrowserVisit(
                url: address,
                title: newer.title.isEmpty ? older.title : newer.title,
                lastVisited: newer.lastVisited,
                visits: kept.visits + visit.visits
            )
        }
        return cleaned == visits ? nil : cleaned
    }

    func record(_ url: URL?, title: String, at date: Date = Date()) {
        guard let address = Self.address(of: url) else { return }
        var visit = BrowserVisit(url: address, title: title, lastVisited: date, visits: 1)
        if let index = visits.firstIndex(where: { $0.url == address }) {
            let earlier = visits.remove(at: index)
            visit.visits = earlier.visits + 1
            if title.isEmpty { visit.title = earlier.title }
        }
        visits.insert(visit, at: 0)
        if visits.count > Self.maxEntries {
            visits.removeLast(visits.count - Self.maxEntries)
        }
        scheduleSave()
    }

    /// Titles often arrive after the page has finished.
    func updateTitle(_ title: String, of address: String) {
        guard !title.isEmpty, let index = visits.firstIndex(where: { $0.url == address }),
              visits[index].title != title else { return }
        visits[index].title = title
        scheduleSave()
    }

    func remove(_ visit: BrowserVisit) {
        visits.removeAll { $0.url == visit.url }
        scheduleSave()
    }

    func removeAll() {
        visits = []
        saveTask?.cancel()
        saveTask = nil
        try? FileManager.default.removeItem(at: fileURL)
    }

    /// Every word must appear in the title or the address.
    func search(_ query: String) -> [BrowserVisit] {
        let words = Self.words(in: query)
        guard !words.isEmpty else { return visits }
        return visits.filter { Self.matches($0, words: words) }
    }

    /// Up to [limit] pages for the text in the address field, leaving out the
    /// page that is already open. Only a real match counts: every typed word
    /// must start a word of the page's title or address. A site whose name
    /// starts with the text comes first, then the pages visited most.
    func suggestions(for text: String, excluding current: URL? = nil, limit: Int = 2) -> [BrowserVisit] {
        let typed = Self.typedWords(text)
        guard typed.joined().count >= Self.minimumTypedLetters else { return [] }
        let open = Self.address(of: current)
        // Worked out once per page: this runs on every keystroke.
        let found = visits
            .filter { $0.url != open && Self.isCloseMatch($0, typed: typed) }
            .enumerated()
            .map { (visit: $0.element, order: $0.offset, namesSite: $0.element.host.lowercased().hasPrefix(typed[0])) }
        let ranked = found.sorted { a, b in
            if a.namesSite != b.namesSite { return a.namesSite }
            if a.visit.visits != b.visit.visits { return a.visit.visits > b.visit.visits }
            return a.order < b.order
        }
        return ranked.prefix(limit).map(\.visit)
    }

    /// Writes a waiting save right away. Nothing is waiting after `removeAll`,
    /// so this cannot bring a removed list back.
    func saveNow() {
        guard saveTask != nil else { return }
        saveTask?.cancel()
        saveTask = nil
        write(visits)
    }

    private func scheduleSave() {
        saveTask?.cancel()
        let delay = saveDelay
        saveTask = Task { [weak self] in
            try? await Task.sleep(for: delay)
            guard !Task.isCancelled, let self else { return }
            self.saveTask = nil
            self.write(self.visits)
        }
    }

    private func write(_ visits: [BrowserVisit]) {
        guard let data = try? JSONEncoder().encode(visits) else { return }
        try? FileManager.default.createDirectory(
            at: fileURL.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )
        guard (try? data.write(to: fileURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])) != nil else { return }
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var url = fileURL
        try? url.setResourceValues(values)
    }

    private nonisolated static let minimumTypedLetters = 2

    /// The typed text as words, without the parts every address shares.
    private nonisolated static func typedWords(_ text: String) -> [String] {
        var value = text.lowercased().trimmingCharacters(in: .whitespacesAndNewlines)
        for prefix in ["https://", "http://", "www."] where value.hasPrefix(prefix) {
            value.removeFirst(prefix.count)
        }
        return tokens(in: value)
    }

    private nonisolated static func tokens(in text: String) -> [String] {
        text.lowercased().split { !$0.isLetter && !$0.isNumber }.map(String.init)
    }

    private nonisolated static func isCloseMatch(_ visit: BrowserVisit, typed: [String]) -> Bool {
        let address = visit.displayAddress.split(separator: "?", maxSplits: 1).first.map(String.init) ?? ""
        let pageWords = tokens(in: visit.title) + tokens(in: address)
        return typed.allSatisfy { word in pageWords.contains { $0.hasPrefix(word) } }
    }

    private nonisolated static func words(in text: String) -> [String] {
        text.lowercased().split(whereSeparator: \.isWhitespace).map(String.init)
    }

    private nonisolated static func matches(_ visit: BrowserVisit, words: [String]) -> Bool {
        let title = visit.title.lowercased()
        let url = visit.url.lowercased()
        return words.allSatisfy { title.contains($0) || url.contains($0) }
    }

    nonisolated static var defaultFileURL: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("browser-history.json")
    }
}
