import SwiftUI
import UIKit

/// A pin destination: a space plus an optional folder inside that space.
/// Nil/empty `folderId` means the space root.
struct PinDestination: Equatable, Hashable {
    let spaceId: String
    var folderId: String?

    init(spaceId: String, folderId: String? = nil) {
        self.spaceId = spaceId
        self.folderId = (folderId?.isEmpty ?? true) ? nil : folderId
    }
}

/// Pure helpers over the synced snapshot: no SwiftUI, no networking — the
/// same shape an Android implementation would consume.
enum PinDestinationModel {
    static func folders(in space: ZenSpace) -> [ZenFolder] {
        space.pinned.compactMap { item in
            if case .folder(let folder) = item { return folder }
            return nil
        }
    }

    /// One folder row for the menu: name plus nesting depth for indentation.
    struct FolderEntry: Equatable {
        let folderId: String
        let name: String
        let depth: Int
    }

    /// Depth-first walk of the space's folder tree: outer folders first,
    /// nested folders after their parent. Cycles are guarded by the
    /// visited set.
    static func folderEntries(in space: ZenSpace) -> [FolderEntry] {
        var out: [FolderEntry] = []
        var visited = Set<String>()

        func walk(_ folders: [ZenFolder], depth: Int) {
            for folder in folders where visited.insert(folder.id).inserted {
                out.append(FolderEntry(folderId: folder.id, name: folder.name, depth: depth))
                walk(folder.subfolders ?? [], depth: depth + 1)
            }
        }
        walk(folders(in: space), depth: 0)
        return out
    }

    /// Human-readable label: space name for the root, folder name otherwise.
    static func displayName(destination: PinDestination, in space: ZenSpace?) -> String {
        guard let space else { return destination.folderId ?? "" }
        guard let folderId = destination.folderId, !folderId.isEmpty else {
            return space.name
        }
        return folderName(folderId: folderId, in: space) ?? folderId
    }

    static func folderName(folderId: String, in space: ZenSpace) -> String? {
        func search(_ folders: [ZenFolder]) -> ZenFolder? {
            for folder in folders {
                if folder.id == folderId { return folder }
                if let nested = search(folder.subfolders ?? []) { return nested }
            }
            return nil
        }
        return search(folders(in: space))?.name
    }
}

/// Where a pinned tab may be moved inside its own space. Normal tabs never
/// carry a `folderId`, and rows that do not rename (splits) do not move.
enum TabMove {
    struct Destination: Equatable, Identifiable {
        /// Nil is the space root.
        let folderId: String?
        let name: String
        /// Folder nesting depth from the tree walk. The space-root row is 0.
        let depth: Int
        let isCurrent: Bool

        var id: String { folderId ?? "" }
    }

    /// Move is offered only on rows that already rename, and never on a normal tab.
    static func offersMove(renames: Bool, isNormalTab: Bool) -> Bool {
        renames && !isNormalTab
    }

    static func normalize(_ folderId: String?) -> String? {
        guard let folderId, !folderId.isEmpty else { return nil }
        return folderId
    }

    static func samePlace(currentFolderId: String?, selectedFolderId: String?) -> Bool {
        normalize(currentFolderId) == normalize(selectedFolderId)
    }

    /// Selecting the current place must not write.
    static func shouldWrite(currentFolderId: String?, selectedFolderId: String?) -> Bool {
        !samePlace(currentFolderId: currentFolderId, selectedFolderId: selectedFolderId)
    }

    /// Space root first, then this space's folders depth-first (nested included).
    static func destinations(
        spaceName: String,
        folders: [PinDestinationModel.FolderEntry],
        currentFolderId: String?
    ) -> [Destination] {
        let current = normalize(currentFolderId)
        var rows = [
            Destination(folderId: nil, name: spaceName, depth: 0, isCurrent: current == nil),
        ]
        for folder in folders {
            rows.append(Destination(
                folderId: folder.folderId,
                name: folder.name,
                depth: folder.depth,
                isCurrent: folder.folderId == current
            ))
        }
        return rows
    }
}

/// The "Pin to Folder…" submenu, shared with moving a tab inside one space.
///
/// Rendered shape, per space: the space name first (its icon, no checkmark —
/// the root marker lives on the main space list), then that space's folders
/// as indented plain-text rows, outer folders before nested children. The
/// current folder carries the menu checkmark. Declaration order is reversed
/// because these context menus render children backwards.
///
/// `markRoot` puts the checkmark on the space row when the destination is the
/// root. The pin menu leaves it off (`false`) because that marker is already
/// on the space list. A move menu has no separate space list, so it passes
/// `true` and the current place — root or folder — stays visible.
enum PinDestinationMenus {
    static func folderSubmenu(
        spaces: [ZenSpace],
        destination: PinDestination,
        scheme: ColorScheme,
        title: String,
        image: UIImage? = UIImage(systemName: "folder.badge.plus"),
        markRoot: Bool = false,
        /// Move to folder wants the folder rows flipped: the menu paints
        /// children backwards, so declaring them in snapshot order puts the
        /// last folder under the space name. The pin menu leaves this off.
        reverseFolders: Bool = false,
        onSelect: @escaping (PinDestination) -> Void
    ) -> UIMenu? {
        var groups: [UIMenuElement] = []
        for space in spaces.reversed() {
            let entries = PinDestinationModel.folderEntries(in: space)
            guard !entries.isEmpty else { continue }
            // Pin menu: folders are declared reversed and the space name last,
            // because that menu paints children backwards (space, then folders).
            // Move to folder paints in declaration order, so the space name is
            // declared first and the flipped folder rows follow it.
            let declared = reverseFolders ? entries : entries.reversed()
            let folderRows: [UIMenuElement] = declared.map { entry in
                let indent = String(repeating: "\u{2003}\u{2003}", count: entry.depth)
                let name = entry.name.isEmpty ? String(localized: "tabs.folder") : entry.name
                let isSelected = space.id == destination.spaceId && destination.folderId == entry.folderId
                return UIAction(
                    title: indent + name,
                    state: isSelected ? .on : .off
                ) { _ in
                    onSelect(PinDestination(spaceId: space.id, folderId: entry.folderId))
                }
            }
            let atRoot = destination.folderId?.isEmpty ?? true
            let spaceRow = UIAction(
                title: space.name,
                image: SpaceMenuIcon.image(for: space.icon, scheme: scheme),
                state: (markRoot && space.id == destination.spaceId && atRoot) ? .on : .off
            ) { _ in
                onSelect(PinDestination(spaceId: space.id))
            }
            let rows = reverseFolders ? [spaceRow] + folderRows : folderRows + [spaceRow]
            groups.append(UIMenu(options: .displayInline, children: rows))
        }
        guard !groups.isEmpty else { return nil }
        return UIMenu(title: title, image: image, children: groups)
    }
}

/// Native context-menu button for choosing a pin destination. Uses UIKit's
/// `UIContextMenuInteraction` directly (instead of SwiftUI `Menu`) so hosts
/// get `onMenuWillOpen` / `onMenuDidDismiss` lifecycle callbacks (e.g. to
/// pause an auto-dismiss timer) and so the button sizes its title natively,
/// avoiding the SwiftUI label truncation when the selected title length
/// changes.
///
/// Layout intent (as rendered): the space list sits directly in the main
/// menu with a divider and the "Pin to Folder…" submenu at the bottom; inside
/// that submenu each space leads with its name, then its folders as indented
/// plain-text rows, outer folders before their nested children. The space
/// name inside the folder submenu carries no checkmark — the selection
/// marker lives only in the main menu.
///
/// IMPORTANT — inverted declaration: on the target iOS versions the context
/// menu renders its children in REVERSE of the declared order, so every level
/// below is declared reversed (submenu first, spaces reversed, folders
/// reversed, space header last) so the rendered output matches the intent.
struct PinDestinationMenuButton: UIViewRepresentable {
    let destination: PinDestination
    let spaces: [ZenSpace]
    let scheme: ColorScheme
    var titleSize: CGFloat = 15
    /// Normal-tab saves always land at the space root, so the "Pin to Folder…"
    /// submenu is hidden when false.
    var allowsFolders: Bool = true
    /// Overrides the button title/icon ink (e.g. when the host capsule is
    /// brand-tinted and the scheme-derived ink would not contrast).
    var inkColor: UIColor? = nil
    let onSelect: (PinDestination) -> Void
    var onMenuWillOpen: () -> Void = {}
    var onMenuDidDismiss: () -> Void = {}

    func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }

    func makeUIView(context: Context) -> UIButton {
        let button = PinMenuButton()
        button.coordinator = context.coordinator
        // Tap presents the menu; the subclass reports open/dismiss lifecycle
        // itself (a separate UIContextMenuInteraction's delegates do NOT fire
        // reliably when `button.menu` + `showsMenuAsPrimaryAction` drive the
        // presentation).
        button.showsMenuAsPrimaryAction = true
        button.menu = context.coordinator.makeMenu()
        button.setContentHuggingPriority(.required, for: .horizontal)
        button.setContentCompressionResistancePriority(.required, for: .horizontal)
        updateButton(button, context: context)
        return button
    }

    func updateUIView(_ uiView: UIButton, context: Context) {
        context.coordinator.parent = self
        updateButton(uiView, context: context)
        // Refresh the menu only while it is NOT presented: rebuilding it
        // while open (e.g. on WebView progress re-renders) would close it.
        if let pinButton = uiView as? PinMenuButton, !pinButton.isMenuPresented {
            uiView.menu = context.coordinator.makeMenu()
        }
    }

    private func updateButton(_ button: UIButton, context: Context) {
        var config = UIButton.Configuration.plain()
        config.contentInsets = NSDirectionalEdgeInsets(top: 0, leading: 0, bottom: 0, trailing: 0)

        let fallbackInk = scheme == .dark ? UIColor.white : UIColor(red: 0.1, green: 0.1, blue: 0.12, alpha: 1.0)
        let ink = inkColor ?? fallbackInk
        let currentSpace = spaces.first(where: { $0.id == destination.spaceId })
        let hasFolder = !(destination.folderId?.isEmpty ?? true)

        let title: String
        if let currentSpace {
            let label = PinDestinationModel.displayName(destination: destination, in: currentSpace)
            title = label == currentSpace.name ? currentSpace.name : "\(currentSpace.name) · \(label)"
        } else {
            title = String(localized: "share.workspace")
        }

        var titleAttr = AttributedString(title)
        titleAttr.font = .systemFont(ofSize: titleSize, weight: .semibold)
        titleAttr.foregroundColor = ink
        config.attributedTitle = titleAttr

        let icon: UIImage?
        if hasFolder {
            icon = UIImage(systemName: "folder.fill")?.withTintColor(ink, renderingMode: .alwaysOriginal)
        } else if let spaceIcon = currentSpace?.icon, !spaceIcon.isEmpty {
            icon = SpaceMenuIcon.image(for: spaceIcon, scheme: scheme, inkColor: ink)
        } else {
            icon = nil
        }
        let chevron = UIImage(systemName: "chevron.up.chevron.down")?
            .withTintColor(ink.withAlphaComponent(0.5), renderingMode: .alwaysOriginal)
            .withConfiguration(UIImage.SymbolConfiguration(pointSize: 10, weight: .bold))
        if let composed = Self.compose(icon: icon, chevron: chevron, gap: 7) {
            config.image = composed
            config.imagePadding = 7
        }

        button.configuration = config
    }

    /// Lays the leading icon and the trailing chevron into one strip, since
    /// UIButton.Configuration carries a single image.
    private static func compose(icon: UIImage?, chevron: UIImage?, gap: CGFloat) -> UIImage? {
        guard icon != nil || chevron != nil else { return nil }
        let height: CGFloat = 24
        let iconSize = icon?.size ?? .zero
        let chevronSize = chevron?.size ?? .zero
        let width = iconSize.width + (icon != nil && chevron != nil ? gap : 0) + chevronSize.width
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: max(width, 1), height: height))
        return renderer.image { _ in
            if let icon {
                icon.draw(in: CGRect(x: 0, y: (height - iconSize.height) / 2, width: iconSize.width, height: iconSize.height))
            }
            if let chevron {
                chevron.draw(in: CGRect(x: iconSize.width + gap, y: (height - chevronSize.height) / 2, width: chevronSize.width, height: chevronSize.height))
            }
        }
    }

    final class Coordinator: NSObject {
        var parent: PinDestinationMenuButton
        /// True while the context menu is on screen; guards menu rebuilds
        /// during `updateUIView` (rebuilding while open closes the menu).
        var isMenuPresented = false

        init(_ parent: PinDestinationMenuButton) {
            self.parent = parent
        }

        func menuDidOpen() {
            isMenuPresented = true
            parent.onMenuWillOpen()
        }

        func menuDidDismiss() {
            isMenuPresented = false
            parent.onMenuDidDismiss()
        }

        func makeMenu() -> UIMenu {
            let destination = parent.destination

            // Spaces of the main menu. Declared reversed → renders forward.
            let spaceActions: [UIMenuElement] = parent.spaces.reversed().map { space in
                let isSelected = space.id == destination.spaceId && (destination.folderId?.isEmpty ?? true)
                return UIAction(
                    title: space.name,
                    image: SpaceMenuIcon.image(for: space.icon, scheme: parent.scheme),
                    state: isSelected ? .on : .off
                ) { [weak self] _ in
                    self?.parent.onSelect(PinDestination(spaceId: space.id))
                }
            }
            let spaceSection = UIMenu(options: .displayInline, children: spaceActions)

            // Main menu. Declared [folder submenu, divider section, spaces]
            // → renders [spaces, divider, folder submenu]. The folder submenu
            // is the same menu a tab uses to move inside its space.
            var children: [UIMenuElement] = []
            if parent.allowsFolders,
               let folders = PinDestinationMenus.folderSubmenu(
                spaces: parent.spaces,
                destination: destination,
                scheme: parent.scheme,
                title: String(localized: "pin.folder_menu"),
                onSelect: { [weak self] chosen in
                    self?.parent.onSelect(chosen)
                }
               ) {
                children.append(folders)
            }
            children.append(spaceSection)
            return UIMenu(children: children)
        }
    }
}

/// UIButton subclass that observes its own context-menu lifecycle. When the
/// system presents `button.menu` (via `showsMenuAsPrimaryAction`), UIKit
/// creates an internal `UIContextMenuInteraction` whose delegate is the
/// button itself — overriding these methods is the reliable way to learn
/// when the menu opens and closes.
class PinMenuButton: UIButton {
    weak var coordinator: PinDestinationMenuButton.Coordinator?
    var isMenuPresented = false

    override func contextMenuInteraction(_ interaction: UIContextMenuInteraction, willDisplayMenuFor configuration: UIContextMenuConfiguration, animator: UIContextMenuInteractionAnimating?) {
        super.contextMenuInteraction(interaction, willDisplayMenuFor: configuration, animator: animator)
        isMenuPresented = true
        coordinator?.menuDidOpen()
    }

    override func contextMenuInteraction(_ interaction: UIContextMenuInteraction, willEndFor configuration: UIContextMenuConfiguration, animator: UIContextMenuInteractionAnimating?) {
        super.contextMenuInteraction(interaction, willEndFor: configuration, animator: animator)
        isMenuPresented = false
        animator?.addCompletion { [weak self] in
            self?.coordinator?.menuDidDismiss()
        }
    }
}
