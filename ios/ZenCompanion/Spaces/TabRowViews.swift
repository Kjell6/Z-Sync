import SwiftUI

// MARK: - One tab row

struct TabRow: View {
    let tab: ZenTab
    let scheme: ColorScheme
    var deletable: Bool
    var onOpen: () -> Void = {}
    var onDelete: () async -> Void = {}

    @State private var deleting = false

    var body: some View {
        Button(action: onOpen) {
            HStack(spacing: 12) {
                ZenTabIcon(tab: tab, size: 28, scheme: scheme)
                Text(displayTitle)
                    .font(.system(size: 17, weight: .medium))
                    .foregroundStyle(Palette.ink(scheme))
                    .lineLimit(1)
                Spacer(minLength: 0)
                if deleting {
                    ProgressView().controlSize(.mini)
                }
            }
            .padding(.vertical, 9)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .contextMenu(deletable ? ContextMenu {
            Button(role: .destructive) {
                Task { await remove() }
            } label: {
                Label(String(localized: "tabs.delete"), systemImage: "trash")
            }
        } : nil)
        .accessibilityHint(Text(tab.url))
    }

    private var displayTitle: String {
        let raw = tab.title.trimmingCharacters(in: .whitespacesAndNewlines)
        if !raw.isEmpty { return raw }
        let host = URL(string: tab.url)?.host ?? ""
        return host.isEmpty ? tab.url : host
    }

    private func remove() async {
        deleting = true
        defer { deleting = false }
        await onDelete()
    }
}

// MARK: - One split row (members side by side, equal width)

struct SplitRow: View {
    let split: ZenSplit
    let scheme: ColorScheme
    var onOpenTab: (ZenTab) -> Void = { _ in }
    var onDelete: () async -> Void = {}

    @State private var deleting = false

    private var displayTitle: String {
        split.tabs.isEmpty ? "" : TabTitle(tab: split.tabs[0]).text
    }

    var body: some View {
        HStack(spacing: 0) {
            ForEach(Array(split.tabs.enumerated()), id: \.element.id) { index, tab in
                if index > 0 {
                    Spacer(minLength: 8)
                    Rectangle()
                        .fill(Palette.ink(scheme).opacity(0.28))
                        .frame(width: 1, height: 24)
                    Spacer(minLength: 8)
                }
                SplitCell(tab: tab, scheme: scheme) { onOpenTab(tab) }
                    .frame(maxWidth: .infinity)
            }
        }
        .contextMenu {
            if deleting {
                Button(role: .destructive) {} label: {
                    Label("tabs.delete", systemImage: "trash")
                }
                .disabled(true)
            } else {
                Button(role: .destructive) {
                    Task { await remove() }
                } label: {
                    Label(String(localized: "tabs.unsplit"), systemImage: "rectangle.split.2x1")
                }
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel(Text(accessibilitySummary))
    }

    private var accessibilitySummary: String {
        let names = split.tabs.map { $0.title.isEmpty ? $0.url : $0.title }.joined(separator: ", ")
        return names.isEmpty ? displayTitle : names
    }

    private func remove() async {
        deleting = true
        defer { deleting = false }
        await onDelete()
    }
}

/// One equal-width pane inside a split row: favicon + faded-out title.
/// Icon and text sizing mirror a normal `TabRow` so split rows read at the
/// same visual weight — they just hold several panes side by side.
private struct SplitCell: View {
    let tab: ZenTab
    let scheme: ColorScheme
    var onOpen: () -> Void = {}

    var body: some View {
        Button(action: onOpen) {
            HStack(spacing: 12) {
                ZenTabIcon(tab: tab, size: 28, scheme: scheme)
                FadeOutTitle(
                    text: TabTitle(tab: tab).text,
                    scheme: scheme,
                    font: .system(size: 17, weight: .medium)
                )
            }
            .padding(.vertical, 9)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(TabTitle(tab: tab).text))
        .accessibilityHint(Text(tab.url))
    }
}

/// Single-line title truncated with an ellipsis when it overflows, exactly
/// like a regular `TabRow`.
private struct FadeOutTitle: View {
    let text: String
    let scheme: ColorScheme
    var font: Font = .system(size: 15, weight: .medium)

    var body: some View {
        Text(text)
            .font(font)
            .foregroundStyle(Palette.ink(scheme))
            .lineLimit(1)
            .truncationMode(.tail)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
    }
}

/// Title text helper shared by rows and split cells (mirrors `TabRow.displayTitle`).
private struct TabTitle {
    let text: String

    init(tab: ZenTab) {
        let raw = tab.title.trimmingCharacters(in: .whitespacesAndNewlines)
        if !raw.isEmpty {
            text = raw
        } else {
            let host = URL(string: tab.url)?.host ?? ""
            text = host.isEmpty ? tab.url : host
        }
    }
}
