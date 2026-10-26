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
        allowsNormalTabs: Bool = false,
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
            allowsNormalTabs: allowsNormalTabs,
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

    func testZoomReadoutResetsToOneHundredPercent() {
        let (model, _) = makeModel(initialURL: nil)
        XCTAssertFalse(model.canResetZoom)

        model.zoomIn()
        model.zoomIn()
        XCTAssertEqual(model.pageZoom, 1.2, accuracy: 0.001)
        XCTAssertTrue(model.canResetZoom)

        model.resetPageZoom()
        XCTAssertEqual(model.pageZoom, MiniBrowserTools.defaultZoom, accuracy: 0.001)
        XCTAssertFalse(model.canResetZoom)
        // Nothing to reset: a second tap leaves the page alone.
        model.resetPageZoom()
        XCTAssertEqual(model.pageZoom, 1, accuracy: 0.001)
    }

    func testZoomScriptZoomsTheRootElement() {
        let zoomed = PageZoomScript.source(for: 1.2)
        XCTAssertTrue(zoomed.contains("root.style.zoom = '1.20'"))
        XCTAssertTrue(zoomed.contains("document.documentElement"))
        // Frames are already scaled with their parent's layout.
        XCTAssertTrue(zoomed.contains("window.self !== window.top"))

        // 100% takes the property off again instead of pinning it at 1.
        let identity = PageZoomScript.source(for: 1)
        XCTAssertTrue(identity.contains("root.style.removeProperty('zoom')"))
        XCTAssertFalse(identity.contains("style.zoom ="))
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
            preferences: preferences(saveKind: .normal),
            allowsNormalTabs: true
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
            preferences: preferences(saveKind: .normal),
            allowsNormalTabs: true
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
            preferences: preferences(saveKind: .normal),
            allowsNormalTabs: true
        )

        model.triggerPinBanner(spaces: [space("a")], fallbackSpace: space("a"))
        model.commitPendingPinSave()
        await model.waitForPinSave()

        XCTAssertNil(model.pinNotice)
    }

    func testPinKindStartsFromTheSettingAndCanChange() async {
        let (model, writer) = makeModel(
            initialURL: URL(string: "https://pin.example/page")!,
            preferences: preferences(saveKind: .pinned),
            allowsNormalTabs: true
        )

        model.triggerPinBanner(spaces: [space("a")], fallbackSpace: space("a"))
        XCTAssertEqual(model.pinSaveKind, .pinned, "the saved setting is the default")

        model.selectPinSaveKind(.normal)
        XCTAssertEqual(model.pinSaveKind, .normal)

        model.commitPendingPinSave()
        await model.waitForPinSave()
        XCTAssertEqual(writer.saved.first?.kind, .normal, "the per-save choice is what gets written")
    }

    func testNormalKindNeedsTheCapability() async {
        let (model, _) = makeModel(
            initialURL: URL(string: "https://pin.example/page")!,
            preferences: preferences(saveKind: .normal)
        )

        model.triggerPinBanner(spaces: [space("a")], fallbackSpace: space("a"))
        XCTAssertEqual(model.pinSaveKind, .pinned, "without the capability only pinned is possible")

        model.selectPinSaveKind(.normal)
        XCTAssertEqual(model.pinSaveKind, .pinned)
    }

    func testPinKindIsNotPersisted() async {
        let (model, _) = makeModel(
            initialURL: URL(string: "https://pin.example/page")!,
            preferences: preferences(saveKind: .pinned),
            allowsNormalTabs: true
        )

        model.triggerPinBanner(spaces: [space("a")], fallbackSpace: space("a"))
        model.selectPinSaveKind(.normal)
        model.commitPendingPinSave()
        await model.waitForPinSave()

        // The next save starts from the setting again.
        let (next, _) = makeModel(
            initialURL: URL(string: "https://pin.example/page")!,
            preferences: preferences(saveKind: .pinned),
            allowsNormalTabs: true
        )
        next.triggerPinBanner(spaces: [space("a")], fallbackSpace: space("a"))
        XCTAssertEqual(next.pinSaveKind, .pinned)
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

    func testReloadRetriesTheFailedAddressNotThePageUnderneath() {
        let (model, _) = makeModel(initialURL: nil)
        let webView = FakeWebView(frame: .zero, configuration: WKWebViewConfiguration())
        webView.stubbedURL = URL(string: "https://previous.example")
        model.webView = webView
        let slow = URL(string: "https://slow.example")!

        model.loadDidFail(loadError(NSURLErrorTimedOut, failing: slow))
        model.reload()
        XCTAssertEqual(webView.loaded, [slow])
        XCTAssertEqual(webView.reloads, 0)

        model.pageDidCommit()
        model.reload()
        XCTAssertEqual(webView.reloads, 1)
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
    var stubbedCanGoBack = false
    private(set) var loaded: [URL] = []
    private(set) var wentBack = 0
    private(set) var reloads = 0

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

    override func reload() -> WKNavigation? {
        reloads += 1
        return nil
    }
}
