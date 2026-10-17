import WebKit
import XCTest

@testable import ZenCompanion

@MainActor
final class MiniBrowserModelTests: XCTestCase {
    private func space(_ id: String) -> ZenCompanion.ZenSpace {
        ZenCompanion.ZenSpace(id: id, name: "Space \(id)")
    }

    private func makeModel(
        initialURL: URL?,
        engine: FakeSearchEngine = FakeSearchEngine(),
        writer: FakePinWriter = FakePinWriter(),
        preferences: FakePreferences? = nil,
        suggestions: RecordingSuggestionClient = RecordingSuggestionClient(),
        suggestionDebounce: Duration = .zero
    ) -> (MiniBrowserModel, FakePinWriter) {
        let model = MiniBrowserModel(
            initialURL: initialURL,
            pinWriter: writer,
            urlOpener: FakeURLOpener(),
            searchEngine: engine,
            haptics: FakeHaptics(),
            preferences: preferences ?? FakePreferences(),
            suggestionClient: suggestions,
            suggestionDebounce: suggestionDebounce
        )
        return (model, writer)
    }

    private func preferences(saveKind: SaveKind) -> FakePreferences {
        let prefs = FakePreferences()
        prefs.setString(saveKind.rawValue, PreferenceKeys.saveKind, scope: .appGroup)
        return prefs
    }

    // MARK: - effectiveURL precedence

    func testEffectiveURLPrefersCurrentURLThenWebViewThenInitialThenTyped() {
        let initial = URL(string: "https://initial.example")!
        let (model, _) = makeModel(initialURL: initial)

        // Typed query is the weakest source while an initial URL is present.
        model.addressText = "typed.example"
        XCTAssertEqual(model.effectiveURL, initial)

        // The web view's live URL outranks the initial URL.
        let webView = FakeWebView(frame: .zero, configuration: WKWebViewConfiguration())
        webView.stubbedURL = URL(string: "https://webview.example")
        model.webView = webView
        XCTAssertEqual(model.effectiveURL, URL(string: "https://webview.example"))

        // The reported current URL outranks everything.
        model.currentURL = URL(string: "https://current.example")
        XCTAssertEqual(model.effectiveURL, URL(string: "https://current.example"))

        // Current URL cleared: fall back to the web view, then the initial URL.
        model.currentURL = nil
        XCTAssertEqual(model.effectiveURL, URL(string: "https://webview.example"))
        model.webView = nil
        XCTAssertEqual(model.effectiveURL, initial)
    }

    func testEffectiveURLFallsBackToTypedQuery() {
        let (model, _) = makeModel(initialURL: nil)

        model.addressText = "dotted.example"
        XCTAssertEqual(model.effectiveURL, URL(string: "https://dotted.example"))

        model.addressText = "hello world"
        XCTAssertEqual(
            model.effectiveURL,
            SearchEngine.duckDuckGo.searchURL(for: "hello world")
        )

        model.addressText = "   "
        XCTAssertNil(model.effectiveURL)
    }

    // MARK: - Page menu tools

    func testMenuOpensOnlyWhenAPageExists() {
        let (model, _) = makeModel(initialURL: nil)
        model.toggleMenu()
        XCTAssertFalse(model.menuExpanded)

        model.currentURL = URL(string: "https://example.com")
        model.toggleMenu()
        XCTAssertTrue(model.menuExpanded)
        model.toggleMenu()
        XCTAssertFalse(model.menuExpanded)
    }

    func testForwardButtonAppearsOnlyWhenForwardIsPossible() {
        XCTAssertFalse(MiniBrowserTools.showsForwardButton(canGoForward: false, addressEditing: false))
        XCTAssertTrue(MiniBrowserTools.showsForwardButton(canGoForward: true, addressEditing: false))
        XCTAssertFalse(MiniBrowserTools.showsForwardButton(canGoForward: true, addressEditing: true))
    }

    func testPageKeyboardDoneStaysOffWhileEditingTheAddress() {
        XCTAssertFalse(MiniBrowserTools.showsPageKeyboardDone(keyboardOverlap: 0, addressEditing: false))
        XCTAssertFalse(MiniBrowserTools.showsPageKeyboardDone(keyboardOverlap: 1, addressEditing: false))
        XCTAssertTrue(MiniBrowserTools.showsPageKeyboardDone(keyboardOverlap: 336, addressEditing: false))
        XCTAssertFalse(MiniBrowserTools.showsPageKeyboardDone(keyboardOverlap: 336, addressEditing: true))
    }

    func testZoomClampsToHalfAndDouble() {
        let (model, _) = makeModel(initialURL: nil)
        XCTAssertEqual(model.pageZoom, 1, accuracy: 0.001)
        XCTAssertEqual(MiniBrowserTools.zoomPercentLabel(1), "100%")

        model.zoomIn()
        XCTAssertEqual(model.pageZoom, 1.1, accuracy: 0.001)

        model.setPageZoom(9)
        XCTAssertEqual(model.pageZoom, MiniBrowserTools.zoomMax, accuracy: 0.001)
        XCTAssertFalse(model.canZoomIn)
        model.zoomIn()
        XCTAssertEqual(model.pageZoom, MiniBrowserTools.zoomMax, accuracy: 0.001)

        model.setPageZoom(0.01)
        XCTAssertEqual(model.pageZoom, MiniBrowserTools.zoomMin, accuracy: 0.001)
        XCTAssertFalse(model.canZoomOut)
        XCTAssertEqual(MiniBrowserTools.zoomPercentLabel(model.pageZoom), "50%")
    }

    func testDesktopSiteTogglesWithoutAWebView() {
        let (model, _) = makeModel(initialURL: URL(string: "https://example.com"))
        XCTAssertFalse(model.requestsDesktopSite)
        model.toggleDesktopSite()
        XCTAssertTrue(model.requestsDesktopSite)
        model.toggleDesktopSite()
        XCTAssertFalse(model.requestsDesktopSite)
    }

    func testFindOnPageClosesTheMenu() {
        let (model, _) = makeModel(initialURL: URL(string: "https://example.com")!)
        model.currentURL = URL(string: "https://example.com")
        model.toggleMenu()
        model.presentFindOnPage()
        XCTAssertFalse(model.menuExpanded)
    }

    func testCopyCurrentLink() {
        let previous = UIPasteboard.general.url
        defer { UIPasteboard.general.url = previous }

        let (model, _) = makeModel(initialURL: nil)
        XCTAssertFalse(model.copyCurrentLink())

        let url = URL(string: "https://example.com/copied")!
        model.currentURL = url
        XCTAssertTrue(model.copyCurrentLink())
        XCTAssertEqual(UIPasteboard.general.url, url)
    }

    func testPageHostUsesDisplayText() {
        let (model, _) = makeModel(initialURL: nil)
        XCTAssertEqual(model.pageHost, "")
        model.currentURL = URL(string: "https://www.example.com/path")
        XCTAssertEqual(model.pageHost, "www.example.com")
    }

    // MARK: - Pin save

    func testPinSaveUsesSelectedTargetAndClearsPending() async {
        let (model, writer) = makeModel(initialURL: URL(string: "https://pin.example/page")!)
        model.pageTitle = "Pinned Page"

        model.triggerPinBanner(spaces: [space("a"), space("b")], fallbackSpace: space("b"))
        XCTAssertTrue(model.showPinBanner)
        XCTAssertEqual(model.pinnedDestination.spaceId, "b")
        XCTAssertTrue(model.hasPendingPinSave)

        model.selectPinDestination(ZenCompanion.PinDestination(spaceId: "a", folderId: "folder-1"))
        XCTAssertEqual(model.pinnedDestination, ZenCompanion.PinDestination(spaceId: "a", folderId: "folder-1"))

        model.commitPendingPinSave()
        XCTAssertFalse(model.hasPendingPinSave)

        await model.waitForPinSave()
        XCTAssertEqual(writer.saved.count, 1)
        XCTAssertEqual(writer.saved.first?.url, URL(string: "https://pin.example/page"))
        XCTAssertEqual(writer.saved.first?.title, "Pinned Page")
        XCTAssertEqual(writer.saved.first?.spaceId, "a")
        XCTAssertEqual(writer.saved.first?.folderId, "folder-1")
    }

    func testPinSaveRejectsUrlShapedTitle() async {
        let (model, writer) = makeModel(initialURL: URL(string: "https://pin.example/page")!)
        model.pageTitle = "https://pin.example/page/"

        model.triggerPinBanner(spaces: [space("a")], fallbackSpace: space("a"))
        model.commitPendingPinSave()
        await model.waitForPinSave()

        XCTAssertEqual(writer.saved.first?.title, "pin.example")
    }

    func testPinSaveFallsBackToHostTitleWhenPageTitleIsEmpty() async {
        let (model, writer) = makeModel(initialURL: URL(string: "https://pin.example/page")!)

        model.triggerPinBanner(spaces: [space("a")], fallbackSpace: space("a"))
        model.commitPendingPinSave()
        await model.waitForPinSave()

        XCTAssertEqual(writer.saved.first?.title, "pin.example")
    }

    func testInvalidPinDestinationResetsToFallbackSpace() {
        let (model, _) = makeModel(initialURL: URL(string: "https://pin.example")!)
        model.pinnedDestination = ZenCompanion.PinDestination(spaceId: "missing", folderId: "folder-x")

        model.triggerPinBanner(spaces: [space("a"), space("b")], fallbackSpace: space("b"))

        XCTAssertEqual(model.pinnedDestination, ZenCompanion.PinDestination(spaceId: "b"))
    }

    func testPinDestinationResetUsesFirstSpaceWhenFallbackIsUnknown() {
        let (model, _) = makeModel(initialURL: URL(string: "https://pin.example")!)
        model.pinnedDestination = ZenCompanion.PinDestination(spaceId: "missing")

        model.triggerPinBanner(spaces: [space("a"), space("b")], fallbackSpace: space("c"))

        XCTAssertEqual(model.pinnedDestination, ZenCompanion.PinDestination(spaceId: "a"))
    }

    func testPinWithoutSpacesShowsNoBannerAndDoesNotWrite() async {
        let (model, writer) = makeModel(initialURL: URL(string: "https://pin.example/page")!)

        model.triggerPinBanner(spaces: [], fallbackSpace: space("fallback"))

        XCTAssertFalse(model.showPinBanner)
        XCTAssertFalse(model.hasPendingPinSave)
        XCTAssertNotNil(model.pinNotice)
        XCTAssertEqual(model.pinnedDestination, ZenCompanion.PinDestination(spaceId: ""))

        model.commitPendingPinSave()
        await model.waitForPinSave()

        XCTAssertTrue(writer.saved.isEmpty)
    }

    func testPinRefusedWithoutSpacesClearsStalePendingSave() async {
        let (model, writer) = makeModel(initialURL: URL(string: "https://pin.example/page")!)

        model.triggerPinBanner(spaces: [space("a")], fallbackSpace: space("a"))
        XCTAssertTrue(model.hasPendingPinSave)

        model.triggerPinBanner(spaces: [], fallbackSpace: space("a"))

        XCTAssertFalse(model.showPinBanner)
        XCTAssertFalse(model.hasPendingPinSave)

        model.commitPendingPinSave()
        await model.waitForPinSave()

        XCTAssertTrue(writer.saved.isEmpty)
    }

    func testCommitWithoutPendingSaveDoesNothing() async {
        let (model, writer) = makeModel(initialURL: URL(string: "https://pin.example")!)

        model.commitPendingPinSave()
        await model.waitForPinSave()

        XCTAssertTrue(writer.saved.isEmpty)
    }

    func testDestinationPickerKeepsTheBannerAlive() {
        let (model, _) = makeModel(initialURL: URL(string: "https://pin.example/page")!)

        model.triggerPinBanner(spaces: [space("a")], fallbackSpace: space("a"))
        XCTAssertTrue(model.showPinBanner)

        // Opening the destination picker cancels the auto-dismiss, and closing
        // it only restarts the timer: the banner stays up either way.
        model.pinMenuWillOpen()
        XCTAssertTrue(model.showPinBanner)

        model.pinMenuDidDismiss()
        XCTAssertTrue(model.showPinBanner)
    }

    // MARK: - Save kind (normal tabs)

    func testNormalSaveKindUsesRootAndPassesNormalKind() async {
        let (model, writer) = makeModel(
            initialURL: URL(string: "https://pin.example/page")!,
            preferences: preferences(saveKind: .normal)
        )

        model.triggerPinBanner(spaces: [space("a")], fallbackSpace: space("a"))
        XCTAssertEqual(model.pinSaveKind, .normal)
        XCTAssertEqual(model.pinnedDestination, ZenCompanion.PinDestination(spaceId: "a"))

        model.selectPinDestination(ZenCompanion.PinDestination(spaceId: "a", folderId: "folder-1"))
        XCTAssertNil(model.pinnedDestination.folderId, "a normal save ignores folders")

        model.commitPendingPinSave()
        await model.waitForPinSave()

        XCTAssertEqual(writer.saved.first?.kind, .normal)
        XCTAssertNil(writer.saved.first?.folderId)
    }

    func testPinnedSaveKindStillPassesFolder() async {
        let (model, writer) = makeModel(
            initialURL: URL(string: "https://pin.example/page")!,
            preferences: preferences(saveKind: .pinned)
        )

        model.triggerPinBanner(spaces: [space("a")], fallbackSpace: space("a"))
        model.selectPinDestination(ZenCompanion.PinDestination(spaceId: "a", folderId: "folder-1"))
        model.commitPendingPinSave()
        await model.waitForPinSave()

        XCTAssertEqual(writer.saved.first?.kind, .pinned)
        XCTAssertEqual(writer.saved.first?.folderId, "folder-1")
    }

    func testFallbackOutcomeShowsNotice() async {
        let writer = FakePinWriter()
        writer.result = ZenCompanion.AddTabOutcome.fallback(recordId: "fallback-id")
        let (model, _) = makeModel(
            initialURL: URL(string: "https://pin.example/page")!,
            writer: writer,
            preferences: preferences(saveKind: .normal)
        )

        model.triggerPinBanner(spaces: [space("a")], fallbackSpace: space("a"))
        model.commitPendingPinSave()
        await model.waitForPinSave()

        XCTAssertEqual(
            model.pinNotice,
            String(localized: "share.saved.fallback_pinned"),
            "a normal-to-pinned fallback must surface a readable notice"
        )
    }

    func testNormalSaveOutcomeShowsNoFallbackNotice() async {
        let writer = FakePinWriter()
        writer.result = ZenCompanion.AddTabOutcome.normal(recordId: "normal-id")
        let (model, _) = makeModel(
            initialURL: URL(string: "https://pin.example/page")!,
            writer: writer,
            preferences: preferences(saveKind: .normal)
        )

        model.triggerPinBanner(spaces: [space("a")], fallbackSpace: space("a"))
        model.commitPendingPinSave()
        await model.waitForPinSave()

        XCTAssertNil(model.pinNotice)
    }

    // MARK: - Suggestions

    func testURLInputDoesNotRequestSuggestions() async {
        let client = RecordingSuggestionClient()
        let (model, _) = makeModel(initialURL: nil, suggestions: client)

        let urls = [
            "https://example.com/private",
            "http://example.com/foo bar",
            "example.com",
            "   \n",
            "mailto:support@kjell.cc",
        ]
        for text in urls {
            model.addressText = text
            model.refreshSuggestions(isEditing: true)
            await model.waitForSuggestions()
        }

        XCTAssertTrue(client.requested.isEmpty)
        XCTAssertTrue(model.suggestions.isEmpty)
    }

    func testSearchQueryRequestsSuggestionsAndParsesThem() async {
        let client = RecordingSuggestionClient()
        client.body = Data(#"["zen",["zen browser","zen browser download"]]"#.utf8)
        let (model, _) = makeModel(initialURL: nil, suggestions: client)
        model.addressText = "zen browser"
        model.refreshSuggestions(isEditing: true)
        await model.waitForSuggestions()

        XCTAssertEqual(client.requested.count, 1)
        let items = URLComponents(url: client.requested[0], resolvingAgainstBaseURL: false)?.queryItems
        XCTAssertEqual(items?.first { $0.name == "q" }?.value, "zen browser")
        XCTAssertEqual(model.suggestions, ["zen browser", "zen browser download"])
    }

    func testChangingToAURLCancelsThePendingQuery() async {
        let client = RecordingSuggestionClient()
        let (model, _) = makeModel(initialURL: nil, suggestions: client, suggestionDebounce: .zero)
        model.addressText = "hello"
        model.refreshSuggestions(isEditing: true)
        // Still on the main actor, so the first request has not started.
        // Replacing it with a URL must cancel it before any request leaves.
        model.addressText = "https://secret.example/path"
        model.refreshSuggestions(isEditing: true)
        for _ in 0..<10 {
            await Task.yield()
        }

        XCTAssertTrue(client.requested.isEmpty)
        XCTAssertTrue(model.suggestions.isEmpty)
    }

    func testSuggestionFailureShowsNoList() async {
        let client = RecordingSuggestionClient()
        client.error = URLError(.notConnectedToInternet)
        let (model, _) = makeModel(initialURL: nil, suggestions: client)
        model.addressText = "hello"
        model.refreshSuggestions(isEditing: true)
        await model.waitForSuggestions()

        XCTAssertEqual(client.requested.count, 1)
        XCTAssertTrue(model.suggestions.isEmpty)
    }

    func testCustomEngineDoesNotRequestSuggestions() async {
        let client = RecordingSuggestionClient()
        let engine = FakeSearchEngine()
        engine.current = ZenCompanion.SearchEngine(
            id: "kagi",
            displayName: "Kagi",
            template: "https://kagi.com/search?q={query}",
            isBuiltIn: false
        )
        let (model, _) = makeModel(initialURL: nil, engine: engine, suggestions: client)
        model.addressText = "hello"
        model.refreshSuggestions(isEditing: true)
        await model.waitForSuggestions()

        XCTAssertTrue(client.requested.isEmpty)
    }

    func testClosedFieldDoesNotRequestSuggestions() async {
        let client = RecordingSuggestionClient()
        let (model, _) = makeModel(initialURL: nil, suggestions: client)
        model.addressText = "hello"
        model.refreshSuggestions(isEditing: false)
        await model.waitForSuggestions()

        XCTAssertTrue(client.requested.isEmpty)
    }
}

// MARK: - Fakes

@MainActor
private final class FakePinWriter: PinWriting {
    nonisolated init() {}

    struct Saved {
        let url: URL
        let title: String
        let spaceId: String
        let folderId: String?
        let kind: ZenCompanion.SaveKind
    }

    private(set) var saved: [Saved] = []
    /// Outcome returned to the model; defaults to a successful pinned save.
    var result: ZenCompanion.AddTabOutcome = ZenCompanion.AddTabOutcome.pinned(recordId: "pin-id")

    @discardableResult
    func addTab(
        url: URL,
        title: String,
        to spaceId: String,
        folderId: String?,
        kind: ZenCompanion.SaveKind
    ) async throws -> ZenCompanion.AddTabOutcome {
        saved.append(Saved(url: url, title: title, spaceId: spaceId, folderId: folderId, kind: kind))
        return result
    }
}

@MainActor
private final class FakeURLOpener: URLOpening {
    private(set) var opened: [URL] = []

    func open(_ url: URL) { opened.append(url) }
}

@MainActor
private final class FakeSearchEngine: SearchEngineProviding {
    nonisolated init() {}

    var current: ZenCompanion.SearchEngine = .duckDuckGo
    var custom: [ZenCompanion.SearchEngine] = []
}

final class RecordingSuggestionClient: SearchSuggestionFetching, @unchecked Sendable {
    private let lock = NSLock()
    private var urls: [URL] = []
    var body = Data("[]".utf8)
    var error: Error?

    var requested: [URL] {
        lock.lock()
        defer { lock.unlock() }
        return urls
    }

    func fetch(_ url: URL) async throws -> Data {
        lock.lock()
        urls.append(url)
        let failure = error
        let payload = body
        lock.unlock()
        if let failure { throw failure }
        return payload
    }
}

@MainActor
private final class FakeHaptics: HapticsPlaying {
    private(set) var pinSuccesses = 0
    private(set) var selectionChanges = 0
    private(set) var mediumImpacts = 0

    func pinSucceeded() { pinSuccesses += 1 }
    func selectionChanged() { selectionChanges += 1 }
    func mediumImpact() { mediumImpacts += 1 }
}

private final class FakeWebView: WKWebView {
    var stubbedURL: URL?

    override var url: URL? { stubbedURL }
}
