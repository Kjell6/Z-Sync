import AVFoundation
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

/// Page zoom inside a loaded document.
///
/// A browser's page zoom reflows the page: the layout viewport narrows, text
/// wraps earlier, and images scale with it, so nothing runs off the screen.
/// Setting CSS `zoom` on the root element does exactly that in both web views.
/// `WKWebView.pageZoom` looks like the native answer but does not: WebKit
/// scales the rendered page and leaves the layout width alone, so a zoomed-in
/// page just runs off the right edge. Android's `WebSettings.textZoom` is no
/// better - it grows fonts only and leaves images and spacing at their size.
///
/// Android port: `PageZoomScript.source` in
/// `ui/screens/MiniBrowserWebView.kt` builds the same function.
enum PageZoomScript {
    /// Applies [zoom] (1.0 = 100%) to the document that is already loaded.
    /// Idempotent, so it is safe to run again after every navigation.
    static func source(for zoom: Double) -> String {
        let body = abs(zoom - MiniBrowserTools.defaultZoom) <= 0.001
            ? "root.style.removeProperty('zoom');"
            : "root.style.zoom = '\(String(format: "%.2f", zoom))';"
        return """
        (function () {
          // Only the top document: an embedded frame is already scaled with
          // its parent's layout, and zooming it again would double the size.
          try {
            if (window.self !== window.top) return;
          } catch (e) {
            return;
          }
          function apply() {
            var root = document.documentElement;
            if (!root) return;
            \(body)
          }
          if (document.documentElement) apply();
          else document.addEventListener('DOMContentLoaded', apply, false);
        })()
        """
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
      var NOISE = { NAV: 1, ASIDE: 1, FOOTER: 1, HEADER: 1, FORM: 1, BUTTON: 1, DIALOG: 1 };
      // ARIA landmarks that are chrome around an article, not the article.
      var NOISE_ROLES = { banner: 1, complementary: 1, navigation: 1, contentinfo: 1, search: 1, menu: 1, menubar: 1, dialog: 1, alertdialog: 1 };
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

      function role(el) {
        var explicit = el.getAttribute && el.getAttribute('role');
        return explicit ? String(explicit).toLowerCase() : '';
      }

      // Cookie walls, donation banners and newsletter pop-ups paint over the
      // article, and their text often outscores the real one. Everything
      // pinned to the viewport is chrome, never the story. Memoised per run:
      // thousands of candidates share the same ancestors, and
      // getComputedStyle is the expensive part of this pass.
      var overlayMemo = new Map();
      function isOverlay(el) {
        if (overlayMemo.has(el)) return overlayMemo.get(el);
        var pinned = false;
        try {
          var s = window.getComputedStyle(el);
          if (s && (s.position === 'fixed' || s.position === 'sticky')) pinned = true;
        } catch (e) {}
        if (!pinned && el !== document.body && el.parentElement) pinned = isOverlay(el.parentElement);
        overlayMemo.set(el, pinned);
        return pinned;
      }

      // Landmarks and overlays that are not the article: walking up from a
      // block (or a candidate container) tells us whether it belongs to one.
      function inNoiseChrome(el, stopAt) {
        var node = el;
        while (node && node !== stopAt) {
          if (NOISE_ROLES[role(node)]) return true;
          if (node.getAttribute && node.getAttribute('aria-hidden') === 'true') return true;
          node = node.parentElement;
        }
        return false;
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
      function collect(skipChrome) {
        var scored = [];
        for (var i = 0; i < candidates.length; i++) {
          var el = candidates[i];
          if (!isVisible(el)) continue;
          if (skipChrome && (isOverlay(el) || inNoiseChrome(el, document.body))) continue;
          var length = textOf(el).length;
          if (length < 300) continue;
          var share = linkShare(el);
          if (share > 0.6 && length < 2000) continue;
          scored.push({ el: el, length: length, score: length - 1.2 * length * share + 30 * el.querySelectorAll('p').length });
        }
        return scored;
      }

      // Overlay filtering is the usual answer, but a site that pins its whole
      // layout (an app shell) would then have no candidates at all: fall back
      // to the unfiltered scan rather than declaring the page unreadable.
      var scored = collect(true);
      if (scored.length === 0) scored = collect(false);
      if (scored.length === 0) return null;

      var longest = 0;
      for (var l = 0; l < scored.length; l++) {
        if (scored[l].length > longest) longest = scored[l].length;
      }

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
          if (NOISE_ROLES[role(p)]) return true;
          if (p.getAttribute && p.getAttribute('aria-hidden') === 'true') return true;
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
    /// Bumped when the reader closes or the page moves on, so an answer for a
    /// page the reader has left cannot open it.
    @ObservationIgnored private var readerRequest = 0
    /// The page scrolled down far enough to tuck the bottom bar away. Fed by
    /// the web view; the view ANDs it with the states that must keep the bar
    /// (editing the address, an open menu, a live toast).
    private(set) var scrollHidesBar = false
    /// Session-only. Navigations request the desktop version of the site.
    var requestsDesktopSite = false
    /// 1.0 is 100%. Always inside `MiniBrowserTools` limits.
    private(set) var pageZoom: Double = MiniBrowserTools.defaultZoom

    /// The web view and zoom last written into a document, so repeated view
    /// updates do not re-run the zoom script.
    @ObservationIgnored private weak var zoomedWebView: WKWebView?
    @ObservationIgnored private var appliedPageZoom: Double?

    /// The page's live web view; nil while the page sleeps. Owned here, not by
    /// the view, so the page survives being off screen.
    var webView: WKWebView?
    /// Keeps the delegates alive with the web view.
    @ObservationIgnored private var driver: MiniBrowserWebDriver?
    /// The open pages this page belongs to; nil outside the browser.
    @ObservationIgnored weak var host: MiniBrowserPageHost?

    // MARK: Address field
    var addressText: String = ""
    /// Suggestions for the text being typed. Empty hides the list.
    var suggestions: [String] = []
    /// Pages from the history for the same text, above the suggestions.
    var historySuggestions: [BrowserVisit] = []
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
    /// The address saved from this page, which does not stay as an open page too.
    private(set) var pinnedURL: URL?
    /// `canGoBack` starts over with the next web view; a sleeping page still
    /// remembers having been browsed.
    private(set) var sleptWithBackHistory = false
    /// How the pending save will be written (pinned vs normal). Captured when
    /// the banner is triggered and kept honest against the capability.
    private(set) var pinSaveKind: SaveKind = .pinned
    /// Short-lived message for a refusal or a dead end (no spaces to pin to,
    /// no article to read). Rendered in the same bottom toast as the banner.
    var pinNotice: String?
    /// The page's open questions, oldest first. The view shows the first.
    private(set) var dialogs: [BrowserDialog] = []
    /// SF Symbol shown with `pinNotice`.
    private(set) var pinNoticeSymbol = "pin.slash.fill"

    // MARK: Content blocking
    /// Ads and trackers blocked on the current page (close, not exact, on iOS).
    private(set) var blockedCount = 0
    /// The page was reloaded once with nothing blocked.
    private(set) var showingOriginal = false
    /// The site's own blocking switches, as a page inside the open menu.
    var menuSiteSettings = false
    @ObservationIgnored private var originalPage: URL?
    @ObservationIgnored private var countedResources = Set<String>()
    /// Reported addresses looked at for this document, counted or not.
    @ObservationIgnored private var checkedResources = 0
    @ObservationIgnored private var appliedBlocking: BlockingConfiguration?
    @ObservationIgnored let blocker: ContentBlocker

    /// Scroll offset captured when the reader asked for a reload.
    @ObservationIgnored private var reloadScrollY: CGFloat?
    @ObservationIgnored private var pinBannerDismissTask: Task<Void, Never>?
    @ObservationIgnored private var pinNoticeDismissTask: Task<Void, Never>?
    @ObservationIgnored private(set) var pinSaveTask: Task<Void, Never>?

    private(set) var initialURL: URL?
    private let pinWriter: PinWriting
    private let urlOpener: URLOpening
    private let searchEngine: SearchEngineProviding
    private let haptics: HapticsPlaying
    private let preferences: PreferencesStoring
    /// Whether this account can write normal tabs at all. Without it the save
    /// row offers pinned only, like the share sheet.
    var allowsNormalTabs: Bool
    private let suggestionClient: SearchSuggestionFetching
    let sitePermissions: SitePermissionStore
    private let history: BrowserHistoryStore
    /// The address last put in the history.
    @ObservationIgnored private var lastRecordedVisit: String?
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
        blocker: ContentBlocker = .shared,
        sitePermissions: SitePermissionStore = .shared,
        history: BrowserHistoryStore = .shared
    ) {
        self.history = history
        self.initialURL = initialURL
        self.sitePermissions = sitePermissions
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
    var saveKind: SaveKind { Self.storedSaveKind(preferences) }

    /// Same read for callers without a browser, such as the home list.
    static func storedSaveKind(_ preferences: PreferencesStoring) -> SaveKind {
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
    var opensWithSearch: Bool { initialURL == nil && currentURL == nil && failedURL == nil }

    /// The page's own address, leaving out what the address field holds. Nil
    /// for a new page nothing has loaded in yet.
    var pageURL: URL? { currentURL ?? initialURL }

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
    /// The percent readout doubles as "back to 100%" once the page is zoomed.
    var canResetZoom: Bool { abs(pageZoom - MiniBrowserTools.defaultZoom) > 0.001 }

    // MARK: - Loading

    func loadInitial() {
        if let initialURL {
            load(url: initialURL)
            addressText = BrowserInput.displayText(for: initialURL)
        }
    }

    // MARK: - Web view lifecycle

    /// Takes on a new web view. The page's own settings go on it first.
    func adopt(_ webView: WKWebView, driver: MiniBrowserWebDriver) {
        self.webView = webView
        self.driver = driver
        applyWebpagePreferences()
    }

    /// Gives the page a web view again: from [history] when there is one, at
    /// its last address otherwise. Nothing happens to a page that is awake.
    func wake(history: Any? = nil) {
        guard webView == nil else { return }
        let view = MiniBrowserWebDriver.attach(to: self)
        // A restored page can start without asking the navigation delegate
        // first, so its lists and scripts go on before the state comes back.
        if let url = pageURL { prepareBlocking(for: url) }
        if let history {
            view.interactionState = history
        } else if let url = pageURL {
            load(url: url)
        }
        if let url = pageURL { addressText = BrowserInput.displayText(for: url) }
    }

    /// Lets the web view go and returns its history, so `wake` can bring it
    /// back. The title, address and page settings stay on the model.
    @discardableResult
    func sleep() -> Any? {
        guard let webView else { return nil }
        let history = webView.interactionState
        webView.stopLoading()
        webView.configuration.userContentController.removeAllScriptMessageHandlers()
        webView.removeFromSuperview()
        self.webView = nil
        driver = nil
        // The next web view starts without the lists and without the zoom.
        appliedBlocking = nil
        zoomedWebView = nil
        appliedPageZoom = nil
        reloadScrollY = nil
        isLoading = false
        estimatedProgress = 0
        // The questions went with the page; dropping them answers WebKit.
        dialogs = []
        sleptWithBackHistory = canGoBack
        canGoBack = false
        canGoForward = false
        return history
    }

    /// The page left the screen: its sound stops with it.
    func didLeaveScreen() {
        webView?.pauseAllMediaPlayback(completionHandler: nil)
        collapseMenu()
        setScrollHidesBar(false)
    }

    /// Brings a page back from the list with what was saved about it.
    func restore(title: String) {
        pageTitle = title
    }

    /// For a page kept from an earlier run: coming back to it is not a visit.
    func markAddressAsVisited() {
        lastRecordedVisit = BrowserHistoryStore.address(of: pageURL)
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

    func reload(fromTop: Bool = false) {
        // After a failed load the web view still holds the old page;
        // reloading means trying the failed address again.
        if pageError != nil {
            retryFailedLoad()
            return
        }
        // Keep the reader where they were. WebKit's own restore can land at
        // the end of a long document, and a page with an anchor (`#cite_note…`)
        // jumps to it, which reads as "reload throws me to the bottom". A pull
        // starts at the top, so it asks for the top: leaving nothing to
        // restore would let WebKit's own landing place win.
        reloadScrollY = fromTop ? topScrollY : webView?.scrollView.contentOffset.y
        webView?.reload()
    }

    /// The page's top edge in the scroll view's offset. A top inset moves it
    /// below zero, so a literal 0 is not the top.
    private var topScrollY: CGFloat? {
        guard let scrollView = webView?.scrollView else { return nil }
        return -scrollView.adjustedContentInset.top
    }

    /// Puts the page back where it was before the reload, once the new
    /// document has laid out. Nil unless a reload is in flight.
    func restoreScrollAfterReload() {
        guard let y = reloadScrollY, let scrollView = webView?.scrollView else { return }
        reloadScrollY = nil
        // Everything above the top edge is out of bounds; the bottom stays
        // where the content ends.
        let topY = -scrollView.adjustedContentInset.top
        let maxY = max(topY, scrollView.contentSize.height - scrollView.bounds.height)
        let target = min(max(topY, y), maxY)
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
            collapseMenu()
            return
        }
        guard effectiveURL != nil else { return }
        menuExpanded = true
        haptics.selectionChanged()
    }

    func collapseMenu() {
        menuExpanded = false
        menuSiteSettings = false
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
        readerRequest += 1
        readerLoading = false
        withAnimation(.easeInOut(duration: 0.2)) {
            readerArticle = nil
        }
    }

    private func loadReader() {
        guard let webView, webView.url != nil, !readerLoading else { return }
        readerLoading = true
        let request = readerRequest
        Task { @MainActor [weak self] in
            guard let self else { return }
            let payload = await self.pageScriptAnswer(ReaderExtractor.jsonScript, within: .seconds(5))
            // The page changed or the reader was closed while this ran, so the
            // article and the "no article" notice both belong to a page that
            // is no longer showing.
            guard request == self.readerRequest else { return }
            self.readerLoading = false
            guard let article = ReaderExtractor.article(from: payload) else {
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
        applyPageZoom()
    }

    /// Back to 100%, the way a browser resets its zoom.
    func resetPageZoom() {
        guard abs(pageZoom - MiniBrowserTools.defaultZoom) > 0.001 else { return }
        setPageZoom(MiniBrowserTools.defaultZoom)
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
        collapseMenu()
        guard let webView else { return }
        webView.isFindInteractionEnabled = true
        webView.findInteraction?.presentFindNavigator(showingReplace: false)
        // The find bar and the pull-to-refresh control share the top of the
        // page; the control waits until the bar is gone.
        (webView as? MiniBrowserWebView)?.findNavigatorDidPresent()
    }

    /// There is a page to print: it is loaded, nothing covers it, and it is
    /// not the reader.
    var canPrint: Bool {
        guard pageError == nil, readerArticle == nil else { return false }
        return pageURL != nil || webView?.url != nil
    }

    /// System print dialog for the page on screen.
    func presentPrint() {
        collapseMenu()
        guard canPrint, let webView else { return }
        let info = UIPrintInfo(dictionary: nil)
        info.jobName = Self.printJobName(title: pageTitle, url: pageURL ?? webView.url)
        info.outputType = .general
        let controller = UIPrintInteractionController.shared
        controller.printInfo = info
        controller.printFormatter = webView.viewPrintFormatter()
        _ = controller.present(animated: true) { panel, _, _ in
            // The shared controller must not keep the page alive after printing.
            panel.printFormatter = nil
            panel.printInfo = nil
        }
    }

    /// What the print dialog calls the job: the page's name, or its host when
    /// the page has no title.
    static func printJobName(title: String, url: URL?) -> String {
        let name = title.trimmingCharacters(in: .whitespacesAndNewlines)
        return name.isEmpty ? (url?.host ?? "") : name
    }

    /// Copies the current page URL. Returns false when there is nothing to copy.
    @discardableResult
    func copyCurrentLink() -> Bool {
        guard let url = effectiveURL else { return false }
        UIPasteboard.general.url = url
        haptics.selectionChanged()
        return true
    }

    /// Keeps WebKit's desktop mode aligned with the model. Called again when
    /// the web view is (re)attached.
    func applyWebpagePreferences() {
        guard let webView else { return }
        webView.configuration.defaultWebpagePreferences.preferredContentMode =
            requestsDesktopSite ? .desktop : .mobile
    }

    /// Puts the current zoom into the loaded document. A new document starts
    /// at 100%, so this runs again after every navigation (see
    /// `MiniBrowserRepresentable`). Skipped when the same web view already
    /// shows this zoom, so repeated SwiftUI updates cost nothing.
    func applyPageZoom(force: Bool = false) {
        guard let webView else { return }
        guard force || zoomedWebView !== webView || appliedPageZoom != pageZoom else { return }
        zoomedWebView = webView
        appliedPageZoom = pageZoom
        webView.evaluateJavaScript(PageZoomScript.source(for: pageZoom))
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

    /// A page from the history, picked while typing an address.
    func openVisit(_ visit: BrowserVisit) {
        suggestionsSuspended = true
        cancelSuggestionRequest()
        loadVisit(visit)
    }

    /// A page from the history, picked outside the address field.
    func loadVisit(_ visit: BrowserVisit) {
        guard let url = URL(string: visit.url) else { return }
        load(url: url)
    }

    /// Puts the page in the history. A reload, or a page coming back from
    /// sleep, is at the same address and so is no new visit.
    func recordVisit() {
        guard pageError == nil, let address = BrowserHistoryStore.address(of: currentURL),
              address != lastRecordedVisit else { return }
        lastRecordedVisit = address
        history.record(currentURL, title: pageTitle)
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
        // Local, so it answers right away and also for a typed address.
        let visits = history.suggestions(for: addressText, excluding: currentURL)
        animateSuggestions { historySuggestions = visits }
        guard let url = SearchSuggestions.requestURL(for: addressText, engine: searchEngine.current) else {
            animateSuggestions { suggestions = [] }
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
                let parsed = SearchSuggestions.parse(data)
                animateSuggestions { suggestions = parsed }
            } catch is CancellationError {
                return
            } catch {
                guard !Task.isCancelled, generation == suggestionGeneration else { return }
                animateSuggestions { suggestions = [] }
            }
        }
    }

    /// The list arrives on its own, so nobody else animates it. The change
    /// has to be one transaction for the whole screen: the column grows
    /// upwards as the list comes, and animated only from inside, its new
    /// place is taken at once and the address field jumps up and springs back.
    private func animateSuggestions(_ change: () -> Void) {
        let motion: Animation = UIAccessibility.isReduceMotionEnabled
            ? .easeOut(duration: 0.12)
            : .spring(response: 0.24, dampingFraction: 0.82)
        withAnimation(motion, change)
    }

    /// Test seam. Production never waits on the suggest request.
    func waitForSuggestions() async {
        await suggestionTask?.value
    }

    private func cancelSuggestionRequest(keepingList: Bool = false) {
        suggestionTask?.cancel()
        suggestionTask = nil
        suggestionGeneration += 1
        if !keepingList {
            animateSuggestions {
                if !suggestions.isEmpty { suggestions = [] }
                if !historySuggestions.isEmpty { historySuggestions = [] }
            }
        }
    }

    /// Drops the previous page's title when the document changes.
    func updateCurrentURL(_ url: URL?) {
        if !PageTitle.sameDocument(currentURL, url) {
            pageTitle = ""
            // A fresh document starts at the top with the bar in place, and
            // ends the reading view.
            scrollHidesBar = false
            readerRequest += 1
            readerArticle = nil
            readerLoading = false
        }
        currentURL = url
        if url != nil { host?.pageDidChange(self) }
        // A single-page site changes its address without loading anything. The
        // web view is asked too: WebKit publishes its new address and its
        // loading state together, and the address can arrive first.
        if !isLoading, webView?.isLoading != true { recordVisit() }
    }

    /// Keeps a real document title. URL-shaped WebKit titles are ignored so
    /// they cannot replace the page name.
    func adoptPageTitle(_ raw: String) {
        guard let title = PageTitle.usable(raw, url: currentURL ?? webView?.url) else { return }
        pageTitle = title
        host?.pageDidChange(self)
        if let lastRecordedVisit, lastRecordedVisit == BrowserHistoryStore.address(of: currentURL) {
            history.updateTitle(title, of: lastRecordedVisit)
        }
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
        checkedResources = 0
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
        for raw in urls where checkedResources < Self.maxCheckedResources {
            checkedResources += 1
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
            collapseMenu()
            reloadForBlocking()
        case .showingOriginal:
            originalPage = nil
            showingOriginal = false
            collapseMenu()
            reloadForBlocking()
        case .offForSite:
            openSiteSettings()
        case .hidden:
            break
        }
    }

    /// Opens the site's switches as a page inside the menu. The panel stays one
    /// surface instead of stacking a second sheet on top of it.
    func openSiteSettings() {
        menuExpanded = true
        menuSiteSettings = true
    }

    /// Back to the menu's own page. The menu stays open.
    func closeSiteSettings() {
        menuSiteSettings = false
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
        // `apply` clears every user script, this one included.
        BrowserGeolocation.addScript(to: controller)
        controller.addUserScript(WKUserScript(
            source: BlockedResourceReport.script,
            injectionTime: .atDocumentStart,
            forMainFrameOnly: true,
            in: BlockedResourceReport.world
        ))
    }

    /// Per document. A page reports at most 2,000 addresses; this also bounds
    /// the work if something reports more.
    private static let maxCheckedResources = 5_000

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

    func present(_ kind: BrowserDialog.Kind) {
        dialogs.append(BrowserDialog(kind: kind))
    }

    func dismissDialog(_ dialog: BrowserDialog) {
        dialogs.removeAll { $0.id == dialog.id }
    }

    /// Runs a site's request through the remembered answer or the browser's
    /// question. [completion] hears true only for an allowed site whose kinds
    /// the system has not refused; the system's own dialog comes after that.
    func requestPermission(_ kinds: [SitePermission], host: String, completion: @escaping (Bool) -> Void) {
        let store = sitePermissions
        switch store.decision(for: kinds, host: host) {
        case .grant:
            completion(allowedBySystem(kinds))
        case .deny:
            completion(false)
        case .ask:
            let answer = DialogAnswer<Bool?>(fallback: nil) { [weak self] allowed in
                guard let allowed else {
                    completion(false)
                    return
                }
                store.set(allowed ? .allow : .block, for: kinds, host: host)
                completion(allowed && (self?.allowedBySystem(kinds) ?? false))
            }
            present(.permission(host: host, kinds: kinds, answer))
        }
    }

    /// True while the system may still say yes. When it already said no it
    /// shows nothing more, so the browser explains where to change that.
    private func allowedBySystem(_ kinds: [SitePermission]) -> Bool {
        let off = kinds.filter { kind in
            switch kind {
            case .camera: Self.isCaptureOff(.video)
            case .microphone: Self.isCaptureOff(.audio)
            case .location: BrowserLocation.shared.isOffForApp
            }
        }
        guard !off.isEmpty else { return true }
        present(.systemAccessOff(kinds: off))
        return false
    }

    private static func isCaptureOff(_ media: AVMediaType) -> Bool {
        switch AVCaptureDevice.authorizationStatus(for: media) {
        case .denied, .restricted: true
        default: false
        }
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
        pinnedURL = pin.url

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
                // Pinned, the page belongs to the space.
                host?.pageWasPinned(self, url: pin.url)
            } catch {
                // It never reached the space, so the page is not its to give up.
                pinnedURL = nil
                showPinNotice("\(String(localized: "browser.pin_failed")) \(error.zenUserMessage)", duration: 6.0)
            }
        }
    }

    /// Runs one script in the page, or nil when it does not answer within
    /// `limit`. A web content process that died or a page that slept never
    /// calls back, and the caller must not wait on it.
    private func pageScriptAnswer(_ script: String, within limit: Duration) async -> String? {
        guard let webView else { return nil }
        let answer = ScriptAnswer()
        return await withCheckedContinuation { continuation in
            answer.wait(continuation)
            webView.evaluateJavaScript(script) { result, _ in
                let value = result as? String
                Task { @MainActor in answer.finish(value) }
            }
            Task { @MainActor in
                try? await Task.sleep(for: limit)
                answer.finish(nil)
            }
        }
    }

    /// The page's own `document.title`, or nil when the page does not answer
    /// within a second. A web content process that died or a page that slept
    /// never calls back, and the pin waiting on it must still save with the
    /// title it already has.
    private func liveDocumentTitle(for url: URL) async -> String? {
        let payload = await pageScriptAnswer(PageTitle.documentTitleScript, within: .seconds(1))
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

/// Settles one script answer. The page's answer and the waiting limit can both
/// arrive, and a continuation may only be resumed once, so the first one wins.
private final class ScriptAnswer: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<String?, Never>?
    private var finished = false

    /// Takes the lookup's continuation. A lookup already settled resumes it
    /// with no answer.
    func wait(_ continuation: CheckedContinuation<String?, Never>) {
        lock.lock()
        if finished {
            lock.unlock()
            continuation.resume(returning: nil)
            return
        }
        self.continuation = continuation
        lock.unlock()
    }

    /// The page's answer, or nil for the limit. Later answers are dropped.
    func finish(_ answer: String?) {
        lock.lock()
        guard !finished else {
            lock.unlock()
            return
        }
        finished = true
        let waiting = continuation
        continuation = nil
        lock.unlock()
        waiting?.resume(returning: answer)
    }
}

/// Loads one suggest response. Failures throw; the model turns them into an empty list.
protocol SearchSuggestionFetching: Sendable {
    func fetch(_ url: URL) async throws -> Data
}

/// Ephemeral session: suggest requests do not send or store browser cookies.
struct LiveSearchSuggestionClient: SearchSuggestionFetching {
    /// A suggest answer is a short list; anything far bigger is not one.
    static let maxBodySize = 64 * 1024

    func fetch(_ url: URL) async throws -> Data {
        var request = URLRequest(url: url)
        request.timeoutInterval = 4
        request.setValue("application/json, text/javascript, */*", forHTTPHeaderField: "Accept")
        let (bytes, response) = try await SearchSuggestionSession.shared.bytes(for: request)
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
            throw URLError(.badServerResponse)
        }
        // The limit is enforced while the answer arrives, so a huge body is
        // never held in memory: a known length is refused before the first byte.
        guard http.expectedContentLength <= Int64(Self.maxBodySize) else {
            throw URLError(.dataLengthExceedsMaximum)
        }
        var data = Data(capacity: Self.maxBodySize)
        for try await byte in bytes {
            data.append(byte)
            guard data.count <= Self.maxBodySize else {
                throw URLError(.dataLengthExceedsMaximum)
            }
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
