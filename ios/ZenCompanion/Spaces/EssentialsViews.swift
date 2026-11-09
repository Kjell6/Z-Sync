import SwiftUI
import UIKit.UIGestureRecognizerSubclass

// MARK: - Essentials grid (icon tiles; rows fill the full width, last row included)

struct EssentialsGrid: View {
    let tabs: [ZenTab]
    let scheme: ColorScheme
    /// How many tiles share a row. The adaptive default is 1–4 in one row,
    /// 5–6 in three columns, 7+ in four. Rows are built manually instead of
    /// with `LazyVGrid` so a short final row stretches its tiles to fill the
    /// width rather than leaving a gap on the right.
    var columns: EssentialsColumns = .automatic
    var onOpen: (ZenTab) -> Void = { _ in }
    var onRenameTab: (String, String) async -> Void = { _, _ in }

    private var columnCount: Int {
        columns.columnCount(forItemCount: tabs.count)
    }

    private var rows: [[ZenTab]] {
        let size = max(1, columnCount)
        return stride(from: 0, to: tabs.count, by: size).map { start in
            Array(tabs[start..<min(start + size, tabs.count)])
        }
    }

    private var tileHeight: CGFloat {
        54
    }

    var body: some View {
        VStack(spacing: 10) {
            ForEach(rows, id: \.first?.id) { row in
                HStack(spacing: 10) {
                    ForEach(row) { tab in
                        EssentialTile(
                            tab: tab,
                            height: tileHeight,
                            scheme: scheme,
                            onOpen: { onOpen(tab) },
                            onRename: { label in await onRenameTab(tab.id, label) }
                        )
                    }
                }
            }
        }
        .padding(.horizontal, 20)
    }
}

private struct EssentialTile: View {
    let tab: ZenTab
    let height: CGFloat
    let scheme: ColorScheme
    var onOpen: () -> Void = {}
    var onRename: (String) async -> Void = { _ in }

    @State private var showingRename = false
    @State private var renameDraft = ""

    var body: some View {
        Button(action: onOpen) {
            let shape = RoundedRectangle(cornerRadius: Palette.liftCornerRadius, style: .continuous)
            ZStack {
                Color.clear
                    .modifier(DomedLiftFill(shape: shape, scheme: scheme))
                ZenTabIcon(tab: tab, size: 24, scheme: scheme)
            }
            .frame(maxWidth: .infinity)
            .frame(height: height)
            .glassRim(cornerRadius: Palette.liftCornerRadius)
            .contentShape(shape)
        }
        .buttonStyle(LiquidPressButtonStyle())
        .contextMenu {
            Button {
                let label = tab.staticLabel?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                renameDraft = label.isEmpty ? tab.title.trimmingCharacters(in: .whitespacesAndNewlines) : label
                showingRename = true
            } label: {
                Label(String(localized: "tabs.rename"), systemImage: "pencil")
            }
        }
        .alert(String(localized: "tabs.rename_title"), isPresented: $showingRename) {
            TextField(String(localized: "tabs.rename_placeholder"), text: $renameDraft)
            Button("common.cancel", role: .cancel) {}
            Button("tabs.rename") {
                let value = renameDraft
                Task { await onRename(value) }
            }
        }
        .accessibilityLabel(Text(tab.displayTitle))
    }
}

// MARK: - Liquid feel without the glass material

/// The lift fill with a very slight dome: narrow soft bands along the top and
/// bottom edges, blended so they keep the page's hue. The middle stays the
/// exact lift color. Flat while the debug comparison shows the original chrome.
struct DomedLiftFill<S: Shape>: ViewModifier {
    let shape: S
    let scheme: ColorScheme
    @Environment(\.zenCompareOriginalStyle) private var compareOriginal

    private var dome: LinearGradient {
        let isDark = scheme == .dark
        return LinearGradient(
            stops: [
                .init(color: .white.opacity(isDark ? 0.07 : 0.1), location: 0),
                .init(color: .white.opacity(0), location: 0.24),
                .init(color: .black.opacity(0), location: 0.76),
                .init(color: .black.opacity(isDark ? 0.07 : 0.05), location: 1),
            ],
            startPoint: .top,
            endPoint: .bottom
        )
    }

    func body(content: Content) -> some View {
        content.background {
            if compareOriginal {
                shape.fill(Palette.lift(scheme))
            } else {
                shape.fill(Palette.lift(scheme))
                    .overlay(shape.fill(dome).blendMode(.softLight))
            }
        }
    }
}

/// Moves like Liquid Glass without looking like it: the surface swells the
/// moment a finger lands, stretches toward a dragging finger and wobbles back
/// on release. Reduce Motion and the debug comparison keep the plain press.
struct LiquidPressButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        LiquidPressBody(configuration: configuration)
    }
}

private struct LiquidPressBody: View {
    let configuration: ButtonStyleConfiguration

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.zenCompareOriginalStyle) private var compareOriginal
    @State private var isTouching = false
    @State private var drag: CGSize = .zero
    @State private var size: CGSize = .zero

    /// Points added to width and height, so a pill and a circle swell alike.
    private static let grow: CGFloat = 3
    private static let maxShift: CGFloat = 4

    private var isDown: Bool { configuration.isPressed || isTouching }

    /// Follows the finger at first, then resists up to `maxShift`.
    private func rubberBand(_ value: CGFloat) -> CGFloat {
        let shift = Self.maxShift * (1 - 1 / (1 + abs(value) / (Self.maxShift * 4)))
        return value < 0 ? -shift : shift
    }

    var body: some View {
        if reduceMotion || compareOriginal {
            configuration.label.pressedFeedback(
                isPressed: configuration.isPressed,
                reduceMotion: reduceMotion
            )
        } else if #available(iOS 18.0, *) {
            liquid.gesture(LiquidTouchTracker { translation in
                isTouching = translation != nil
                drag = translation ?? .zero
            })
        } else {
            liquid
        }
    }

    private var liquid: some View {
        let shiftX = rubberBand(drag.width)
        let shiftY = rubberBand(drag.height)
        let grow = isDown ? Self.grow : 0
        // Stretching one way narrows the other, like a drop of liquid.
        let scaleX = 1 + (grow + abs(shiftX) - abs(shiftY) * 0.5) / max(size.width, 1)
        let scaleY = 1 + (grow + abs(shiftY) - abs(shiftX) * 0.5) / max(size.height, 1)

        return configuration.label
            .onGeometryChange(for: CGSize.self) { $0.size } action: { size = $0 }
            .scaleEffect(x: scaleX, y: scaleY)
            .offset(x: shiftX, y: shiftY)
            // The press spring is innermost so it wins on release.
            .animation(.spring(response: 0.36, dampingFraction: 0.72), value: isDown)
            .animation(.interactiveSpring(response: 0.18, dampingFraction: 0.8), value: drag)
    }
}

/// Reports a finger's travel, or nil once it is gone, without ever claiming the
/// touch: buttons keep working and a scroll view's pan ends the tracking.
@available(iOS 18.0, *)
private struct LiquidTouchTracker: UIGestureRecognizerRepresentable {
    let onChange: (CGSize?) -> Void

    func makeUIGestureRecognizer(context: Context) -> PassiveTouchRecognizer {
        let recognizer = PassiveTouchRecognizer(target: nil, action: nil)
        recognizer.onChange = onChange
        return recognizer
    }

    func updateUIGestureRecognizer(_ recognizer: PassiveTouchRecognizer, context: Context) {
        recognizer.onChange = onChange
    }
}

private final class PassiveTouchRecognizer: UIGestureRecognizer {
    var onChange: (CGSize?) -> Void = { _ in }
    private var touch: UITouch?
    private var start: CGPoint = .zero

    override init(target: Any?, action: Selector?) {
        super.init(target: target, action: action)
        cancelsTouchesInView = false
        delaysTouchesBegan = false
        delaysTouchesEnded = false
    }

    override func canPrevent(_ preventedGestureRecognizer: UIGestureRecognizer) -> Bool { false }

    override func canBePrevented(by preventingGestureRecognizer: UIGestureRecognizer) -> Bool {
        preventingGestureRecognizer is UIPanGestureRecognizer
            && preventingGestureRecognizer.view is UIScrollView
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent) {
        guard touch == nil, let first = touches.first else { return }
        touch = first
        start = first.location(in: nil)
        onChange(.zero)
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent) {
        guard let touch, touches.contains(touch) else { return }
        let point = touch.location(in: nil)
        onChange(CGSize(width: point.x - start.x, height: point.y - start.y))
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent) {
        guard let touch, touches.contains(touch) else { return }
        state = .failed
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent) {
        guard let touch, touches.contains(touch) else { return }
        state = .failed
    }

    override func reset() {
        super.reset()
        guard touch != nil else { return }
        touch = nil
        onChange(nil)
    }
}

struct SquircleButtonStyle: ButtonStyle {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func makeBody(configuration: Configuration) -> some View {
        configuration.label.pressedFeedback(
            isPressed: configuration.isPressed,
            reduceMotion: reduceMotion
        )
    }
}
