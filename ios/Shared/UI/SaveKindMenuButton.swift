import SwiftUI

/// Per-save pinned/normal choice, next to the destination.
///
/// The same control in the share sheet and in the mini-browser's save row: a
/// round menu that shows the current state and opens the choice. It only ever
/// applies to the save it belongs to; leaving it alone keeps the persisted
/// "save shared tabs as" setting.
struct SaveKindMenuButton: View {
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
    let onSelect: (SaveKind) -> Void

    var body: some View {
        Menu {
            // A picker gives the menu its native checkmark and lets VoiceOver
            // announce the selected option.
            Picker(
                selection: Binding(
                    get: { kind },
                    set: { onSelect($0) }
                )
            ) {
                Text("share.save_kind.pinned_menu").tag(SaveKind.pinned)
                Text("share.save_kind.normal_menu").tag(SaveKind.normal)
            } label: {
                Text("share.save_as")
            }
            .pickerStyle(.inline)
        } label: {
            label
                .simultaneousGesture(TapGesture().onEnded { onOpen() })
        }
        .accessibilityLabel(Text("share.save_as"))
        .accessibilityValue(
            Text(LocalizedStringKey(kind == .pinned ? "share.save_kind.pinned" : "share.save_kind.normal"))
        )
        .accessibilityHint(Text("share.save_kind.hint"))
        .disabled(!isEnabled)
    }

    @ViewBuilder
    private var label: some View {
        switch style {
        case .circle:
            glyph(size: 15)
                .foregroundStyle(Palette.ink(scheme))
                .frame(width: 44, height: 44)
                .modifier(DomedFill(shape: Circle(), scheme: scheme))
                .glassRimCapsule()
                .contentShape(Circle())
        case .compact:
            // Small, but still a control: raised like the destination pill
            // beside it, so a finger can tell it apart from a label.
            glyph(size: 14)
                .foregroundStyle(Palette.ink(scheme))
                .frame(width: 32, height: 32)
                .modifier(DomedFill(shape: Circle(), scheme: scheme))
                .glassRimCapsule()
                // 32pt reads right in the row; 44pt is what a finger needs.
                .frame(width: 44, height: 44)
                .contentShape(Circle())
        }
    }

    private func glyph(size: CGFloat) -> some View {
        Image(systemName: kind == .pinned ? "pin.fill" : "rectangle.stack")
            .font(.system(size: size, weight: .semibold))
    }
}
