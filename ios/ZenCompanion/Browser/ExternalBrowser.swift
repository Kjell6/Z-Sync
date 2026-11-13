import UIKit

/// Opens a URL with the system default handler (default browser for http(s),
/// or the app that owns a Universal Link — that's iOS, not us).
enum ExternalBrowser {
    /// How long an http(s) address that left the app still counts as this
    /// app's own hand-off when it comes straight back through `onOpenURL`.
    /// Long enough for the system to switch back, short enough that a later
    /// link from another app is a fresh arrival.
    private static let roundTripWindow: TimeInterval = 10

    /// The last web address the app asked the system to open. Main thread only,
    /// like the UIKit call below.
    private static var lastHandOff: (url: URL, at: Date)?

    /// Hands the URL to its owner. The completion reports whether anything took
    /// it - a scheme nobody handles fails there, and the caller has to say so.
    /// No `canOpenURL` gate: that would need every scheme in
    /// `LSApplicationQueriesSchemes`, while opening needs none.
    static func open(_ url: URL, completion: ((Bool) -> Void)? = nil) {
        recordHandOff(url)
        UIApplication.shared.open(url, options: [:]) { opened in
            completion?(opened)
        }
    }

    /// Remembers a web address the app is about to send out. Only those can
    /// come back to Z-Sync once it holds the browser role.
    static func recordHandOff(_ url: URL, at date: Date = Date()) {
        guard BrowserInput.isWebAddress(url) else { return }
        lastHandOff = (url, date)
    }

    /// True when [url] is the very address this app just handed to the system
    /// and iOS is handing it back, because Z-Sync is the default browser. The
    /// caller opens it in the in-app browser instead of sending it out again,
    /// so the two can never keep passing one link back and forth.
    static func consumeOwnHandOff(_ url: URL, now: Date = Date()) -> Bool {
        guard let handOff = lastHandOff else { return false }
        guard now.timeIntervalSince(handOff.at) <= roundTripWindow else {
            lastHandOff = nil
            return false
        }
        guard handOff.url.absoluteString == url.absoluteString else { return false }
        lastHandOff = nil
        return true
    }
}
