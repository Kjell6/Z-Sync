import SwiftUI
import WebKit

struct MiniBrowserRepresentable: UIViewRepresentable {
    var model: MiniBrowserModel
    /// Native `.bottomBar` is visible (hidden while the address field is focused).
    var bottomBarVisible: Bool

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
        webView.isOpaque = false
        webView.backgroundColor = .clear
        webView.scrollView.backgroundColor = .clear
        webView.bottomBarVisible = bottomBarVisible

        // Paint under the translucent bar. Layout inset is `obscuredContentInsets`,
        // not scroll-view contentInset — contentInset would clip the background.
        webView.scrollView.contentInsetAdjustmentBehavior = .never
        webView.scrollView.contentInset = .zero
        webView.scrollView.automaticallyAdjustsScrollIndicatorInsets = false
        webView.scrollView.scrollIndicatorInsets = .zero
        if #available(iOS 26.0, *) {
            webView.scrollView.bottomEdgeEffect.isHidden = true
        }

        context.coordinator.setupObservers(for: webView)
        model.webView = webView
        return webView
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {
        guard let webView = uiView as? MiniBrowserWebView else { return }
        webView.bottomBarVisible = bottomBarVisible
        webView.updateObscuredInsets()
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

        init(model: MiniBrowserModel) {
            self.model = model
        }

        func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration, for navigationAction: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
            if navigationAction.targetFrame == nil {
                webView.load(navigationAction.request)
            }
            return nil
        }

        func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            guard let url = navigationAction.request.url, !BrowserNavigationPolicy.loadsInWebView(url) else {
                decisionHandler(.allow)
                return
            }
            ExternalBrowser.open(url)
            decisionHandler(.cancel)
        }

        func setupObservers(for webView: WKWebView) {
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
                    self?.model.currentURL = wv.url
                }
            }

            titleObservation = webView.observe(\.title, options: [.new]) { [weak self] wv, _ in
                DispatchQueue.main.async {
                    self?.model.pageTitle = wv.title ?? ""
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
        }

        func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
            DispatchQueue.main.async {
                self.model.isLoading = true
            }
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            DispatchQueue.main.async {
                self.model.isLoading = false
                self.model.currentURL = webView.url
                self.model.pageTitle = webView.title ?? ""
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
            }
        }

        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
            DispatchQueue.main.async {
                self.model.isLoading = false
            }
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
