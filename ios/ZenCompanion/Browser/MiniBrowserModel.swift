import Foundation
import Observation
import SwiftUI
import WebKit

/// Page-tool rules for the bottom browser card.
///
/// Android port: copy these constants into `MiniBrowserViewModel` rather than
/// inventing new ones from the layout. Zoom is a fraction of 1.0 (1.0 = 100%),
/// clamped to 0.5...2.0 in steps of 0.1. Forward is visible only when the page
/// can go forward and the address field is not being edited.
enum MiniBrowserTools {
    static let zoomMin = 0.5
    static let zoomMax = 2.0
    static let zoomStep = 0.1
    static let defaultZoom = 1.0

    static func clampedZoom(_ value: Double) -> Double {
        let steps = (value / zoomStep).rounded()
        let stepped = steps * zoomStep
        let clamped = min(zoomMax, max(zoomMin, stepped))
        return (clamped * 100).rounded() / 100
    }

    static func zoomPercentLabel(_ value: Double) -> String {
        "\(Int((clampedZoom(value) * 100).rounded()))%"
    }

    static func canZoomIn(_ value: Double) -> Bool {
        clampedZoom(value) < zoomMax - 0.001
    }

    static func canZoomOut(_ value: Double) -> Bool {
        clampedZoom(value) > zoomMin + 0.001
    }

    /// Back stays. Forward appears beside it only when there is a forward entry
    /// and the user is not editing the address.
    static func showsForwardButton(canGoForward: Bool, addressEditing: Bool) -> Bool {
        canGoForward && !addressEditing
    }

    /// A webpage or find field has the keyboard. The nav bar stays under the
    /// keys; a checkmark above them resigns the field. The address composer
    /// keeps the bar, so this stays false while that field is editing.
    static func showsPageKeyboardDone(keyboardOverlap: CGFloat, addressEditing: Bool) -> Bool {
        keyboardOverlap > 1 && !addressEditing
    }
}

/// State owner for the mini-browser: web-view progress/URL state fed by KVO,
/// the address field text, URL/search resolution and the pin-save banner state
/// machine. Replaces the former `MiniBrowserState` + view state in
/// `MiniBrowserView`.
@MainActor
@Observable
final class MiniBrowserModel {
    // MARK: Web-view state (updated by `MiniBrowserRepresentable`'s KVO)
    var currentURL: URL?
    var pageTitle: String = ""
    var isLoading: Bool = false
    var estimatedProgress: Double = 0.0
    var canGoBack: Bool = false
    var canGoForward: Bool = false
    var themeColor: Color?

    /// Expanded page menu on the bottom card.
    var menuExpanded = false
    /// Session-only. Navigations request the desktop version of the site.
    var requestsDesktopSite = false
    /// 1.0 is 100%. Always inside `MiniBrowserTools` limits.
    private(set) var pageZoom: Double = MiniBrowserTools.defaultZoom

    @ObservationIgnored weak var webView: WKWebView?

    // MARK: Address field
    var addressText: String = ""
    /// Suggestions for the text being typed. Empty hides the list.
    var suggestions: [String] = []
    /// Set while a suggestion was just chosen or the field was submitted, so
    /// the text change that follows does not send another suggest request.
    @ObservationIgnored private var suggestionsSuspended = false
    @ObservationIgnored private var suggestionTask: Task<Void, Never>?
    @ObservationIgnored private var suggestionGeneration = 0

    // MARK: Pin banner
    var showPinBanner = false
    var pinnedDestination = PinDestination(spaceId: "")
    var hasPendingPinSave = false
    /// How the pending save will be written (pinned vs normal). Captured when
    /// the banner is triggered and kept honest against the capability.
    private(set) var pinSaveKind: SaveKind = .pinned
    /// Short-lived refusal message when a pin cannot start (no spaces to
    /// attach the page to). Rendered in the same bottom toast as the banner.
    var pinNotice: String?
    /// Set once the banner's destination picker has been opened. While a
    /// native context menu is presented, iOS 26 renders `glassEffect` as an
    /// opaque white slab and it stays broken through the closing animation,
    /// so the banner drops to its plain brand fill for the rest of its run.
    private(set) var pinBannerGlassSuppressed = false

    @ObservationIgnored private var pinBannerDismissTask: Task<Void, Never>?
    @ObservationIgnored private var pinNoticeDismissTask: Task<Void, Never>?
    @ObservationIgnored private(set) var pinSaveTask: Task<Void, Never>?

    private let initialURL: URL?
    private let pinWriter: PinWriting
    private let urlOpener: URLOpening
    private let searchEngine: SearchEngineProviding
    private let haptics: HapticsPlaying
    private let preferences: PreferencesStoring
    private let suggestionClient: SearchSuggestionFetching
    private let suggestionDebounce: Duration

    init(
        initialURL: URL?,
        pinWriter: PinWriting = AppServices.pinWriter,
        urlOpener: URLOpening = AppServices.urlOpener,
        searchEngine: SearchEngineProviding = AppServices.searchEngine,
        haptics: HapticsPlaying = AppServices.haptics,
        preferences: PreferencesStoring = AppServices.preferences,
        suggestionClient: SearchSuggestionFetching = LiveSearchSuggestionClient(),
        suggestionDebounce: Duration = SearchSuggestions.debounce
    ) {
        self.initialURL = initialURL
        self.pinWriter = pinWriter
        self.urlOpener = urlOpener
        self.searchEngine = searchEngine
        self.haptics = haptics
        self.preferences = preferences
        self.suggestionClient = suggestionClient
        self.suggestionDebounce = suggestionDebounce
    }

    /// The persisted "save shared tabs as" choice. AppGroup first, standard
    /// fallback; unknown values mean `.pinned`.
    var saveKind: SaveKind {
        let key = PreferenceKeys.saveKind
        if let raw = preferences.string(key, scope: .appGroup), let value = SaveKind(rawValue: raw) {
            return value
        }
        if let raw = preferences.string(key, scope: .standard), let value = SaveKind(rawValue: raw) {
            return value
        }
        return .pinned
    }

    /// A new tab opens with the search field focused instead of loading a page.
    var opensWithSearch: Bool { initialURL == nil }

    // MARK: - URL resolution

    /// Precedence: live page URL → the web view's URL → the initial URL →
    /// whatever the address field currently resolves to.
    var effectiveURL: URL? {
        if let current = currentURL {
            return current
        }
        if let webURL = webView?.url {
            return webURL
        }
        if let initialURL {
            return initialURL
        }
        return BrowserInput.resolve(addressText, engine: searchEngine.current)
    }

    /// Host shown in the address field and under the page title. Empty when
    /// nothing is loaded.
    var pageHost: String {
        guard let url = effectiveURL else { return "" }
        return BrowserInput.displayText(for: url)
    }

    var canZoomIn: Bool { MiniBrowserTools.canZoomIn(pageZoom) }
    var canZoomOut: Bool { MiniBrowserTools.canZoomOut(pageZoom) }

    // MARK: - Loading

    func loadInitial() {
        if let initialURL {
            load(url: initialURL)
            addressText = BrowserInput.displayText(for: initialURL)
        }
    }

    func load(url: URL) {
        webView?.load(URLRequest(url: url))
    }

    func reload() {
        webView?.reload()
    }

    func stopLoading() {
        webView?.stopLoading()
    }

    func goBack() {
        webView?.goBack()
    }

    func goForward() {
        webView?.goForward()
    }

    func openCurrentInExternalBrowser() {
        if let url = effectiveURL {
            urlOpener.open(url)
        }
    }

    // MARK: - Page menu

    /// Opens the page menu only when there is a page to act on. Closing is
    /// always allowed.
    func toggleMenu() {
        if menuExpanded {
            menuExpanded = false
            return
        }
        guard effectiveURL != nil else { return }
        menuExpanded = true
        haptics.selectionChanged()
    }

    func collapseMenu() {
        menuExpanded = false
    }

    func setPageZoom(_ value: Double) {
        let next = MiniBrowserTools.clampedZoom(value)
        guard abs(next - pageZoom) > 0.001 else { return }
        pageZoom = next
        applyWebpagePreferences()
    }

    func zoomIn() {
        guard canZoomIn else { return }
        setPageZoom(pageZoom + MiniBrowserTools.zoomStep)
        haptics.selectionChanged()
    }

    func zoomOut() {
        guard canZoomOut else { return }
        setPageZoom(pageZoom - MiniBrowserTools.zoomStep)
        haptics.selectionChanged()
    }

    func toggleDesktopSite() {
        requestsDesktopSite.toggle()
        applyWebpagePreferences()
        haptics.selectionChanged()
        if webView?.url != nil {
            webView?.reload()
        }
    }

    /// System find bar. The page menu closes so the two bars do not stack.
    func presentFindOnPage() {
        menuExpanded = false
        guard let webView else { return }
        webView.isFindInteractionEnabled = true
        webView.findInteraction?.presentFindNavigator(showingReplace: false)
    }

    /// Copies the current page URL. Returns false when there is nothing to copy.
    @discardableResult
    func copyCurrentLink() -> Bool {
        guard let url = effectiveURL else { return false }
        UIPasteboard.general.url = url
        haptics.selectionChanged()
        return true
    }

    /// Keeps WebKit's desktop mode and page zoom aligned with the model.
    /// Called again when the web view is (re)attached.
    func applyWebpagePreferences() {
        guard let webView else { return }
        webView.configuration.defaultWebpagePreferences.preferredContentMode =
            requestsDesktopSite ? .desktop : .mobile
        let zoom = CGFloat(pageZoom)
        if abs(webView.pageZoom - zoom) > 0.001 {
            webView.pageZoom = zoom
        }
    }

    // MARK: - Address field intents

    func submitAddress() {
        suggestionsSuspended = true
        cancelSuggestionRequest()
        guard let url = BrowserInput.resolve(addressText, engine: searchEngine.current) else { return }
        load(url: url)
    }

    /// A tapped suggestion searches; only an explicit http(s) address loads
    /// directly (see `SearchSuggestions.target`).
    func selectSuggestion(_ suggestion: String) {
        suggestionsSuspended = true
        cancelSuggestionRequest()
        addressText = suggestion
        guard let url = SearchSuggestions.target(of: suggestion, engine: searchEngine.current) else { return }
        load(url: url)
    }

    /// Asks for suggestions when [isEditing] and the text is a search query.
    /// A URL, blank text, a custom engine, or a closed field sends nothing.
    func refreshSuggestions(isEditing: Bool) {
        if !isEditing || suggestionsSuspended {
            if !isEditing { suggestionsSuspended = false }
            cancelSuggestionRequest()
            return
        }
        // Keep the current list until the new answer arrives, as Android
        // does, so it does not flicker on every keystroke.
        cancelSuggestionRequest(keepingList: true)
        guard let url = SearchSuggestions.requestURL(for: addressText, engine: searchEngine.current) else {
            suggestions = []
            return
        }
        let generation = suggestionGeneration
        let client = suggestionClient
        let debounce = suggestionDebounce
        suggestionTask = Task {
            if debounce > .zero {
                try? await Task.sleep(for: debounce)
            }
            guard !Task.isCancelled, generation == suggestionGeneration else { return }
            do {
                let data = try await client.fetch(url)
                guard !Task.isCancelled, generation == suggestionGeneration else { return }
                suggestions = SearchSuggestions.parse(data)
            } catch is CancellationError {
                return
            } catch {
                guard !Task.isCancelled, generation == suggestionGeneration else { return }
                suggestions = []
            }
        }
    }

    /// Test seam. Production never waits on the suggest request.
    func waitForSuggestions() async {
        await suggestionTask?.value
    }

    func clearAddressText() {
        addressText = ""
        refreshSuggestions(isEditing: true)
    }

    private func cancelSuggestionRequest(keepingList: Bool = false) {
        suggestionTask?.cancel()
        suggestionTask = nil
        suggestionGeneration += 1
        if !keepingList, !suggestions.isEmpty {
            suggestions = []
        }
    }

    /// Drops the previous page's title when the document changes.
    func updateCurrentURL(_ url: URL?) {
        if !PageTitle.sameDocument(currentURL, url) {
            pageTitle = ""
        }
        currentURL = url
    }

    /// Keeps a real document title. URL-shaped WebKit titles are ignored so
    /// they cannot replace the page name.
    func adoptPageTitle(_ raw: String) {
        guard let title = PageTitle.usable(raw, url: currentURL ?? webView?.url) else { return }
        pageTitle = title
    }

    func currentURLDidChange(_ url: URL?, isAddressFocused: Bool) {
        if let url {
            if !isAddressFocused {
                addressText = BrowserInput.displayText(for: url)
            }
            showPinBanner = false
        }
    }

    /// Full URL for the editor. Call only while no address field is first
    /// responder. Writing the focused field from the focus callback deadlocks
    /// the keyboard queue, and the scene-update watchdog kills the app.
    func prepareAddressForEditing() {
        guard let url = effectiveURL else { return }
        let full = url.absoluteString
        guard addressText != full else { return }
        addressText = full
    }

    /// Short host label for the collapsed bar. Call after the editor is gone.
    func restoreAddressDisplay() {
        guard let url = effectiveURL else { return }
        let display = BrowserInput.displayText(for: url)
        guard addressText != display else { return }
        addressText = display
    }

    // MARK: - Pin save state machine

    func triggerPinBanner(spaces: [ZenSpace], fallbackSpace: ZenSpace) {
        guard effectiveURL != nil else { return }
        guard !spaces.isEmpty else {
            // There is no attached destination, so refuse instead of writing
            // to an unattached workspace id (fallbackSpace is not in sync).
            refusePinWithoutSpaces()
            return
        }
        pinNoticeDismissTask?.cancel()
        pinNotice = nil
        pinSaveKind = saveKind
        pinBannerGlassSuppressed = false
        if pinnedDestination.spaceId.isEmpty
            || !spaces.contains(where: { $0.id == pinnedDestination.spaceId }) {
            pinnedDestination = PinDestination(
                spaceId: spaces.first(where: { $0.id == fallbackSpace.id })?.id
                    ?? spaces.first?.id
                    ?? fallbackSpace.id
            )
        }
        // A normal save ignores folders; never leave a stale folder selected.
        if pinSaveKind == .normal {
            pinnedDestination = PinDestination(spaceId: pinnedDestination.spaceId)
        }
        hasPendingPinSave = true

        haptics.pinSucceeded()

        withAnimation(.spring(response: 0.35, dampingFraction: 0.85)) {
            showPinBanner = true
        }

        // Auto-dismiss banner after initial 5.0s and then commit save
        restartBannerDismissTimer(after: 5.0)
    }

    /// No spaces to pin into: keep the write path off and tell the user with
    /// the same bottom-toast language as the pin banner.
    private func refusePinWithoutSpaces() {
        hasPendingPinSave = false
        withAnimation(.spring(response: 0.35, dampingFraction: 0.85)) {
            showPinBanner = false
        }
        showPinNotice(String(localized: "browser.pin_no_space"))
    }

    private func showPinNotice(_ text: String, duration: TimeInterval = 4.0) {
        pinNotice = text
        pinNoticeDismissTask?.cancel()
        pinNoticeDismissTask = Task {
            let nanoseconds = UInt64(duration * 1_000_000_000)
            try? await Task.sleep(nanoseconds: nanoseconds)
            guard !Task.isCancelled else { return }
            withAnimation(.spring(response: 0.35, dampingFraction: 0.85)) {
                pinNotice = nil
            }
        }
    }

    func selectPinDestination(_ destination: PinDestination) {
        pinnedDestination = pinSaveKind == .normal
            ? PinDestination(spaceId: destination.spaceId)
            : destination
        hasPendingPinSave = true

        haptics.selectionChanged()

        // 1.0s confirmation after user explicitly selected a destination
        restartBannerDismissTimer(after: 1.0)
    }

    /// Keep the banner alive while the picker is open.
    func pinMenuWillOpen() {
        pinBannerDismissTask?.cancel()
        pinBannerGlassSuppressed = true
    }

    func pinMenuDidDismiss() {
        restartBannerDismissTimer(after: 1.5)
    }

    func commitPendingPinSave() {
        guard hasPendingPinSave,
              let url = effectiveURL else {
            return
        }
        hasPendingPinSave = false
        let target = pinnedDestination
        let kind = pinSaveKind
        let fallbackTitle = pageTitle

        pinSaveTask = Task(priority: .userInitiated) {
            // document.title is the browser tab name. WebKit's `title` is often
            // still the URL when the pin is saved.
            let live = await liveDocumentTitle(for: url)
            let titleToSave = PageTitle.headline(live ?? fallbackTitle, url: url)
            // Decoupled task with userInitiated priority guarantees completion even if view/sheet is dismissed.
            // A failed write surfaces through the app's existing error handling.
            if let outcome = try? await pinWriter.addTab(
                url: url,
                title: titleToSave,
                to: target.spaceId,
                folderId: kind == .normal ? nil : target.folderId,
                kind: kind
            ), outcome.fellBackToPinned {
                showPinNotice(String(localized: "share.saved.fallback_pinned"), duration: 6.0)
            }
        }
    }

    private func liveDocumentTitle(for url: URL) async -> String? {
        guard let webView else { return nil }
        let payload: String? = await withCheckedContinuation { continuation in
            webView.evaluateJavaScript(PageTitle.documentTitleScript) { result, _ in
                continuation.resume(returning: result as? String)
            }
        }
        return PageTitle.pickDocumentTitle(payload, url: url)
    }

    /// Test/await seam for the decoupled pin write; production never waits.
    func waitForPinSave() async {
        await pinSaveTask?.value
    }

    private func restartBannerDismissTimer(after seconds: Double = 5.0) {
        pinBannerDismissTask?.cancel()
        pinBannerDismissTask = Task {
            let nanoseconds = UInt64(seconds * 1_000_000_000)
            try? await Task.sleep(nanoseconds: nanoseconds)
            guard !Task.isCancelled else { return }
            commitPendingPinSave()
            withAnimation(.spring(response: 0.35, dampingFraction: 0.85)) {
                showPinBanner = false
            }
        }
    }

    // MARK: - Theme color

    func updateThemeColor(from string: String?, nativeColor: UIColor?) {
        if let native = nativeColor {
            self.themeColor = Color(uiColor: native)
            return
        }
        guard let string = string?.trimmingCharacters(in: .whitespacesAndNewlines), !string.isEmpty else {
            self.themeColor = nil
            return
        }
        if string.starts(with: "#") {
            self.themeColor = Color(hex: string)
        } else if string.starts(with: "rgb") {
            self.themeColor = Color(cssRGB: string)
        } else {
            self.themeColor = nil
        }
    }
}

/// Loads one suggest response. Failures throw; the model turns them into an empty list.
protocol SearchSuggestionFetching: Sendable {
    func fetch(_ url: URL) async throws -> Data
}

/// Ephemeral session: suggest requests do not send or store browser cookies.
struct LiveSearchSuggestionClient: SearchSuggestionFetching {
    func fetch(_ url: URL) async throws -> Data {
        var request = URLRequest(url: url)
        request.timeoutInterval = 4
        request.setValue("application/json, text/javascript, */*", forHTTPHeaderField: "Accept")
        let (data, response) = try await SearchSuggestionSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
            throw URLError(.badServerResponse)
        }
        return data
    }
}

private enum SearchSuggestionSession {
    static let shared: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 4
        config.timeoutIntervalForResource = 4
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        config.httpShouldSetCookies = false
        config.httpCookieAcceptPolicy = .never
        return URLSession(configuration: config)
    }()
}
