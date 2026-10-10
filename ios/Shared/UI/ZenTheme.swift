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

    /// Hairline around a lift tile. Lighter on the top edge, so the flat fill
    /// reads as a thin piece of glass. A stroke, not `glassEffect`.
    static func glassRim(_ scheme: ColorScheme) -> LinearGradient {
        if scheme == .dark {
            return LinearGradient(
                colors: [
                    Color.white.opacity(0.40),
                    Color.white.opacity(0.14)
                ],
                startPoint: .top,
                endPoint: .bottom
            )
        }
        return LinearGradient(
            colors: [
                Color.white.opacity(0.95),
                Color.black.opacity(0.16)
            ],
            startPoint: .top,
            endPoint: .bottom
        )
    }
}

extension View {
    /// 1pt rim for lift tiles. Stays inside the shape so neighboring tiles
    /// do not share an edge.
    func glassRim(cornerRadius: CGFloat, scheme: ColorScheme) -> some View {
        overlay {
            RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                .strokeBorder(Palette.glassRim(scheme), lineWidth: 1)
                .allowsHitTesting(false)
        }
    }
}
