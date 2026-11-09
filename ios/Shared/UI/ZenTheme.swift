import ImageIO
import SwiftUI
import UIKit
import UIKit.UIGestureRecognizerSubclass

enum Palette {
    static func coral(_ scheme: ColorScheme) -> Color {
        scheme == .dark
            ? Color(red: 163 / 255, green: 204 / 255, blue: 198 / 255)
            : Color(red: 38 / 255, green: 69 / 255, blue: 64 / 255)
    }

    /// The normal surface for chrome, sheets and pages: a very light cool
    /// white. The warmer `paper` below stays the base of an empty space.
    static func background(_ scheme: ColorScheme) -> Color {
        scheme == .dark
            ? Color(red: 31 / 255, green: 31 / 255, blue: 31 / 255)
            : Color(red: 250 / 255, green: 247 / 255, blue: 241 / 255)
    }

    /// The paper base under a space. A space without its own colour shows
    /// this warm tone; everything else uses `background` above.
    static func paper(_ scheme: ColorScheme) -> Color {
        scheme == .dark
            ? Color(red: 31 / 255, green: 31 / 255, blue: 31 / 255)
            : Color(red: 243 / 255, green: 236 / 255, blue: 230 / 255)
    }

    static func ink(_ scheme: ColorScheme) -> Color {
        scheme == .dark
            ? Color(red: 243 / 255, green: 236 / 255, blue: 230 / 255)
            : Color(red: 46 / 255, green: 46 / 255, blue: 46 / 255)
    }

    /// Foreground for content placed on top of `coral` (the brand capsule):
    /// paper on the dark light-mode brand, near-black on the light dark-mode
    /// brand. Both hold AA contrast regardless of what shows through the glass.
    static func onCoral(_ scheme: ColorScheme) -> Color {
        scheme == .dark
            ? Color(red: 31 / 255, green: 31 / 255, blue: 31 / 255)
            : Color(red: 243 / 255, green: 236 / 255, blue: 230 / 255)
    }

    static func lift(_ scheme: ColorScheme) -> Color {
        scheme == .dark
            ? Color.white.opacity(0.07)
            : Color.black.opacity(0.06)
    }

    /// Continuous corner on the lift tiles. 16pt on a 44pt side is large
    /// enough to read as a squircle, and still leaves a flat edge.
    static let liftCornerRadius: CGFloat = 16

    /// One hairline weight for every lift surface, so a tile and a settings
    /// card share the same edge. Android keeps its own slightly lighter pair
    /// in `ButtonChrome.kt`.
    static let rimWidth: CGFloat = 0.6
    static let rimOpacity: Double = 0.12

}

extension View {
    /// Flat hairline for lift surfaces: the same dark line in Light and Dark
    /// Mode. Stays inside the shape so neighboring tiles do not share an edge.
    func glassRim(cornerRadius: CGFloat) -> some View {
        overlay {
            RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                .strokeBorder(Color.black.opacity(Palette.rimOpacity), lineWidth: Palette.rimWidth)
                .allowsHitTesting(false)
        }
    }

    /// Same hairline on a pill. Capsules are the other lift shape in the app.
    func glassRimCapsule() -> some View {
        overlay {
            Capsule(style: .continuous)
                .strokeBorder(Color.black.opacity(Palette.rimOpacity), lineWidth: Palette.rimWidth)
                .allowsHitTesting(false)
        }
    }

    /// The one card look: lift fill plus the hairline rim, on the shared
    /// continuous corner. Every tappable card uses this so the chrome reads
    /// the same on every screen.
    func liftCard(
        _ scheme: ColorScheme,
        cornerRadius: CGFloat = Palette.liftCornerRadius
    ) -> some View {
        self
            .background(
                Palette.lift(scheme),
                in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
            )
            .glassRim(cornerRadius: cornerRadius)
    }

    /// Pressed state for a custom button. Starts the moment the finger goes
    /// down and stays until it lifts. Scale stays off when motion is reduced.
    func pressedFeedback(isPressed: Bool, reduceMotion: Bool) -> some View {
        self
            .scaleEffect(reduceMotion || !isPressed ? 1 : 0.94)
            .opacity(isPressed ? 0.58 : 1)
            .animation(.easeOut(duration: reduceMotion ? 0.01 : 0.08), value: isPressed)
    }
}

/// Replaces `.plain` where the label draws itself. `.plain` swallows the
/// system highlight, so a held finger otherwise changes nothing.
struct PressedButtonStyle: ButtonStyle {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func makeBody(configuration: Configuration) -> some View {
        configuration.label.pressedFeedback(
            isPressed: configuration.isPressed,
            reduceMotion: reduceMotion
        )
    }
}

// MARK: - Liquid feel without the glass material

/// A button's fill (the lift color by default) with a very slight dome: narrow
/// soft bands along the top and bottom edges, blended so they keep the hue. The
/// middle stays the exact fill color.
struct DomedFill<S: Shape>: ViewModifier {
    let shape: S
    let scheme: ColorScheme
    var fill: Color? = nil

    private var dome: LinearGradient {
        let isDark = scheme == .dark
        return LinearGradient(
            stops: [
                .init(color: .white.opacity(isDark ? 0.075 : 0.105), location: 0),
                .init(color: .white.opacity(0), location: 0.24),
                .init(color: .black.opacity(0), location: 0.76),
                .init(color: .black.opacity(isDark ? 0.075 : 0.052), location: 1),
            ],
            startPoint: .top,
            endPoint: .bottom
        )
    }

    func body(content: Content) -> some View {
        content.background {
            shape.fill(fill ?? Palette.lift(scheme))
                .overlay(shape.fill(dome).blendMode(.softLight))
        }
    }
}

/// Moves like Liquid Glass without looking like it: the surface swells the
/// moment a finger lands, stretches toward a dragging finger and wobbles back
/// on release. Reduce Motion keeps the plain press.
struct LiquidPressButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        LiquidPressBody(configuration: configuration)
    }
}

private struct LiquidPressBody: View {
    let configuration: ButtonStyleConfiguration

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.isEnabled) private var isEnabled
    @State private var isTouching = false
    @State private var drag: CGSize = .zero
    @State private var size: CGSize = .zero

    /// Points added to width and height, so a pill and a circle swell alike.
    private static let grow: CGFloat = 3
    private static let maxShift: CGFloat = 4

    private var isDown: Bool { configuration.isPressed || isTouching }

    /// Follows the finger at first, then resists up to `maxShift`.
    private func rubberBand(_ value: CGFloat) -> CGFloat {
        let shift = Self.maxShift * (1 - 1 / (1 + abs(value) / (Self.maxShift * 4)))
        return value < 0 ? -shift : shift
    }

    var body: some View {
        if reduceMotion {
            configuration.label.pressedFeedback(
                isPressed: configuration.isPressed,
                reduceMotion: reduceMotion
            )
        } else if #available(iOS 18.0, *) {
            liquid.gesture(LiquidTouchTracker { translation in
                // A disabled button stays still under the finger.
                isTouching = isEnabled && translation != nil
                drag = isEnabled ? translation ?? .zero : .zero
            })
        } else {
            liquid
        }
    }

    private var liquid: some View {
        let shiftX = rubberBand(drag.width)
        let shiftY = rubberBand(drag.height)
        let grow = isDown ? Self.grow : 0
        // Stretching one way narrows the other, like a drop of liquid.
        let scaleX = 1 + (grow + abs(shiftX) - abs(shiftY) * 0.5) / max(size.width, 1)
        let scaleY = 1 + (grow + abs(shiftY) - abs(shiftX) * 0.5) / max(size.height, 1)

        return configuration.label
            .onGeometryChange(for: CGSize.self) { $0.size } action: { size = $0 }
            .scaleEffect(x: scaleX, y: scaleY)
            .offset(x: shiftX, y: shiftY)
            // The press spring is innermost so it wins on release.
            .animation(.spring(response: 0.36, dampingFraction: 0.72), value: isDown)
            .animation(.interactiveSpring(response: 0.18, dampingFraction: 0.8), value: drag)
    }
}

/// Reports a finger's travel, or nil once it is gone, without ever claiming the
/// touch: buttons keep working and a scroll view's pan ends the tracking.
@available(iOS 18.0, *)
private struct LiquidTouchTracker: UIGestureRecognizerRepresentable {
    let onChange: (CGSize?) -> Void

    func makeUIGestureRecognizer(context: Context) -> PassiveTouchRecognizer {
        let recognizer = PassiveTouchRecognizer(target: nil, action: nil)
        recognizer.onChange = onChange
        return recognizer
    }

    func updateUIGestureRecognizer(_ recognizer: PassiveTouchRecognizer, context: Context) {
        recognizer.onChange = onChange
    }
}

private final class PassiveTouchRecognizer: UIGestureRecognizer {
    var onChange: (CGSize?) -> Void = { _ in }
    private var touch: UITouch?
    private var start: CGPoint = .zero

    override init(target: Any?, action: Selector?) {
        super.init(target: target, action: action)
        cancelsTouchesInView = false
        delaysTouchesBegan = false
        delaysTouchesEnded = false
    }

    override func canPrevent(_ preventedGestureRecognizer: UIGestureRecognizer) -> Bool { false }

    override func canBePrevented(by preventingGestureRecognizer: UIGestureRecognizer) -> Bool {
        preventingGestureRecognizer is UIPanGestureRecognizer
            && preventingGestureRecognizer.view is UIScrollView
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent) {
        guard touch == nil, let first = touches.first else { return }
        touch = first
        start = first.location(in: nil)
        onChange(.zero)
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent) {
        guard let touch, touches.contains(touch) else { return }
        let point = touch.location(in: nil)
        onChange(CGSize(width: point.x - start.x, height: point.y - start.y))
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent) {
        guard let touch, touches.contains(touch) else { return }
        state = .failed
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent) {
        guard let touch, touches.contains(touch) else { return }
        state = .failed
    }

    override func reset() {
        super.reset()
        guard touch != nil else { return }
        touch = nil
        onChange(nil)
    }
}

extension Color {
    /// Perceptual luminance test for colors that carry a real value: a page's
    /// `theme-color`, a hex or CSS rgb string, a native UIColor. Non-RGB colors
    /// (materials, dynamic system colors that cannot be resolved) report
    /// `false`; the caller decides its own fallback for those.
    var isDarkBackground: Bool {
        var red: CGFloat = 0
        var green: CGFloat = 0
        var blue: CGFloat = 0
        var alpha: CGFloat = 0
        guard UIColor(self).getRed(&red, green: &green, blue: &blue, alpha: &alpha) else {
            return false
        }
        return 0.2126 * red + 0.7152 * green + 0.0722 * blue < 0.5
    }

    /// The same color, nudged away from the page so the bar reads as its own
    /// surface: a touch darker on light colors, a touch lighter on dark ones.
    /// Nil when the color cannot be resolved.
    func nudgedForBar(by amount: CGFloat = 0.08) -> Color? {
        var red: CGFloat = 0
        var green: CGFloat = 0
        var blue: CGFloat = 0
        var alpha: CGFloat = 0
        guard UIColor(self).getRed(&red, green: &green, blue: &blue, alpha: &alpha) else {
            return nil
        }
        let luminance = 0.2126 * red + 0.7152 * green + 0.0722 * blue
        let target: CGFloat = luminance < 0.5 ? 1 : 0
        return Color(
            .sRGB,
            red: Double(red + (target - red) * amount),
            green: Double(green + (target - green) * amount),
            blue: Double(blue + (target - blue) * amount),
            opacity: Double(alpha)
        )
    }
}
