import SwiftUI
import UIKit

/// Per-save pinned/normal choice, next to the destination.
///
/// The same control in the share sheet and in the mini-browser's save row: a
/// round menu that shows the current state and opens the choice. It only ever
/// applies to the save it belongs to; leaving it alone keeps the persisted
/// "save shared tabs as" setting.
///
/// A `UIButton` with its own menu rather than a SwiftUI `Menu`, so the host
/// learns when the menu closes again: the save confirmation's timer stops
/// while the choice is on screen and only restarts from `onMenuDidDismiss`.
struct SaveKindMenuButton: UIViewRepresentable {
    /// A round button of its own in a sheet, or the small raised control the
    /// save row uses.
    enum Style { case circle, compact }

    let kind: SaveKind
    let scheme: ColorScheme
    var style: Style = .circle
    var isEnabled: Bool = true
    /// Called as the menu opens, so a host can hold its own timer (the save
    /// confirmation) while the choice is on screen.
    var onOpen: () -> Void = {}
    /// Called once the menu has closed again, whether or not the kind changed.
    var onMenuDidDismiss: () -> Void = {}
    let onSelect: (SaveKind) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }

    func makeUIView(context: Context) -> SaveKindMenuControl {
        let button = SaveKindMenuControl()
        button.coordinator = context.coordinator
        // Tap presents the menu; the subclass reports open and dismiss itself
        // (a separate interaction's delegates do not fire when `button.menu`
        // and `showsMenuAsPrimaryAction` drive the presentation).
        button.showsMenuAsPrimaryAction = true
        button.menu = context.coordinator.makeMenu()
        button.setContentHuggingPriority(.required, for: .horizontal)
        button.setContentCompressionResistancePriority(.required, for: .horizontal)
        apply(to: button)
        return button
    }

    func updateUIView(_ button: SaveKindMenuControl, context: Context) {
        context.coordinator.parent = self
        apply(to: button)
        // Refresh the menu only while it is NOT presented: rebuilding it
        // while open (e.g. on a re-render from the page) would close it.
        if !button.isMenuPresented {
            button.menu = context.coordinator.makeMenu()
        }
    }

    /// Both styles are a 44pt touch target, as the SwiftUI label was.
    func sizeThatFits(
        _ proposal: ProposedViewSize,
        uiView: SaveKindMenuControl,
        context: Context
    ) -> CGSize? {
        CGSize(width: 44, height: 44)
    }

    private func apply(to button: UIButton) {
        let inked = UIColor(Palette.ink(scheme))
        button.isEnabled = isEnabled
        button.tintColor = inked
        button.configuration = configuration(inked: inked)
        button.accessibilityLabel = String(localized: "share.save_as")
        let valueKey: String.LocalizationValue =
            kind == .pinned ? "share.save_kind.pinned" : "share.save_kind.normal"
        button.accessibilityValue = String(localized: valueKey)
        button.accessibilityHint = String(localized: "share.save_kind.hint")
    }

    private func configuration(inked: UIColor) -> UIButton.Configuration {
        var config = UIButton.Configuration.plain()
        config.contentInsets = .zero
        config.image = UIImage(
            systemName: kind == .pinned ? "pin.fill" : "rectangle.stack",
            withConfiguration: UIImage.SymbolConfiguration(
                pointSize: style == .circle ? 15 : 14,
                weight: .semibold
            )
        )
        config.baseForegroundColor = inked
        // The lift tile the whole app uses: a faint fill with one hairline rim.
        config.background.backgroundColor = UIColor(Palette.lift(scheme))
        config.background.strokeColor = UIColor.label.withAlphaComponent(CGFloat(Palette.rimOpacity))
        config.background.strokeWidth = Palette.rimWidth
        switch style {
        case .circle:
            config.background.cornerRadius = 22
        case .compact:
            // 32pt reads right in the row; the rest is the finger's.
            config.background.cornerRadius = 16
            config.background.backgroundInsets = NSDirectionalEdgeInsets(
                top: 6,
                leading: 6,
                bottom: 6,
                trailing: 6
            )
        }
        return config
    }

    final class Coordinator: NSObject {
        var parent: SaveKindMenuButton

        init(_ parent: SaveKindMenuButton) {
            self.parent = parent
        }

        func menuDidOpen() {
            parent.onOpen()
        }

        func menuDidDismiss() {
            parent.onMenuDidDismiss()
        }

        /// The choice, in the order the picker listed it, with the current
        /// kind checked.
        func makeMenu() -> UIMenu {
            let kind = parent.kind
            let actions = [SaveKind.pinned, SaveKind.normal].map { option in
                let key: String.LocalizationValue =
                    option == .pinned ? "share.save_kind.pinned_menu" : "share.save_kind.normal_menu"
                return UIAction(
                    title: String(localized: key),
                    state: option == kind ? .on : .off
                ) { [weak self] _ in
                    self?.parent.onSelect(option)
                }
            }
            return UIMenu(title: String(localized: "share.save_as"), children: actions)
        }
    }
}

/// UIButton subclass that observes its own context-menu lifecycle. When the
/// system presents `button.menu` (via `showsMenuAsPrimaryAction`), UIKit
/// creates an internal `UIContextMenuInteraction` whose delegate is the
/// button itself — overriding these methods is the reliable way to learn
/// when the menu opens and closes.
final class SaveKindMenuControl: UIButton {
    weak var coordinator: SaveKindMenuButton.Coordinator?
    var isMenuPresented = false

    override func contextMenuInteraction(
        _ interaction: UIContextMenuInteraction,
        willDisplayMenuFor configuration: UIContextMenuConfiguration,
        animator: UIContextMenuInteractionAnimating?
    ) {
        super.contextMenuInteraction(
            interaction,
            willDisplayMenuFor: configuration,
            animator: animator
        )
        isMenuPresented = true
        coordinator?.menuDidOpen()
    }

    override func contextMenuInteraction(
        _ interaction: UIContextMenuInteraction,
        willEndFor configuration: UIContextMenuConfiguration,
        animator: UIContextMenuInteractionAnimating?
    ) {
        super.contextMenuInteraction(interaction, willEndFor: configuration, animator: animator)
        isMenuPresented = false
        // UIKit may hand over no animator; the completion would then never
        // run and the host's save confirmation would stay paused.
        guard let animator else {
            coordinator?.menuDidDismiss()
            return
        }
        animator.addCompletion { [weak self] in
            self?.coordinator?.menuDidDismiss()
        }
    }
}
