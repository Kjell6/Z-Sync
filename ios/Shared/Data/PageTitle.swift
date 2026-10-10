import Foundation

/// The name stored for a pinned or shared page.
///
/// Share sheets and WebKit often hand back the URL itself (sometimes with a
/// trailing slash or a `www` prefix). A browser tab shows `document.title`,
/// so a string that is only the address is not a title.
enum PageTitle {
    /// Trimmed title, or nil when it is empty or just the page address.
    static func usable(_ raw: String, url: URL?) -> String? {
        let collapsed = collapse(raw)
        guard !collapsed.isEmpty else { return nil }
        if isAbsoluteHTTP(collapsed) { return nil }
        if let url, identifies(collapsed, url) { return nil }
        return collapsed
    }

    /// Usable title, otherwise the host, otherwise the URL, otherwise "Tab".
    static func headline(_ pageTitle: String, url: URL?) -> String {
        if let title = usable(pageTitle, url: url) { return title }
        if let host = url?.host, !host.isEmpty { return host }
        if let absolute = url?.absoluteString, !absolute.isEmpty { return absolute }
        return "Tab"
    }

    /// True when both addresses are the same http(s) document.
    /// Fragment-only changes do not count; a trailing slash does not either.
    static func sameDocument(_ lhs: URL?, _ rhs: URL?) -> Bool {
        guard let lhs, let rhs else { return false }
        guard let left = identity(lhs), let right = identity(rhs) else {
            return lhs.absoluteString == rhs.absoluteString
        }
        return left == right
    }

    /// `<title>` when it is a real title, otherwise `og:title` / `twitter:title`.
    static func extract(fromHTML html: String, url: URL?) -> String? {
        if let raw = tagText("title", in: html), let title = usable(decodeEntities(raw), url: url) {
            return title
        }
        if let raw = socialTitle(in: html), let title = usable(decodeEntities(raw), url: url) {
            return title
        }
        return nil
    }

    static func htmlString(from data: Data, contentType: String?) -> String {
        let declared = charsetName(in: contentType) ?? charsetName(inPrefix: data)
        if let declared, let encoding = encoding(iana: declared),
           let text = String(data: data, encoding: encoding) {
            return text
        }
        if let text = String(data: data, encoding: .utf8) { return text }
        return String(data: data, encoding: .isoLatin1) ?? ""
    }

    /// `document.title` and a social title joined by a record separator.
    /// WebKit returns the string as-is; Android's bridge JSON-encodes it.
    static let documentTitleScript = """
    (function() {
      function clean(value) {
        return String(value || "").replace(/\\s+/g, " ").trim();
      }
      var title = clean(document.title);
      var node = document.querySelector('meta[property="og:title"], meta[name="og:title"], meta[name="twitter:title"]');
      var social = clean(node && node.content);
      return title + "\\u001e" + social;
    })()
    """

    /// Picks a usable title from the script above. `encoded` is WebKit's raw string.
    static func pickDocumentTitle(_ payload: String?, url: URL?) -> String? {
        guard let payload else { return nil }
        let parts = payload.split(separator: "\u{1e}", maxSplits: 1, omittingEmptySubsequences: false)
        let title = String(parts.first ?? "")
        let social = parts.count > 1 ? String(parts[1]) : ""
        return usable(title, url: url) ?? usable(social, url: url)
    }

    // MARK: - Matching

    private static func collapse(_ raw: String) -> String {
        raw.replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func isAbsoluteHTTP(_ value: String) -> Bool {
        guard let url = URL(string: value), let scheme = url.scheme?.lowercased() else { return false }
        guard scheme == "http" || scheme == "https" else { return false }
        return url.host?.isEmpty == false
    }

    /// Host, `www` host, or the same path written without a scheme.
    private static func identifies(_ title: String, _ url: URL) -> Bool {
        guard let host = url.host?.lowercased(), !host.isEmpty else { return false }
        let bare = host.hasPrefix("www.") ? String(host.dropFirst(4)) : host
        let lower = title.lowercased()
        if lower == host || lower == bare || lower == "www.\(bare)" { return true }
        let candidates = ["https://\(title)", "http://\(title)"]
        guard let target = identity(url) else { return false }
        for candidate in candidates {
            guard let parsed = URL(string: candidate), let id = identity(parsed) else { continue }
            if id == target { return true }
        }
        return false
    }

    private static func identity(_ url: URL) -> String? {
        guard let scheme = url.scheme?.lowercased(), scheme == "http" || scheme == "https" else { return nil }
        guard var host = url.host?.lowercased(), !host.isEmpty else { return nil }
        if host.hasPrefix("www.") { host.removeFirst(4) }
        let path = url.path == "/" ? "" : url.path.trimmingSuffixSlash().lowercased()
        let query = url.query.flatMap { $0.isEmpty ? nil : "?\($0)" } ?? ""
        return host + path + query
    }

    // MARK: - HTML

    private static func tagText(_ name: String, in html: String) -> String? {
        let pattern = "<\(name)\\b[^>]*>([\\s\\S]*?)</\(name)\\s*>"
        return firstGroup(pattern, in: html)
    }

    private static func socialTitle(in html: String) -> String? {
        guard let regex = try? NSRegularExpression(pattern: "<meta\\b[^>]*>", options: [.caseInsensitive]) else {
            return nil
        }
        let range = NSRange(html.startIndex..., in: html)
        var twitter: String?
        for match in regex.matches(in: html, range: range) {
            guard let tagRange = Range(match.range, in: html) else { continue }
            let tag = String(html[tagRange])
            let key = (attribute("property", in: tag) ?? attribute("name", in: tag))?.lowercased()
            guard let content = attribute("content", in: tag) else { continue }
            if key == "og:title" { return content }
            if key == "twitter:title", twitter == nil { twitter = content }
        }
        return twitter
    }

    private static func attribute(_ name: String, in tag: String) -> String? {
        let pattern = "\\b\(NSRegularExpression.escapedPattern(for: name))\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'=<>`]+))"
        return firstGroup(pattern, in: tag, options: [.caseInsensitive])
    }

    private static func firstGroup(
        _ pattern: String,
        in text: String,
        options: NSRegularExpression.Options = [.caseInsensitive]
    ) -> String? {
        guard let regex = try? NSRegularExpression(pattern: pattern, options: options) else { return nil }
        let range = NSRange(text.startIndex..., in: text)
        guard let match = regex.firstMatch(in: text, range: range) else { return nil }
        for index in 1..<match.numberOfRanges {
            guard let group = Range(match.range(at: index), in: text) else { continue }
            return String(text[group])
        }
        return nil
    }

    static func decodeEntities(_ text: String) -> String {
        guard let regex = try? NSRegularExpression(pattern: "&(?:#x([0-9A-Fa-f]+)|#([0-9]+)|([A-Za-z][A-Za-z0-9]+));") else {
            return text
        }
        let range = NSRange(text.startIndex..., in: text)
        var result = ""
        var cursor = text.startIndex
        for match in regex.matches(in: text, range: range) {
            guard let whole = Range(match.range, in: text) else { continue }
            result += text[cursor..<whole.lowerBound]
            cursor = whole.upperBound
            result += decodedEntity(in: text, match: match) ?? String(text[whole])
        }
        result += text[cursor...]
        return result
    }

    private static func decodedEntity(in text: String, match: NSTextCheckingResult) -> String? {
        if let hexRange = Range(match.range(at: 1), in: text), let value = UInt32(text[hexRange], radix: 16),
           let scalar = Unicode.Scalar(value) {
            return String(Character(scalar))
        }
        if let decRange = Range(match.range(at: 2), in: text), let value = UInt32(text[decRange]),
           let scalar = Unicode.Scalar(value) {
            return String(Character(scalar))
        }
        if let nameRange = Range(match.range(at: 3), in: text) {
            return namedEntities[String(text[nameRange]).lowercased()]
        }
        return nil
    }

    private static let namedEntities = [
        "amp": "&", "lt": "<", "gt": ">", "quot": "\"", "apos": "'", "nbsp": " "
    ]

    private static func charsetName(in contentType: String?) -> String? {
        guard let contentType else { return nil }
        return firstGroup("charset\\s*=\\s*[\"']?([A-Za-z0-9._-]+)", in: contentType)
    }

    private static func charsetName(inPrefix data: Data) -> String? {
        let ascii = String(data: data.prefix(2048), encoding: .isoLatin1) ?? ""
        return firstGroup("charset\\s*=\\s*[\"']?([A-Za-z0-9._-]+)", in: ascii)
    }

    private static func encoding(iana name: String) -> String.Encoding? {
        let cf = CFStringConvertIANACharSetNameToEncoding(name as CFString)
        guard cf != kCFStringEncodingInvalidId else { return nil }
        return String.Encoding(rawValue: CFStringConvertEncodingToNSStringEncoding(cf))
    }
}

private extension String {
    func trimmingSuffixSlash() -> String {
        hasSuffix("/") ? String(dropLast()) : self
    }
}
