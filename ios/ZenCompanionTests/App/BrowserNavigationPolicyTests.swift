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
}
