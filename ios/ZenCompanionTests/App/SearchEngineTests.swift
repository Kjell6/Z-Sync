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

    // MARK: - Suggestions
    //
    // Bodies are the live answers from 2026-10-11, trimmed only by the parser's
    // eight-row cap. Each engine returned `["query", ["…"]]`. Google adds more
    // elements after the list.

    func testURLInputDoesNotBuildASuggestRequest() {
        let urls = [
            "https://example.com/private",
            "http://example.com/foo bar",
            "example.com",
            "192.168.0.1:8080",
            "mailto:support@kjell.cc",
            "   \n\t",
            "",
        ]
        for engine in SearchEngines.builtIn {
            for text in urls {
                XCTAssertNil(
                    SearchSuggestions.requestURL(for: text, engine: engine),
                    "\(engine.id) must not be asked about \(text.debugDescription)"
                )
            }
        }
    }

    func testSearchQueryBuildsTheCheckedEndpoint() {
        func query(_ engine: SearchEngine, _ name: String) -> String? {
            let url = SearchSuggestions.requestURL(for: "zen browser", engine: engine)
            return URLComponents(url: url!, resolvingAgainstBaseURL: false)?
                .queryItems?
                .first { $0.name == name }?
                .value
        }

        XCTAssertEqual(SearchSuggestions.requestURL(for: "zen browser", engine: .duckDuckGo)?.host, "duckduckgo.com")
        XCTAssertEqual(query(.duckDuckGo, "q"), "zen browser")
        XCTAssertEqual(query(.duckDuckGo, "type"), "list")

        XCTAssertEqual(SearchSuggestions.requestURL(for: "zen browser", engine: .google)?.host, "suggestqueries.google.com")
        XCTAssertEqual(query(.google, "q"), "zen browser")
        XCTAssertEqual(query(.google, "client"), "firefox")

        XCTAssertEqual(SearchSuggestions.requestURL(for: "zen browser", engine: .ecosia)?.host, "ac.ecosia.org")
        XCTAssertEqual(query(.ecosia, "type"), "list")

        XCTAssertEqual(SearchSuggestions.requestURL(for: "zen browser", engine: .brave)?.host, "search.brave.com")
        XCTAssertEqual(query(.brave, "q"), "zen browser")

        XCTAssertEqual(SearchSuggestions.requestURL(for: "zen browser", engine: .bing)?.host, "www.bing.com")
        XCTAssertEqual(query(.bing, "query"), "zen browser")
        XCTAssertNil(query(.bing, "q"))
    }

    func testDotWithSpaceStillSuggests() {
        XCTAssertNotNil(SearchSuggestions.requestURL(for: "hello.world foo", engine: .google))
    }

    func testCustomEngineHasNoSuggestRequest() {
        let custom = SearchEngine(
            id: "kagi",
            displayName: "Kagi",
            template: "https://kagi.com/search?q={query}",
            isBuiltIn: false
        )
        XCTAssertNil(custom.suggestEndpoint)
        XCTAssertNil(SearchSuggestions.requestURL(for: "kagi", engine: custom))
    }

    func testParseLiveSuggestBodies() {
        let duckDuckGo = Data(
            #"["zen browser",["zen browser","zen browser download","zen browser windows","zen browser reddit","zen browser portable","zen browser review","zen browser twilight","zen browser github"]]"#.utf8
        )
        XCTAssertEqual(
            SearchSuggestions.parse(duckDuckGo),
            [
                "zen browser", "zen browser download", "zen browser windows", "zen browser reddit",
                "zen browser portable", "zen browser review", "zen browser twilight", "zen browser github",
            ]
        )

        let ecosia = Data(
            #"["zen browser",["zen browser","zen browser download","zen browser windows","zen browser reddit","zen browser portable","zen browser github","zen browser twilight","zen browser vs firefox"]]"#.utf8
        )
        XCTAssertEqual(SearchSuggestions.parse(ecosia).count, 8)
        XCTAssertEqual(SearchSuggestions.parse(ecosia).last, "zen browser vs firefox")

        let brave = Data(
            #"["zen browser",["zen browser","zen browser android","zen browser download","zen browser windows","zen browser arch","zen browser ios","zen browser apk","zen browser mobile"]]"#.utf8
        )
        XCTAssertEqual(SearchSuggestions.parse(brave)[1], "zen browser android")

        // Google's live body has an empty third element and a subtype object.
        let google = Data(
            #"["zen browser",["zen browser","zen browser download","zen browser android","zen browser linux","zen browser ios","zen browser windows","zen browser arch","zen browser reddit","zen browser adblocker","zen browser github"],[],{"google:suggestsubtypes":[[512,433],[512]]}]"#.utf8
        )
        let googleSuggestions = SearchSuggestions.parse(google)
        XCTAssertEqual(googleSuggestions.count, 8)
        XCTAssertEqual(googleSuggestions.first, "zen browser")
        XCTAssertFalse(googleSuggestions.contains("zen browser adblocker"))

        let bing = Data(
            #"["zen browser",["zen browser","zen browser download","zen browser windows","zen browser reddit","zen browser portable","zen browser github","zen browser twilight","zen browser vs firefox","zen browser vs brave","zen browser available in windows","zen browser winget","zen browser android"]]"#.utf8
        )
        XCTAssertEqual(SearchSuggestions.parse(bing).count, 8)
        XCTAssertFalse(SearchSuggestions.parse(bing).contains("zen browser android"))
    }

    func testParseDropsBlankDuplicatesAndNonStrings() {
        let body = Data(#"["q",["ok","  ","ok",1,null," second "]]"#.utf8)
        XCTAssertEqual(SearchSuggestions.parse(body), ["ok", "second"])
    }

    func testParseRejectsAnythingElse() {
        XCTAssertTrue(SearchSuggestions.parse(Data("[]".utf8)).isEmpty)
        XCTAssertTrue(SearchSuggestions.parse(Data(#"["only"]"#.utf8)).isEmpty)
        XCTAssertTrue(SearchSuggestions.parse(Data(#"{"suggestions":["a"]}"#.utf8)).isEmpty)
        XCTAssertTrue(SearchSuggestions.parse(Data("not json".utf8)).isEmpty)
        XCTAssertTrue(SearchSuggestions.parse(Data()).isEmpty)
    }
}
