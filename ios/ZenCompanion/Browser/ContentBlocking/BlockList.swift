import Foundation

/// The domain rules of one EasyList-format filter list (EasyList for ads,
/// EasyPrivacy for trackers). Only rules that name a whole domain are kept:
/// `||ads.example^`, optionally limited to third-party requests, plus the
/// exceptions that undo them. Everything else in the list (paths, page element
/// hiding, rules for one site only) is skipped.
///
/// Mirrored on Android in `BlockList.kt`; both read the same files.
struct BlockList: Sendable {
    /// Blocked wherever they load.
    let anywhere: Set<String>
    /// Blocked only when loaded by a page on another site.
    let thirdParty: Set<String>
    /// Exceptions: never blocked.
    let allowedEverywhere: Set<String>
    /// Exceptions: never blocked when the page is on the same site.
    let allowedFirstParty: Set<String>
    /// Exceptions: blocked domain to the sites where it is allowed.
    let allowedOn: [String: Set<String>]
    /// Sites the list leaves alone entirely (`$document` exceptions).
    let allowedPages: Set<String>
    /// The list's own version line, e.g. `202610242150`.
    let version: String?
    /// The list's "Last modified" line, e.g. `24 Oct 2026 21:50 UTC`.
    let lastModified: String?

    var ruleCount: Int { anywhere.count + thirdParty.count }

    /// True when a request to `requestHost` from a page on `pageHost` is blocked.
    func blocks(requestHost: String, pageHost: String?) -> Bool {
        let request = Self.normalized(requestHost)
        let page = pageHost.map(Self.normalized)
        if let page, Self.matchesSuffix(page, allowedPages) { return false }
        let thirdPartyRequest = page.map { !Self.isSameSite(request, $0) } ?? false
        let listed = Self.matchesSuffix(request, anywhere)
            || (thirdPartyRequest && Self.matchesSuffix(request, thirdParty))
        guard listed else { return false }
        if Self.matchesSuffix(request, allowedEverywhere) { return false }
        if !thirdPartyRequest, Self.matchesSuffix(request, allowedFirstParty) { return false }
        if let page {
            for domain in Self.suffixes(of: request) {
                if let sites = allowedOn[domain], Self.matchesSuffix(page, sites) { return false }
            }
        }
        return true
    }

    // MARK: - Parsing

    /// Request types a domain rule may be limited to. They are all blocked alike.
    private static let requestTypes: Set<Substring> = [
        "script", "image", "stylesheet", "xmlhttprequest", "subdocument", "ping",
        "media", "font", "object", "other", "websocket", "all", "important",
    ]

    static func parse(_ text: String) -> BlockList {
        var anywhere = Set<String>()
        var thirdParty = Set<String>()
        var allowedEverywhere = Set<String>()
        var allowedFirstParty = Set<String>()
        var allowedOn: [String: Set<String>] = [:]
        var allowedPages = Set<String>()
        var version: String?
        var lastModified: String?

        text.enumerateLines { raw, _ in
            let line = raw.trimmingCharacters(in: .whitespaces)
            if line.hasPrefix("!") {
                if version == nil { version = headerValue(line, prefix: "! Version:") }
                if lastModified == nil { lastModified = headerValue(line, prefix: "! Last modified:") }
                return
            }
            guard let rule = DomainRule(line.lowercased()) else { return }

            if !rule.exception {
                var onlyThirdParty = false
                for option in rule.options {
                    if option == "third-party" || option == "3p" {
                        onlyThirdParty = true
                    } else if !requestTypes.contains(option) {
                        // Popups, page blocks, one-site rules, redirects: not ours.
                        return
                    }
                }
                if onlyThirdParty { thirdParty.insert(rule.domain) } else { anywhere.insert(rule.domain) }
                return
            }

            if rule.options.isEmpty {
                allowedEverywhere.insert(rule.domain)
                return
            }
            if let domains = rule.options.first(where: { $0.hasPrefix("domain=") }) {
                let sites = domains.dropFirst("domain=".count)
                    .split(separator: "|")
                    .filter { !$0.isEmpty && !$0.hasPrefix("~") }
                    .map(String.init)
                if !sites.isEmpty { allowedOn[rule.domain, default: []].formUnion(sites) }
            } else if rule.options.contains("document") {
                allowedPages.insert(rule.domain)
            } else if rule.options.contains("~third-party") || rule.options.contains("1p") {
                allowedFirstParty.insert(rule.domain)
            }
            // Element hiding and the like: nothing to undo here.
        }

        return BlockList(
            anywhere: anywhere,
            thirdParty: thirdParty,
            allowedEverywhere: allowedEverywhere,
            allowedFirstParty: allowedFirstParty,
            allowedOn: allowedOn,
            allowedPages: allowedPages,
            version: version,
            lastModified: lastModified
        )
    }

    /// `||domain^` or `@@||domain^`, with the options after `$`.
    private struct DomainRule {
        let exception: Bool
        let domain: String
        let options: [Substring]

        init?(_ line: String) {
            var rest = Substring(line)
            exception = rest.hasPrefix("@@")
            if exception { rest = rest.dropFirst(2) }
            guard rest.hasPrefix("||") else { return nil }
            rest = rest.dropFirst(2)
            guard let caret = rest.firstIndex(of: "^") else { return nil }
            let name = rest[..<caret]
            guard !name.isEmpty, name.allSatisfy(Self.isDomainCharacter) else { return nil }
            let trimmed = name.trimmingCharacters(in: CharacterSet(charactersIn: "."))
            guard trimmed.contains(".") else { return nil }
            domain = trimmed
            let after = rest[rest.index(after: caret)...]
            if after.isEmpty {
                options = []
            } else if after.hasPrefix("$") {
                options = after.dropFirst().split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces)[...] }
                    .filter { !$0.isEmpty }
            } else {
                // Anything after the separator is a path: not a whole-domain rule.
                return nil
            }
        }

        private static func isDomainCharacter(_ character: Character) -> Bool {
            character.isASCII && (character.isLetter || character.isNumber || character == "." || character == "-" || character == "_")
        }
    }

    private static func headerValue(_ line: String, prefix: String) -> String? {
        guard line.lowercased().hasPrefix(prefix.lowercased()) else { return nil }
        let value = line.dropFirst(prefix.count).trimmingCharacters(in: .whitespaces)
        return value.isEmpty ? nil : value
    }

    // MARK: - Hosts

    private static func normalized(_ host: String) -> String {
        var value = host.lowercased()
        while value.hasSuffix(".") { value.removeLast() }
        return value
    }

    /// `a.b.example.com`, `b.example.com`, `example.com`: never the bare top-level domain.
    static func suffixes(of host: String) -> [String] {
        var result: [String] = []
        var rest = Substring(host)
        while let dot = rest.firstIndex(of: ".") {
            result.append(String(rest))
            rest = rest[rest.index(after: dot)...]
        }
        return result
    }

    private static func matchesSuffix(_ host: String, _ set: Set<String>) -> Bool {
        guard !set.isEmpty else { return false }
        return suffixes(of: host).contains(where: set.contains)
    }

    /// Whether two hosts belong to the same site, for third-party rules.
    ///
    /// shortcut: compares the last two labels, or three under a short
    /// second-level label such as `co.uk`, instead of reading the Public
    /// Suffix List. It can only treat two sites as one (a rule then doesn't
    /// fire), never split one site in two. Upgrade if that misses real
    /// trackers.
    static func isSameSite(_ a: String, _ b: String) -> Bool {
        site(of: a) == site(of: b)
    }

    private static func site(of host: String) -> String {
        let labels = host.split(separator: ".")
        guard labels.count > 2 else { return host }
        let keep = labels[labels.count - 1].count == 2 && labels[labels.count - 2].count <= 3 ? 3 : 2
        return labels.suffix(keep).joined(separator: ".")
    }

    // MARK: - WebKit rules

    /// Resource types the rules apply to. Pages themselves ("document",
    /// "popup") are never blocked, so a tapped link always opens.
    private static let resourceTypes = ["image", "style-sheet", "script", "font", "raw", "svg-document", "media", "ping"]

    /// The list as a WebKit content rule list: blocks first, exceptions after,
    /// because `ignore-previous-rules` only undoes rules above it.
    func contentRuleListJSON() -> String {
        var rules: [String] = []
        rules.reserveCapacity(ruleCount + allowedOn.count + 16)

        func filter(_ domain: String) -> String {
            "^[^:]+://+([^:/]+\\\\.)?" + domain.replacingOccurrences(of: ".", with: "\\\\.") + "[:/]"
        }
        func quoted(_ values: [String]) -> String {
            "[" + values.map { "\"*\($0)\"" }.joined(separator: ",") + "]"
        }
        let types = "[" + Self.resourceTypes.map { "\"\($0)\"" }.joined(separator: ",") + "]"

        for domain in anywhere.sorted() {
            rules.append(#"{"trigger":{"url-filter":"\#(filter(domain))","resource-type":\#(types)},"action":{"type":"block"}}"#)
        }
        for domain in thirdParty.sorted() {
            rules.append(#"{"trigger":{"url-filter":"\#(filter(domain))","resource-type":\#(types),"load-type":["third-party"]},"action":{"type":"block"}}"#)
        }
        for domain in allowedEverywhere.sorted() {
            rules.append(#"{"trigger":{"url-filter":"\#(filter(domain))"},"action":{"type":"ignore-previous-rules"}}"#)
        }
        for domain in allowedFirstParty.sorted() {
            rules.append(#"{"trigger":{"url-filter":"\#(filter(domain))","load-type":["first-party"]},"action":{"type":"ignore-previous-rules"}}"#)
        }
        for (domain, sites) in allowedOn.sorted(by: { $0.key < $1.key }) {
            rules.append(#"{"trigger":{"url-filter":"\#(filter(domain))","if-domain":\#(quoted(sites.sorted()))},"action":{"type":"ignore-previous-rules"}}"#)
        }
        if !allowedPages.isEmpty {
            rules.append(#"{"trigger":{"url-filter":".*","if-domain":\#(quoted(allowedPages.sorted()))},"action":{"type":"ignore-previous-rules"}}"#)
        }
        return "[" + rules.joined(separator: ",") + "]"
    }
}
