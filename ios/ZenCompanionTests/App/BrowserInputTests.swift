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
            URL(string: "http://192.168.0.1:8080"),
            "IP addresses and localhost use http, like Safari and Chrome"
        )
    }

    /// Same table as Android `BrowserInputTests`: page addresses resolve.
    func testPageAddressesResolve() {
        let cases: [(String, String)] = [
            ("example.com", "https://example.com"),
            ("github.com/torvalds/linux", "https://github.com/torvalds/linux"),
            ("example.com:8080/x", "https://example.com:8080/x"),
            ("localhost", "http://localhost"),
            ("localhost:3000/api", "http://localhost:3000/api"),
            ("192.168.0.1:8080", "http://192.168.0.1:8080"),
            ("https://example.com/path?q=1", "https://example.com/path?q=1"),
            ("HTTP://Example.com", "HTTP://Example.com"),
            ("mailto:support@kjell.cc", "mailto:support@kjell.cc"),
            ("tel:+391234567", "tel:+391234567"),
            ("about:blank", "about:blank"),
        ]
        for (input, expected) in cases {
            XCTAssertEqual(BrowserInput.typedURL(input)?.absoluteString, expected, input)
        }
    }

    /// Same table as Android: anything that isn't a page address searches,
    /// including script and file URLs.
    func testEverythingElseSearches() {
        let searches = [
            "site:example.com",
            "javascript:alert(1)",
            "file:///etc/hosts",
            "intent://scan#Intent;scheme=zxing;end",
            "data:text/html,hi",
            "3.14",
            "hello.world foo",
            "zen companion",
        ]
        for input in searches {
            XCTAssertNil(BrowserInput.typedURL(input), input)
            XCTAssertEqual(
                BrowserInput.resolve(input, engine: .duckDuckGo),
                SearchEngine.duckDuckGo.searchURL(for: input),
                input
            )
        }
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

    /// Links from other apps may only be web addresses: anything else would
    /// reach the browser as script, a file or another app's data.
    func testOnlyWebAddressesAreAcceptedFromOtherApps() {
        XCTAssertTrue(BrowserInput.isWebAddress(URL(string: "https://example.com/a?b=1")!))
        XCTAssertTrue(BrowserInput.isWebAddress(URL(string: "http://localhost:8765/x")!))
        XCTAssertTrue(BrowserInput.isWebAddress(URL(string: "HTTPS://Example.com")!))
        for raw in [
            "file:///etc/hosts",
            "javascript:alert(1)",
            "data:text/html,hi",
            "mailto:nobody@example.com",
            "tel:+391234567",
            "about:blank",
        ] {
            XCTAssertFalse(BrowserInput.isWebAddress(URL(string: raw)!), raw)
        }
    }

    // MARK: - zsync:// links

    /// The app's own `zsync://open?url=` form opens a page in Z-Sync before
    /// Apple grants the browser role, so a Shortcut can hand one over.
    func testZsyncOpenLinkNamesItsTargetPage() {
        let cases: [(String, String)] = [
            (
                "zsync://open?url=https%3A%2F%2Fexample.com%2Fa%3Fb%3D1%26c%3D2",
                "https://example.com/a?b=1&c=2"
            ),
            // Encoded parameters inside the target survive exactly one decode.
            (
                "zsync://open?url=https%3A%2F%2Fexample.com%2Fsearch%3Fq%3Da%2520b%26page%3D2",
                "https://example.com/search?q=a%20b&page=2"
            ),
            // An http target, and a plus that is part of the address.
            (
                "zsync://open?url=http%3A%2F%2Flocalhost%3A8765%2Fx",
                "http://localhost:8765/x"
            ),
            (
                "zsync://open?url=https%3A%2F%2Fexample.com%2Fa+b",
                "https://example.com/a+b"
            ),
            // Scheme and host match like any URI; a bare slash is the same
            // host.
            (
                "ZSYNC://OPEN/?url=https%3A%2F%2Fexample.com%2F",
                "https://example.com/"
            ),
        ]
        for (raw, expected) in cases {
            XCTAssertEqual(
                BrowserInput.incomingWebAddress(URL(string: raw)!)?.absoluteString,
                expected,
                raw
            )
        }
    }

    /// A `zsync://` link is ignored silently unless it is exactly
    /// `zsync://open?url=<http(s) address with a host>`.
    func testZsyncOpenLinkAcceptsNothingElse() {
        for raw in [
            "zsync://other?url=https%3A%2F%2Fexample.com",
            "zsync://open/elsewhere?url=https%3A%2F%2Fexample.com",
            "zsync://open",
            "zsync://open?url=",
            "zsync://open?target=https%3A%2F%2Fexample.com",
            "zsync://open?url=https%3A%2F%2Fexample.com&x=1",
            "zsync://open?url=https%3A%2F%2F",
            "zsync:open?url=https%3A%2F%2Fexample.com",
        ] {
            XCTAssertNil(BrowserInput.incomingWebAddress(URL(string: raw)!), raw)
        }
        for encoded in [
            "javascript%3Aalert(1)",
            "file%3A%2F%2F%2Fetc%2Fhosts",
            "data%3Atext%2Fhtml%2Chi",
            "intent%3A%2F%2Fscan%2F%23Intent%3Bscheme%3Dzxing%3Bend",
            "mailto%3Anobody%40example.com",
        ] {
            let raw = "zsync://open?url=\(encoded)"
            XCTAssertNil(BrowserInput.incomingWebAddress(URL(string: raw)!), encoded)
        }
    }

    /// A plain web link from another app still passes through untouched, and
    /// a web address without a host does not.
    func testIncomingWebAddressPassesPlainWebLinks() {
        XCTAssertEqual(
            BrowserInput.incomingWebAddress(URL(string: "https://example.com/a?b=1")!)?.absoluteString,
            "https://example.com/a?b=1"
        )
        XCTAssertNil(BrowserInput.incomingWebAddress(URL(string: "https://")!))
    }
}
