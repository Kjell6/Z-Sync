import ImageIO
import SwiftUI
import UIKit

enum Palette {
    static func coral(_ scheme: ColorScheme) -> Color {
        scheme == .dark
            ? Color(red: 163 / 255, green: 204 / 255, blue: 198 / 255)
            : Color(red: 38 / 255, green: 69 / 255, blue: 64 / 255)
    }

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
    static let rimWidth: CGFloat = 0.5
    static let rimOpacity: Double = 0.08

}

extension View {
    /// Flat hairline for lift surfaces. One color, just stronger than the lift
    /// fill (0.06–0.07), so the edge does not read as a separate line.
    /// Width stays 0.5. Stays inside the shape so neighboring tiles do not
    /// share an edge.
    func glassRim(cornerRadius: CGFloat) -> some View {
        overlay {
            RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                .strokeBorder(Color.primary.opacity(Palette.rimOpacity), lineWidth: Palette.rimWidth)
                .allowsHitTesting(false)
        }
    }

    /// Same hairline on a pill. Capsules are the other lift shape in the app.
    func glassRimCapsule() -> some View {
        overlay {
            Capsule(style: .continuous)
                .strokeBorder(Color.primary.opacity(Palette.rimOpacity), lineWidth: Palette.rimWidth)
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

extension Color {
    /// Perceptual luminance test for colors that carry a real value: a page's
    /// `theme-color`, a hex or CSS rgb string, a native UIColor. Non-RGB
    /// colors (system, material, dynamic) report `false`; the caller decides
    /// its own fallback for those.
    var isDarkBackground: Bool {
        var red: CGFloat = 0
        var green: CGFloat = 0
        var blue: CGFloat = 0
        var alpha: CGFloat = 0
        guard UIColor(self).getRed(&red, green: &green, blue: &blue, alpha: &alpha) else {
            return false
        }
        let luminance = 0.2126 * red + 0.7152 * green + 0.0722 * blue
        return luminance < 0.5
    }
}
