import SwiftUI

/// The What's New sheet: one short, scrollable release note in the platform's
/// own idiom (large title, system text styles) with the app's coral as the
/// only accent. The release is a short row of feature pills that wraps onto as many lines as the text size needs,
/// so the content scrolls rather than clipping. The Done button is pinned
/// below the scroll area.
struct WhatsNewSheet: View {
    let release: WhatsNewRelease
    let scheme: ColorScheme

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    HStack {
                        Spacer(minLength: 12)
                        closeButton
                    }

                    header
                    features
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
        VStack(alignment: .leading, spacing: 8) {
            Text(release.eyebrow)
                .zenFont(12, weight: .semibold)
                .tracking(1.2)
                .foregroundStyle(Palette.coral(scheme))

            Text(release.title)
                .font(.largeTitle.bold())
                .foregroundStyle(Palette.ink(scheme))
                .accessibilityAddTraits(.isHeader)

            Text(release.subtitle)
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

    /// A caption and the remaining features as small pills. VoiceOver reads
    /// the caption as a header and each pill as plain text.
    private var features: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(release.featuresHeading)
                .zenFont(13, weight: .semibold)
                .tracking(0.5)
                .foregroundStyle(Palette.ink(scheme).opacity(0.65))
                .accessibilityAddTraits(.isHeader)

            PillFlowLayout(spacing: 8) {
                ForEach(release.items) { item in
                    Text(item.title)
                        .zenFont(14)
                        .foregroundStyle(Palette.ink(scheme))
                        .padding(.horizontal, 12)
                        .padding(.vertical, 7)
                        .background(Palette.lift(scheme), in: Capsule(style: .continuous))
                        .glassRimCapsule()
                }
            }
        }
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

/// Lays pills out left to right and wraps them onto new lines, so the row
/// grows with Dynamic Type instead of clipping.
private struct PillFlowLayout: Layout {
    var spacing: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let rows = arrange(subviews, maxWidth: proposal.width ?? .infinity)
        let width = rows.map(\.width).max() ?? 0
        let height = rows.map(\.height).reduce(0, +) + spacing * CGFloat(max(rows.count - 1, 0))
        return CGSize(width: width, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var y = bounds.minY
        for row in arrange(subviews, maxWidth: bounds.width) {
            var x = bounds.minX
            for index in row.indices {
                let size = subviews[index].sizeThatFits(.init(width: bounds.width, height: nil))
                subviews[index].place(at: CGPoint(x: x, y: y), proposal: .init(size))
                x += size.width + spacing
            }
            y += row.height + spacing
        }
    }

    private struct Row {
        var indices: [Int] = []
        var width: CGFloat = 0
        var height: CGFloat = 0
    }

    private func arrange(_ subviews: Subviews, maxWidth: CGFloat) -> [Row] {
        var rows: [Row] = []
        var current = Row()
        for index in subviews.indices {
            let size = subviews[index].sizeThatFits(.init(width: maxWidth, height: nil))
            let needed = current.indices.isEmpty ? size.width : current.width + spacing + size.width
            if needed > maxWidth, !current.indices.isEmpty {
                rows.append(current)
                current = Row()
            }
            current.width = current.indices.isEmpty ? size.width : current.width + spacing + size.width
            current.height = max(current.height, size.height)
            current.indices.append(index)
        }
        if !current.indices.isEmpty {
            rows.append(current)
        }
        return rows
    }
}
