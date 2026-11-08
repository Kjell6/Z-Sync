import Foundation
import Observation

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

    init(fileURL: URL = BrowserHistoryStore.defaultFileURL, saveDelay: Duration = .seconds(1)) {
        self.fileURL = fileURL
        self.saveDelay = saveDelay
        let decoder = JSONDecoder()
        visits = (try? Data(contentsOf: fileURL)).flatMap { try? decoder.decode([BrowserVisit].self, from: $0) } ?? []
    }

    /// The address history keeps, or nil for what is not a web page. A jump
    /// inside a page is the same page.
    nonisolated static func address(of url: URL?) -> String? {
        guard let url,
              let scheme = url.scheme?.lowercased(), scheme == "http" || scheme == "https",
              var components = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return nil }
        components.fragment = nil
        return components.url?.absoluteString
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
    /// page that is already open. A site whose name starts with the text comes
    /// first, then the pages visited most.
    func suggestions(for text: String, excluding current: URL? = nil, limit: Int = 3) -> [BrowserVisit] {
        let words = Self.words(in: text)
        guard !words.isEmpty else { return [] }
        let open = Self.address(of: current)
        let first = words[0]
        // Worked out once per page: this runs on every keystroke.
        let found = visits
            .filter { $0.url != open && Self.matches($0, words: words) }
            .enumerated()
            .map { (visit: $0.element, order: $0.offset, namesSite: $0.element.host.lowercased().hasPrefix(first)) }
        let ranked = found.sorted { a, b in
            if a.namesSite != b.namesSite { return a.namesSite }
            if a.visit.visits != b.visit.visits { return a.visit.visits > b.visit.visits }
            return a.order < b.order
        }
        return ranked.prefix(limit).map(\.visit)
    }

    /// Writes what is pending right away. For tests and for the app leaving.
    func saveNow() {
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
            self.write(self.visits)
        }
    }

    private func write(_ visits: [BrowserVisit]) {
        guard let data = try? JSONEncoder().encode(visits) else { return }
        try? FileManager.default.createDirectory(
            at: fileURL.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )
        guard (try? data.write(to: fileURL, options: [.atomic, .completeFileProtection])) != nil else { return }
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var url = fileURL
        try? url.setResourceValues(values)
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
