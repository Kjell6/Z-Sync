import SwiftUI
import UIKit

/// The display's own corner radius, in points. Read from the screen so the
/// expanded bar matches the phone it is running on. Falls back only when the
/// system does not publish a radius.
enum DisplayCornerRadius {
    static func points(for screen: UIScreen? = nil) -> CGFloat {
        let screen = screen
            ?? UIApplication.shared.connectedScenes
                .compactMap { $0 as? UIWindowScene }
                .flatMap(\.windows)
                .first(where: \.isKeyWindow)?
                .screen
            ?? UIScreen.main
        let selector = NSSelectorFromString("_displayCornerRadius")
        guard screen.responds(to: selector) else { return 55 }
        let value = screen.value(forKey: "_displayCornerRadius") as? CGFloat ?? 0
        return value > 1 ? value : 55
    }
}

// MARK: - Liquid Glass View Modifier Helper

extension View {
    /// iOS 26 SDK leaves the bottom bar chrome-less on iOS 17/18 unless we
    /// force a visible material. No-op on iOS 26 (glass is automatic).
    @ViewBuilder
    func legacyBottomToolbarBackground(_ color: Color) -> some View {
        if #available(iOS 26.0, *) {
            self
        } else {
            self
                .toolbarBackground(.visible, for: .bottomBar)
                .toolbarBackground(color, for: .bottomBar)
        }
    }

    /// iOS 26: no scroll-edge pocket under the bottom toolbar (Apple's "None").
    /// Styles are only `.automatic` / `.soft` / `.hard`; hiding the effect is
    /// `scrollEdgeEffectHidden` — see https://developer.apple.com/documentation/swiftui/view/scrolledgeeffecthidden(_:for:)
    @ViewBuilder
    func hiddenBottomScrollEdgeEffect() -> some View {
        if #available(iOS 26.0, *) {
            self.scrollEdgeEffectHidden(true, for: .bottom)
        } else {
            self
        }
    }

    /// Untinted capsule. `interactive` is the same touch flex native glass
    /// buttons get. `glassVisible == false` drops the glass layer: while a
    /// native context menu is up, iOS 26 paints `glassEffect` as an opaque
    /// white slab, so the pin banner falls back to a plain material.
    @ViewBuilder
    func liquidGlassCapsule(interactive: Bool = false, glassVisible: Bool = true) -> some View {
        if #available(iOS 26.0, *) {
            if glassVisible {
                self.glassEffect(interactive ? .regular.interactive() : .regular, in: Capsule())
            } else {
                self.background(.regularMaterial, in: Capsule())
            }
        } else {
            self
                .background(.ultraThinMaterial, in: Capsule())
                .overlay(
                    Capsule().strokeBorder(Color.primary.opacity(0.10), lineWidth: 0.6)
                )
        }
    }

    /// Soft lift. No fill: the page stays what the glass samples.
    func floatingGlassShadow(scheme: ColorScheme) -> some View {
        shadow(
            color: Color.black.opacity(scheme == .dark ? 0.35 : 0.16),
            radius: 14,
            x: 0,
            y: 6
        )
    }

    /// Full-width bottom bar. Bottom corners stay square so the glass is cut
    /// off by the screen. `topRadius` is 0 while collapsed. The open menu and
    /// the keyboard composer pass a radius smaller than the display corner.
    @ViewBuilder
    func liquidGlassBar(topRadius: CGFloat) -> some View {
        let shape = UnevenRoundedRectangle(
            topLeadingRadius: topRadius,
            bottomLeadingRadius: 0,
            bottomTrailingRadius: 0,
            topTrailingRadius: topRadius,
            style: .continuous
        )
        if #available(iOS 26.0, *) {
            self
                .clipShape(shape)
                .glassEffect(.regular, in: shape)
        } else {
            self
                .background(.ultraThinMaterial, in: shape)
                .clipShape(shape)
                .overlay(
                    shape.strokeBorder(Color.primary.opacity(0.10), lineWidth: 0.6)
                )
        }
    }

    /// Untinted regular glass for the bottom browser card. One radius for the
    /// collapsed bar and the expanded page menu, so the card reads as the same
    /// surface while it grows. No tint: the sheet stays the system material.
    @ViewBuilder
    func liquidGlassCard(cornerRadius: CGFloat) -> some View {
        let shape = RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
        if #available(iOS 26.0, *) {
            self
                .clipShape(shape)
                .glassEffect(.regular, in: shape)
        } else {
            self
                .background(.ultraThinMaterial, in: shape)
                .clipShape(shape)
                .overlay(
                    shape.strokeBorder(Color.primary.opacity(0.10), lineWidth: 0.6)
                )
        }
    }

    @ViewBuilder
    func liquidGlassCircle() -> some View {
        if #available(iOS 26.0, *) {
            self.glassEffect(in: Circle())
        } else {
            self
                .background(.ultraThinMaterial, in: Circle())
                .overlay(
                    Circle().strokeBorder(Color.primary.opacity(0.10), lineWidth: 0.6)
                )
        }
    }

    /// Brand-tinted Liquid Glass: keeps the glass material but grounds it on a
    /// solid tint so the capsule color never depends on the website behind it
    /// (which previously caused light-on-light, unreadable toasts).
    ///
    /// `glassVisible == false` drops the glass layer: while a native context
    /// menu is presented iOS 26 renders `glassEffect` as an opaque white slab
    /// (and it stays broken through the menu's closing animation), so the pin
    /// banner disables it once its destination picker has been opened.
    @ViewBuilder
    func liquidGlassTintedCapsule(_ tint: Color, glassVisible: Bool = true) -> some View {
        if #available(iOS 26.0, *) {
            self.background {
                ZStack {
                    Capsule().fill(tint.opacity(0.88))
                    Capsule()
                        .fill(Color.clear)
                        .glassEffect(.regular.tint(tint), in: Capsule())
                        .opacity(glassVisible ? 1 : 0)
                }
            }
        } else {
            self
                .background {
                    ZStack {
                        Capsule()
                            .fill(.ultraThinMaterial)
                            .opacity(glassVisible ? 1 : 0)
                        Capsule().fill(tint.opacity(0.92))
                    }
                }
                .overlay(
                    Capsule().strokeBorder(Color.primary.opacity(0.10), lineWidth: 0.6)
                )
        }
    }
}
