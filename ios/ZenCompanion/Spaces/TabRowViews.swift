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
    /// Space the tab already lives in. Move stays inside this space and reuses
    /// the share sheet's folder menu. Nil hides the action (normal tabs).
    var moveSpace: ZenSpace? = nil
    var onOpen: () -> Void = {}
    var onDelete: () async -> Void = {}
    var onRename: (String) async -> Void = { _ in }
    var onMove: (String?) async -> Void = { _ in }
    /// Pins the tab to its space root. Only offered on normal tabs.
    var onPin: () async -> Void = {}

    @State private var deleting = false
    @State private var showingRename = false
    @State private var renameDraft = ""

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
        .buttonStyle(PressedButtonStyle())
        .contextMenu {
            if isNormalTab {
                Button {
                    Task { await onPin() }
                } label: {
                    Label(String(localized: "tabs.pin"), systemImage: "pin")
                }
            }
            Button {
                renameDraft = renameSeed
                showingRename = true
            } label: {
                Label(String(localized: "tabs.rename"), systemImage: "pencil")
            }
            if let moveSpace, offersMove(in: moveSpace) {
                Menu {
                    moveDestinations(in: moveSpace)
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
                .disabled(deleting)
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
        .accessibilityHint(Text(tab.url))
    }

    /// Space name first, then folders in sidebar order. A folder that contains
    /// other folders opens that next level, in the same order as the sidebar.
    @ViewBuilder
    private func moveDestinations(in space: ZenSpace) -> some View {
        let spaceName = space.name.isEmpty ? String(localized: "share.workspace") : space.name
        let atRoot = currentFolderId?.isEmpty ?? true
        Button {
            guard TabMove.shouldWrite(currentFolderId: currentFolderId, selectedFolderId: nil) else { return }
            Task { await onMove(nil) }
        } label: {
            if atRoot {
                Label(spaceName, systemImage: "checkmark")
            } else {
                Text(spaceName)
            }
        }
        MoveFolderDestinations(
            folders: PinDestinationModel.folders(in: space),
            currentFolderId: currentFolderId,
            onMove: onMove
        )
    }

    /// Pinned and folder rows that have somewhere else to land. A root tab in a
    /// space with no folders has only its current place, so the action stays hidden.
    private func offersMove(in space: ZenSpace) -> Bool {
        guard TabMove.offersMove(renames: true, isNormalTab: isNormalTab) else { return false }
        let name = space.name.isEmpty ? String(localized: "share.workspace") : space.name
        return TabMove.destinations(
            spaceName: name,
            folders: PinDestinationModel.folderEntries(in: space),
            currentFolderId: currentFolderId
        ).contains { !$0.isCurrent }
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

/// One level of the move menu. A folder with children opens the next level,
/// and its own row is the first item there, so the tab can still land in it.
private struct MoveFolderDestinations: View {
    let folders: [ZenFolder]
    let currentFolderId: String?
    let onMove: (String?) async -> Void

    var body: some View {
        ForEach(folders) { folder in
            let children = folder.subfolders ?? []
            if children.isEmpty {
                destinationButton(folder)
            } else {
                Menu {
                    destinationButton(folder)
                    MoveFolderDestinations(
                        folders: children,
                        currentFolderId: currentFolderId,
                        onMove: onMove
                    )
                } label: {
                    if TabMove.samePlace(currentFolderId: currentFolderId, selectedFolderId: folder.id) {
                        Label(Self.displayName(folder), systemImage: "checkmark")
                    } else {
                        Text(Self.displayName(folder))
                    }
                }
            }
        }
    }

    private func destinationButton(_ folder: ZenFolder) -> some View {
        let current = TabMove.samePlace(currentFolderId: currentFolderId, selectedFolderId: folder.id)
        return Button {
            guard TabMove.shouldWrite(
                currentFolderId: currentFolderId,
                selectedFolderId: folder.id
            ) else { return }
            let folderId = folder.id
            Task { await onMove(folderId) }
        } label: {
            if current {
                Label(Self.displayName(folder), systemImage: "checkmark")
            } else {
                Text(Self.displayName(folder))
            }
        }
    }

    private static func displayName(_ folder: ZenFolder) -> String {
        folder.name.isEmpty ? String(localized: "tabs.folder") : folder.name
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
        .buttonStyle(PressedButtonStyle())
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




