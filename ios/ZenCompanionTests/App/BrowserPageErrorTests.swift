import XCTest

@testable import ZenCompanion

final class BrowserPageErrorTests: XCTestCase {
    func testLoadFailuresMapToTheirPanel() {
        let cases: [(Int, BrowserPageError)] = [
            (NSURLErrorNotConnectedToInternet, .offline),
            (NSURLErrorDataNotAllowed, .offline),
            (NSURLErrorInternationalRoamingOff, .offline),
            (NSURLErrorCannotFindHost, .hostNotFound),
            (NSURLErrorDNSLookupFailed, .hostNotFound),
            (NSURLErrorTimedOut, .notResponding),
            (NSURLErrorCannotConnectToHost, .notResponding),
            (NSURLErrorNetworkConnectionLost, .notResponding),
            (NSURLErrorSecureConnectionFailed, .insecure),
            (NSURLErrorServerCertificateHasBadDate, .insecure),
            (NSURLErrorServerCertificateUntrusted, .insecure),
            (NSURLErrorServerCertificateHasUnknownRoot, .insecure),
            (NSURLErrorServerCertificateNotYetValid, .insecure),
        ]
        for (code, expected) in cases {
            XCTAssertEqual(BrowserPageError.forLoadFailure(domain: NSURLErrorDomain, code: code), expected, "\(code)")
        }
    }

    func testCancelledAndInterruptedLoadsShowNoPanel() {
        XCTAssertNil(BrowserPageError.forLoadFailure(domain: NSURLErrorDomain, code: NSURLErrorCancelled))
        XCTAssertNil(BrowserPageError.forLoadFailure(domain: NSURLErrorDomain, code: NSURLErrorUnsupportedURL))
        // WebKit's "frame load interrupted": a download or an app link ended the navigation.
        XCTAssertNil(BrowserPageError.forLoadFailure(domain: "WebKitErrorDomain", code: 102))
    }

    func testGoBackShowsThePageUnderneathWhenTheLoadNeverReplacedIt() {
        let old = URL(string: "https://previous.example")
        let failed = URL(string: "https://expired.example")
        XCTAssertEqual(BrowserPageError.exit(currentURL: old, failedURL: failed, canGoBack: true), .reveal)
        XCTAssertEqual(BrowserPageError.exit(currentURL: failed, failedURL: failed, canGoBack: true), .goBack)
        XCTAssertEqual(BrowserPageError.exit(currentURL: failed, failedURL: failed, canGoBack: false), .dismiss)
        XCTAssertEqual(BrowserPageError.exit(currentURL: nil, failedURL: failed, canGoBack: false), .dismiss)
    }
}
