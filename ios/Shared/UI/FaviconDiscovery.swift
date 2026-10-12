import Foundation

/// How Zen Browser picks a favicon, without a third-party icon service.
///
/// Zen is Firefox here: `LinkHandlerChild` reads `rel="icon"` /
/// `apple-touch-icon` / `fluid-icon` (not `mask-icon`), and `FaviconLoader`
/// also requests `/favicon.ico` on the page. The image is loaded from that
/// address. SVG is skipped because UIKit cannot decode it; a touch icon near
/// 128px is preferred because rows are larger than Firefox's 16px tab icon.
///
/// A few hosts only publish the real product icon inside a signed-in document
/// (Google Calendar, Docs, Shopify admin). For those, the fallback URL is the
/// public file that page links to, not a search-engine favicon API.
enum FaviconDiscovery {
    private static let interstitialHosts: Set<String> = [
        "accounts.google.com",
        "myaccount.google.com",
        "workspace.google.com"
    ]

    /// Tab address we are willing to request: http(s), no userinfo, query, or fragment.
    static func documentURL(from pageURL: String) -> URL? {
        let trimmed = pageURL.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, var components = URLComponents(string: trimmed) else { return nil }
        guard let scheme = components.scheme?.lowercased(), scheme == "http" || scheme == "https" else { return nil }
        guard let host = components.host?.lowercased(), !host.isEmpty else { return nil }
        components.scheme = scheme
        components.host = host
        components.user = nil
        components.password = nil
        components.query = nil
        components.fragment = nil
        if let port = components.port,
           (scheme == "https" && port == 443) || (scheme == "http" && port == 80) {
            components.port = nil
        }
        if components.path.isEmpty { components.path = "/" }
        return components.url
    }

    static func siteKey(for url: URL) -> String? {
        guard let scheme = url.scheme?.lowercased(), scheme == "http" || scheme == "https" else { return nil }
        guard let host = url.host?.lowercased(), !host.isEmpty else { return nil }
        var key = "\(scheme)://\(host)"
        if let port = url.port, port != (scheme == "https" ? 443 : 80) {
            key += ":\(port)"
        }
        if host == "docs.google.com" {
            let segment = url.path.split(separator: "/").first.map { $0.lowercased() } ?? ""
            if !segment.isEmpty { key += "/\(segment)" }
        }
        return key
    }

    /// Icon URLs to try, best first. `pageURL` is the tab; `finalURL` is where
    /// the document request landed. Icons on a login interstitial are ignored,
    /// and `/favicon.ico` then stays on the tab's own host.
    static func candidates(html: String?, finalURL: URL?, pageURL: URL) -> [URL] {
        let blocked = finalURL.map(isInterstitial) ?? false
        let document = blocked ? nil : finalURL
        var urls: [URL] = []
        if let html, let document {
            urls.append(contentsOf: declaredIcons(in: html, document: document))
        }
        if let known = knownIcon(for: pageURL) {
            urls.append(known)
        }
        if let favicon = originFavicon(document ?? pageURL) {
            urls.append(favicon)
        }
        var seen = Set<String>()
        return urls.filter { seen.insert($0.absoluteString).inserted }
    }

    /// True when this host's logged-out page does not carry the product icon,
    /// so the loader can skip that document and request the known file.
    static func hasKnownIcon(for pageURL: URL) -> Bool {
        knownIcon(for: pageURL) != nil
    }

    /// Desktop `localhost` is not this phone. Skip it instead of waiting on a timeout.
    static func isLoopback(_ url: URL) -> Bool {
        guard let host = url.host?.lowercased() else { return false }
        return host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "0.0.0.0"
    }

    // MARK: - Known product icons

    /// Public files the signed-in page links to. `docs.google.com/favicon.ico`
    /// is the Drive mark, so Docs, Sheets, Slides, and Forms are chosen from
    /// the path. Calendar has no `/favicon.ico`. Shopify admin refuses an
    /// anonymous fetch; the bag icon is the one on shopify.com.
    static func knownIcon(for pageURL: URL) -> URL? {
        guard let host = pageURL.host?.lowercased() else { return nil }
        let path = pageURL.path.lowercased()
        let raw: String?
        switch host {
        case "calendar.google.com":
            raw = "https://calendar.google.com/googlecalendar/images/favicons_2020q4/calendar_31.ico"
        case "docs.google.com" where path.contains("/spreadsheets"):
            raw = "https://ssl.gstatic.com/docs/spreadsheets/favicon3.ico"
        case "docs.google.com" where path.contains("/presentation"):
            raw = "https://ssl.gstatic.com/docs/presentations/images/favicon5.ico"
        case "docs.google.com" where path.contains("/forms"):
            raw = "https://ssl.gstatic.com/docs/forms/device_home/android_192.png"
        case "docs.google.com":
            raw = "https://ssl.gstatic.com/docs/documents/images/kix-favicon7.ico"
        case "admin.shopify.com":
            raw = "https://cdn.shopify.com/static/images/favicon.ico"
        default:
            raw = nil
        }
        guard let raw, let url = URL(string: raw) else { return nil }
        return url
    }

    // MARK: - Link parsing

    private struct Icon {
        var url: URL
        var width: Int
        var rich: Bool
        var kind: Kind
    }

    private enum Kind {
        case raster, ico, svg
    }

    private static func declaredIcons(in html: String, document: URL) -> [URL] {
        let base = resolutionBase(in: html, document: document)
        let icons = parseIcons(in: head(html), base: base)
        let raster = icons.filter { $0.kind != .svg }
        let rich = raster.filter(\.rich).sorted { a, b in
            let ra = rank(a, target: 128), rb = rank(b, target: 128)
            return ra == rb ? a.url.absoluteString < b.url.absoluteString : ra < rb
        }
        let plain = raster.filter { !$0.rich && $0.kind == .raster }.sorted { a, b in
            let ra = rank(a, target: 64), rb = rank(b, target: 64)
            return ra == rb ? a.url.absoluteString < b.url.absoluteString : ra < rb
        }
        let icos = raster.filter { !$0.rich && $0.kind == .ico }
        // SVG is a real declaration; the loader rasterizes it, so it beats the
        // guessed `/favicon.ico`. Many sites only ship an SVG icon.
        let svgs = icons.filter { $0.kind == .svg }
        return (rich + plain + icos + svgs).map(\.url)
    }

    /// True for a declared `.svg` icon; the loader must rasterize it, not decode it.
    static func isSvgIcon(_ url: URL) -> Bool {
        url.path.lowercased().hasSuffix(".svg") || url.lastPathComponent.lowercased().contains("favicon.svg")
    }

    /// Unknown sizes sort after a sized icon. Closer to `target` wins.
    private static func rank(_ icon: Icon, target: Int) -> (Int, Int, Int) {
        guard icon.width > 0 else { return (1, 0, 0) }
        return (0, abs(icon.width - target), -icon.width)
    }

    private static func parseIcons(in html: String, base: URL) -> [Icon] {
        guard let regex = try? NSRegularExpression(pattern: "<link\\b[^>]*>", options: [.caseInsensitive]) else {
            return []
        }
        let range = NSRange(html.startIndex..., in: html)
        var icons: [Icon] = []
        for match in regex.matches(in: html, range: range) {
            guard let tagRange = Range(match.range, in: html) else { continue }
            let tag = String(html[tagRange])
            guard let icon = icon(from: tag, base: base) else { continue }
            icons.append(icon)
        }
        return icons
    }

    private static func icon(from tag: String, base: URL) -> Icon? {
        guard attribute("color", in: tag) == nil else { return nil }
        guard let rel = attribute("rel", in: tag)?.lowercased(), !rel.contains("mask") else { return nil }
        let tokens = Set(rel.split { $0.isWhitespace }.map(String.init))
        let rich = !tokens.isDisjoint(with: ["apple-touch-icon", "apple-touch-icon-precomposed", "fluid-icon"])
        let isIcon = rich || tokens.contains("icon")
        guard isIcon, let href = attribute("href", in: tag) else { return nil }
        let decoded = decodeEntities(href.trimmingCharacters(in: .whitespacesAndNewlines))
        guard let resolved = URL(string: decoded, relativeTo: base)?.absoluteURL,
              let url = httpURL(resolved) else { return nil }
        let type = attribute("type", in: tag)
        return Icon(url: url, width: iconWidth(attribute("sizes", in: tag)), rich: rich, kind: kind(type: type, url: url))
    }

    private static func kind(type: String?, url: URL) -> Kind {
        let declared = type?.lowercased() ?? ""
        let path = url.path.lowercased()
        if declared.contains("svg") || path.hasSuffix(".svg") { return .svg }
        if declared.contains("icon") || declared.contains("ico") || path.hasSuffix(".ico") { return .ico }
        return .raster
    }

    private static func iconWidth(_ sizes: String?) -> Int {
        guard let sizes, let regex = try? NSRegularExpression(
            pattern: "([1-9][0-9]*)x[1-9][0-9]*",
            options: [.caseInsensitive]
        ) else { return 0 }
        let range = NSRange(sizes.startIndex..., in: sizes)
        var best = 0
        for match in regex.matches(in: sizes, range: range) {
            guard let group = Range(match.range(at: 1), in: sizes),
                  let width = Int(sizes[group]) else { continue }
            best = max(best, width)
        }
        return best
    }

    private static func resolutionBase(in html: String, document: URL) -> URL {
        guard let regex = try? NSRegularExpression(pattern: "<base\\b[^>]*>", options: [.caseInsensitive]) else {
            return document
        }
        let scoped = head(html)
        let range = NSRange(scoped.startIndex..., in: scoped)
        guard let match = regex.firstMatch(in: scoped, range: range),
              let tagRange = Range(match.range, in: scoped),
              let href = attribute("href", in: String(scoped[tagRange])) else { return document }
        let decoded = decodeEntities(href.trimmingCharacters(in: .whitespacesAndNewlines))
        guard let resolved = URL(string: decoded, relativeTo: document)?.absoluteURL,
              let url = httpURL(resolved) else { return document }
        return url
    }

    private static func head(_ html: String) -> String {
        let stripped = html.replacingOccurrences(
            of: "<!--[\\s\\S]*?-->",
            with: "",
            options: .regularExpression
        )
        if let end = stripped.range(of: "</head>", options: [.caseInsensitive]) {
            return String(stripped[..<end.upperBound])
        }
        return stripped
    }

    private static func originFavicon(_ page: URL) -> URL? {
        guard var components = URLComponents(url: page, resolvingAgainstBaseURL: true) else { return nil }
        components.user = nil
        components.password = nil
        components.path = "/favicon.ico"
        components.query = nil
        components.fragment = nil
        return components.url
    }

    private static func isInterstitial(_ url: URL) -> Bool {
        guard let host = url.host?.lowercased() else { return false }
        return interstitialHosts.contains(host)
    }

    private static func httpURL(_ url: URL) -> URL? {
        guard var components = URLComponents(url: url, resolvingAgainstBaseURL: true) else { return nil }
        guard let scheme = components.scheme?.lowercased(), scheme == "http" || scheme == "https" else { return nil }
        guard let host = components.host, !host.isEmpty else { return nil }
        components.scheme = scheme
        components.host = host
        components.user = nil
        components.password = nil
        return components.url
    }

    private static func attribute(_ name: String, in tag: String) -> String? {
        let pattern = "\\b\(NSRegularExpression.escapedPattern(for: name))\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'=<>`]+))"
        guard let regex = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]) else { return nil }
        let range = NSRange(tag.startIndex..., in: tag)
        guard let match = regex.firstMatch(in: tag, range: range) else { return nil }
        for index in 1..<match.numberOfRanges {
            guard let group = Range(match.range(at: index), in: tag) else { continue }
            return String(tag[group])
        }
        return nil
    }

    private static func decodeEntities(_ text: String) -> String {
        var result = text.replacingOccurrences(of: "&amp;", with: "&")
        result = result.replacingOccurrences(of: "&quot;", with: "\"")
        result = result.replacingOccurrences(of: "&#39;", with: "'")
        result = result.replacingOccurrences(of: "&apos;", with: "'")
        result = result.replacingOccurrences(of: "&lt;", with: "<")
        result = result.replacingOccurrences(of: "&gt;", with: ">")
        return result
    }
}
