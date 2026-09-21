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
