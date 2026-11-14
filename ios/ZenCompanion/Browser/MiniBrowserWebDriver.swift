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
    /// The document the chrome last started over for, so one page resets once.
    private var currentDocumentURL: URL?
    /// Counts the documents the page colour was asked of, so an answer about an
    /// older page cannot paint the one on screen.
    private var pageGeneration = 0
    /// The pull-to-refresh's own limit: a load that never reports back cannot
    /// leave the spinner going.
    private var refreshDeadline: Task<Void, Never>?

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
        // A page restored from WebKit's back/forward cache answers none of the
        // load callbacks, so it reports its own `pageshow` instead.
        let driver = MiniBrowserWebDriver(model: model)
        configuration.userContentController.add(
            BrowserPageShow.Handler(driver: driver),
            name: BrowserPageShow.handlerName
        )
        BrowserPageShow.addScript(to: configuration.userContentController)

        let webView = MiniBrowserWebView(frame: .zero, configuration: configuration)
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

        // Pulling down at the top reloads, with the system's own control. Its
        // tint follows the page's own colour, since the spinner sits on the
        // page and not on the app's chrome (see `MiniBrowserModel.setThemeColor`).
        let refresh = UIRefreshControl()
        refresh.tintColor = MiniBrowserRefresh.spinnerTint(pageIsDark: nil)
        refresh.accessibilityLabel = String(localized: "browser.reload")
        refresh.addTarget(driver, action: #selector(MiniBrowserWebDriver.refreshPulled(_:)), for: .valueChanged)
        webView.refreshControl = refresh

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
            // A link that opened a window only for its file leaves nothing to
            // show. The window closes once the file's question is answered, so
            // the reader still sees that question on the page that asked it.
            let closesPage = webView.url == nil && !webView.canGoBack
            BrowserDownloads.shared.adopt(
                download,
                from: model,
                closingPageWhenAnswered: closesPage
            )
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
            miniWebView.refreshWasEnded = { [weak self] in
                self?.rebaseAfterRefreshEnds()
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
                // A step back or forward, or a fresh load, is a new document
                // for the chrome even when WebKit answers it from its cache.
                self?.startOver(for: wv.url)
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
                        self?.model?.setThemeColor(Color(uiColor: native))
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
            // Read here: the drag has ended by the time the hop below runs.
            let byReader = MiniBrowserBarScroll.isReaderScroll(
                isDragging: scrollView.isDragging,
                isDecelerating: scrollView.isDecelerating
            )
            DispatchQueue.main.async {
                self?.updateBarVisibility(
                    scrollY: y,
                    viewport: viewport,
                    contentHeight: content,
                    isUserDriven: byReader
                )
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

    // MARK: - Pull to refresh

    /// The reader pulled the page down at its top.
    @objc private func refreshPulled(_ sender: UIRefreshControl) {
        MainActor.assumeIsolated {
            guard let model else {
                sender.endRefreshing()
                return
            }
            // A pull starts at the top: the page is reloaded from there.
            model.reload(fromTop: true)
            startRefreshDeadline()
        }
    }

    /// The spinner ends with the load, and at the latest after ten seconds.
    private func startRefreshDeadline() {
        refreshDeadline?.cancel()
        refreshDeadline = Task { @MainActor [weak self] in
            try? await Task.sleep(for: .seconds(10))
            guard !Task.isCancelled else { return }
            self?.finishRefresh()
        }
    }

    /// Ends the pull's spinner. The control's own collapse moves the scroll
    /// offset without a finger, so the bar's origin follows where it settles
    /// instead of reading the change as a scroll.
    private func finishRefresh() {
        refreshDeadline?.cancel()
        refreshDeadline = nil
        guard let control = (webView as? MiniBrowserWebView)?.refreshControl,
              control.isRefreshing else { return }
        control.endRefreshing()
        rebaseAfterRefreshEnds()
    }

    /// The control is still animating its inset away; its offset move is not
    /// the reader scrolling.
    private func rebaseAfterRefreshEnds() {
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) { [weak self] in
            self?.rebaseScrollOrigin()
        }
    }

    /// Tucks the bottom bar away once the page has scrolled down and
    /// brings it back as soon as the page scrolls up again. A short page,
    /// or the very top of a long one, always keeps the bar. The rule itself
    /// lives in `MiniBrowserBarScroll`; a move without a finger only becomes
    /// the new baseline.
    private func updateBarVisibility(scrollY: CGFloat, viewport: CGFloat, contentHeight: CGFloat, isUserDriven: Bool) {
        let delta = scrollY - lastScrollY
        let (decision, downRun) = MiniBrowserBarScroll.decision(
            delta: delta,
            scrollY: scrollY,
            viewportHeight: viewport,
            contentHeight: contentHeight,
            downRun: scrollDownRun,
            isUserDriven: isUserDriven
        )
        lastScrollY = scrollY
        scrollDownRun = downRun
        switch decision {
        case .keep: break
        case .show: model?.setScrollHidesBar(false)
        case .hide: model?.setScrollHidesBar(true)
        }
    }

    // MARK: - A page coming on screen

    /// The chrome starts over for the page [url] names: the bar is in place,
    /// the page's own offset is the scroll baseline, and its colour is read
    /// again. A back or a forward may put a page on screen with none of the
    /// load callbacks that used to carry this, so every signal lands here.
    private func startOver(for url: URL?) {
        // One document resets once, however many signals announce it.
        guard !PageTitle.sameDocument(url, currentDocumentURL) else { return }
        currentDocumentURL = url
        pageGeneration += 1
        lastScrollY = webView?.scrollView.contentOffset.y ?? 0
        scrollDownRun = 0
        // The previous page's colour is not this one's: until this page
        // answers, the top area shows the default.
        model?.updateThemeColor(from: nil, nativeColor: nil)
        model?.setScrollHidesBar(false)
        readPageColor()
    }

    /// A page reported its own `pageshow`: a restore from the back/forward
    /// cache shows a page with its address already known, so the reset is
    /// taken even though nothing about the document changed.
    func pageShownAgain() {
        currentDocumentURL = nil
        startOver(for: webView?.url)
    }

    /// The page's colour, from its `theme-color` tag, else its own background.
    /// Every answer is checked against the document it was asked of: an older
    /// page answering late must not paint the page the reader is on now.
    private func readPageColor() {
        guard let webView else { return }
        let generation = pageGeneration
        let url = webView.url
        webView.evaluateJavaScript(Self.pageColorScript) { [weak self] result, _ in
            guard let self else { return }
            DispatchQueue.main.async {
                guard generation == self.pageGeneration,
                      PageTitle.sameDocument(url, self.webView?.url) else { return }
                self.model?.updateThemeColor(from: result as? String, nativeColor: self.webView?.themeColor)
            }
        }
    }

    /// `<meta name="theme-color">`, else the body's background, else the
    /// document's own.
    private static let pageColorScript = """
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
        // A committed document is on screen: the chrome starts over for it,
        // even when it is the previous page coming back.
        startOver(for: webView.url)
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
        // The document the reader sees is settled: read its colour once more,
        // since a page can set its theme after its first paint.
        startOver(for: webView.url)
        readPageColor()
        DispatchQueue.main.async {
            self.model?.isLoading = false
            self.finishRefresh()
            self.model?.updateCurrentURL(webView.url)
            if let model = self.model { model.host?.pageDidFinishLoading(model) }
            self.model?.adoptPageTitle(webView.title ?? "")
            self.model?.recordVisit()
            // A page whose own scripts rewrote the root style gets the
            // zoom back once it has settled.
            self.model?.applyPageZoom(force: true)
        }
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        DispatchQueue.main.async {
            self.model?.isLoading = false
            self.finishRefresh()
            // The page may now be an error page that cannot scroll.
            self.model?.setScrollHidesBar(false)
        }
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        DispatchQueue.main.async {
            self.model?.isLoading = false
            self.finishRefresh()
            self.model?.setScrollHidesBar(false)
            self.model?.loadDidFail(error)
        }
    }

    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        DispatchQueue.main.async {
            self.model?.isLoading = false
            self.finishRefresh()
            guard let model = self.model else { return }
            // A page out of sight just reloads when it comes back.
            if model.host?.pageProcessDidTerminate(model) != true {
                model.webContentProcessDidTerminate()
            }
        }
    }
}

/// The page script behind WebKit's back/forward cache, and its handler.
///
/// A page taken from the cache is put back with its scroll position and its
/// old theme colour, and WebKit runs none of the callbacks a load would. The
/// page says so itself instead, so the chrome can start over for it.
enum BrowserPageShow {
    static let handlerName = "zenPageShow"

    static func addScript(to controller: WKUserContentController) {
        controller.addUserScript(WKUserScript(
            source: script,
            injectionTime: .atDocumentStart,
            forMainFrameOnly: true
        ))
    }

    static let script = """
    (function () {
      if (window.__zenPageShow) return;
      window.__zenPageShow = true;
      window.addEventListener('pageshow', function (event) {
        try {
          window.webkit.messageHandlers.\(handlerName).postMessage({ persisted: event.persisted });
        } catch (e) {}
      });
    })();
    """

    /// Weak to the driver, which owns the web view holding this handler.
    final class Handler: NSObject, WKScriptMessageHandler {
        private weak var driver: MiniBrowserWebDriver?

        init(driver: MiniBrowserWebDriver) {
            self.driver = driver
        }

        func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
            guard message.frameInfo.isMainFrame else { return }
            MainActor.assumeIsolated {
                driver?.pageShownAgain()
            }
        }
    }
}
