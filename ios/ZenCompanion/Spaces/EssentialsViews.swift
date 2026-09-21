import SwiftUI

// MARK: - Essentials grid (icon tiles adaptive columns: 1–4: single row, 5–6: 3 cols, 7+: 4 cols)

struct EssentialsGrid: View {
    let tabs: [ZenTab]
    let scheme: ColorScheme
    var onOpen: (ZenTab) -> Void = { _ in }

    private var columnCount: Int {
        switch tabs.count {
        case 1: return 1
        case 2: return 2
        case 3: return 3
        case 4: return 4
        case 5, 6: return 3
        case 7, 8: return 4
        default: return 4
        }
    }

    private var tileHeight: CGFloat {
        54
    }

    private var columns: [GridItem] {
        Array(repeating: GridItem(.flexible(), spacing: 10), count: columnCount)
    }

    var body: some View {
        LazyVGrid(columns: columns, spacing: 10) {
            ForEach(tabs) { tab in
                EssentialTile(
                    tab: tab,
                    height: tileHeight,
                    scheme: scheme,
                    onOpen: { onOpen(tab) }
                )
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

    var body: some View {
        Button(action: onOpen) {
            let shape = RoundedRectangle(cornerRadius: 13, style: .continuous)
            ZStack {
                shape
                    .fill(Palette.lift(scheme))
                ZenTabIcon(tab: tab, size: 24, scheme: scheme)
            }
            .frame(maxWidth: .infinity)
            .frame(height: height)
            .contentShape(shape)
        }
        .buttonStyle(SquircleButtonStyle())
        .accessibilityLabel(Text(tab.title.isEmpty ? tab.url : tab.title))
    }
}

struct SquircleButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.95 : 1.0)
            .opacity(configuration.isPressed ? 0.85 : 1.0)
            .animation(.spring(response: 0.25, dampingFraction: 0.75), value: configuration.isPressed)
    }
}
