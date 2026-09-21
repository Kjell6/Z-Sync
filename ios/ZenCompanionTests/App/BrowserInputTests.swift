import XCTest

@testable import ZenCompanion

/// URL/search resolution parity for the address field: an explicit scheme
/// wins, `dot-and-no-space` is a host, everything else searches — with
/// `dots and a space` deliberately searching (never treated as a host).
final class BrowserInputTests: XCTestCase {
    func testEmptyQueryResolvesToNil() {
        XCTAssertNil(BrowserInput.resolve("", engine: .duckDuckGo))
        XCTAssertNil(BrowserInput.resolve("   \n\t ", engine: .google))
    }

    func testWhitespaceIsTrimmedBeforeResolving() {
        XCTAssertEqual(
            BrowserInput.resolve("  example.com \n", engine: .duckDuckGo),
            URL(string: "https://example.com")
        )
    }

    func testExplicitSchemeIsPassedThrough() {
        XCTAssertEqual(
            BrowserInput.resolve("https://example.com/path?q=1", engine: .duckDuckGo),
            URL(string: "https://example.com/path?q=1")
        )
        XCTAssertEqual(
            BrowserInput.resolve("mailto:support@kjell.cc", engine: .duckDuckGo),
            URL(string: "mailto:support@kjell.cc")
        )
    }

    func testDotWithoutSpaceBecomesHTTPS() {
        XCTAssertEqual(
            BrowserInput.resolve("example.com", engine: .duckDuckGo),
            URL(string: "https://example.com")
        )
        XCTAssertEqual(
            BrowserInput.resolve("192.168.0.1:8080", engine: .duckDuckGo),
            URL(string: "https://192.168.0.1:8080")
        )
    }

    func testDotWithSpaceSearches() {
        let resolved = BrowserInput.resolve("hello.world foo", engine: .duckDuckGo)
        XCTAssertEqual(resolved, SearchEngine.duckDuckGo.searchURL(for: "hello.world foo"))
        XCTAssertTrue(resolved?.absoluteString.contains("duckduckgo.com") == true)
    }

    func testPlainQuerySearchesWithSelectedEngine() {
        for engine in ZenCompanion.SearchEngines.builtIn {
            XCTAssertEqual(
                BrowserInput.resolve("zen companion", engine: engine),
                engine.searchURL(for: "zen companion"),
                "resolution must use the selected engine \(engine.id)"
            )
        }
    }

    func testDisplayTextPrefersHost() {
        XCTAssertEqual(
            BrowserInput.displayText(for: URL(string: "https://example.com/some/path")!),
            "example.com"
        )
        XCTAssertEqual(
            BrowserInput.displayText(for: URL(string: "about:config")!),
            "about:config"
        )
    }
}
