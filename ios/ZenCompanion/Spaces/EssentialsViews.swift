import SwiftUI

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
                shape
                    .fill(Palette.lift(scheme))
                ZenTabIcon(tab: tab, size: 24, scheme: scheme)
            }
            .frame(maxWidth: .infinity)
            .frame(height: height)
            .glassRim(cornerRadius: Palette.liftCornerRadius)
            .contentShape(shape)
        }
        .buttonStyle(SquircleButtonStyle())
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

struct SquircleButtonStyle: ButtonStyle {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func makeBody(configuration: Configuration) -> some View {
        configuration.label.pressedFeedback(
            isPressed: configuration.isPressed,
            reduceMotion: reduceMotion
        )
    }
}
