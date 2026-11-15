import SwiftUI
import WebKit

/// A page beside the one on screen while the address bar is being swiped.
struct PeekingPage {
    let webView: WKWebView?
    let shot: UIImage?
    /// The page's own color, for what the web view does not paint.
    let color: UIColor
}

/// Shows the active page's web view. The web view belongs to the page, not to
/// this view: switching pages moves another live web view in, with its
/// scroll position, form text and running scripts untouched.
///
/// During a swipe the neighbours' live web views sit right beside it, past the
/// edges. The whole host follows the finger, so at the end of a swipe the next
/// page is already exactly where it belongs and nothing has to be swapped.
struct MiniBrowserRepresentable: UIViewRepresentable {
    var model: MiniBrowserModel
    /// Height of the floating chrome over the bottom of the page. The page's
    /// layout viewport is pushed up by this much, so its bottom-most content
    /// and fixed banners stay clear of the glass. Zero while the bar is away.
    var bottomClearance: CGFloat = 0
    var previous: PeekingPage?
    var next: PeekingPage?
    /// The current page's color while it is sliding; nil while it sits still.
    var slidingColor: UIColor?
    /// How far above the host the screen goes on: each sliding page brings its
    /// color for the status bar along.
    var topOverflow: CGFloat = 0
    /// Rounded like the screen while the page slides off towards a new one.
    var cornerRadius: CGFloat = 0

    func makeUIView(context: Context) -> PageHostView {
        PageHostView()
    }

    func updateUIView(_ host: PageHostView, context: Context) {
        host.show(
            current: model.webView,
            previous: previous,
            next: next,
            slidingColor: slidingColor,
            topOverflow: topOverflow
        )
        // Carried by the SwiftUI animation that changed it, so the corners
        // round in step with the page shrinking into its card.
        if #available(iOS 18.0, *) {
            context.animate { host.setCornerRadius(cornerRadius) }
        } else {
            host.setCornerRadius(cornerRadius)
        }
        for peek in [previous, next] {
            (peek?.webView as? MiniBrowserWebView)?.setBottomClearance(bottomClearance)
        }
        guard let webView = model.webView as? MiniBrowserWebView else { return }
        if !webView.isUserInteractionEnabled {
            webView.isUserInteractionEnabled = true
        }
        webView.setBottomClearance(bottomClearance)
        model.applyWebpagePreferences()
        model.applyPageZoom()
    }

    static func dismantleUIView(_ host: PageHostView, coordinator: ()) {
        host.show(current: nil, previous: nil, next: nil, slidingColor: nil, topOverflow: 0)
        host.setCornerRadius(0)
    }
}

/// Holds the web view on screen and, during a swipe, its neighbours. All of
/// them are direct subviews: finishing a swipe only changes frames, so the
/// incoming page never leaves the view tree and cannot flash.
final class PageHostView: UIView {
    /// Space between two pages while they slide past each other.
    static let pageGap: CGFloat = 12

    private var current: WKWebView?
    private var previousPeek: PeekingPage?
    private var nextPeek: PeekingPage?
    private let previousShot = UIImageView()
    private let nextShot = UIImageView()
    /// Each page's color, from the top of the screen to the bottom. A page is
    /// a whole screen while it slides, not just the part the web view fills.
    private let currentBacking = UIView()
    private let previousBacking = UIView()
    private let nextBacking = UIView()
    private var topOverflow: CGFloat = 0

    override init(frame: CGRect) {
        super.init(frame: frame)
        clipsToBounds = false
        for backing in [currentBacking, previousBacking, nextBacking] {
            backing.isHidden = true
            addSubview(backing)
        }
        for shot in [previousShot, nextShot] {
            shot.contentMode = .scaleAspectFill
            shot.clipsToBounds = true
            shot.isHidden = true
            addSubview(shot)
        }
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    func show(
        current: WKWebView?,
        previous: PeekingPage?,
        next: PeekingPage?,
        slidingColor: UIColor?,
        topOverflow: CGFloat
    ) {
        let wanted = [current, previous?.webView, next?.webView].compactMap { $0 }
        for view in subviews where view is WKWebView && !wanted.contains(where: { $0 === view }) {
            view.removeFromSuperview()
        }
        for view in wanted where view.superview !== self {
            view.autoresizingMask = []
            addSubview(view)
        }
        self.current = current
        previousPeek = previous
        nextPeek = next
        self.topOverflow = topOverflow
        currentBacking.backgroundColor = slidingColor
        currentBacking.isHidden = slidingColor == nil
        previousBacking.backgroundColor = previous?.color
        previousBacking.isHidden = previous == nil
        nextBacking.backgroundColor = next?.color
        nextBacking.isHidden = next == nil
        previousShot.image = previous?.shot
        nextShot.image = next?.shot
        previousShot.isHidden = previous?.shot == nil
        nextShot.isHidden = next?.shot == nil
        setNeedsLayout()
        layoutIfNeeded()
    }

    /// The backing carries the top corners, which sit above the web view; the
    /// web view rounds only the bottom ones. It keeps masking at zero, so the
    /// corners can still animate on their way back to square.
    func setCornerRadius(_ radius: CGFloat) {
        currentBacking.layer.cornerRadius = radius
        currentBacking.layer.cornerCurve = .continuous
        current?.layer.cornerCurve = .continuous
        current?.layer.maskedCorners = [.layerMinXMaxYCorner, .layerMaxXMaxYCorner]
        current?.layer.masksToBounds = true
        current?.layer.cornerRadius = radius
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        current?.frame = bounds
        let step = bounds.width + Self.pageGap
        previousPeek?.webView?.frame = bounds.offsetBy(dx: -step, dy: 0)
        nextPeek?.webView?.frame = bounds.offsetBy(dx: step, dy: 0)
        let screen = CGRect(x: 0, y: -topOverflow, width: bounds.width, height: bounds.height + topOverflow)
        currentBacking.frame = screen
        previousBacking.frame = screen.offsetBy(dx: -step, dy: 0)
        nextBacking.frame = screen.offsetBy(dx: step, dy: 0)
        // Under the live page, which paints over it once it has loaded. Sized
        // by its own proportions, so the picture is not cropped from the top.
        for (shot, dx) in [(previousShot, -step), (nextShot, step)] {
            let ratio = shot.image.map { $0.size.height / max($0.size.width, 1) } ?? 0
            shot.frame = CGRect(x: dx, y: 0, width: bounds.width, height: bounds.width * ratio)
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
            let b = Double(rgb & 0x00F) / 15.0
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
