import Foundation

/// Maps raw errors to one-line, user-appropriate messages. Technical detail
/// stays in os_log; the UI never shows HTTP bodies or server internals.
extension Error {
    var zenUserMessage: String {
        if let urlError = self as? URLError, Self.isOffline(urlError.code) {
            return String(localized: "error.offline")
        }
        let ns = self as NSError
        if ns.domain == NSURLErrorDomain, Self.isOffline(URLError.Code(rawValue: ns.code)) {
            return String(localized: "error.offline")
        }
        if let sync = self as? SyncError {
            switch sync {
            case .totpRequired, .notSignedIn:
                return sync.localizedDescription
            case .storageUnavailable:
                return String(localized: "error.storage_unavailable")
            case .conflict:
                return String(localized: "error.conflict")
            case .auth:
                return String(localized: "error.auth")
            case .crypto:
                return String(localized: "error.crypto")
            case .network:
                return String(localized: "error.network")
            }
        }
        return String(localized: "error.generic")
    }

    private static func isOffline(_ code: URLError.Code) -> Bool {
        switch code {
        case .notConnectedToInternet, .networkConnectionLost, .timedOut,
             .cannotFindHost, .dnsLookupFailed, .dataNotAllowed:
            true
        default:
            false
        }
    }
}
