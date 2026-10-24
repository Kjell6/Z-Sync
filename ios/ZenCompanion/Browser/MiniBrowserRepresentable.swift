import SwiftUI
import WebKit

struct MiniBrowserRepresentable: UIViewRepresentable {
    var model: MiniBrowserModel
    /// Height of the floating chrome over the bottom of the page. The page's
    /// layout viewport is pushed up by this much, so its bottom-most content
    /// and fixed banners stay clear of the glass. Zero while the bar is away.
    var bottomClearance: CGFloat = 0

    func makeCoordinator() -> Coordinator {
        Coordinator(model: model)
    }

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.allowsInlineMediaPlayback = true

        let webView = MiniBrowserWebView(frame: .zero, configuration: config)
        webView.navigationDelegate = context.coordinator
        webView.uiDelegate = context.coordinator
        webView.allowsBackForwardNavigationGestures = true
        webView.isFindInteractionEnabled = true
        webView.isOpaque = false
        webView.backgroundColor = .clear
        webView.scrollView.backgroundColor = .clear

        // No auto-insets and no scroll-view content inset: the page fills the
        // web view exactly, and the chrome is reserved through
        // `obscuredContentInsets` instead (see MiniBrowserWebView).
        webView.scrollView.contentInsetAdjustmentBehavior = .never
        webView.scrollView.contentInset = .zero
        webView.scrollView.automaticallyAdjustsScrollIndicatorInsets = false
        webView.scrollView.scrollIndicatorInsets = .zero

        context.coordinator.setupObservers(for: webView)
        context.coordinator.applyBottomClearance(bottomClearance, to: webView)
        model.webView = webView
        model.applyWebpagePreferences()
        return webView
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {
        guard let webView = uiView as? MiniBrowserWebView else { return }
        if !webView.isUserInteractionEnabled {
            webView.isUserInteractionEnabled = true
        }
        context.coordinator.applyBottomClearance(bottomClearance, to: webView)
        model.applyWebpagePreferences()
    }

    final class Coordinator: NSObject, WKNavigationDelegate, WKUIDelegate {
        private let model: MiniBrowserModel
        private var progressObservation: NSKeyValueObservation?
        private var loadingObservation: NSKeyValueObservation?
        private var urlObservation: NSKeyValueObservation?
        private var titleObservation: NSKeyValueObservation?
        private var backObservation: NSKeyValueObservation?
        private var forwardObservation: NSKeyValueObservation?
        private var themeColorObservation: NSKeyValueObservation?
        private var scrollObservation: NSKeyValueObservation?
        /// For re-basing the scroll origin after the chrome's clearance moves.
        private weak var webView: WKWebView?
        /// Last vertical offset, to read the scroll direction.
        private var lastScrollY: CGFloat = 0
        /// Downward travel since the last direction change. Hides the bar only
        /// after a committed scroll, not on the first jitter.
        private var scrollDownRun: CGFloat = 0

        init(model: MiniBrowserModel) {
            self.model = model
        }

        func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration, for navigationAction: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
            // One web view. A new window with a real http(s) address replaces this page.
            if navigationAction.targetFrame == nil, let url = navigationAction.request.url {
                if let sameTab = BrowserNavigationPolicy.sameTabURL(url) {
                    webView.load(URLRequest(url: sameTab))
                } else if !BrowserNavigationPolicy.loadsInWebView(url) {
                    // A popup aimed at an app: hand it over instead of dropping it.
                    handOff(url)
                }
            }
            return nil
        }

        /// Offers a link to the app that owns its scheme. When nothing takes it
        /// the page stays where it is and the toast says why - silently doing
        /// nothing reads like a broken browser.
        private func handOff(_ url: URL) {
            ExternalBrowser.open(url) { [weak self] opened in
                guard !opened else { return }
                DispatchQueue.main.async {
                    self?.model.showNotice(String(localized: "browser.link_unavailable"))
                }
            }
        }

        func webView(
            _ webView: WKWebView,
            decidePolicyFor navigationAction: WKNavigationAction,
            preferences: WKWebpagePreferences,
            decisionHandler: @escaping (WKNavigationActionPolicy, WKWebpagePreferences) -> Void
        ) {
            preferences.preferredContentMode = model.requestsDesktopSite ? .desktop : .mobile
            guard let url = navigationAction.request.url, !BrowserNavigationPolicy.loadsInWebView(url) else {
                decisionHandler(.allow, preferences)
                return
            }
            // Only a tap in the main frame may leave the app; a page that
            // opens another app on its own is ignored.
            if BrowserNavigationPolicy.opensExternally(
                userTapped: navigationAction.navigationType == .linkActivated,
                isMainFrame: navigationAction.targetFrame?.isMainFrame ?? true
            ) {
                handOff(url)
            }
            decisionHandler(.cancel, preferences)
        }

        func setupObservers(for webView: WKWebView) {
            self.webView = webView
            if let miniWebView = webView as? MiniBrowserWebView {
                miniWebView.clearanceDidSettle = { [weak self] in
                    self?.rebaseScrollOrigin()
                }
            }

            progressObservation = webView.observe(\.estimatedProgress, options: [.new]) { [weak self] wv, _ in
                DispatchQueue.main.async {
                    self?.model.estimatedProgress = wv.estimatedProgress
                }
            }

            loadingObservation = webView.observe(\.isLoading, options: [.new]) { [weak self] wv, _ in
                DispatchQueue.main.async {
                    self?.model.isLoading = wv.isLoading
                }
            }

            urlObservation = webView.observe(\.url, options: [.new]) { [weak self] wv, _ in
                DispatchQueue.main.async {
                    self?.model.updateCurrentURL(wv.url)
                }
            }

            titleObservation = webView.observe(\.title, options: [.new]) { [weak self] wv, _ in
                DispatchQueue.main.async {
                    self?.model.adoptPageTitle(wv.title ?? "")
                }
            }

            backObservation = webView.observe(\.canGoBack, options: [.new]) { [weak self] wv, _ in
                DispatchQueue.main.async {
                    self?.model.canGoBack = wv.canGoBack
                }
            }

            forwardObservation = webView.observe(\.canGoForward, options: [.new]) { [weak self] wv, _ in
                DispatchQueue.main.async {
                    self?.model.canGoForward = wv.canGoForward
                }
            }

            if #available(iOS 15.0, *) {
                themeColorObservation = webView.observe(\.themeColor, options: [.new]) { [weak self] wv, _ in
                    DispatchQueue.main.async {
                        if let native = wv.themeColor {
                            self?.model.themeColor = Color(uiColor: native)
                        }
                    }
                }
            }

            // KVO, not `scrollView.delegate`: WebKit owns that delegate and
            // replacing it breaks its own scrolling.
            scrollObservation = webView.scrollView.observe(\.contentOffset, options: [.new]) { [weak self, weak webView] scrollView, _ in
                // Reserving or releasing the chrome's space moves the offset
                // without a finger. Those moves must not drive the bar.
                guard (webView as? MiniBrowserWebView)?.scrollClearanceIsChanging != true else { return }
                let y = scrollView.contentOffset.y
                let viewport = scrollView.bounds.height
                let content = scrollView.contentSize.height
                DispatchQueue.main.async {
                    self?.updateBarVisibility(scrollY: y, viewport: viewport, contentHeight: content)
                }
            }
        }

        /// Pushes the chrome's height into the page's layout viewport.
        func applyBottomClearance(_ clearance: CGFloat, to webView: WKWebView) {
            (webView as? MiniBrowserWebView)?.setBottomClearance(clearance)
        }

        /// The clearance change may have clamped the offset. Take whatever the
        /// page sits at now as the baseline, so the clamp is not read as the
        /// user scrolling up (which would bring the bar straight back).
        private func rebaseScrollOrigin() {
            guard let scrollView = webView?.scrollView else { return }
            lastScrollY = scrollView.contentOffset.y
            scrollDownRun = 0
        }

        /// Tucks the bottom bar away once the page has scrolled down and
        /// brings it back as soon as the page scrolls up again. A short page,
        /// or the very top of a long one, always keeps the bar.
        private func updateBarVisibility(scrollY: CGFloat, viewport: CGFloat, contentHeight: CGFloat) {
            let maxY = contentHeight - viewport
            let delta = scrollY - lastScrollY
            lastScrollY = scrollY

            guard maxY > 60, scrollY > 40 else {
                scrollDownRun = 0
                model.setScrollHidesBar(false)
                return
            }

            if delta > 0.5 {
                scrollDownRun += delta
                if scrollDownRun > 24, scrollY > 80 {
                    model.setScrollHidesBar(true)
                }
            } else if delta < -2 {
                scrollDownRun = 0
                model.setScrollHidesBar(false)
            }
        }

        func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
            DispatchQueue.main.async {
                self.model.isLoading = true
            }
            // A new document starts at the top: drop the previous page's
            // scroll history so the bar's first decision is not carried over.
            lastScrollY = 0
            scrollDownRun = 0
            // A page that cannot scroll - an error page, a dead link - must not
            // leave the chrome tucked away with no way to bring it back.
            DispatchQueue.main.async {
                self.model.setScrollHidesBar(false)
            }
        }

        func webView(_ webView: WKWebView, didCommit navigation: WKNavigation!) {
            DispatchQueue.main.async {
                self.model.pageDidCommit()
            }
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            // The reloaded document is laid out, but WebKit may still settle the
            // offset against the chrome's clearance; put the reader back after it.
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.12) { [weak self] in
                self?.model.restoreScrollAfterReload()
            }
            DispatchQueue.main.async {
                self.model.isLoading = false
                self.model.updateCurrentURL(webView.url)
                self.model.adoptPageTitle(webView.title ?? "")
                if #available(iOS 15.0, *) {
                    if let native = webView.themeColor {
                        self.model.themeColor = Color(uiColor: native)
                    }
                }
            }

            // Extract background / theme-color from DOM
            let js = """
            (function() {
                var meta = document.querySelector('meta[name="theme-color"]');
                if (meta && meta.content) return meta.content;
                var bg = window.getComputedStyle(document.body).backgroundColor;
                if (bg && bg !== 'rgba(0, 0, 0, 0)' && bg !== 'transparent') return bg;
                var htmlBg = window.getComputedStyle(document.documentElement).backgroundColor;
                if (htmlBg && htmlBg !== 'rgba(0, 0, 0, 0)' && htmlBg !== 'transparent') return htmlBg;
                return null;
            })()
            """
            webView.evaluateJavaScript(js) { [weak self] result, _ in
                guard let self else { return }
                let colorStr = result as? String
                DispatchQueue.main.async {
                    self.model.updateThemeColor(from: colorStr, nativeColor: webView.themeColor)
                }
            }
        }

        func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
            DispatchQueue.main.async {
                self.model.isLoading = false
                // The page may now be an error page that cannot scroll.
                self.model.setScrollHidesBar(false)
            }
        }

        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
            DispatchQueue.main.async {
                self.model.isLoading = false
                self.model.setScrollHidesBar(false)
                self.model.loadDidFail(error)
            }
        }

        func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
            DispatchQueue.main.async {
                self.model.isLoading = false
                self.model.webContentProcessDidTerminate()
            }
        }
    }
}

/// Tap catcher above the page while the menu is open. A plain UIKit view,
/// removed with the menu, so it cannot leave a SwiftUI gesture on the web view.
struct PageDismissScrim: UIViewRepresentable {
    var onTap: () -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(onTap: onTap)
    }

    func makeUIView(context: Context) -> UIView {
        let view = UIView()
        view.backgroundColor = .clear
        view.isAccessibilityElement = true
        view.accessibilityTraits = .button
        let tap = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.tapped))
        view.addGestureRecognizer(tap)
        return view
    }

    func updateUIView(_ uiView: UIView, context: Context) {
        context.coordinator.onTap = onTap
    }

    final class Coordinator: NSObject {
        var onTap: () -> Void

        init(onTap: @escaping () -> Void) {
            self.onTap = onTap
        }

        @objc func tapped() {
            onTap()
        }
    }
}

// MARK: - Color Parsing Helpers

extension Color {
    init?(hex: String) {
        var hexSanitized = hex.trimmingCharacters(in: .whitespacesAndNewlines)
        hexSanitized = hexSanitized.replacingOccurrences(of: "#", with: "")

        var rgb: UInt64 = 0
        guard Scanner(string: hexSanitized).scanHexInt64(&rgb) else { return nil }

        let length = hexSanitized.count
        guard length == 6 || length == 8 || length == 3 else { return nil }

        if length == 6 {
            let r = Double((rgb & 0xFF0000) >> 16) / 255.0
            let g = Double((rgb & 0x00FF00) >> 8) / 255.0
            let b = Double(rgb & 0x0000FF) / 255.0
            self.init(red: r, green: g, blue: b)
        } else if length == 8 {
            let r = Double((rgb & 0xFF000000) >> 24) / 255.0
            let g = Double((rgb & 0x00FF0000) >> 16) / 255.0
            let b = Double((rgb & 0x0000FF00) >> 8) / 255.0
            let a = Double(rgb & 0x000000FF) / 255.0
            self.init(red: r, green: g, blue: b, opacity: a)
        } else {
            let r = Double((rgb & 0xF00) >> 8) / 15.0
            let g = Double((rgb & 0x0F0) >> 4) / 15.0
            let b = Double((rgb & 0x00F) >> 4) / 15.0
            self.init(red: r, green: g, blue: b)
        }
    }

    init?(cssRGB: String) {
        let pattern = "rgba?\\(\\s*([0-9.]+)\\s*,\\s*([0-9.]+)\\s*,\\s*([0-9.]+)(?:\\s*,\\s*([0-9.]+))?\\s*\\)"
        guard let regex = try? NSRegularExpression(pattern: pattern, options: .caseInsensitive),
              let match = regex.firstMatch(in: cssRGB, options: [], range: NSRange(location: 0, length: cssRGB.utf16.count)) else {
            return nil
        }
        guard let rRange = Range(match.range(at: 1), in: cssRGB),
              let gRange = Range(match.range(at: 2), in: cssRGB),
              let bRange = Range(match.range(at: 3), in: cssRGB),
              let r = Double(cssRGB[rRange]),
              let g = Double(cssRGB[gRange]),
              let b = Double(cssRGB[bRange]) else {
            return nil
        }
        var a = 1.0
        if match.numberOfRanges > 4, let aRange = Range(match.range(at: 4), in: cssRGB), let parsedA = Double(cssRGB[aRange]) {
            a = parsedA
        }
        self.init(red: r / 255.0, green: g / 255.0, blue: b / 255.0, opacity: a)
    }
}
