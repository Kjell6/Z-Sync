import Foundation
import Observation
import WebKit

/// What a site can ask for. The browser asks first; the system's own question
/// only follows once the reader has allowed the site.
enum SitePermission: String, CaseIterable, Codable, Identifiable {
    case camera
    case microphone
    case location

    var id: String { rawValue }

    var symbol: String {
        switch self {
        case .camera: "camera"
        case .microphone: "mic"
        case .location: "location"
        }
    }

    var title: LocalizedStringResource {
        switch self {
        case .camera: "site_permission.camera"
        case .microphone: "site_permission.microphone"
        case .location: "site_permission.location"
        }
    }
}

/// A remembered answer for one site. No entry means the browser asks.
enum SitePermissionChoice: String, Codable {
    case allow
    case block
}

enum SitePermissionDecision: Equatable {
    case grant
    case deny
    case ask
}

/// The browser's answers per site, on this phone only: never synced and left
/// out of device backups.
///
/// Android port: `SitePermissionStore` in `data/SitePermissions.kt`.
@MainActor
@Observable
final class SitePermissionStore {
    static let shared = SitePermissionStore()

    private(set) var choices: [String: SitePermissionChoice]
    @ObservationIgnored private let fileURL: URL
    /// Kinds whose question went unanswered this run: no until the app restarts.
    @ObservationIgnored private var unanswered: Set<String> = []
    /// Sites whose closed system access was already explained this run.
    @ObservationIgnored private var explainedSystemAccess: Set<String> = []

    init(fileURL: URL = SitePermissionStore.defaultFileURL) {
        self.fileURL = fileURL
        let saved = (try? Data(contentsOf: fileURL))
            .flatMap { try? JSONDecoder().decode([String: SitePermissionChoice].self, from: $0) }
        choices = Self.foldingLegacyKeys(saved ?? [:])
        // Answers saved under a leading `www.` move over once, so a later
        // change and a read agree on the same key.
        if let saved, choices != saved { save() }
    }

    /// One blocked kind blocks the whole request, and every kind must be
    /// allowed for it to pass without asking.
    func decision(for kinds: [SitePermission], host: String) -> SitePermissionDecision {
        let answers: [SitePermissionChoice?] = kinds.map { kind in
            // A question that went away unanswered is a no for the rest of this
            // run, so the page hears the refusal instead of asking again.
            unanswered.contains(Self.key(kind, host: host)) ? .block : choice(for: kind, host: host)
        }
        if answers.contains(.block) { return .deny }
        if !answers.isEmpty, answers.allSatisfy({ $0 == .allow }) { return .grant }
        return .ask
    }

    func choice(for kind: SitePermission, host: String) -> SitePermissionChoice? {
        choices[Self.key(kind, host: host)]
    }

    func set(_ choice: SitePermissionChoice?, for kinds: [SitePermission], host: String) {
        for kind in kinds {
            let key = Self.key(kind, host: host)
            choices[key] = choice
            // A real answer replaces what an unanswered question left behind.
            unanswered.remove(key)
        }
        save()
    }

    /// Nobody answered the question: that is a no for the rest of this run,
    /// and only in memory, so the next launch asks again.
    func markUnanswered(for kinds: [SitePermission], host: String) {
        for kind in kinds {
            unanswered.insert(Self.key(kind, host: host))
        }
    }

    /// True only the first time this run explains that the system refuses a
    /// site, so a page asking again just hears the refusal.
    func shouldExplainSystemAccess(host: String) -> Bool {
        explainedSystemAccess.insert(Self.foldedHost(host)).inserted
    }

    func removeAll() {
        choices = [:]
        unanswered = []
        explainedSystemAccess = []
        try? FileManager.default.removeItem(at: fileURL)
    }

    private func save() {
        guard let data = try? JSONEncoder().encode(choices) else { return }
        try? FileManager.default.createDirectory(
            at: fileURL.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )
        guard (try? data.write(to: fileURL, options: .atomic)) != nil else { return }
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var url = fileURL
        try? url.setResourceValues(values)
    }

    /// A leading `www.` is the same site as the bare host: one answer covers
    /// both. Only that one prefix is folded, so maps.google.com keeps its own.
    private static func foldedHost(_ host: String) -> String {
        let lower = host.lowercased()
        return lower.hasPrefix("www.") ? String(lower.dropFirst(4)) : lower
    }

    private static func key(_ kind: SitePermission, host: String) -> String {
        "\(foldedHost(host))|\(kind.rawValue)"
    }

    /// Keys saved before `www.` folded into the bare host keep working: an
    /// answer already stored for the bare host wins over the old `www.` one.
    private static func foldingLegacyKeys(
        _ saved: [String: SitePermissionChoice]
    ) -> [String: SitePermissionChoice] {
        var folded: [String: SitePermissionChoice] = [:]
        for (key, choice) in saved {
            let bare = key.hasPrefix("www.") ? String(key.dropFirst(4)) : key
            if folded[bare] == nil || bare == key { folded[bare] = choice }
        }
        return folded
    }

    nonisolated static var defaultFileURL: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("site-permissions.json")
    }
}

/// What the browser keeps for websites. Open pages and history are the
/// reader's own and stay.
enum BrowserWebsiteData {
    @MainActor
    static func clearAll() {
        WKWebsiteDataStore.default().removeData(
            ofTypes: WKWebsiteDataStore.allWebsiteDataTypes(),
            modifiedSince: Date.distantPast
        ) {}
        SitePermissionStore.shared.removeAll()
    }
}
