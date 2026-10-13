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
        preferences: FakePreferences? = nil
    ) -> (MiniBrowserModel, FakePinWriter) {
        let model = MiniBrowserModel(
            initialURL: initialURL,
            pinWriter: writer,
            urlOpener: FakeURLOpener(),
            searchEngine: engine,
            haptics: FakeHaptics(),
            preferences: preferences ?? FakePreferences()
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

    func testPinBannerGlassSuppressedOnceDestinationPickerOpened() {
        let (model, _) = makeModel(initialURL: URL(string: "https://pin.example/page")!)

        model.triggerPinBanner(spaces: [space("a")], fallbackSpace: space("a"))
        XCTAssertFalse(model.pinBannerGlassSuppressed)

        model.pinMenuWillOpen()
        XCTAssertTrue(model.pinBannerGlassSuppressed)

        // Stays suppressed after the menu closes: iOS 26 keeps rendering the
        // glass as an opaque slab through the closing animation.
        model.pinMenuDidDismiss()
        XCTAssertTrue(model.pinBannerGlassSuppressed)

        // A fresh banner (new pin tap) brings the glass back.
        model.triggerPinBanner(spaces: [space("a")], fallbackSpace: space("a"))
        XCTAssertFalse(model.pinBannerGlassSuppressed)
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

    /// Navigating away while the banner is up saves the page that was pinned,
    /// right away, instead of whatever page shows when the timer fires.
    func testNavigatingAwayCommitsThePinnedPage() async {
        let pinned = URL(string: "https://pin.example/a")!
        let (model, writer) = makeModel(initialURL: pinned)
        model.currentURL = pinned
        model.pageTitle = "Page A"

        model.triggerPinBanner(spaces: [space("a")], fallbackSpace: space("a"))
        let next = URL(string: "https://pin.example/b")!
        model.currentURL = next
        model.pageTitle = "Page B"
        model.currentURLDidChange(next, isAddressFocused: false)
        await model.waitForPinSave()

        XCTAssertFalse(model.hasPendingPinSave)
        XCTAssertEqual(writer.saved.map(\.url), [pinned])
        XCTAssertEqual(writer.saved.first?.title, "Page A")
    }

    /// A failed write tells the user instead of failing silently.
    func testPinFailureShowsNotice() async {
        let writer = FakePinWriter()
        writer.error = ZenCompanion.SyncError.conflict
        let (model, _) = makeModel(initialURL: URL(string: "https://pin.example/page")!, writer: writer)

        model.triggerPinBanner(spaces: [space("a")], fallbackSpace: space("a"))
        model.commitPendingPinSave()
        await model.waitForPinSave()

        XCTAssertEqual(
            model.pinNotice,
            "\(String(localized: "browser.pin_failed")) \(String(localized: "error.conflict"))"
        )
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

    // MARK: - Error panel

    private func loadError(_ code: Int, failing url: URL) -> NSError {
        NSError(domain: NSURLErrorDomain, code: code, userInfo: [NSURLErrorFailingURLErrorKey: url])
    }

    func testFailedLoadShowsThePanelForTheFailedAddress() {
        let (model, _) = makeModel(initialURL: nil)
        let webView = FakeWebView(frame: .zero, configuration: WKWebViewConfiguration())
        webView.stubbedURL = URL(string: "https://previous.example")
        model.webView = webView
        model.currentURL = webView.stubbedURL
        let typo = URL(string: "https://wikipeida.org")!

        model.loadDidFail(loadError(NSURLErrorCannotFindHost, failing: typo))

        XCTAssertEqual(model.pageError, .hostNotFound)
        XCTAssertEqual(model.failedURL, typo)
        // Safari and the address bar act on the address that failed, not the old page.
        XCTAssertEqual(model.effectiveURL, typo)
        model.pageErrorDidChange(isAddressFocused: false)
        XCTAssertEqual(model.addressText, "wikipeida.org")
        // The old page's address arriving late doesn't overwrite it.
        model.currentURLDidChange(URL(string: "https://previous.example"), isAddressFocused: false)
        XCTAssertEqual(model.addressText, "wikipeida.org")
    }

    func testCancelledLoadsShowNoPanel() {
        let (model, _) = makeModel(initialURL: nil)
        model.loadDidFail(loadError(NSURLErrorCancelled, failing: URL(string: "https://example.com")!))
        model.loadDidFail(NSError(domain: "WebKitErrorDomain", code: 102))
        XCTAssertNil(model.pageError)
    }

    func testCommitClearsThePanelAndRestoresTheAddress() {
        let (model, _) = makeModel(initialURL: nil)
        model.currentURL = URL(string: "https://previous.example")
        model.loadDidFail(loadError(NSURLErrorTimedOut, failing: URL(string: "https://slow.example")!))
        XCTAssertEqual(model.pageError, .notResponding)

        model.pageDidCommit()
        model.pageErrorDidChange(isAddressFocused: false)

        XCTAssertNil(model.pageError)
        XCTAssertNil(model.failedURL)
        XCTAssertEqual(model.addressText, "previous.example")
    }

    func testRetryAndSearchLoadTheFailedAddress() {
        let (model, _) = makeModel(initialURL: nil)
        let webView = FakeWebView(frame: .zero, configuration: WKWebViewConfiguration())
        model.webView = webView
        let typo = URL(string: "https://wikipeida.org/wiki/Aurora")!
        model.loadDidFail(loadError(NSURLErrorCannotFindHost, failing: typo))

        model.retryFailedLoad()
        model.searchFailedHost()

        XCTAssertEqual(webView.loaded, [
            typo,
            SearchEngine.duckDuckGo.searchURL(for: "wikipeida.org")!,
        ])
    }

    func testCrashKeepsThePageAddressAndReloadsIt() {
        let (model, _) = makeModel(initialURL: nil)
        let webView = FakeWebView(frame: .zero, configuration: WKWebViewConfiguration())
        let page = URL(string: "https://maps.example/route")!
        webView.stubbedURL = page
        model.webView = webView

        model.webContentProcessDidTerminate()
        model.retryFailedLoad()

        XCTAssertEqual(model.pageError, .crashed)
        XCTAssertEqual(webView.loaded, [page])
    }

    func testGoBackRevealsTheOldPageOrClosesTheBrowser() {
        let (model, _) = makeModel(initialURL: nil)
        let webView = FakeWebView(frame: .zero, configuration: WKWebViewConfiguration())
        model.webView = webView
        let bad = URL(string: "https://expired.example")!

        // The failed load never replaced the page underneath.
        webView.stubbedURL = URL(string: "https://previous.example")
        model.loadDidFail(loadError(NSURLErrorServerCertificateHasBadDate, failing: bad))
        XCTAssertFalse(model.leaveErrorPage())
        XCTAssertNil(model.pageError)

        // The browser opened straight onto the bad page: nothing to go back to.
        webView.stubbedURL = bad
        model.loadDidFail(loadError(NSURLErrorServerCertificateHasBadDate, failing: bad))
        XCTAssertTrue(model.leaveErrorPage())

        webView.stubbedCanGoBack = true
        XCTAssertFalse(model.leaveErrorPage())
        XCTAssertEqual(webView.wentBack, 1)
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
    /// When set, the write throws this instead of returning `result`.
    var error: Error?

    @discardableResult
    func addTab(
        url: URL,
        title: String,
        to spaceId: String,
        folderId: String?,
        kind: ZenCompanion.SaveKind
    ) async throws -> ZenCompanion.AddTabOutcome {
        saved.append(Saved(url: url, title: title, spaceId: spaceId, folderId: folderId, kind: kind))
        if let error { throw error }
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
    var stubbedCanGoBack = false
    private(set) var loaded: [URL] = []
    private(set) var wentBack = 0

    override var url: URL? { stubbedURL }
    override var canGoBack: Bool { stubbedCanGoBack }

    override func load(_ request: URLRequest) -> WKNavigation? {
        if let url = request.url { loaded.append(url) }
        return nil
    }

    override func goBack() -> WKNavigation? {
        wentBack += 1
        return nil
    }
}
