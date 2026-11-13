import WebKit
import UIKit

/// WKWebView for the mini-browser: the web content, nothing else.
///
/// The web view stays full-bleed: the glass bar floats over it and media keeps
/// running underneath. What must not run underneath is page *layout* — cookie
/// banners, sticky CTAs, consent bars with "Allow all" pinned to the bottom.
/// `obscuredContentInsets` (iOS 26) shrinks the layout viewport from the
/// bottom, so fixed and sticky page elements lay out above the bar while the
/// page's background still paints behind it. That is the same mechanism Safari
/// uses for its floating toolbar; plain `contentInset` would only add scroll
/// range and leave fixed banners under the glass.
///
/// What is left is WebKit's own chrome: the bottom scroll-edge pocket on
/// iOS 26, and the keyboard accessory, which is replaced by our own bar
/// (arrows plus Done) so it cannot stack under the floating toolbar.
@MainActor
final class MiniBrowserWebView: WKWebView {
    /// How far the page's layout viewport is pushed up from the bottom. Zero
    /// while the bar is tucked away, so the page uses the full height again.
    private(set) var bottomClearance: CGFloat = 0

    /// True while a clearance change is settling. WebKit may clamp the scroll
    /// offset as part of it; the scroll observer must not read that as a scroll.
    private(set) var scrollClearanceIsChanging = false

    /// Runs after a clearance change has been applied and WebKit has had its
    /// say about the scroll offset. A smaller inset raises the maximum offset,
    /// a larger one can clamp the page; both move `contentOffset` without a
    /// finger, so the scroll observer has to re-base on it.
    var clearanceDidSettle: (() -> Void)?

    /// The page's pull-to-refresh control, held here so it can leave the
    /// scroll view while something covers the page and come back after.
    var refreshControl: UIRefreshControl? {
        didSet { syncRefreshControl() }
    }

    /// Whether the chrome allows a pull right now. The reader, the address
    /// editor and the error panel take it away; the find bar does too.
    private(set) var pullToRefreshAllowed = true

    /// Watches the find bar while it is up, since it goes away without a
    /// callback of its own.
    private var findBarWatcher: Task<Void, Never>?
    /// When the bar was asked to open. It gets a moment to appear before its
    /// absence counts, so a slow animation cannot bring the control back.
    private var findBarOpenedAt: Date?

    /// Runs after a refresh was ended by taking the control away. Ending it
    /// moves the offset without a finger, like the end of a pull does.
    var refreshWasEnded: (() -> Void)?

    override var inputAccessoryView: UIView? { nil }

    func setPullToRefreshAllowed(_ allowed: Bool) {
        guard pullToRefreshAllowed != allowed else { return }
        pullToRefreshAllowed = allowed
        syncRefreshControl()
    }

    /// The system find bar was just asked to open. The refresh control leaves
    /// the top edge with it and comes back once the bar is dismissed.
    func findNavigatorDidPresent() {
        guard refreshControl != nil else { return }
        findBarOpenedAt = Date()
        if scrollView.refreshControl != nil {
            endRefresh()
            scrollView.refreshControl = nil
        }
        watchFindBar()
    }

    /// Puts the refresh control in the scroll view only while the page is
    /// clear to pull. The find bar sits at the same top edge, so it wins
    /// while it is visible.
    func syncRefreshControl() {
        guard let refreshControl else { return }
        let findBarIsUp = findBarIsOpening || findInteraction?.isFindNavigatorVisible == true
        let wanted = pullToRefreshAllowed && !findBarIsUp
        if wanted {
            if scrollView.refreshControl !== refreshControl {
                scrollView.refreshControl = refreshControl
            }
            stopWatchingFindBar()
            return
        }
        if scrollView.refreshControl != nil {
            // Leaving the spinner behind would keep the page's top inset.
            endRefresh()
            scrollView.refreshControl = nil
        }
        guard findBarIsUp else { return }
        watchFindBar()
    }

    /// True while the bar has just been asked to open and may still be on its
    /// way in.
    private var findBarIsOpening: Bool {
        guard let findBarOpenedAt else { return false }
        return Date().timeIntervalSince(findBarOpenedAt) < 2
    }

    /// Ends a pull that the chrome took away, and lets the bar re-base on the
    /// offset move that follows.
    private func endRefresh() {
        guard let refreshControl, refreshControl.isRefreshing else { return }
        refreshControl.endRefreshing()
        refreshWasEnded?()
    }

    /// The find interaction has no dismissal callback, so its visibility is
    /// read until it is gone, then the control comes back.
    private func watchFindBar() {
        guard findBarWatcher == nil else { return }
        findBarWatcher = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(400))
                guard !Task.isCancelled, let self else { return }
                if self.findInteraction?.isFindNavigatorVisible == true {
                    // Seen: from here on only its dismissal matters.
                    self.findBarOpenedAt = nil
                    continue
                }
                guard !self.findBarIsOpening else { continue }
                // Done: the next bar starts a watcher of its own.
                self.findBarWatcher = nil
                self.syncRefreshControl()
                return
            }
        }
    }

    private func stopWatchingFindBar() {
        findBarWatcher?.cancel()
        findBarWatcher = nil
        findBarOpenedAt = nil
    }

    /// The page's own bottom edge stays clear of the chrome. Values are
    /// clamped and de-duplicated so a re-layout of the view does not make
    /// WebKit reflow the page for nothing.
    func setBottomClearance(_ clearance: CGFloat) {
        let target = max(0, clearance.rounded())
        guard abs(target - bottomClearance) > 0.5 else { return }
        bottomClearance = target

        guard #available(iOS 26.0, *) else { return }
        scrollClearanceIsChanging = true
        var insets = obscuredContentInsets
        insets.bottom = target
        obscuredContentInsets = insets

        // The offset WebKit settles on is not known synchronously: it may
        // clamp during the next layout pass instead.
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.clearanceDidSettle?()
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                self.clearanceDidSettle?()
                self.scrollClearanceIsChanging = false
            }
        }
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        configureScrollEdgeEffects()
        installKeyboardAccessory()
        syncRefreshControl()
    }

    /// The bar is a floating panel, not a scroll edge: no pocket below it.
    /// The top keeps its own edge as well - the page does not run under the
    /// status bar, so there is nothing for it to fade.
    private func configureScrollEdgeEffects() {
        if #available(iOS 26.0, *) {
            scrollView.bottomEdgeEffect.isHidden = true
            scrollView.topEdgeEffect.isHidden = true
        }
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        installKeyboardAccessory()
        // The find bar can appear and go without a callback; every layout pass
        // checks whether the refresh control belongs in the scroll view now.
        syncRefreshControl()
    }

    /// WKContentView (not WKWebView) owns the keyboard accessory. It is given
    /// our own bar, with arrows to move between the page's fields and a
    /// checkmark to finish, so a page field can always be closed.
    private func installKeyboardAccessory() {
        for view in scrollView.subviews {
            let name = NSStringFromClass(type(of: view))
            guard name.contains("WKContent") else { continue }
            view.inputAssistantItem.leadingBarButtonGroups = []
            view.inputAssistantItem.trailingBarButtonGroups = []
            WebKitFormAccessoryInstaller.install(
                on: view,
                accessory: WebKeyboardAccessory(webView: self)
            )
        }
    }
}

/// Overrides `inputAccessoryView` on WebKit's internal content view so the
/// page's keyboard gets our bar instead of the system one.
private enum WebKitFormAccessoryInstaller {
    private static let subclassName = "ZenMiniBrowserWKContentAccessory"
    private static let lock = NSLock()
    /// One key per content view; associated objects keep the bar alive.
    private static let accessoryKey = UnsafeMutablePointer<UInt8>.allocate(capacity: 1)

    static func install(on contentView: UIView, accessory: UIView) {
        lock.lock()
        defer { lock.unlock() }

        // Layout runs often; the bar is installed once per content view.
        guard objc_getAssociatedObject(contentView, accessoryKey) == nil else { return }
        objc_setAssociatedObject(
            contentView,
            accessoryKey,
            accessory,
            .OBJC_ASSOCIATION_RETAIN_NONATOMIC
        )
        if let existing = NSClassFromString(subclassName) {
            if object_getClass(contentView) != existing {
                object_setClass(contentView, existing)
            }
            return
        }
        guard let currentClass = object_getClass(contentView),
              let subclass = objc_allocateClassPair(currentClass, subclassName, 0) else {
            return
        }
        let selector = #selector(getter: UIResponder.inputAccessoryView)
        let impl: @convention(block) (AnyObject) -> UIView? = { view in
            objc_getAssociatedObject(view, accessoryKey) as? UIView
        }
        class_addMethod(subclass, selector, imp_implementationWithBlock(impl), "@@:")
        objc_registerClassPair(subclass)
        object_setClass(contentView, subclass)
    }
}
