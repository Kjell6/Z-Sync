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

}

/// One block of extracted article text, in document order.
struct ReaderBlock: Equatable, Identifiable {
    let id: Int
    /// Lowercase source tag: "h1"…"h6", "p", "li", "blockquote", "pre".
    let kind: String
    let text: String
}

/// A page lifted into a plain reading view.
struct ReaderArticle: Equatable {
    let title: String
    let site: String
    let byline: String
    let blocks: [ReaderBlock]
}

/// Finds the article on a page and returns it as text. The script runs inside
/// the page, so it stays self-contained and ES5-only: WKWebView here and
/// Android's WebView evaluate the same source.
enum ReaderExtractor {
    static let script = #"""
    (function () {
      var BLOCK_SELECTOR = 'h1,h2,h3,h4,h5,h6,p,li,blockquote,pre';
      var NOISE = { NAV: 1, ASIDE: 1, FOOTER: 1, HEADER: 1, FORM: 1, BUTTON: 1 };
      // Chrome headings that survive the noise filter but say nothing in a reader.
      var JUNK_HEADINGS = { contents: 1, 'table of contents': 1, 'inhaltsverzeichnis': 1, inhalt: 1, menu: 1, navigation: 1 };

      function textOf(el) {
        if (!el) return '';
        var raw = el.innerText;
        if (raw === undefined || raw === null) raw = el.textContent || '';
        return String(raw).replace(/\s+/g, ' ').trim();
      }

      function isVisible(el) {
        try {
          var s = window.getComputedStyle(el);
          if (!s) return true;
          if (s.display === 'none' || s.visibility === 'hidden') return false;
          if (parseFloat(s.opacity) === 0) return false;
        } catch (e) {}
        return true;
      }

      function linkShare(el) {
        var total = textOf(el).length;
        if (total === 0) return 1;
        var links = el.querySelectorAll('a');
        var len = 0;
        for (var i = 0; i < links.length; i++) len += textOf(links[i]).length;
        return len / total;
      }

      function meta(selector) {
        var el = document.querySelector(selector);
        if (!el) return '';
        var value = el.content !== undefined && el.content !== null ? el.content : textOf(el);
        return String(value).replace(/\s+/g, ' ').trim();
      }

      var candidates = document.querySelectorAll('article, main, [role="main"], section, div, td');
      var scored = [];
      var longest = 0;
      for (var i = 0; i < candidates.length; i++) {
        var el = candidates[i];
        if (!isVisible(el)) continue;
        var length = textOf(el).length;
        if (length < 300) continue;
        var share = linkShare(el);
        if (share > 0.6 && length < 2000) continue;
        if (length > longest) longest = length;
        scored.push({ el: el, length: length, score: length - 1.2 * length * share + 30 * el.querySelectorAll('p').length });
      }
      if (scored.length === 0) return null;

      var winner = scored[0];
      for (var s = 1; s < scored.length; s++) {
        if (scored[s].score > winner.score) winner = scored[s];
      }

      // The whole page usually wins on raw text. Prefer the most nested
      // container that still holds nearly all of it, so the article body wins
      // over the shell around it.
      var chosen = winner.el;
      var deepest = -1;
      for (var c = 0; c < scored.length; c++) {
        if (scored[c].length < longest * 0.85) continue;
        var depth = 0;
        var node = scored[c].el;
        while (node) { depth++; node = node.parentElement; }
        if (depth > deepest) { deepest = depth; chosen = scored[c].el; }
      }

      function insideNoise(el) {
        var p = el.parentElement;
        while (p && p !== chosen) {
          if (NOISE[(p.tagName || '').toUpperCase()]) return true;
          p = p.parentElement;
        }
        return false;
      }

      function insideBlock(el) {
        var p = el.parentElement;
        while (p && p !== chosen) {
          var tag = (p.tagName || '').toLowerCase();
          if (tag === 'li' || tag === 'blockquote' || tag === 'pre' || tag === 'figcaption') return true;
          p = p.parentElement;
        }
        return false;
      }

      var blocks = [];
      var nodes = chosen.querySelectorAll(BLOCK_SELECTOR);
      for (var b = 0; b < nodes.length; b++) {
        var node = nodes[b];
        if (!isVisible(node)) continue;
        if (insideNoise(node) || insideBlock(node)) continue;
        var text = textOf(node);
        if (text.length < 2) continue;
        if (linkShare(node) > 0.7) continue;
        var tag = node.tagName.toLowerCase();
        if (tag === 'h1' || tag === 'h2' || tag === 'h3') {
          if (JUNK_HEADINGS[text.toLowerCase()]) continue;
        }
        blocks.push({ kind: tag, text: text });
      }

      var merged = [];
      for (var m = 0; m < blocks.length; m++) {
        var previous = merged[merged.length - 1];
        if (previous && previous.text === blocks[m].text) continue;
        merged.push(blocks[m]);
      }
      if (merged.length === 0) return null;

      var title = meta('meta[property="og:title"]') || meta('meta[name="twitter:title"]');
      if (!title) {
        var heading = document.querySelector('h1');
        title = heading ? textOf(heading) : '';
      }
      if (!title) title = String(document.title || '').trim();

      var byline = meta('meta[name="author"]') || meta('meta[property="article:author"]');
      if (!byline) {
        var author = document.querySelector('[rel="author"], .author, .byline');
        if (author) byline = textOf(author).slice(0, 80);
      }

      var site = meta('meta[property="og:site_name"]') || location.hostname.replace(/^www\./, '');

      return { title: title, site: site, byline: byline, blocks: merged };
    })()
    """#

    /// The script wrapped so the page hands back one JSON string.
    static var jsonScript: String { "JSON.stringify(\(script))" }

    /// Parses the page's payload. Nil means the page has no article to lift.
    static func article(from payload: String?) -> ReaderArticle? {
        guard let payload, let data = payload.data(using: .utf8),
              let raw = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return nil
        }
        let entries = raw["blocks"] as? [[String: Any]] ?? []
        let blocks = entries.enumerated().compactMap { index, entry -> ReaderBlock? in
            guard let text = entry["text"] as? String, !text.isEmpty else { return nil }
            return ReaderBlock(id: index, kind: (entry["kind"] as? String) ?? "p", text: text)
        }
        guard !blocks.isEmpty else { return nil }
        return ReaderArticle(
            title: (raw["title"] as? String) ?? "",
            site: (raw["site"] as? String) ?? "",
            byline: (raw["byline"] as? String) ?? "",
            blocks: blocks
        )
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
    /// Reading view: the article lifted out of the page, or nil for the page
    /// itself. The script below finds it; a page without an article returns nil.
    private(set) var readerArticle: ReaderArticle?
    /// A reader extraction is in flight (the row shows a spinner).
    private(set) var readerLoading = false
    /// The page scrolled down far enough to tuck the bottom bar away. Fed by
    /// the web view; the view ANDs it with the states that must keep the bar
    /// (editing the address, an open menu, a live toast).
    private(set) var scrollHidesBar = false
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

    // MARK: Error panel
    /// Set while a panel covers the web view because the page couldn't load.
    private(set) var pageError: BrowserPageError?
    /// The address that failed. The address bar, Safari and the panel's
    /// buttons act on it rather than on the page underneath.
    private(set) var failedURL: URL?

    // MARK: Pin banner
    var showPinBanner = false
    var pinnedDestination = PinDestination(spaceId: "")
    var hasPendingPinSave = false
    /// The page the user pinned, captured when the banner opened, so a later
    /// navigation can't swap in another page before the save commits.
    private var pendingPin: (url: URL, title: String)?
    /// How the pending save will be written (pinned vs normal). Captured when
    /// the banner is triggered and kept honest against the capability.
    private(set) var pinSaveKind: SaveKind = .pinned
    /// Short-lived message for a refusal or a dead end (no spaces to pin to,
    /// no article to read). Rendered in the same bottom toast as the banner.
    var pinNotice: String?
    /// SF Symbol shown with `pinNotice`.
    private(set) var pinNoticeSymbol = "pin.slash.fill"

    // MARK: Content blocking
    /// Ads and trackers blocked on the current page (close, not exact, on iOS).
    private(set) var blockedCount = 0
    /// The page was reloaded once with nothing blocked.
    private(set) var showingOriginal = false
    /// The site's own blocking switches, opened from the menu.
    var siteSettingsPresented = false
    @ObservationIgnored private var originalPage: URL?
    @ObservationIgnored private var countedResources = Set<String>()
    @ObservationIgnored private var appliedBlocking: BlockingConfiguration?
    @ObservationIgnored let blocker: ContentBlocker

    /// Scroll offset captured when the reader asked for a reload.
    @ObservationIgnored private var reloadScrollY: CGFloat?
    @ObservationIgnored private var pinBannerDismissTask: Task<Void, Never>?
    @ObservationIgnored private var pinNoticeDismissTask: Task<Void, Never>?
    @ObservationIgnored private(set) var pinSaveTask: Task<Void, Never>?

    private let initialURL: URL?
    private let pinWriter: PinWriting
    private let urlOpener: URLOpening
    private let searchEngine: SearchEngineProviding
    private let haptics: HapticsPlaying
    private let preferences: PreferencesStoring
    /// Whether this account can write normal tabs at all. Without it the save
    /// row offers pinned only, like the share sheet.
    let allowsNormalTabs: Bool
    private let suggestionClient: SearchSuggestionFetching
    private let suggestionDebounce: Duration

    init(
        initialURL: URL?,
        pinWriter: PinWriting = AppServices.pinWriter,
        urlOpener: URLOpening = AppServices.urlOpener,
        searchEngine: SearchEngineProviding = AppServices.searchEngine,
        haptics: HapticsPlaying = AppServices.haptics,
        preferences: PreferencesStoring = AppServices.preferences,
        allowsNormalTabs: Bool = false,
        suggestionClient: SearchSuggestionFetching = LiveSearchSuggestionClient(),
        suggestionDebounce: Duration = SearchSuggestions.debounce,
        blocker: ContentBlocker = .shared
    ) {
        self.initialURL = initialURL
        self.blocker = blocker
        self.pinWriter = pinWriter
        self.urlOpener = urlOpener
        self.searchEngine = searchEngine
        self.haptics = haptics
        self.preferences = preferences
        self.allowsNormalTabs = allowsNormalTabs
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
        if pageError != nil, let failedURL {
            return failedURL
        }
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
        // A typed or synced mailto:/tel: is an explicit request to leave the
        // browser; the page navigation policy only lets taps through.
        guard BrowserNavigationPolicy.loadsInWebView(url) else {
            urlOpener.open(url)
            return
        }
        webView?.load(URLRequest(url: url))
    }

    func reload() {
        // After a failed load the web view still holds the old page;
        // reloading means trying the failed address again.
        if pageError != nil {
            retryFailedLoad()
            return
        }
        // Keep the reader where they were. WebKit's own restore can land at
        // the end of a long document, and a page with an anchor (`#cite_note…`)
        // jumps to it, which reads as "reload throws me to the bottom".
        reloadScrollY = webView?.scrollView.contentOffset.y
        webView?.reload()
    }

    /// Puts the page back where it was before the reload, once the new
    /// document has laid out. Nil unless a reload is in flight.
    func restoreScrollAfterReload() {
        guard let y = reloadScrollY, let scrollView = webView?.scrollView else { return }
        reloadScrollY = nil
        let maxY = max(0, scrollView.contentSize.height - scrollView.bounds.height)
        let target = min(max(0, y), maxY)
        guard abs(scrollView.contentOffset.y - target) > 1 else { return }
        scrollView.setContentOffset(
            CGPoint(x: scrollView.contentOffset.x, y: target),
            animated: false
        )
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

    /// Scroll-driven bar visibility. No-op when the value is unchanged so a
    /// long scroll does not re-render on every frame.
    func setScrollHidesBar(_ hidden: Bool) {
        guard scrollHidesBar != hidden else { return }
        scrollHidesBar = hidden
    }

    // MARK: - Reader

    /// Toggles the reading view. Leaves the page loaded, so closing it is
    /// instant and the page keeps its scroll position.
    func toggleReader() {
        if readerArticle != nil {
            closeReader()
            return
        }
        loadReader()
    }

    func closeReader() {
        readerLoading = false
        withAnimation(.easeInOut(duration: 0.2)) {
            readerArticle = nil
        }
    }

    private func loadReader() {
        guard let webView, webView.url != nil, !readerLoading else { return }
        readerLoading = true
        webView.evaluateJavaScript(ReaderExtractor.jsonScript) { [weak self] result, _ in
            guard let self else { return }
            self.readerLoading = false
            guard let article = ReaderExtractor.article(from: result as? String) else {
                self.readerArticle = nil
                self.showPinNotice(
                    String(localized: "browser.reader_unavailable"),
                    symbol: "doc.plaintext"
                )
                return
            }
            withAnimation(.easeInOut(duration: 0.2)) {
                self.readerArticle = article
            }
        }
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
        // Keep the current list until the new answer arrives, so it does not
        // flicker on every keystroke.
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
            // A fresh document starts at the top with the bar in place, and
            // ends the reading view.
            scrollHidesBar = false
            readerArticle = nil
            readerLoading = false
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
            // A failed load puts the old page's address back; the bar keeps
            // showing the address that failed.
            if !isAddressFocused && pageError == nil {
                addressText = BrowserInput.displayText(for: url)
            }
            // Leaving the pinned page saves it now instead of letting the
            // banner timer save whatever page is showing later.
            if hasPendingPinSave, let pinned = pendingPin?.url, !PageTitle.sameDocument(pinned, url) {
                pinBannerDismissTask?.cancel()
                commitPendingPinSave()
                // Only a real navigation ends the confirmation. A same-document
                // change (an anchor, an SPA route) keeps the save pending and
                // must not hide the row before it commits.
                showPinBanner = false
            }
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

    // MARK: - Error panel

    /// A load failed before the page appeared.
    func loadDidFail(_ error: Error) {
        let nsError = error as NSError
        guard let kind = BrowserPageError.forLoadFailure(domain: nsError.domain, code: nsError.code) else {
            return
        }
        let failing = nsError.userInfo[NSURLErrorFailingURLErrorKey] as? URL
            ?? (nsError.userInfo[NSURLErrorFailingURLStringErrorKey] as? String).flatMap(URL.init(string:))
        pageError = kind
        failedURL = failing ?? webView?.url ?? currentURL
    }

    func webContentProcessDidTerminate() {
        pageError = .crashed
        failedURL = webView?.url ?? currentURL
    }

    /// A new page replaced whatever failed.
    func pageDidCommit() {
        pageError = nil
        failedURL = nil
        countedResources.removeAll()
        blockedCount = 0
    }

    // MARK: - Content blocking

    /// The page's host for blocking, or nil for a blank or non-web page.
    var blockingHost: String? {
        guard let url = effectiveURL, ["http", "https"].contains(url.scheme?.lowercased() ?? "") else { return nil }
        return url.host
    }

    var blockingCard: BlockingCard {
        BlockingCard.of(settings: blocker.settings, host: blockingHost, blockedCount: blockedCount, showingOriginal: showingOriginal)
    }

    /// A main-frame load is about to start: the lists and scripts for its site
    /// go on the web view first. Leaving the page shown as the original turns
    /// blocking back on.
    func prepareBlocking(for url: URL) {
        let page = Self.withoutFragment(url)
        if originalPage != nil, originalPage != page {
            originalPage = nil
            showingOriginal = false
        }
        applyBlocking(host: url.host)
    }

    /// Resources the page reported. WebKit doesn't say what it blocked, so the
    /// count is the reported resources an active list matches.
    func resourcesLoaded(_ urls: [String], page: URL?) {
        guard originalPage == nil, let host = currentURL?.host, page?.host == host else { return }
        for raw in urls where countedResources.count < Self.maxCountedResources {
            guard !countedResources.contains(raw), let url = URL(string: raw), blocker.blocks(url, pageHost: host) else { continue }
            countedResources.insert(raw)
        }
        if blockedCount != countedResources.count { blockedCount = countedResources.count }
    }

    func tapBlockingCard() {
        switch blockingCard {
        case .blocked:
            originalPage = currentURL.map(Self.withoutFragment)
            showingOriginal = true
            menuExpanded = false
            reloadForBlocking()
        case .showingOriginal:
            originalPage = nil
            showingOriginal = false
            menuExpanded = false
            reloadForBlocking()
        case .offForSite:
            openSiteSettings()
        case .hidden:
            break
        }
    }

    func openSiteSettings() {
        menuExpanded = false
        siteSettingsPresented = true
    }

    /// A switch changed in Site Settings: the page reloads with it.
    func blockingSettingsDidChange() {
        reloadForBlocking()
    }

    private func reloadForBlocking() {
        applyBlocking(host: currentURL?.host)
        webView?.reload()
    }

    private func applyBlocking(host: String?) {
        guard let controller = webView?.configuration.userContentController else { return }
        let configuration = blocker.configuration(host: host, original: originalPage != nil)
        guard configuration != appliedBlocking else { return }
        appliedBlocking = configuration
        blocker.apply(configuration, to: controller)
        controller.addUserScript(WKUserScript(
            source: BlockedResourceReport.script,
            injectionTime: .atDocumentStart,
            forMainFrameOnly: true
        ))
    }

    private static let maxCountedResources = 2_000

    private static func withoutFragment(_ url: URL) -> URL {
        guard var components = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return url }
        components.fragment = nil
        return components.url ?? url
    }

    /// Keeps the address bar on the failed address while the panel shows,
    /// and back on the live page once it's gone.
    func pageErrorDidChange(isAddressFocused: Bool) {
        guard !isAddressFocused, let url = failedURL ?? currentURL else { return }
        addressText = BrowserInput.displayText(for: url)
    }

    func retryFailedLoad() {
        guard let failedURL else {
            webView?.reload()
            return
        }
        load(url: failedURL)
    }

    /// Searches for the host that couldn't be found, usually a typo.
    func searchFailedHost() {
        guard let failedURL,
              let url = searchEngine.current.searchURL(for: failedURL.host ?? failedURL.absoluteString)
        else { return }
        load(url: url)
    }

    /// "Go back" on the panel. Returns true when the browser should close.
    func leaveErrorPage() -> Bool {
        let exit = BrowserPageError.exit(
            currentURL: webView?.url,
            failedURL: failedURL,
            canGoBack: webView?.canGoBack ?? false
        )
        switch exit {
        case .reveal:
            pageDidCommit()
        case .goBack:
            goBack()
        case .dismiss:
            return true
        }
        return false
    }

    // MARK: - Pin save state machine

    func triggerPinBanner(spaces: [ZenSpace], fallbackSpace: ZenSpace) {
        // A page that failed to load has nothing worth pinning.
        guard let url = effectiveURL, pageError == nil else { return }
        guard !spaces.isEmpty else {
            // There is no attached destination, so refuse instead of writing
            // to an unattached workspace id (fallbackSpace is not in sync).
            refusePinWithoutSpaces()
            return
        }
        pinNoticeDismissTask?.cancel()
        pinNotice = nil
        // The saved setting is the default for this save; the row can change it.
        pinSaveKind = allowsNormalTabs ? saveKind : .pinned
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
        pendingPin = (url: url, title: pageTitle)

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
        pendingPin = nil
        withAnimation(.spring(response: 0.35, dampingFraction: 0.85)) {
            showPinBanner = false
        }
        showPinNotice(String(localized: "browser.pin_no_space"))
    }

    /// Bottom toast for the browser itself, same language as the pin notice.
    func showNotice(_ text: String, symbol: String = "exclamationmark.circle") {
        showPinNotice(text, symbol: symbol)
    }

    private func showPinNotice(_ text: String, symbol: String = "pin.slash.fill", duration: TimeInterval = 4.0) {
        pinNotice = text
        pinNoticeSymbol = symbol
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

    /// Per-save pinned/normal choice. Nothing is persisted: the next save
    /// starts from the setting again, as in the share sheet.
    func selectPinSaveKind(_ kind: SaveKind) {
        guard kind != pinSaveKind else { return }
        guard kind == .pinned || allowsNormalTabs else { return }
        pinSaveKind = kind
        // A normal save always lands at the space root.
        if kind == .normal {
            pinnedDestination = PinDestination(spaceId: pinnedDestination.spaceId)
        }
        haptics.selectionChanged()
        restartBannerDismissTimer(after: 1.0)
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
    }

    func pinMenuDidDismiss() {
        restartBannerDismissTimer(after: 1.5)
    }

    func commitPendingPinSave() {
        guard hasPendingPinSave,
              let pin = pendingPin ?? effectiveURL.map({ (url: $0, title: pageTitle) })
        else {
            return
        }
        hasPendingPinSave = false
        pendingPin = nil
        // Still on the pinned page: the live title, or one that loaded after
        // the tap, wins over the one captured when the banner opened.
        let stillOnPage = PageTitle.sameDocument(pin.url, effectiveURL)
        let fallbackTitle = stillOnPage && !pageTitle.isEmpty ? pageTitle : pin.title
        let target = pinnedDestination
        let kind = pinSaveKind

        pinSaveTask = Task(priority: .userInitiated) {
            // document.title is the browser tab name. WebKit's `title` is often
            // still the URL when the pin is saved.
            let live = stillOnPage ? await liveDocumentTitle(for: pin.url) : nil
            let titleToSave = PageTitle.headline(live ?? fallbackTitle, url: pin.url)
            // Decoupled task with userInitiated priority guarantees completion even if view/sheet is dismissed.
            do {
                let outcome = try await pinWriter.addTab(
                    url: pin.url,
                    title: titleToSave,
                    to: target.spaceId,
                    folderId: kind == .normal ? nil : target.folderId,
                    kind: kind
                )
                if outcome.fellBackToPinned {
                    showPinNotice(String(localized: "share.saved.fallback_pinned"), duration: 6.0)
                }
            } catch {
                showPinNotice("\(String(localized: "browser.pin_failed")) \(error.zenUserMessage)", duration: 6.0)
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
