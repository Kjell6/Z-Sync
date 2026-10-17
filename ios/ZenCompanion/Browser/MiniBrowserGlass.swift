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

    /// Full-width bottom bar. Bottom corners stay square so the plate is cut
    /// off by the screen. `topRadius` is 0 while collapsed; the open menu and
    /// the keyboard composer pass their own radius.
    ///
    /// One flat plate of the page color. The bar and the open menu are the same
    /// surface, so the menu does not turn glassy as it grows up over the page,
    /// and iOS 26's `glassEffect` specular edge (which read as a stroke around
    /// the sides and bottom) is replaced by the explicit hairline below.
    ///
    /// The plate cannot reach outside its sheet: a seam along the bottom
    /// corners is fixed on the presentation instead (`presentationCornerRadius`
    /// on the browser sheet).
    ///
    /// `backdrop` is the app's paper rather than the page's color, so the bar
    /// reads as app chrome and the open menu stays distinguishable from the
    /// page behind it. The lift shadow does the rest of the separating.
    func browserBar(topRadius: CGFloat, backdrop: Color) -> some View {
        let shape = UnevenRoundedRectangle(
            topLeadingRadius: topRadius,
            bottomLeadingRadius: 0,
            bottomTrailingRadius: 0,
            topTrailingRadius: topRadius,
            style: .continuous
        )
        return self
            .background(shape.fill(backdrop).allowsHitTesting(false))
            .clipShape(shape)
            .shadow(color: .black.opacity(0.18), radius: 12, y: -2)
            .overlay(
                shape.strokeBorder(Color.primary.opacity(Palette.rimOpacity), lineWidth: Palette.rimWidth)
                    .allowsHitTesting(false)
            )
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
