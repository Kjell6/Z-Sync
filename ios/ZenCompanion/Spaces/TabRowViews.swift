import SwiftUI

// MARK: - One tab row

struct TabRow: View {
    let tab: ZenTab
    let scheme: ColorScheme
    var deletable: Bool
    /// Normal (unpinned) tabs never carry a `folderId`, so they rename but do not move.
    var isNormalTab: Bool = false
    /// Nil when the tab sits at the space root. Marked in the picker and not written.
    var currentFolderId: String? = nil
    var spaceName: String = ""
    var moveFolders: [PinDestinationModel.FolderEntry] = []
    var onOpen: () -> Void = {}
    var onDelete: () async -> Void = {}
    var onRename: (String) async -> Void = { _ in }
    var onMove: (String?) async -> Void = { _ in }

    @State private var deleting = false
    @State private var showingRename = false
    @State private var renameDraft = ""
    @State private var showingMove = false

    var body: some View {
        Button(action: onOpen) {
            HStack(spacing: 12) {
                ZenTabIcon(tab: tab, size: 28, scheme: scheme)
                Text(tab.displayTitle)
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
        .contextMenu {
            Button {
                renameDraft = renameSeed
                showingRename = true
            } label: {
                Label(String(localized: "tabs.rename"), systemImage: "pencil")
            }
            if offersMove {
                Button {
                    showingMove = true
                } label: {
                    Label(String(localized: "tabs.move"), systemImage: "folder")
                }
            }
            if deletable {
                Button(role: .destructive) {
                    Task { await remove() }
                } label: {
                    Label(String(localized: "tabs.delete"), systemImage: "trash")
                }
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
        .confirmationDialog(
            String(localized: "tabs.move_title"),
            isPresented: $showingMove,
            titleVisibility: .visible
        ) {
            ForEach(moveDestinations) { destination in
                Button(moveButtonTitle(destination)) {
                    guard TabMove.shouldWrite(
                        currentFolderId: currentFolderId,
                        selectedFolderId: destination.folderId
                    ) else { return }
                    let folderId = destination.folderId
                    Task { await onMove(folderId) }
                }
                .disabled(destination.isCurrent)
            }
            Button("common.cancel", role: .cancel) {}
        }
        .accessibilityHint(Text(tab.url))
    }

    /// Pinned and folder rows that have somewhere else to land. A root tab in a
    /// space with no folders has only its current place, so the action stays hidden.
    private var offersMove: Bool {
        guard TabMove.offersMove(renames: true, isNormalTab: isNormalTab) else { return false }
        return moveDestinations.contains { !$0.isCurrent }
    }

    private var moveDestinations: [TabMove.Destination] {
        TabMove.destinations(
            spaceName: spaceName,
            folders: moveFolders,
            currentFolderId: currentFolderId
        )
    }

    private func moveButtonTitle(_ destination: TabMove.Destination) -> String {
        let trimmed = destination.name.trimmingCharacters(in: .whitespacesAndNewlines)
        let name: String
        if !trimmed.isEmpty {
            name = trimmed
        } else if destination.folderId == nil {
            name = String(localized: "share.workspace")
        } else {
            name = String(localized: "tabs.folder")
        }
        let indent = String(repeating: "– ", count: destination.depth)
        let mark = destination.isCurrent ? " ✓" : ""
        return indent + name + mark
    }

    private var renameSeed: String {
        let label = tab.staticLabel?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if !label.isEmpty { return label }
        return tab.title.trimmingCharacters(in: .whitespacesAndNewlines)
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
        split.tabs.isEmpty ? "" : split.tabs[0].displayTitle
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
        let names = split.tabs.map(\.displayTitle).joined(separator: ", ")
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
                    text: tab.displayTitle,
                    scheme: scheme,
                    font: .system(size: 17, weight: .medium)
                )
            }
            .padding(.vertical, 9)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(tab.displayTitle))
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


