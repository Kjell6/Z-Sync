import Foundation

enum BrowserNavigationPolicy {
    private static let webSchemes: Set<String> = ["http", "https", "about", "data", "blob", "file", "javascript"]

    static func loadsInWebView(_ url: URL) -> Bool {
        guard let scheme = url.scheme?.lowercased() else { return true }
        return webSchemes.contains(scheme)
    }
}
