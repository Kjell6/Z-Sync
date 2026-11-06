import SwiftUI

/// The app's design type, expressed so that it follows the system text size.
///
/// `Font.system(size:)` is deliberately fixed: it ignores Dynamic Type. Every
/// label in Z-Sync used it, so the system text-size setting did nothing (issue
/// #58). `ZenScaledFont` keeps the design's point size at the default text size
/// — "Large" renders exactly as before — and scales it from there.
///
/// The Dynamic Type curve comes from `zenTextStyle(for:)`, so a 32pt title
/// grows like a large title and an 11pt caption grows like a caption.
struct ZenScaledFont: ViewModifier {
    @ScaledMetric private var size: CGFloat
    private let weight: Font.Weight
    private let design: Font.Design

    init(_ base: CGFloat, weight: Font.Weight, design: Font.Design) {
        _size = ScaledMetric(wrappedValue: base, relativeTo: zenTextStyle(for: base))
        self.weight = weight
        self.design = design
    }

    func body(content: Content) -> some View {
        content.font(.system(size: size, weight: weight, design: design))
    }
}

extension View {
    /// Design font that follows the system text size. Use for text, not for
    /// icons: symbols keep their fixed size so the chrome stays put.
    func zenFont(
        _ size: CGFloat,
        weight: Font.Weight = .regular,
        design: Font.Design = .default
    ) -> some View {
        modifier(ZenScaledFont(size, weight: weight, design: design))
    }
}

/// Text style whose Dynamic Type curve matches a design point size, so the
/// scale factor grows at the rate the size warrants.
func zenTextStyle(for size: CGFloat) -> Font.TextStyle {
    switch size {
    case 30...: return .largeTitle
    case 24..<30: return .title
    case 21..<24: return .title2
    case 19..<21: return .title3
    case 18..<19: return .headline
    case 17..<18: return .body
    case 16..<17: return .callout
    case 15..<16: return .subheadline
    case 13..<15: return .footnote
    case 11..<13: return .caption
    default: return .caption2
    }
}
