import Foundation

/// Why the quick browser shows a panel instead of the page. Same cases as
/// the Android `BrowserPageError`.
enum BrowserPageError: Equatable {
    case offline
    case hostNotFound
    case notResponding
    case insecure
    case crashed

    /// The panel for a load that failed before the page appeared, or nil when
    /// the failure needs none: a cancelled load, or a download or app link
    /// that ended the navigation (WebKit's "frame load interrupted").
    static func forLoadFailure(domain: String, code: Int) -> BrowserPageError? {
        guard domain == NSURLErrorDomain else { return nil }
        switch code {
        case NSURLErrorNotConnectedToInternet,
             NSURLErrorDataNotAllowed,
             NSURLErrorInternationalRoamingOff:
            return .offline
        case NSURLErrorCannotFindHost,
             NSURLErrorDNSLookupFailed:
            return .hostNotFound
        case NSURLErrorTimedOut,
             NSURLErrorCannotConnectToHost,
             NSURLErrorNetworkConnectionLost:
            return .notResponding
        case NSURLErrorSecureConnectionFailed,
             NSURLErrorServerCertificateHasBadDate,
             NSURLErrorServerCertificateUntrusted,
             NSURLErrorServerCertificateHasUnknownRoot,
             NSURLErrorServerCertificateNotYetValid:
            return .insecure
        default:
            return nil
        }
    }

    /// What "Go back" on the panel does.
    enum Exit: Equatable {
        /// The failed load never replaced the page underneath: show it again.
        case reveal
        case goBack
        /// Nothing to go back to: close the browser.
        case dismiss
    }

    static func exit(currentURL: URL?, failedURL: URL?, canGoBack: Bool) -> Exit {
        if currentURL != nil, !PageTitle.sameDocument(currentURL, failedURL) {
            return .reveal
        }
        return canGoBack ? .goBack : .dismiss
    }
}
