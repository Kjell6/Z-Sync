import UIKit

/// Opens a URL with the system default handler (default browser for http(s),
/// or the app that owns a Universal Link — that's iOS, not us).
enum ExternalBrowser {
    static func open(_ url: URL) {
        UIApplication.shared.open(url)
    }
}
