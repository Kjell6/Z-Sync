import UIKit
import WebKit
import XCTest

@testable import ZenCompanion

/// The keyboard accessory has to have a real size: an input accessory is laid
/// out from its frame, so a zero frame means the bar silently disappears.
@MainActor
final class WebKeyboardAccessoryTests: XCTestCase {
    func testAccessoryHasAHeightAndItsGlassBar() {
        let accessory = WebKeyboardAccessory(webView: WKWebView(frame: .zero))

        XCTAssertEqual(accessory.frame.height, 56)
        XCTAssertEqual(accessory.sizeThatFits(CGSize(width: 390, height: 0)).height, 56)
        XCTAssertEqual(accessory.intrinsicContentSize.height, 56)
        XCTAssertTrue(
            accessory.subviews.contains { $0 is UIVisualEffectView },
            "the bar's own surface is missing"
        )
    }

    // MARK: - Installing the bar on WebKit's content view

    private final class Observer: NSObject {
        override func observeValue(
            forKeyPath keyPath: String?,
            of object: Any?,
            change: [NSKeyValueChangeKey: Any]?,
            context: UnsafeMutableRawPointer?
        ) {}
    }

    private func hostWindow() throws -> UIWindow {
        let scene = try XCTUnwrap(
            UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        )
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(x: 0, y: 0, width: 390, height: 844)
        window.rootViewController = UIViewController()
        window.isHidden = false
        return window
    }

    private func contentView(of webView: WKWebView) throws -> UIView {
        try XCTUnwrap(webView.scrollView.subviews.first {
            NSStringFromClass(type(of: $0)).contains("WKContent")
        })
    }

    /// Installing the bar used to swap the content view's class, which threw
    /// away an observer's class that was on the view at that moment; removing
    /// that observer then raised and crashed the app.
    func testInstallingKeepsAnObserverOnTheContentView() throws {
        let window = try hostWindow()
        let webView = MiniBrowserWebView(frame: window.bounds, configuration: WKWebViewConfiguration())
        let content = try contentView(of: webView)
        let observer = Observer()
        content.addObserver(observer, forKeyPath: "alpha", options: [.new], context: nil)

        window.rootViewController?.view.addSubview(webView)
        webView.layoutIfNeeded()

        content.removeObserver(observer, forKeyPath: "alpha")
        webView.removeFromSuperview()
    }

    func testOurBarOnlyOnTheBrowsersContentView() throws {
        let window = try hostWindow()
        let webView = MiniBrowserWebView(frame: window.bounds, configuration: WKWebViewConfiguration())
        let plain = WKWebView(frame: window.bounds)
        window.rootViewController?.view.addSubview(webView)
        window.rootViewController?.view.addSubview(plain)
        webView.layoutIfNeeded()
        plain.layoutIfNeeded()

        XCTAssertTrue(try contentView(of: webView).inputAccessoryView is WebKeyboardAccessory)
        XCTAssertFalse(try contentView(of: plain).inputAccessoryView is WebKeyboardAccessory)
    }
}
