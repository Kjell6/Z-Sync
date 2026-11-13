import Foundation

/// Pure URL/search resolution shared by the address field and the pin/safari
/// actions. Mirrors the previous inline logic in `MiniBrowserView` — and the
/// Android `BrowserInput` — so both platforms resolve input identically.
enum BrowserInput {
    static func trimmed(_ raw: String) -> String {
        raw.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Resolves typed text to the page it names (`typedURL`) or a search.
    /// Returns nil for empty input.
    static func resolve(_ raw: String, engine: SearchEngine) -> URL? {
        let query = trimmed(raw)
        guard !query.isEmpty else { return nil }
        return typedURL(query) ?? engine.searchURL(for: query)
    }

    /// Schemes a person may type as-is. Anything else (javascript:, file:,
    /// intent:, …) searches.
    private static let typedSchemes: Set<String> = ["http", "https", "about", "mailto", "tel"]
    private static let schemePrefix = try! NSRegularExpression(pattern: #"^([A-Za-z][A-Za-z0-9+.-]*):"#)
    private static let localhost = try! NSRegularExpression(
        pattern: #"^localhost(?::\d{1,5})?(?:[/?#]\S*)?$"#,
        options: .caseInsensitive
    )
    private static let hostLike = try! NSRegularExpression(
        pattern: #"^([^\s/?#:.]+(?:\.[^\s/?#:.]+)+)(?::\d{1,5})?(?:[/?#]\S*)?$"#
    )
    private static let ipv4 = try! NSRegularExpression(pattern: #"^\d{1,3}(?:\.\d{1,3}){3}$"#)

    /// The URL that `text` names, or nil when it should be searched. Same
    /// rules as Android `typedUrl`:
    /// - an `http`, `https`, `about`, `mailto` or `tel` URL is taken as typed;
    /// - `localhost` and IPv4 hosts (with optional port and path) get `http://`;
    /// - `host.tld` with an optional port and path gets `https://` when the
    ///   last label has a letter, so `3.14` and `site:example.com` search.
    static func typedURL(_ text: String) -> URL? {
        guard !text.contains(where: \.isWhitespace) else { return nil }
        if let scheme = firstGroup(schemePrefix, in: text)?.lowercased(), typedSchemes.contains(scheme) {
            return URL(string: text)
        }
        if firstGroup(localhost, in: text, group: 0) != nil {
            return URL(string: "http://\(text)")
        }
        guard let hostname = firstGroup(hostLike, in: text) else { return nil }
        if firstGroup(ipv4, in: hostname, group: 0) != nil {
            return URL(string: "http://\(text)")
        }
        let tld = hostname.split(separator: ".").last ?? ""
        guard tld.contains(where: \.isLetter) else { return nil }
        return URL(string: "https://\(text)")
    }

    private static func firstGroup(_ regex: NSRegularExpression, in text: String, group: Int = 1) -> String? {
        let range = NSRange(text.startIndex..., in: text)
        guard let match = regex.firstMatch(in: text, range: range),
              let matched = Range(match.range(at: group), in: text)
        else { return nil }
        return String(text[matched])
    }

    /// True for an `http` or `https` link another app handed to Z-Sync. Only
    /// these may open: the address then goes through the same input handling
    /// as a typed one, and no other scheme (file, javascript, data) gets that
    /// far.
    static func isWebAddress(_ url: URL) -> Bool {
        switch url.scheme?.lowercased() {
        case "http", "https": return true
        default: return false
        }
    }

    /// The page an incoming link names, or nil when it is not one. Two forms
    /// are accepted, nothing else: a plain `http`/`https` address handed over
    /// by another app, and Z-Sync's own `zsync://open?url=` link.
    static func incomingWebAddress(_ url: URL) -> URL? {
        guard url.scheme?.lowercased() == "zsync" else {
            guard isWebAddress(url), url.host?.isEmpty == false else { return nil }
            return url
        }
        // Another host or path, a missing or extra parameter, or a target that
        // is not a web address with a host: not this link, so nothing opens.
        guard url.host?.lowercased() == "open",
              url.path.isEmpty || url.path == "/",
              let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
              let items = components.queryItems,
              items.count == 1,
              items[0].name == "url",
              let value = items[0].value, !value.isEmpty,
              let target = URL(string: value),
              isWebAddress(target),
              target.host?.isEmpty == false
        else { return nil }
        return target
    }

    /// Address-bar display text for a loaded URL (host, or the full string for
    /// schemeless/local URLs).
    static func displayText(for url: URL) -> String {
        url.host ?? url.absoluteString
    }
}
