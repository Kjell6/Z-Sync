import SwiftUI
import UIKit

private extension Color {
    /// Extracts sRGB components; falls back to gray for non-RGB colors.
    var components: (red: Double, green: Double, blue: Double) {
        var red: CGFloat = 0, green: CGFloat = 0, blue: CGFloat = 0, alpha: CGFloat = 0
        UIColor(self).getRed(&red, green: &green, blue: &blue, alpha: &alpha)
        return (Double(red), Double(green), Double(blue))
    }
}

// MARK: - Official Zen Sidebar Folder Icon (Exact vector path + dynamic open/close folding states)

struct ZenFolderSidebarIcon: View {
    let isExpanded: Bool
    let scheme: ColorScheme
    /// Zen folder user icon (`chrome://…/selectable/….svg`, emoji or data URL).
    /// Zen draws it as an 11×11 `<image>` inside the folder flap, never instead
    /// of the folder silhouette. We render it a little smaller (9pt) so it sits
    /// comfortably inside the flap instead of nearly filling it.
    var userIcon: String? = nil

    private var strokeColor: Color {
        Palette.ink(scheme).opacity(0.85)
    }

    private var backFillColor: Color {
        scheme == .dark
            ? Color(red: 0.16, green: 0.16, blue: 0.18)
            : Color(red: 228 / 255, green: 222 / 255, blue: 216 / 255)
    }

    private var frontFillColor: Color {
        scheme == .dark
            ? Color(red: 0.22, green: 0.22, blue: 0.25)
            : Color(red: 243 / 255, green: 236 / 255, blue: 230 / 255)
    }

    var body: some View {
        ZStack {
            // 1. Back Folder
            BackFolderShape()
                .fill(backFillColor)
                .overlay(
                    BackFolderShape()
                        .stroke(strokeColor, lineWidth: 1.4)
                )
                .frame(width: 28, height: 28)
                .modifier(FolderTransformModifier(progress: isExpanded ? 1.0 : 0.0, isFront: false))

            // 2. Front Folder Flap (Solid opaque shape with exact matching coordinate system).
            // The user icon rides on the flap: Zen transforms `.front, .dots,
            // .icon` together when the folder opens.
            ZStack {
                FrontFolderShape()
                    .fill(frontFillColor)
                    .overlay(
                        FrontFolderShape()
                            .stroke(strokeColor, lineWidth: 1.4)
                    )
                if let userIcon, !userIcon.isEmpty {
                    ZenIconView(icon: userIcon, size: 9, foreground: strokeColor)
                        // Zen centers the image on the front rect (y 9.625…22.375
                        // → center 16), two points below the 28pt frame center.
                        .offset(y: 2)
                }
            }
            .frame(width: 28, height: 28)
            .modifier(FolderTransformModifier(progress: isExpanded ? 1.0 : 0.0, isFront: true))
        }
        .scaleEffect(1.28)
        .frame(width: 28, height: 28)
        .animation(.spring(response: 0.3, dampingFraction: 0.8), value: isExpanded)
    }
}

private struct FrontFolderShape: Shape {
    func path(in rect: CGRect) -> Path {
        var path = Path()
        let flapRect = CGRect(x: 5.625, y: 9.625, width: 16.75, height: 12.75)
        path.addRoundedRect(in: flapRect, cornerSize: CGSize(width: 2.375, height: 2.375))
        return path
    }
}

private struct FolderTransformModifier: GeometryEffect {
    var progress: Double
    let isFront: Bool

    var animatableData: Double {
        get { progress }
        set { progress = newValue }
    }

    func effectValue(size: CGSize) -> ProjectionTransform {
        let scale = 1.0 - 0.15 * progress
        let skewAngle = (isFront ? -16.0 : 16.0) * progress * .pi / 180.0
        let tx = (isFront ? 8.0 : -5.2) * progress
        let ty = 2.0 * progress

        let transform = CGAffineTransform(scaleX: scale, y: scale)
            .translatedBy(x: tx, y: ty)
            .concatenating(CGAffineTransform(a: 1, b: 0, c: tan(skewAngle), d: 1, tx: 0, ty: 0))

        return ProjectionTransform(transform)
    }
}

private struct BackFolderShape: Shape {
    func path(in rect: CGRect) -> Path {
        var path = Path()
        path.move(to: CGPoint(x: 8, y: 5.625))
        path.addLine(to: CGPoint(x: 11.9473, y: 5.625))
        path.addCurve(
            to: CGPoint(x: 13.4316, y: 6.14551),
            control1: CGPoint(x: 12.4866, y: 5.625),
            control2: CGPoint(x: 13.0105, y: 5.80861)
        )
        path.addLine(to: CGPoint(x: 14.2881, y: 6.83105))
        path.addCurve(
            to: CGPoint(x: 16.5527, y: 7.625),
            control1: CGPoint(x: 14.9308, y: 7.34508),
            control2: CGPoint(x: 15.7298, y: 7.625)
        )
        path.addLine(to: CGPoint(x: 20, y: 7.625))
        path.addCurve(
            to: CGPoint(x: 22.375, y: 10),
            control1: CGPoint(x: 21.3117, y: 7.625),
            control2: CGPoint(x: 22.375, y: 8.68832)
        )
        path.addLine(to: CGPoint(x: 22.375, y: 20))
        path.addCurve(
            to: CGPoint(x: 20, y: 22.375),
            control1: CGPoint(x: 22.375, y: 21.3117),
            control2: CGPoint(x: 21.3117, y: 22.375)
        )
        path.addLine(to: CGPoint(x: 8, y: 22.375))
        path.addCurve(
            to: CGPoint(x: 5.625, y: 20),
            control1: CGPoint(x: 6.68832, y: 22.375),
            control2: CGPoint(x: 5.625, y: 21.3117)
        )
        path.addLine(to: CGPoint(x: 5.625, y: 8))
        path.addCurve(
            to: CGPoint(x: 8, y: 5.625),
            control1: CGPoint(x: 5.625, y: 6.68832),
            control2: CGPoint(x: 6.68832, y: 5.625)
        )
        path.closeSubpath()
        return path
    }
}
