import SwiftUI
import WebKit

/// One page's WebKit side: builds its web view, feeds its model through KVO and
/// answers the navigation and UI delegates. It lives as long as the web view
/// and has no tie to the view hierarchy, so a page keeps running while another
/// one is on screen.
final class MiniBrowserWebDriver: NSObject, WKNavigationDelegate, WKUIDelegate {
    /// Weak: the model owns this driver and its web view.
    private weak var model: MiniBrowserModel?
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

    /// A new web view for [model], wired up and handed to it. A popup passes
    /// WebKit's own configuration, which ties the window to its opener.
    @MainActor
    @discardableResult
    static func attach(to model: MiniBrowserModel, configuration: WKWebViewConfiguration = WKWebViewConfiguration()) -> WKWebView {
        // A popup's configuration carries the opener's content controller. The
        // lists and scripts are swapped per site, so each page needs its own.
        configuration.userContentController = WKUserContentController()
        configuration.allowsInlineMediaPlayback = true
        // A player's own fullscreen button then fills the screen, as it does
        // in Safari.
        configuration.preferences.isElementFullscreenEnabled = true
        configuration.userContentController.addScriptMessageHandler(
            BrowserGeolocation.Handler(model: model),
            contentWorld: .page,
            name: BrowserGeolocation.handlerName
        )
        BrowserGeolocation.addScript(to: configuration.userContentController)
        // The blocked count's reports. The lists and scripts themselves go on
        // per page, in `decidePolicyFor`.
        configuration.userContentController.add(
            BlockedResourceReport.Handler(model: model),
            contentWorld: BlockedResourceReport.world,
            name: BlockedResourceReport.handlerName
        )

        let webView = MiniBrowserWebView(frame: .zero, configuration: configuration)
        let driver = MiniBrowserWebDriver(model: model)
        webView.navigationDelegate = driver
        webView.uiDelegate = driver
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

        driver.setupObservers(for: webView)
        model.adopt(webView, driver: driver)
        return webView
    }

    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration, for navigationAction: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        guard navigationAction.targetFrame == nil else { return nil }
        let url = navigationAction.request.url
        if let url, !BrowserNavigationPolicy.loadsInWebView(url) {
            // A popup aimed at an app: hand it over instead of dropping it.
            handOff(url)
            return nil
        }
        return MainActor.assumeIsolated {
            if let model, let host = model.host {
                // A real window: the page keeps its `window.opener`, and a
                // blank popup can still be given its address afterwards.
                return host.openPopup(from: model, url: url, configuration: configuration)
            }
            if let sameTab = BrowserNavigationPolicy.sameTabURL(url) {
                webView.load(URLRequest(url: sameTab))
            }
            return nil
        }
    }

    /// `window.close()`: the page that opened the window asks for it back.
    func webViewDidClose(_ webView: WKWebView) {
        MainActor.assumeIsolated {
            guard let model else { return }
            model.host?.pageRequestedClose(model)
        }
    }

    /// Long press on a link. The system's own actions stay; a web link gains
    /// the one action only this browser can offer.
    func webView(
        _ webView: WKWebView,
        contextMenuConfigurationForElement elementInfo: WKContextMenuElementInfo,
        completionHandler: @escaping (UIContextMenuConfiguration?) -> Void
    ) {
        guard let url = BrowserNavigationPolicy.sameTabURL(elementInfo.linkURL), model?.host != nil else {
            completionHandler(nil)
            return
        }
        completionHandler(UIContextMenuConfiguration(identifier: nil, previewProvider: nil) { [weak self] suggested in
            let open = UIAction(
                title: String(localized: "browser.open_in_new_page"),
                image: UIImage(systemName: "plus.square.on.square")
            ) { _ in
                MainActor.assumeIsolated {
                    guard let model = self?.model else { return }
                    model.host?.openInNewPage(url, from: model)
                }
            }
            return UIMenu(children: [open] + suggested)
        })
    }

    // MARK: - What a page asks of the reader

    /// The browser's question comes first. Granting here is what lets WebKit
    /// raise the system's camera or microphone dialog.
    func webView(
        _ webView: WKWebView,
        requestMediaCapturePermissionFor origin: WKSecurityOrigin,
        initiatedByFrame frame: WKFrameInfo,
        type: WKMediaCaptureType,
        decisionHandler: @escaping (WKPermissionDecision) -> Void
    ) {
        let kinds: [SitePermission] = switch type {
        case .camera: [.camera]
        case .microphone: [.microphone]
        case .cameraAndMicrophone: [.camera, .microphone]
        @unknown default: []
        }
        MainActor.assumeIsolated {
            guard let model, !kinds.isEmpty else {
                decisionHandler(.deny)
                return
            }
            model.requestPermission(kinds, host: origin.host) { granted in
                decisionHandler(granted ? .grant : .deny)
            }
        }
    }

    func webView(
        _ webView: WKWebView,
        runJavaScriptAlertPanelWithMessage message: String,
        initiatedByFrame frame: WKFrameInfo,
        completionHandler: @escaping () -> Void
    ) {
        MainActor.assumeIsolated {
            guard let model else {
                completionHandler()
                return
            }
            let answer = DialogAnswer<Void>(fallback: (), completionHandler)
            model.present(.alert(host: frame.securityOrigin.host, message: message, answer))
        }
    }

    func webView(
        _ webView: WKWebView,
        runJavaScriptConfirmPanelWithMessage message: String,
        initiatedByFrame frame: WKFrameInfo,
        completionHandler: @escaping (Bool) -> Void
    ) {
        MainActor.assumeIsolated {
            guard let model else {
                completionHandler(false)
                return
            }
            let answer = DialogAnswer<Bool>(fallback: false, completionHandler)
            model.present(.confirm(host: frame.securityOrigin.host, message: message, answer))
        }
    }

    func webView(
        _ webView: WKWebView,
        runJavaScriptTextInputPanelWithPrompt prompt: String,
        defaultText: String?,
        initiatedByFrame frame: WKFrameInfo,
        completionHandler: @escaping (String?) -> Void
    ) {
        MainActor.assumeIsolated {
            guard let model else {
                completionHandler(nil)
                return
            }
            let answer = DialogAnswer<String?>(fallback: nil, completionHandler)
            model.present(.prompt(
                host: frame.securityOrigin.host,
                message: prompt,
                defaultText: defaultText ?? "",
                answer
            ))
        }
    }

    /// HTTP login, as on a router's page. Other challenges, certificates
    /// among them, keep WebKit's own handling.
    func webView(
        _ webView: WKWebView,
        didReceive challenge: URLAuthenticationChallenge,
        completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
    ) {
        let method = challenge.protectionSpace.authenticationMethod
        let asksForLogin = method == NSURLAuthenticationMethodHTTPBasic
            || method == NSURLAuthenticationMethodHTTPDigest
            || method == NSURLAuthenticationMethodNTLM
        MainActor.assumeIsolated {
            guard asksForLogin, let model else {
                completionHandler(.performDefaultHandling, nil)
                return
            }
            let answer = DialogAnswer<BrowserLogin?>(fallback: nil) { login in
                guard let login else {
                    // The server's own 401 page stays, as in Safari.
                    completionHandler(.rejectProtectionSpace, nil)
                    return
                }
                let credential = URLCredential(user: login.user, password: login.password, persistence: .forSession)
                completionHandler(.useCredential, credential)
            }
            model.present(.login(
                host: challenge.protectionSpace.host,
                failedBefore: challenge.previousFailureCount > 0,
                answer
            ))
        }
    }

    // MARK: - Downloads

    func webView(
        _ webView: WKWebView,
        decidePolicyFor navigationResponse: WKNavigationResponse,
        decisionHandler: @escaping (WKNavigationResponsePolicy) -> Void
    ) {
        let disposition = (navigationResponse.response as? HTTPURLResponse)?
            .value(forHTTPHeaderField: "Content-Disposition")
        let downloads = BrowserDownloadPolicy.shouldDownload(
            isMainFrame: navigationResponse.isForMainFrame,
            canShowMIMEType: navigationResponse.canShowMIMEType,
            contentDisposition: disposition
        )
        decisionHandler(downloads ? .download : .allow)
    }

    func webView(_ webView: WKWebView, navigationAction: WKNavigationAction, didBecome download: WKDownload) {
        startDownload(download, in: webView)
    }

    func webView(_ webView: WKWebView, navigationResponse: WKNavigationResponse, didBecome download: WKDownload) {
        startDownload(download, in: webView)
    }

    private func startDownload(_ download: WKDownload, in webView: WKWebView) {
        MainActor.assumeIsolated {
            BrowserDownloads.shared.adopt(download, from: model)
            // A link that opened a window only for its file leaves nothing to show.
            if webView.url == nil, !webView.canGoBack, let model {
                model.host?.pageRequestedClose(model)
            }
        }
    }

    /// Offers a link to the app that owns its scheme. When nothing takes it
    /// the page stays where it is and the toast says why - silently doing
    /// nothing reads like a broken browser.
    private func handOff(_ url: URL) {
        ExternalBrowser.open(url) { [weak self] opened in
            guard !opened else { return }
            DispatchQueue.main.async {
                self?.model?.showNotice(String(localized: "browser.link_unavailable"))
            }
        }
    }

    func webView(
        _ webView: WKWebView,
        decidePolicyFor navigationAction: WKNavigationAction,
        preferences: WKWebpagePreferences,
        decisionHandler: @escaping (WKNavigationActionPolicy, WKWebpagePreferences) -> Void
    ) {
        preferences.preferredContentMode = (model?.requestsDesktopSite ?? false) ? .desktop : .mobile
        // `<a download>`, `blob:` files included.
        if navigationAction.shouldPerformDownload {
            decisionHandler(.download, preferences)
            return
        }
        if navigationAction.targetFrame?.isMainFrame ?? true,
           let url = navigationAction.request.url,
           BrowserNavigationPolicy.loadsInWebView(url) {
            // Before the page starts loading, so its first requests already
            // see the site's lists and the cookie banner script.
            MainActor.assumeIsolated { model?.prepareBlocking(for: url) }
        }
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
                self?.model?.estimatedProgress = wv.estimatedProgress
            }
        }

        loadingObservation = webView.observe(\.isLoading, options: [.new]) { [weak self] wv, _ in
            DispatchQueue.main.async {
                self?.model?.isLoading = wv.isLoading
            }
        }

        urlObservation = webView.observe(\.url, options: [.new]) { [weak self] wv, _ in
            DispatchQueue.main.async {
                self?.model?.updateCurrentURL(wv.url)
            }
        }

        titleObservation = webView.observe(\.title, options: [.new]) { [weak self] wv, _ in
            DispatchQueue.main.async {
                self?.model?.adoptPageTitle(wv.title ?? "")
            }
        }

        backObservation = webView.observe(\.canGoBack, options: [.new]) { [weak self] wv, _ in
            DispatchQueue.main.async {
                self?.model?.canGoBack = wv.canGoBack
            }
        }

        forwardObservation = webView.observe(\.canGoForward, options: [.new]) { [weak self] wv, _ in
            DispatchQueue.main.async {
                self?.model?.canGoForward = wv.canGoForward
            }
        }

        if #available(iOS 15.0, *) {
            themeColorObservation = webView.observe(\.themeColor, options: [.new]) { [weak self] wv, _ in
                DispatchQueue.main.async {
                    if let native = wv.themeColor {
                        self?.model?.themeColor = Color(uiColor: native)
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
            model?.setScrollHidesBar(false)
            return
        }

        if delta > 0.5 {
            scrollDownRun += delta
            if scrollDownRun > 24, scrollY > 80 {
                model?.setScrollHidesBar(true)
            }
        } else if delta < -2 {
            scrollDownRun = 0
            model?.setScrollHidesBar(false)
        }
    }

    func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
        DispatchQueue.main.async {
            self.model?.isLoading = true
        }
        // A new document starts at the top: drop the previous page's
        // scroll history so the bar's first decision is not carried over.
        lastScrollY = 0
        scrollDownRun = 0
        // A page that cannot scroll - an error page, a dead link - must not
        // leave the chrome tucked away with no way to bring it back.
        DispatchQueue.main.async {
            self.model?.setScrollHidesBar(false)
        }
    }

    func webView(_ webView: WKWebView, didCommit navigation: WKNavigation!) {
        DispatchQueue.main.async {
            self.model?.pageDidCommit()
            // A new document starts at 100%: this lands before the page is
            // painted, so the zoom never jumps in front of the reader.
            self.model?.applyPageZoom(force: true)
        }
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        // The reloaded document is laid out, but WebKit may still settle the
        // offset against the chrome's clearance; put the reader back after it.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.12) { [weak self] in
            self?.model?.restoreScrollAfterReload()
        }
        DispatchQueue.main.async {
            self.model?.isLoading = false
            self.model?.updateCurrentURL(webView.url)
            if let model = self.model { model.host?.pageDidFinishLoading(model) }
            self.model?.adoptPageTitle(webView.title ?? "")
            self.model?.recordVisit()
            // A page whose own scripts rewrote the root style gets the
            // zoom back once it has settled.
            self.model?.applyPageZoom(force: true)
            if #available(iOS 15.0, *) {
                if let native = webView.themeColor {
                    self.model?.themeColor = Color(uiColor: native)
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
                self.model?.updateThemeColor(from: colorStr, nativeColor: webView.themeColor)
            }
        }
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        DispatchQueue.main.async {
            self.model?.isLoading = false
            // The page may now be an error page that cannot scroll.
            self.model?.setScrollHidesBar(false)
        }
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        DispatchQueue.main.async {
            self.model?.isLoading = false
            self.model?.setScrollHidesBar(false)
            self.model?.loadDidFail(error)
        }
    }

    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        DispatchQueue.main.async {
            self.model?.isLoading = false
            guard let model = self.model else { return }
            // A page out of sight just reloads when it comes back.
            if model.host?.pageProcessDidTerminate(model) != true {
                model.webContentProcessDidTerminate()
            }
        }
    }
}
