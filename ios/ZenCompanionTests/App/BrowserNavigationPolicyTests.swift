import XCTest

@testable import ZenCompanion

final class BrowserNavigationPolicyTests: XCTestCase {
    func testWebSchemesLoadInWebView() {
        let urls = [
            "https://example.com",
            "http://example.com",
            "about:blank",
            "data:text/plain,hi",
            "blob:https://example.com/1234",
            "file:///tmp/index.html",
            "javascript:void(0)",
        ]
        for raw in urls {
            XCTAssertTrue(
                BrowserNavigationPolicy.loadsInWebView(URL(string: raw)!),
                raw
            )
        }
    }

    func testAppSchemesOpenExternally() {
        let urls = [
            "mailto:test@example.com",
            "tel:+491234567",
            "sms:+491234567",
            "facetime:+491234567",
            "youtube://watch?v=abc",
            "intent://watch?v=abc#Intent;scheme=youtube;end",
        ]
        for raw in urls {
            XCTAssertFalse(
                BrowserNavigationPolicy.loadsInWebView(URL(string: raw)!),
                raw
            )
        }
    }

    func testSchemeMatchingIsCaseInsensitive() {
        XCTAssertTrue(BrowserNavigationPolicy.loadsInWebView(URL(string: "HTTPS://EXAMPLE.COM")!))
    }

    func testUrlWithoutSchemeStaysInWebView() {
        XCTAssertTrue(BrowserNavigationPolicy.loadsInWebView(URL(string: "example.com")!))
    }

    func testNewWindowKeepsHttpAndHttpsInTheSameTab() {
        XCTAssertEqual(
            BrowserNavigationPolicy.sameTabURL(URL(string: "http://www.org.chemie.uni-muenchen.de/ac/kornath/flusssaeure-hf/"))?.absoluteString,
            "http://www.org.chemie.uni-muenchen.de/ac/kornath/flusssaeure-hf/"
        )
        XCTAssertEqual(
            BrowserNavigationPolicy.sameTabURL(URL(string: "https://www.google.com/goto?url=abc"))?.absoluteString,
            "https://www.google.com/goto?url=abc"
        )
    }

    func testBlankNewWindowDoesNotReplaceThePage() {
        XCTAssertNil(BrowserNavigationPolicy.sameTabURL(URL(string: "about:blank")))
        XCTAssertNil(BrowserNavigationPolicy.sameTabURL(URL(string: "about:blank/")))
        XCTAssertNil(BrowserNavigationPolicy.sameTabURL(URL(string: "javascript:void(0)")))
        XCTAssertNil(BrowserNavigationPolicy.sameTabURL(nil))
    }

    /// Another app opens only from a tap in the main page; script, redirect
    /// and iframe navigations are dropped (matches Android `BLOCK`).
    func testOtherAppsOpenOnlyFromATapInTheMainPage() {
        XCTAssertTrue(BrowserNavigationPolicy.opensExternally(userTapped: true, isMainFrame: true))
        XCTAssertFalse(BrowserNavigationPolicy.opensExternally(userTapped: false, isMainFrame: true))
        XCTAssertFalse(BrowserNavigationPolicy.opensExternally(userTapped: true, isMainFrame: false))
        XCTAssertFalse(BrowserNavigationPolicy.opensExternally(userTapped: false, isMainFrame: false))
    }

    /// Sites such as X send a user agent without Safari's ending to
    /// `x-safari-https://`, which leaves the page blank.
    func testUserAgentEndsLikeSafari() {
        XCTAssertEqual(
            BrowserNavigationPolicy.safariApplicationName(systemVersion: "26.1"),
            "Version/26.1 Mobile/15E148 Safari/604.1"
        )
        XCTAssertEqual(
            BrowserNavigationPolicy.safariApplicationName(systemVersion: "26"),
            "Version/26.0 Mobile/15E148 Safari/604.1"
        )
        XCTAssertEqual(
            BrowserNavigationPolicy.safariApplicationName(systemVersion: "18.6.2"),
            "Version/18.6 Mobile/15E148 Safari/604.1"
        )
    }

    /// Desktop mode must not carry the `Mobile` token, or sites keep serving
    /// the mobile version.
    func testDesktopUserAgentIsSafariOnAMac() {
        let agent = BrowserNavigationPolicy.desktopUserAgent(systemVersion: "26.1")
        XCTAssertEqual(
            agent,
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.1 Safari/605.1.15"
        )
        XCTAssertFalse(agent.contains("Mobile"))
    }
}
