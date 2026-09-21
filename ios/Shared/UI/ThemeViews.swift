import SwiftUI
import UIKit

/// Toolbars use icons only — never visible text. VoiceOver still gets a label.
struct ToolbarIconButton: View {
    var systemName: String
    var accessibilityKey: LocalizedStringKey
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: 16, weight: .semibold))
        }
        .accessibilityLabel(Text(accessibilityKey))
    }
}

struct ZenMark: View {
    var scheme: ColorScheme
    var size: CGFloat = 34

    var body: some View {
        Image("AppMark")
            .resizable()
            .scaledToFill()
            .frame(width: size, height: size)
            .clipShape(RoundedRectangle(cornerRadius: size * 0.26, style: .continuous))
            .accessibilityHidden(true)
    }
}

/// Decodes the icon payloads Zen syncs for tabs and folders.
///
/// Static tab icons and folder user icons come from Zen's emoji picker
/// (`ZenEmojiPicker.mjs`): selectable SVGs are sent as
/// `chrome://browser/skin/zen-icons/selectable/<name>.svg`, emoji are sent as
/// `data:image/svg+xml;base64,…` wrapping a one-node `<text>` SVG. Favicon
/// payloads (non-static tabs) are plain `data:` image URLs.
enum ZenIconDecoder {
    static let emojiSVGPrefix = "data:image/svg+xml;base64,"

    /// The emoji inside a Zen emoji-as-SVG data URL, nil for every other icon.
    static func emoji(fromDataURL raw: String) -> String? {
        guard raw.hasPrefix(emojiSVGPrefix) else { return nil }
        let base64 = String(raw.dropFirst(emojiSVGPrefix.count))
        guard let data = Data(base64Encoded: base64, options: [.ignoreUnknownCharacters]),
              let svg = String(data: data, encoding: .utf8),
              let text = firstTextNode(in: svg) else { return nil }
        let emoji = unescapeXML(text).trimmingCharacters(in: .whitespacesAndNewlines)
        return emoji.isEmpty ? nil : emoji
    }

    /// Raw bytes of any `data:` URL, base64 or percent-encoded.
    static func dataURLBytes(_ raw: String) -> Data? {
        guard raw.hasPrefix("data:"), let comma = raw.firstIndex(of: ",") else { return nil }
        let meta = raw[..<comma].lowercased()
        let payload = String(raw[raw.index(after: comma)...])
        if meta.contains(";base64") {
            return Data(base64Encoded: payload, options: [.ignoreUnknownCharacters])
        }
        return payload.removingPercentEncoding?.data(using: .utf8)
    }

    private static func firstTextNode(in svg: String) -> String? {
        guard let open = svg.range(of: "<text"),
              let openEnd = svg.range(of: ">", range: open.upperBound..<svg.endIndex),
              let close = svg.range(of: "</text", range: openEnd.upperBound..<svg.endIndex)
        else { return nil }
        return String(svg[openEnd.upperBound..<close.lowerBound])
    }

    private static func unescapeXML(_ text: String) -> String {
        text
            .replacingOccurrences(of: "&lt;", with: "<")
            .replacingOccurrences(of: "&gt;", with: ">")
            .replacingOccurrences(of: "&quot;", with: "\"")
            .replacingOccurrences(of: "&#39;", with: "'")
            .replacingOccurrences(of: "&amp;", with: "&")
    }
}

/// Renders Zen vector SVG assets, synced data-URL icons, fallback emojis or dots.
struct ZenIconView: View {
    let icon: String?
    var size: CGFloat = 20
    var foreground: Color? = nil

    var body: some View {
        if let icon = icon?.trimmingCharacters(in: .whitespacesAndNewlines), !icon.isEmpty {
            ZStack {
                if let emoji = ZenIconDecoder.emoji(fromDataURL: icon) {
                    Text(emoji)
                        .font(.system(size: size * 0.90))
                        .lineLimit(1)
                        .fixedSize()
                } else if icon.hasPrefix("data:") {
                    // Non-emoji data payloads (e.g. favicon raster bytes). Never
                    // fall through to the text branch: a base64 blob must not
                    // render as text.
                    if let data = ZenIconDecoder.dataURLBytes(icon), let image = FaviconDecoder.image(from: data) {
                        Image(uiImage: image)
                            .resizable()
                            .interpolation(.high)
                            .aspectRatio(contentMode: .fit)
                            .frame(width: size * 0.88, height: size * 0.88)
                    }
                } else if let asset = Self.assetName(for: icon), UIImage(named: asset) != nil {
                    Image(asset)
                        .renderingMode(.template)
                        .resizable()
                        .aspectRatio(contentMode: .fit)
                        .frame(width: size * 0.88, height: size * 0.88)
                        .foregroundStyle(foreground ?? .primary)
                } else if icon.count <= 2 || !icon.contains("/") {
                    Text(icon)
                        .font(.system(size: size * 0.90))
                        .lineLimit(1)
                        .fixedSize()
                } else {
                    Circle()
                        .fill(foreground ?? .primary)
                        .frame(width: size * 0.38, height: size * 0.38)
                }
            }
            .frame(width: size, height: size, alignment: .center)
        }
    }

    static func assetName(for raw: String) -> String? {
        let clean = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if clean.isEmpty { return nil }

        // Handles full URIs like chrome://browser/skin/zen-icons/selectable/baseball.svg
        // or filenames like baseball.svg / baseball
        let lastSegment = clean.components(separatedBy: "/").last ?? clean
        let base = lastSegment
            .replacingOccurrences(of: ".svg", with: "")
            .replacingOccurrences(of: ".png", with: "")
            .trimmingCharacters(in: .whitespacesAndNewlines)

        if base.isEmpty { return nil }
        return "zen-\(base)"
    }
}

/// One tab's leading icon: synced Zen static icon (vector asset, emoji or data
/// URL), a gear for host-less `about:`/`chrome:` pages, or the favicon. The
/// single source for tab rows, split panes and essentials tiles.
struct ZenTabIcon: View {
    let tab: ZenTab
    var size: CGFloat = 28
    let scheme: ColorScheme

    var body: some View {
        if tab.hasStaticIcon == true, let icon = tab.icon, !icon.isEmpty {
            ZenIconView(icon: icon, size: size * 0.8, foreground: Palette.ink(scheme))
                .frame(width: size, height: size)
        } else if FaviconResolver.isLocalURL(tab.url) {
            Image(systemName: "gearshape.fill")
                .font(.system(size: size * 0.54, weight: .medium))
                .foregroundStyle(Palette.ink(scheme).opacity(0.55))
                .frame(width: size, height: size)
        } else {
            Favicon(urlString: tab.url, directURL: tab.iconURL, size: size)
        }
    }
}

/// Helper to render uniformly sized 24x24 icons for iOS UIMenus.
/// Guarantees that SVG icons, Emojis, and Dots all share the exact same leading slot and alignment.
enum SpaceMenuIcon {
    static func image(for icon: String?, scheme: ColorScheme = .light, inkColor: UIColor? = nil) -> UIImage {
        let size = CGSize(width: 24, height: 24)
        let renderer = UIGraphicsImageRenderer(size: size)
        let clean = icon?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let isDark = scheme == .dark
        let color: UIColor = inkColor ?? (isDark ? UIColor(red: 243/255, green: 236/255, blue: 230/255, alpha: 1) : UIColor(red: 46/255, green: 46/255, blue: 46/255, alpha: 1))
        
        if !clean.isEmpty {
            if let asset = ZenIconView.assetName(for: clean), let img = UIImage(named: asset) {
                return renderer.image { _ in
                    let targetRect = CGRect(x: 3.5, y: 3.5, width: 17, height: 17)
                    img.withTintColor(color).draw(in: targetRect)
                }.withRenderingMode(.alwaysOriginal)
            } else if clean.count <= 2 || !clean.contains("/") {
                return renderer.image { _ in
                    let string = NSString(string: clean)
                    let attrs: [NSAttributedString.Key: Any] = [
                        .font: UIFont.systemFont(ofSize: 18)
                    ]
                    let strSize = string.size(withAttributes: attrs)
                    let x = (size.width - strSize.width) / 2
                    let y = (size.height - strSize.height) / 2
                    string.draw(at: CGPoint(x: x, y: y), withAttributes: attrs)
                }.withRenderingMode(.alwaysOriginal)
            }
        }
        
        // 6.5x6.5 dot for spaces without icon
        return renderer.image { ctx in
            let dotRect = CGRect(x: 8.75, y: 8.75, width: 6.5, height: 6.5)
            ctx.cgContext.setFillColor(color.cgColor)
            ctx.cgContext.fillEllipse(in: dotRect)
        }.withRenderingMode(.alwaysOriginal)
    }
}

// MARK: - Native Zen Space Gradient Engine (Matching Zen Desktop ZenGradientGenerator.mjs)

/// Renders Zen Browser workspace gradients with full fidelity to Zen Desktop's engine.
struct ZenSpaceGradientBackground: View {
    let theme: ZenSpaceTheme?
    let scheme: ColorScheme
    // TEST (dark-mode experiment, easily reverted): when true, the theme dots
    // are darkened (same hue) so Dark Mode visibly differs from Light Mode.
    // Light Mode rendering is untouched when false.
    var darkenDots: Bool = false

    @State private var previousTheme: ZenSpaceTheme?
    @State private var transitionProgress: Double = 1.0
    // Paper of the outgoing snapshot (set on scheme changes so the old
    // background melts as one finished piece).
    @State private var previousPaperScheme: ColorScheme?

    private var activeTexture: Double {
        theme?.texture ?? 0.0
    }

    var body: some View {
        ZStack {
            if transitionProgress < 1.0 {
                // Outgoing finished background (paper + its theme already
                // mixed), fading out...
                backgroundSnapshot(
                    theme: previousTheme,
                    paperScheme: previousPaperScheme ?? scheme
                )
                .opacity(1.0 - transitionProgress)
                .ignoresSafeArea()

                // ...over the incoming finished background, fading in.
                // Blending two flattened backgrounds stays bright; stacking
                // two translucent gradients over one shared paper filtered
                // the light twice and dipped dark mid-transition.
                backgroundSnapshot(theme: theme, paperScheme: scheme)
                    .opacity(transitionProgress)
                    .ignoresSafeArea()
            } else {
                backgroundSnapshot(theme: theme, paperScheme: scheme)
                    .ignoresSafeArea()
            }

            // 4. Subtle paper noise texture (present on all spaces)
            ZenNoiseOverlay(opacity: 0.022 + min(max(activeTexture, 0.0), 1.0) * 0.04)
                .zIndex(2)
                .ignoresSafeArea()
        }
        .onAppear {
            previousTheme = nil
            transitionProgress = 1.0
        }
        .onChange(of: theme) { oldTheme, newTheme in
            guard oldTheme != newTheme else { return }
            previousTheme = oldTheme
            transitionProgress = 0.0
            withAnimation(.easeInOut(duration: 0.28)) {
                transitionProgress = 1.0
            }
        }
        .onChange(of: scheme) { oldScheme, newScheme in
            guard oldScheme != newScheme else { return }
            previousPaperScheme = oldScheme
            // Keep the outgoing gradient in the snapshot (the theme handler
            // above doesn't run on a pure scheme change). Don't clobber an
            // outgoing theme captured by a simultaneous theme change.
            if previousTheme == nil { previousTheme = theme }
            transitionProgress = 0.0
            withAnimation(.easeInOut(duration: 0.28)) {
                transitionProgress = 1.0
            }
        }
        .onChange(of: transitionProgress) { _, newValue in
            // Transition finished: drop the outgoing layers. Otherwise old
            // themes stack up underneath forever (ghost bleed through the
            // translucent gradients) and corrupt the next switch's baseline.
            if newValue >= 1.0 {
                previousTheme = nil
                previousPaperScheme = nil
            }
        }
    }

    private func calcOpacity(for theme: ZenSpaceTheme?) -> Double {
        guard let theme, !theme.dots.isEmpty else { return 0.0 }
        let raw = theme.opacity ?? 0.85
        return min(max(raw * 0.82, 0.60), 0.85)
    }

    /// One finished background: paper with its theme already mixed in.
    /// Snapshots are what crossfade — never the raw translucent gradients.
    @ViewBuilder
    private func backgroundSnapshot(theme: ZenSpaceTheme?, paperScheme: ColorScheme) -> some View {
        ZStack {
            Palette.paper(paperScheme)
            if let theme, !theme.dots.isEmpty {
                GradientDrawerView(theme: theme, scheme: paperScheme, darken: darkenDots)
                    .opacity(calcOpacity(for: theme))
            }
        }
    }
}

private struct GradientDrawerView: View {
    let theme: ZenSpaceTheme
    let scheme: ColorScheme
    // TEST (dark-mode experiment): darkens dots when true, no-op when false.
    var darken: Bool = false

    private var orderedDots: [ZenThemeDot] {
        let dots = theme.dots
        guard !dots.isEmpty else { return [] }
        if let primaryIndex = dots.firstIndex(where: \.isPrimary), primaryIndex > 0 {
            var reordered = dots
            let primary = reordered.remove(at: primaryIndex)
            return [primary] + reordered
        }
        return dots
    }

    var body: some View {
        let dots = orderedDots
        GeometryReader { geo in
            let maxDim = max(geo.size.width, geo.size.height)
            ZStack {
                if dots.isEmpty {
                    Color.clear
                } else if dots.count == 1 {
                    colorForDot(dots[0])
                } else if dots.count == 2 {
                    let baseColor = colorForDot(dots[0])
                    let accentColor = colorForDot(dots[1])

                    // Base primary color covering the whole canvas
                    baseColor

                    // Accent ambient glow in the top-right
                    RadialGradient(
                        stops: [
                            .init(color: accentColor.opacity(0.90), location: 0.0),
                            .init(color: accentColor.opacity(0.0), location: 0.85),
                        ],
                        center: UnitPoint(x: 1.0, y: 0.15),
                        startRadius: 0,
                        endRadius: maxDim * 0.95
                    )
                } else {
                    let baseColor = colorForDot(dots[0])
                    let accent1 = colorForDot(dots[1])
                    let accent2 = colorForDot(dots[2])

                    // Base primary color covering the whole canvas
                    baseColor

                    // First accent glow in the top-right
                    RadialGradient(
                        stops: [
                            .init(color: accent1.opacity(0.90), location: 0.0),
                            .init(color: accent1.opacity(0.0), location: 0.80),
                        ],
                        center: UnitPoint(x: 1.0, y: 0.12),
                        startRadius: 0,
                        endRadius: maxDim * 0.85
                    )

                    // Second accent glow in the bottom-right
                    RadialGradient(
                        stops: [
                            .init(color: accent2.opacity(0.90), location: 0.0),
                            .init(color: accent2.opacity(0.0), location: 0.80),
                        ],
                        center: UnitPoint(x: 0.85, y: 0.88),
                        startRadius: 0,
                        endRadius: maxDim * 0.85
                    )
                }
            }
        }
    }

    private func colorForDot(_ dot: ZenThemeDot) -> Color {
        let base = Color(
            red: dot.color.red,
            green: dot.color.green,
            blue: dot.color.blue
        )
        // TEST (dark-mode experiment): same hue, clearly darker so Dark Mode
        // differs from Light Mode. Delete this branch to revert.
        guard darken else { return base }
        let ui = UIColor(base)
        var hue: CGFloat = 0
        var saturation: CGFloat = 0
        var brightness: CGFloat = 0
        var alpha: CGFloat = 0
        guard ui.getHue(&hue, saturation: &saturation, brightness: &brightness, alpha: &alpha) else {
            return base
        }
        return Color(
            hue: Double(hue),
            saturation: Double(min(saturation * 1.06, 1.0)),
            brightness: Double(brightness * 0.60),
            opacity: Double(alpha)
        )
    }
}

// MARK: - Zero-cost Tiled Noise Texture Cache

private enum ZenNoiseImageCache {
    static let image: UIImage = {
        let size = 64
        var pixels = [UInt8](repeating: 0, count: size * size * 4)
        for i in 0..<(size * size) {
            let v = UInt8.random(in: 0...255)
            pixels[i * 4 + 0] = v
            pixels[i * 4 + 1] = v
            pixels[i * 4 + 2] = v
            pixels[i * 4 + 3] = UInt8(round(Double(v) * 0.25))
        }
        let colorSpace = CGColorSpaceCreateDeviceRGB()
        let bitmapInfo = CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue)
        guard let context = CGContext(
            data: &pixels,
            width: size,
            height: size,
            bitsPerComponent: 8,
            bytesPerRow: size * 4,
            space: colorSpace,
            bitmapInfo: bitmapInfo.rawValue
        ), let cgImage = context.makeImage() else {
            return UIImage()
        }
        return UIImage(cgImage: cgImage)
    }()
}

struct ZenNoiseOverlay: View {
    var opacity: Double

    var body: some View {
        Image(uiImage: ZenNoiseImageCache.image)
            .resizable(resizingMode: .tile)
            .opacity(opacity)
            .blendMode(.overlay)
            .allowsHitTesting(false)
    }
}
