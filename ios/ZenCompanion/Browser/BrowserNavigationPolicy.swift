import Foundation

enum BrowserNavigationPolicy {
    private static let webSchemes: Set<String> = ["http", "https", "about", "data", "blob", "file", "javascript"]

    static func loadsInWebView(_ url: URL) -> Bool {
        guard let scheme = url.scheme?.lowercased() else { return true }
        return webSchemes.contains(scheme)
    }

    /// The web address of a link or window, or nil for anything else. Decides
    /// what "Open in New Page" is offered on, and what a window can load when
    /// no open pages are around to give it a page of its own.
    static func sameTabURL(_ url: URL?) -> URL? {
        guard let url, loadsInWebView(url) else { return nil }
        switch url.scheme?.lowercased() {
        case "http", "https":
            return url
        default:
            return nil
        }
    }

    /// The app name WebKit appends to the user agent: Safari's own ending.
    /// Without `Version/… Safari/…` sites read the page as an in-app web view,
    /// and some send it away instead of answering - X redirects to
    /// `x-safari-https://`, which only Safari opens, so the page stayed blank.
    static func safariApplicationName(systemVersion: String) -> String {
        "Version/\(safariVersion(systemVersion)) Mobile/15E148 Safari/604.1"
    }

    /// The whole user agent for "Request Desktop Site": Safari on a Mac, as
    /// Safari on iPhone sends it. WebKit adds the app name in desktop mode
    /// too, and its `Mobile` token would bring the mobile site back.
    static func desktopUserAgent(systemVersion: String) -> String {
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 "
            + "(KHTML, like Gecko) Version/\(safariVersion(systemVersion)) Safari/605.1.15"
    }

    /// `26.1` from `26.1.2`; `26.0` from `26`.
    private static func safariVersion(_ systemVersion: String) -> String {
        let parts = systemVersion.split(separator: ".")
        let major = parts.first.map(String.init) ?? "26"
        let minor = parts.count > 1 ? String(parts[1]) : "0"
        return "\(major).\(minor)"
    }

    /// Another app opens only from a link the user tapped in the main page.
    /// Script, redirect and iframe navigations to app schemes are dropped,
    /// matching the Android policy.
    static func opensExternally(userTapped: Bool, isMainFrame: Bool) -> Bool {
        userTapped && isMainFrame
    }
}
