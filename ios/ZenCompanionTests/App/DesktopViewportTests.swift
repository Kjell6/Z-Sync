import WebKit
import XCTest

@testable import ZenCompanion

/// "Request Desktop Site" must also widen pages that size themselves to the
/// screen, or responsive sites keep their mobile version.
@MainActor
final class DesktopViewportTests: XCTestCase {
    private let site = URL(string: "https://example.com/")!

    private func hasViewportScript(_ webView: WKWebView) -> Bool {
        webView.configuration.userContentController.userScripts
            .contains { $0.source == DesktopViewportScript.source }
    }

    func testDesktopSiteAddsTheViewportScriptAndTakesItAwayAgain() {
        let model = MiniBrowserModel(initialURL: nil, preferences: FakePreferences())
        let webView = MiniBrowserWebDriver.attach(to: model)

        model.prepareBlocking(for: site)
        XCTAssertFalse(hasViewportScript(webView))

        model.toggleDesktopSite()
        model.prepareBlocking(for: site)
        XCTAssertTrue(hasViewportScript(webView))

        model.toggleDesktopSite()
        model.prepareBlocking(for: site)
        XCTAssertFalse(hasViewportScript(webView))
    }

    /// The script itself, in a plain web view: a `width=device-width` page
    /// lays out at the desktop width, also when the tag comes in later.
    func testScriptLaysAResponsivePageOutAtDesktopWidth() async throws {
        let configuration = WKWebViewConfiguration()
        configuration.userContentController.addUserScript(WKUserScript(
            source: DesktopViewportScript.source,
            injectionTime: .atDocumentStart,
            forMainFrameOnly: true
        ))
        let webView = WKWebView(frame: CGRect(x: 0, y: 0, width: 402, height: 874), configuration: configuration)
        let window = UIWindow(frame: webView.frame)
        window.addSubview(webView)
        window.isHidden = false
        webView.loadHTMLString("""
        <html><head><meta name="viewport" content="width=device-width, initial-scale=1"></head>
        <body><script>
        var late = document.createElement('meta');
        late.name = 'viewport';
        late.content = 'width=device-width';
        document.head.appendChild(late);
        </script></body></html>
        """, baseURL: site)

        var width: Int?
        for _ in 0..<50 where width != DesktopViewportScript.width {
            try await Task.sleep(for: .milliseconds(100))
            guard !webView.isLoading else { continue }
            width = try? await webView.evaluateJavaScript("window.innerWidth") as? Int
        }
        XCTAssertEqual(width, DesktopViewportScript.width)
        let contents = try await webView.evaluateJavaScript(
            "Array.from(document.querySelectorAll('meta[name=viewport]')).map(function (m) { return m.content; }).join(',')"
        ) as? String
        XCTAssertEqual(contents, "width=980,width=980")
        withExtendedLifetime(window) {}
    }
}
