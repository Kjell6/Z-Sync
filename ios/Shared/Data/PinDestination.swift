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

            // Folder submenu. Declared reversed → renders forward. Hidden for
            // normal-tab saves, which ignore the folder.
            var folderGroups: [UIMenuElement] = []
            if parent.allowsFolders {
                for space in parent.spaces.reversed() {
                    let entries = PinDestinationModel.folderEntries(in: space)
                    guard !entries.isEmpty else { continue }
                    // Declared [folders reversed, header] → renders [header, folders].
                    var group: [UIMenuElement] = entries.reversed().map { entry in
                        let indent = String(repeating: "\u{2003}\u{2003}", count: entry.depth)
                        let name = entry.name.isEmpty ? String(localized: "tabs.folder") : entry.name
                        let isSelected = space.id == destination.spaceId && destination.folderId == entry.folderId
                        return UIAction(
                            title: indent + name,
                            state: isSelected ? .on : .off
                        ) { [weak self] _ in
                            self?.parent.onSelect(PinDestination(spaceId: space.id, folderId: entry.folderId))
                        }
                    }
                    // Space header (no checkmark). Declared last → renders first.
                    group.append(UIAction(
                        title: space.name,
                        image: SpaceMenuIcon.image(for: space.icon, scheme: parent.scheme)
                    ) { [weak self] _ in
                        self?.parent.onSelect(PinDestination(spaceId: space.id))
                    })
                    folderGroups.append(UIMenu(options: .displayInline, children: group))
                }
            }

            // Main menu. Declared [folder submenu, divider section, spaces]
            // → renders [spaces, divider, folder submenu].
            var children: [UIMenuElement] = []
            if !folderGroups.isEmpty {
                children.append(UIMenu(
                    title: String(localized: "pin.folder_menu"),
                    image: UIImage(systemName: "folder.badge.plus"),
                    children: folderGroups
                ))
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
final class PinMenuButton: UIButton {
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
