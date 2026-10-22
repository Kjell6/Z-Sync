import SwiftUI

// MARK: - Liquid Glass View Modifier Helper

extension View {
    /// Untinted capsule. `interactive` is the same touch flex native glass
    /// buttons get.
    @ViewBuilder
    func liquidGlassCapsule(interactive: Bool = false) -> some View {
        if #available(iOS 26.0, *) {
            self.glassEffect(interactive ? .regular.interactive() : .regular, in: Capsule())
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

    /// Floating glass capsule: a pill that hovers over the page.
    @ViewBuilder
    func floatingGlassCapsule() -> some View {
        let shape = Capsule(style: .continuous)
        if #available(iOS 26.0, *) {
            self
                .clipShape(shape)
                .glassEffect(.regular, in: shape)
                .shadow(color: .black.opacity(0.20), radius: 16, y: 6)
        } else {
            self
                .background(.ultraThinMaterial, in: shape)
                .clipShape(shape)
                .overlay(shape.strokeBorder(Color.primary.opacity(0.10), lineWidth: 0.6))
                .shadow(color: .black.opacity(0.16), radius: 14, y: 5)
        }
    }

    /// Floating glass panel, for the open menu.
    @ViewBuilder
    func floatingGlassPanel(cornerRadius: CGFloat) -> some View {
        let shape = RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
        if #available(iOS 26.0, *) {
            self
                .clipShape(shape)
                .glassEffect(.regular, in: shape)
                .shadow(color: .black.opacity(0.22), radius: 18, y: 8)
        } else {
            self
                .background(.ultraThinMaterial, in: shape)
                .clipShape(shape)
                .overlay(shape.strokeBorder(Color.primary.opacity(0.10), lineWidth: 0.6))
                .shadow(color: .black.opacity(0.18), radius: 16, y: 6)
        }
    }



    /// Untinted regular glass, for a floating card over the page.
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
}

/// Soft bottom fade over the page. A live blur that ramps in towards the screen
/// bottom and stops at half strength, so the page dissolves into the controls
/// instead of being cut off by them.
struct PageBottomFade: View {
    /// Height of the ramp. The fade starts this far above the physical bottom.
    var height: CGFloat = 82

    var body: some View {
        Rectangle()
            .fill(.ultraThinMaterial)
            .mask(
                LinearGradient(
                    stops: [
                        .init(color: .clear, location: 0),
                        .init(color: .white.opacity(0.08), location: 0.28),
                        .init(color: .white.opacity(0.24), location: 0.58),
                        .init(color: .white.opacity(0.5), location: 1)
                    ],
                    startPoint: .top,
                    endPoint: .bottom
                )
            )
            .frame(height: height)
            .allowsHitTesting(false)
            .accessibilityHidden(true)
    }
}
