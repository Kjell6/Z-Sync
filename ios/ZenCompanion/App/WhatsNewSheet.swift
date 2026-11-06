import SwiftUI

/// The What's New sheet: one short, scrollable release note in the platform's
/// own idiom (large title, SF Symbols, system text styles) with the app's
/// coral used only as an accent. Text uses Dynamic Type styles throughout, so
/// the list scrolls rather than clipping. The Done button is pinned below the
/// scroll area.
struct WhatsNewSheet: View {
    let release: WhatsNewRelease
    let scheme: ColorScheme

    @Environment(\.dismiss) private var dismiss
    @ScaledMetric(relativeTo: .body) private var badgeSize: CGFloat = 44

    var body: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 28) {
                    header

                    VStack(alignment: .leading, spacing: 30) {
                        ForEach(release.items) { item in
                            row(item)
                        }
                    }
                }
                .frame(maxWidth: 560, alignment: .leading)
                .frame(maxWidth: .infinity)
                .padding(.horizontal, 24)
                .padding(.top, 8)
                .padding(.bottom, 24)
            }

            doneButton
                .padding(.horizontal, 24)
                .padding(.top, 4)
                .padding(.bottom, 20)
                .background(Palette.background(scheme))
        }
        .background(Palette.background(scheme).ignoresSafeArea())
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
        // Swiping down is a valid dismissal; the caller already marks the
        // version as shown when it presents the sheet.
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Spacer(minLength: 12)
                closeButton
            }

            Text("whats_new.title")
                .font(.largeTitle.bold())
                .foregroundStyle(Palette.ink(scheme))
                .accessibilityAddTraits(.isHeader)

            Text("whats_new.subtitle")
                .font(.body)
                .foregroundStyle(Palette.ink(scheme).opacity(0.65))
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private var closeButton: some View {
        Button {
            dismiss()
        } label: {
            Image(systemName: "xmark")
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(Palette.ink(scheme).opacity(0.65))
                .frame(width: 36, height: 36)
                .background(Palette.lift(scheme), in: Circle())
                .contentShape(Circle())
        }
        .buttonStyle(PressedButtonStyle())
        .accessibilityLabel(Text("common.done"))
    }

    private func row(_ item: WhatsNewItem) -> some View {
        HStack(alignment: .top, spacing: 14) {
            Image(systemName: item.systemImage)
                .font(.body.weight(.semibold))
                .foregroundStyle(Palette.coral(scheme))
                .frame(width: min(badgeSize, 60), height: min(badgeSize, 60))
                .background(
                    Palette.coral(scheme).opacity(0.12),
                    in: RoundedRectangle(cornerRadius: 12, style: .continuous)
                )
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 4) {
                Text(item.title)
                    .font(.headline)
                    .foregroundStyle(Palette.ink(scheme))
                Text(item.detail)
                    .font(.body)
                    .foregroundStyle(Palette.ink(scheme).opacity(0.7))
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(item.title))
        .accessibilityValue(Text(item.detail))
    }

    private var doneButton: some View {
        Button {
            dismiss()
        } label: {
            Text("whats_new.done")
                .font(.headline)
                .foregroundStyle(Palette.onCoral(scheme))
                .frame(maxWidth: .infinity)
                .padding(.vertical, 15)
                .background(
                    Palette.coral(scheme),
                    in: RoundedRectangle(cornerRadius: 14, style: .continuous)
                )
        }
        .buttonStyle(PressedButtonStyle())
    }
}
