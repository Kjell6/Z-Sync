import SwiftUI

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

    @ViewBuilder
    func liquidGlassCapsule() -> some View {
        if #available(iOS 26.0, *) {
            self.glassEffect(in: Capsule())
        } else {
            self
                .background(.ultraThinMaterial, in: Capsule())
                .overlay(
                    Capsule().strokeBorder(Color.primary.opacity(0.10), lineWidth: 0.6)
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
