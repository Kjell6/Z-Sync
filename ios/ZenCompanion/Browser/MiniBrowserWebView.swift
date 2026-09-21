import WebKit
import UIKit

/// WKWebView that paints full-bleed under app chrome while shrinking the *layout*
/// viewport by the overlapping bottom toolbar — the iOS 26 Safari mechanism
/// (`obscuredContentInsets`). Overriding `safeAreaInsets` does not do this:
/// WebKit does not map that override onto `position: fixed` or the layout viewport.
@MainActor
final class MiniBrowserWebView: WKWebView {
    /// Bottom toolbar is visible (hidden while the address field is focused).
    var bottomBarVisible: Bool = true {
        didSet {
            guard bottomBarVisible != oldValue else { return }
            updateObscuredInsets()
        }
    }

    private var appliedBottomInset: CGFloat = -1

    override var inputAccessoryView: UIView? { nil }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        hideBottomScrollEdgeEffect()
        hideWebKitFormAccessory(includingWindow: true)
        updateObscuredInsets()
    }

    /// Matches SwiftUI `scrollEdgeEffectHidden(true, for: .bottom)` on the
    /// WKWebView scroll view so the bottom toolbar buttons have no pocket.
    private func hideBottomScrollEdgeEffect() {
        if #available(iOS 26.0, *) {
            scrollView.bottomEdgeEffect.isHidden = true
        }
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        hideWebKitFormAccessory(includingWindow: false)
        updateObscuredInsets()
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

    override func safeAreaInsetsDidChange() {
        super.safeAreaInsetsDidChange()
        updateObscuredInsets()
    }

    func updateObscuredInsets() {
        applyObscuredBottomInset(computedBottomObscuredInset())
    }

    private func computedBottomObscuredInset() -> CGFloat {
        let homeIndicator = super.safeAreaInsets.bottom
        guard bottomBarVisible else { return homeIndicator }

        // Measured overlap with the real toolbar, if it is reachable in the hierarchy.
        if let overlap = overlappingBottomChromeHeight() {
            return max(homeIndicator, overlap)
        }

        // No reachable bar: assume the iOS 26 floating bar with the home indicator
        // already included in the overlap estimate. Much smaller than the old
        // `homeIndicator + 49pt` fallback, which left a large gap.
        return max(homeIndicator, Self.floatingToolbarHeight)
    }

    /// Height of a compact iOS 26 floating toolbar (without the home indicator).
    private static let floatingToolbarHeight: CGFloat = 44

    /// Vertical overlap between this web view and the app's bottom chrome, in the
    /// web view's coordinate space (same space `obscuredContentInsets` uses).
    private func overlappingBottomChromeHeight() -> CGFloat? {
        guard let window else { return nil }
        let webFrame = convert(bounds, to: window)
        guard webFrame.height > 1 else { return nil }

        var best: CGFloat?

        func consider(_ view: UIView) {
            guard !view.isHidden, view.alpha > 0.05 else { return }
            let name = NSStringFromClass(type(of: view))
            let isBar = view is UIToolbar
                || view is UITabBar
                || name.contains("UIToolbar")
                || (name.contains("Toolbar") && !name.contains("NavigationBar") && !name.contains("WK"))
            guard isBar else { return }

            let frame = view.convert(view.bounds, to: window)
            guard frame.intersects(webFrame) else { return }
            let overlap = webFrame.maxY - frame.minY
            guard overlap > 20, overlap < 200 else { return }
            best = max(best ?? 0, overlap)
        }

        func walk(_ view: UIView) {
            if view is WKWebView { return }
            consider(view)
            for subview in view.subviews {
                walk(subview)
            }
        }

        var responder: UIResponder? = self
        var searchRoot: UIView = window
        while let current = responder {
            if let nav = current as? UINavigationController {
                if let toolbar = nav.toolbar, !nav.isToolbarHidden {
                    // The navigation controller's toolbar is *the* bar that overlaps
                    // us; measure it directly so its height wins over any other
                    // chrome found from the window root.
                    consider(toolbar)
                }
                searchRoot = nav.view
                break
            }
            if let vc = current as? UIViewController {
                searchRoot = vc.navigationController?.view ?? vc.view
            }
            responder = current.next
        }
        walk(searchRoot)
        return best
    }

    private func applyObscuredBottomInset(_ bottom: CGFloat) {
        let inset = max(0, bottom.rounded())
        guard bounds.height > inset + 8 else { return }
        guard abs(inset - appliedBottomInset) > 0.5 else { return }

        let edges = UIEdgeInsets(top: 0, left: 0, bottom: inset, right: 0)
        if #available(iOS 26.0, *) {
            obscuredContentInsets = edges
        } else {
            // Must not exceed safeAreaInsets or WebKit ignores/throws the call.
            // This API only feeds svh/lvh — it does not move position:fixed.
            let safeBottom = super.safeAreaInsets.bottom
            let clamped = UIEdgeInsets(top: 0, left: 0, bottom: min(inset, safeBottom), right: 0)
            setMinimumViewportInset(clamped, maximumViewportInset: clamped)
        }
        scrollView.verticalScrollIndicatorInsets.bottom = inset
        appliedBottomInset = inset
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
