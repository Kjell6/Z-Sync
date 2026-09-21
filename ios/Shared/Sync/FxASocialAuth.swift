import Foundation

/// Mozilla's Continue with Apple / Google cannot complete Firefox Sync without
/// a Mozilla password afterwards. App Review treats that as Guideline 4, so
/// the in-app WebView must not open those OAuth hosts.
enum FxASocialAuth {
    static func shouldBlock(_ url: URL) -> Bool {
        guard let host = url.host?.lowercased() else { return false }
        if host == "appleid.apple.com" || host.hasSuffix(".appleid.apple.com") {
            return true
        }
        if host == "accounts.google.com" || host.hasSuffix(".accounts.google.com") {
            return true
        }
        return false
    }
}
