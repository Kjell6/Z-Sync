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
/// iOS 26, and the iPhone Prev/Next/Done accessory bar that can otherwise stack
/// under our toolbar.
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

    override var inputAccessoryView: UIView? { nil }

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
        hideWebKitFormAccessory(includingWindow: true)
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
        hideWebKitFormAccessory(includingWindow: false)
    }

    /// WKContentView (not WKWebView) owns the iPhone Prev/Next/Done bar.
    /// On iOS 18 it can sit under our bottom toolbar after in-page navigation.
    private func hideWebKitFormAccessory(includingWindow: Bool) {
        for view in scrollView.subviews {
            let name = NSStringFromClass(type(of: view))
            guard name.contains("WKContent") else { continue }
            view.inputAssistantItem.leadingBarButtonGroups = []
            view.inputAssistantItem.trailingBarButtonGroups = []
            WebKitFormAccessoryHider.install(on: view)
        }
        if includingWindow, let window {
            hideFormAccessoryChrome(in: window)
        }
    }

    private func hideFormAccessoryChrome(in view: UIView) {
        if view is WKWebView { return }
        let name = NSStringFromClass(type(of: view))
        if name.contains("FormInputAccessory") || name.contains("WKFormInput") {
            view.isHidden = true
            return
        }
        view.subviews.forEach { hideFormAccessoryChrome(in: $0) }
    }
}

/// Overrides `inputAccessoryView` on WebKit's internal content view so the
/// system Prev/Next/Done bar cannot stack under the mini-browser toolbar.
private enum WebKitFormAccessoryHider {
    private static let subclassName = "ZenMiniBrowserWKContentNoAccessory"
    private static let lock = NSLock()

    static func install(on contentView: UIView) {
        lock.lock()
        defer { lock.unlock() }

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
        let impl: @convention(block) (AnyObject) -> UIView? = { _ in nil }
        class_addMethod(subclass, selector, imp_implementationWithBlock(impl), "@@:")
        objc_registerClassPair(subclass)
        object_setClass(contentView, subclass)
    }
}
