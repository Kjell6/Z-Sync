import Foundation

/// Pure URL/search resolution shared by the address field and the pin/safari
/// actions. Mirrors the previous inline logic in `MiniBrowserView` — and the
/// Android `BrowserInput` — so both platforms resolve input identically.
enum BrowserInput {
    static func trimmed(_ raw: String) -> String {
        raw.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Resolves typed text: an explicit scheme wins, a dot without spaces is
    /// treated as a host (`https://…`), anything else becomes a search.
    /// Returns nil for empty input.
    static func resolve(_ raw: String, engine: SearchEngine) -> URL? {
        let query = trimmed(raw)
        guard !query.isEmpty else { return nil }

        if let url = URL(string: query), url.scheme != nil {
            return url
        }
        if query.contains(".") && !query.contains(" ") {
            return URL(string: "https://\(query)")
        }
        return engine.searchURL(for: query)
    }

    /// Address-bar display text for a loaded URL (host, or the full string for
    /// schemeless/local URLs).
    static func displayText(for url: URL) -> String {
        url.host ?? url.absoluteString
    }
}
