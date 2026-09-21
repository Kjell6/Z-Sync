import XCTest

@testable import ZenCompanion

/// Search-engine model: built-in catalogue, template substitution, draft
/// validation, "paste a search link" derivation and the persistence of
/// user-defined engines.
final class SearchEngineTests: XCTestCase {
    private let selectedKey = "selected_search_engine"
    private let customKey = "custom_search_engines"

    override func setUp() {
        super.setUp()
        clearPersistence()
    }

    override func tearDown() {
        clearPersistence()
        super.tearDown()
    }

    private func clearPersistence() {
        for defaults in [AppGroup.defaults, UserDefaults.standard] {
            defaults.removeObject(forKey: selectedKey)
            defaults.removeObject(forKey: customKey)
        }
    }

    // MARK: - Catalogue

    func testBuiltInsHaveStableOrderAndIDs() {
        XCTAssertEqual(
            SearchEngines.builtIn.map { $0.id },
            ["duckduckgo", "google", "ecosia", "brave", "bing"]
        )
        XCTAssertTrue(SearchEngines.builtIn.allSatisfy { $0.isBuiltIn })
    }

    func testSearchURLSubstitutesAndEncodes() {
        XCTAssertEqual(
            SearchEngine.duckDuckGo.searchURL(for: "zen companion"),
            URL(string: "https://duckduckgo.com/?q=zen%20companion")
        )
    }

    func testCustomTemplateSearchURL() {
        let engine = SearchEngine(
            id: "kagi", displayName: "Kagi",
            template: "https://kagi.com/search?q={query}", isBuiltIn: false
        )
        XCTAssertEqual(
            engine.searchURL(for: "swift"),
            URL(string: "https://kagi.com/search?q=swift")
        )
    }

    // MARK: - Validation

    func testValidateRejectsEmptyName() {
        XCTAssertEqual(
            SearchEngineTemplate.validate(name: "  ", template: "https://x.com/?q={query}"),
            .emptyName
        )
    }

    func testValidateRejectsNonHTTPTemplates() {
        XCTAssertEqual(
            SearchEngineTemplate.validate(name: "Evil", template: "javascript:alert(1){query}"),
            .invalidURL
        )
        XCTAssertEqual(
            SearchEngineTemplate.validate(name: "Evil", template: "file:///tmp/{query}"),
            .invalidURL
        )
    }

    func testValidateRequiresPlaceholder() {
        XCTAssertEqual(
            SearchEngineTemplate.validate(name: "Kagi", template: "https://kagi.com/search?q=test"),
            .missingPlaceholder
        )
    }

    func testValidateAcceptsGoodDrafts() {
        XCTAssertNil(SearchEngineTemplate.validate(name: "Kagi", template: "https://kagi.com/search?q={query}"))
        XCTAssertNil(SearchEngineTemplate.validate(name: "Searx", template: "http://localhost:8080/search?q={query}"))
    }

    // MARK: - Paste derivation

    func testDeriveReplacesPreferredQueryParameter() {
        XCTAssertEqual(
            SearchEngineTemplate.derive(fromPastedURL: "https://kagi.com/search?q=blauer+kaffee"),
            "https://kagi.com/search?q={query}"
        )
    }

    func testDeriveKeepsOtherParametersAndFragment() {
        XCTAssertEqual(
            SearchEngineTemplate.derive(fromPastedURL: "https://x.com/s?lang=de&q=hello#top"),
            "https://x.com/s?lang=de&q={query}#top"
        )
    }

    func testDeriveFallsBackToLastValuedParameter() {
        XCTAssertEqual(
            SearchEngineTemplate.derive(fromPastedURL: "https://search.example/find?term=abc"),
            "https://search.example/find?term={query}"
        )
    }

    func testDeriveRejectsNonSearches() {
        XCTAssertNil(SearchEngineTemplate.derive(fromPastedURL: "https://example.com/path"))
        XCTAssertNil(SearchEngineTemplate.derive(fromPastedURL: "not a url"))
        XCTAssertNil(SearchEngineTemplate.derive(fromPastedURL: "javascript:alert(1)"))
    }

    // MARK: - Suggested name

    func testSuggestedNameUsesRegistrableDomain() {
        // A sub-domain like "de" or a "search." prefix must not become the name.
        XCTAssertEqual(
            SearchEngineTemplate.suggestedName(fromTemplate: "https://de.search.yahoo.com/search?p={query}"),
            "Yahoo"
        )
        XCTAssertEqual(
            SearchEngineTemplate.suggestedName(fromTemplate: "https://search.brave.com/search?q={query}"),
            "Brave"
        )
        XCTAssertEqual(
            SearchEngineTemplate.suggestedName(fromTemplate: "https://www.kagi.com/search?q={query}"),
            "Kagi"
        )
        XCTAssertEqual(
            SearchEngineTemplate.suggestedName(fromTemplate: "https://duckduckgo.com/?q={query}"),
            "Duckduckgo"
        )
        XCTAssertEqual(
            SearchEngineTemplate.suggestedName(fromTemplate: "https://www.bbc.co.uk/search?q={query}"),
            "Bbc"
        )
        XCTAssertEqual(
            SearchEngineTemplate.suggestedName(fromTemplate: "http://localhost:8080/search?q={query}"),
            "Localhost"
        )
        XCTAssertNil(SearchEngineTemplate.suggestedName(fromTemplate: "not a url"))
    }

    // MARK: - Persistence

    func testCurrentDefaultsToDuckDuckGo() {
        XCTAssertEqual(SearchEngines.current, .duckDuckGo)
    }

    func testAddCustomPersistsAndCanBeSelected() {
        let engine = SearchEngines.addCustom(name: "Kagi", template: "https://kagi.com/search?q={query}")
        XCTAssertEqual(engine.displayName, "Kagi")
        XCTAssertEqual(SearchEngines.custom.count, 1)
        XCTAssertTrue(SearchEngines.all.contains(engine))

        SearchEngines.current = engine
        XCTAssertEqual(SearchEngines.current, engine)
    }

    func testCustomEngineRoundTripsThroughEncoding() {
        _ = SearchEngines.addCustom(name: "Kagi", template: "https://kagi.com/search?q={query}")
        let reloaded = SearchEngines.custom
        XCTAssertEqual(reloaded.first?.template, "https://kagi.com/search?q={query}")
        XCTAssertEqual(reloaded.first?.isBuiltIn, false)
    }

    func testDeletingSelectedCustomFallsBack() {
        let engine = SearchEngines.addCustom(name: "Kagi", template: "https://kagi.com/search?q={query}")
        SearchEngines.current = engine

        SearchEngines.deleteCustom(id: engine.id)

        XCTAssertTrue(SearchEngines.custom.isEmpty)
        XCTAssertEqual(SearchEngines.current, .duckDuckGo)
    }
}
