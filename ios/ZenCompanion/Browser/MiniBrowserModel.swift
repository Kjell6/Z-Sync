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

    // MARK: Pin banner
    var showPinBanner = false
    var pinnedDestination = PinDestination(spaceId: "")
    var hasPendingPinSave = false
    /// How the pending save will be written (pinned vs normal). Captured when
    /// the banner is triggered and kept honest against the capability.
    private(set) var pinSaveKind: SaveKind = .pinned
    /// Short-lived message for a refusal or a dead end (no spaces to pin to,
    /// no article to read). Rendered in the same bottom toast as the banner.
    var pinNotice: String?
    /// SF Symbol shown with `pinNotice`.
    private(set) var pinNoticeSymbol = "pin.slash.fill"

    @ObservationIgnored private var pinBannerDismissTask: Task<Void, Never>?
    @ObservationIgnored private var pinNoticeDismissTask: Task<Void, Never>?
    @ObservationIgnored private(set) var pinSaveTask: Task<Void, Never>?

    private let initialURL: URL?
    private let pinWriter: PinWriting
    private let urlOpener: URLOpening
    private let searchEngine: SearchEngineProviding
    private let haptics: HapticsPlaying
    private let preferences: PreferencesStoring

    init(
        initialURL: URL?,
        pinWriter: PinWriting = AppServices.pinWriter,
        urlOpener: URLOpening = AppServices.urlOpener,
        searchEngine: SearchEngineProviding = AppServices.searchEngine,
        haptics: HapticsPlaying = AppServices.haptics,
        preferences: PreferencesStoring = AppServices.preferences
    ) {
        self.initialURL = initialURL
        self.pinWriter = pinWriter
        self.urlOpener = urlOpener
        self.searchEngine = searchEngine
        self.haptics = haptics
        self.preferences = preferences
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
        guard let url = BrowserInput.resolve(addressText, engine: searchEngine.current) else { return }
        load(url: url)
    }

    func clearAddressText() {
        addressText = ""
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
