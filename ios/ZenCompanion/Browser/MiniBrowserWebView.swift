import WebKit
import UIKit

/// WKWebView for the mini-browser: the web content, nothing else.
///
/// The page stops where the bar begins (the bar is an opaque plate, so there is
/// nothing to paint under it). The layout viewport therefore ends at the web
/// view's own frame, and no `obscuredContentInsets` bookkeeping is needed.
///
/// What is left is WebKit's own chrome: the bottom scroll-edge pocket on
/// iOS 26, and the iPhone Prev/Next/Done accessory bar that can otherwise stack
/// under our toolbar.
@MainActor
final class MiniBrowserWebView: WKWebView {
    override var inputAccessoryView: UIView? { nil }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        hideBottomScrollEdgeEffect()
        hideWebKitFormAccessory(includingWindow: true)
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
