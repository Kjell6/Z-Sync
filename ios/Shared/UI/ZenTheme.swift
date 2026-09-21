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
}
