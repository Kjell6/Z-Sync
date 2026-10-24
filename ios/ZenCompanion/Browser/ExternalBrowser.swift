import UIKit

/// Opens a URL with the system default handler (default browser for http(s),
/// or the app that owns a Universal Link — that's iOS, not us).
enum ExternalBrowser {
    /// Hands the URL to its owner. The completion reports whether anything took
    /// it - a scheme nobody handles fails there, and the caller has to say so.
    /// No `canOpenURL` gate: that would need every scheme in
    /// `LSApplicationQueriesSchemes`, while opening needs none.
    static func open(_ url: URL, completion: ((Bool) -> Void)? = nil) {
        UIApplication.shared.open(url, options: [:]) { opened in
            completion?(opened)
        }
    }
}
