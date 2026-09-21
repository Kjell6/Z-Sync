import Foundation

/// A search provider: a display name plus a URL template whose `{query}`
/// placeholder is replaced with the percent-encoded query.
///
/// The built-in engines are the fixed list in `SearchEngines.builtIn`. Users
/// may add their own; those live only on the device and are never synced.
public struct SearchEngine: Identifiable, Hashable, Codable, Sendable {
    public let id: String
    public let displayName: String
    public let template: String
    public let isBuiltIn: Bool

    /// Marker in `template` that receives the encoded query.
    public static let queryPlaceholder = "{query}"

    public init(id: String, displayName: String, template: String, isBuiltIn: Bool) {
        self.id = id
        self.displayName = displayName
        self.template = template
        self.isBuiltIn = isBuiltIn
    }

    /// The search URL for `query`, or nil when the template is unusable.
    public func searchURL(for query: String) -> URL? {
        guard let encoded = query.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) else { return nil }
        return URL(string: template.replacingOccurrences(of: SearchEngine.queryPlaceholder, with: encoded))
    }
}

// MARK: - Built-ins

public extension SearchEngine {
    static let duckDuckGo = SearchEngine(
        id: "duckduckgo", displayName: "DuckDuckGo",
        template: "https://duckduckgo.com/?q={query}", isBuiltIn: true
    )
    static let google = SearchEngine(
        id: "google", displayName: "Google",
        template: "https://www.google.com/search?q={query}", isBuiltIn: true
    )
    static let ecosia = SearchEngine(
        id: "ecosia", displayName: "Ecosia",
        template: "https://www.ecosia.org/search?q={query}", isBuiltIn: true
    )
    static let brave = SearchEngine(
        id: "brave", displayName: "Brave",
        template: "https://search.brave.com/search?q={query}", isBuiltIn: true
    )
    static let bing = SearchEngine(
        id: "bing", displayName: "Bing",
        template: "https://www.bing.com/search?q={query}", isBuiltIn: true
    )
}

// MARK: - Catalogue + persistence

/// The engine catalogue and the persisted selection. Reads the App Group
/// first, then standard defaults, and writes to both — mirroring the previous
/// enum so the Share Extension sees the same choice.
public enum SearchEngines {
    public static let builtIn: [SearchEngine] = [.duckDuckGo, .google, .ecosia, .brave, .bing]

    private static let selectedKey = "selected_search_engine"
    private static let customKey = "custom_search_engines"

    /// User-defined engines, in the order they were added.
    public static var custom: [SearchEngine] {
        get {
            guard let data = object(forKey: customKey) as? Data,
                  let decoded = try? JSONDecoder().decode([SearchEngine].self, from: data)
            else { return [] }
            return decoded.filter { !$0.isBuiltIn }
        }
        set {
            let sanitized = newValue.filter { !$0.isBuiltIn }
            guard let data = try? JSONEncoder().encode(sanitized) else { return }
            setObject(data, forKey: customKey)
        }
    }

    /// Built-ins in stable order, then the user's own engines.
    public static var all: [SearchEngine] { builtIn + custom }

    /// The selected engine, resolved by id. Unknown or missing ids fall back
    /// to DuckDuckGo — including after a selected custom engine is deleted.
    public static var current: SearchEngine {
        get {
            guard let id = object(forKey: selectedKey) as? String else { return .duckDuckGo }
            return all.first { $0.id == id } ?? .duckDuckGo
        }
        set { setObject(newValue.id, forKey: selectedKey) }
    }

    /// Appends a custom engine and returns it (caller decides on selection).
    @discardableResult
    public static func addCustom(name: String, template: String) -> SearchEngine {
        let engine = SearchEngine(
            id: UUID().uuidString,
            displayName: name.trimmingCharacters(in: .whitespacesAndNewlines),
            template: template.trimmingCharacters(in: .whitespacesAndNewlines),
            isBuiltIn: false
        )
        custom.append(engine)
        return engine
    }

    /// Removes a custom engine; built-ins are ignored.
    public static func deleteCustom(id: String) {
        custom = custom.filter { $0.id != id }
    }

    // MARK: Storage

    private static func object(forKey key: String) -> Any? {
        AppGroup.defaults.object(forKey: key) ?? UserDefaults.standard.object(forKey: key)
    }

    private static func setObject(_ value: Any?, forKey key: String) {
        AppGroup.defaults.set(value, forKey: key)
        UserDefaults.standard.set(value, forKey: key)
    }
}

// MARK: - Draft validation + paste derivation

/// Why a custom-engine draft cannot be saved yet.
public enum SearchEngineValidation: Equatable {
    case emptyName
    case invalidURL
    case missingPlaceholder
}

/// Validation and the "paste a search link" template derivation for custom
/// engines. Pure and side-effect free, so it is unit-testable.
public enum SearchEngineTemplate {
    public static let placeholder = SearchEngine.queryPlaceholder

    /// nil when the draft is ready to save, otherwise the first problem.
    public static func validate(name: String, template: String) -> SearchEngineValidation? {
        if name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return .emptyName }
        let trimmed = template.trimmingCharacters(in: .whitespacesAndNewlines)
        let lower = trimmed.lowercased()
        guard lower.hasPrefix("http://") || lower.hasPrefix("https://") else { return .invalidURL }
        guard URL(string: trimmed.replacingOccurrences(of: placeholder, with: "test")) != nil else { return .invalidURL }
        guard trimmed.contains(placeholder) else { return .missingPlaceholder }
        return nil
    }

    /// Turns a real search URL (e.g. copied from the address bar) into a
    /// template by replacing the query parameter's value with `{query}`.
    /// Prefers well-known parameter names (`q`, `query`, …), else the last
    /// parameter that has a value.
    public static func derive(fromPastedURL raw: String) -> String? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        let lower = trimmed.lowercased()
        guard lower.hasPrefix("http://") || lower.hasPrefix("https://") else { return nil }
        guard let questionMark = trimmed.firstIndex(of: "?") else { return nil }

        let base = String(trimmed[..<questionMark])
        var remainder = String(trimmed[trimmed.index(after: questionMark)...])
        var fragment = ""
        if let hash = remainder.firstIndex(of: "#") {
            fragment = String(remainder[hash...])
            remainder = String(remainder[..<hash])
        }
        guard !remainder.isEmpty else { return nil }

        let preferredKeys: Set<String> = ["q", "query", "search", "p", "text", "wd", "k"]
        var pairs = remainder.components(separatedBy: "&")

        func key(of pair: String) -> String {
            let rawKey = pair.components(separatedBy: "=").first ?? pair
            return (rawKey.removingPercentEncoding ?? rawKey).lowercased()
        }
        func value(of pair: String) -> String {
            let parts = pair.components(separatedBy: "=")
            return parts.count > 1 ? parts.dropFirst().joined(separator: "=") : ""
        }

        var chosen: Int?
        for (index, pair) in pairs.enumerated() where !value(of: pair).isEmpty {
            if preferredKeys.contains(key(of: pair)) {
                chosen = index
                break
            }
        }
        if chosen == nil {
            chosen = pairs.lastIndex(where: { !value(of: $0).isEmpty })
        }
        guard let index = chosen else { return nil }

        let originalKey = pairs[index].components(separatedBy: "=").first ?? ""
        pairs[index] = "\(originalKey)=\(placeholder)"
        return base + "?" + pairs.joined(separator: "&") + fragment
    }

    /// Best-effort display name for a pasted search link: the registrable
    /// domain's label, so `de.search.yahoo.com` → "Yahoo" and
    /// `www.bbc.co.uk` → "Bbc" (not the sub-domain "de" or the "www").
    public static func suggestedName(fromTemplate template: String) -> String? {
        guard let base = template.components(separatedBy: "?").first,
              let host = URL(string: base)?.host else { return nil }
        return suggestedName(fromHost: host)
    }

    /// Common country-code second-level domains, so `co.uk` style suffixes are
    /// skipped when looking for the registrable label.
    private static let compoundSuffixes: Set<String> = [
        "co.uk", "org.uk", "ac.uk", "gov.uk", "me.uk", "net.uk", "sch.uk",
        "com.au", "net.au", "org.au", "edu.au", "gov.au",
        "co.nz", "net.nz", "org.nz", "govt.nz",
        "co.jp", "or.jp", "ne.jp", "ac.jp", "go.jp",
        "com.br", "net.br", "org.br", "gov.br",
        "co.in", "net.in", "org.in", "gov.in",
        "com.cn", "net.cn", "org.cn", "gov.cn",
        "co.za", "org.za", "gov.za",
        "co.kr", "or.kr",
        "com.mx", "com.tr", "com.sg", "com.hk", "com.tw", "co.id", "co.il",
    ]

    /// `www.` is stripped; otherwise the label before the public suffix wins.
    static func suggestedName(fromHost host: String) -> String? {
        var labels = host.lowercased().split(separator: ".").map(String.init)
        if labels.first == "www" { labels.removeFirst() }
        guard let first = labels.first else { return nil }

        let label: String
        if labels.count == 1 {
            label = first
        } else if labels.count >= 3,
                  compoundSuffixes.contains(labels.suffix(2).joined(separator: ".")) {
            label = labels[labels.count - 3]
        } else {
            label = labels[labels.count - 2]
        }

        guard !label.isEmpty else { return nil }
        return label.prefix(1).uppercased() + label.dropFirst()
    }
}
