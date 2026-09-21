import SwiftUI

// MARK: - Bottom space switcher (Zen style: Icons/Emojis/Dots, no background)
//
// Layout contract: this view must NEVER grow wider than the screen.
// With many spaces the items shrink a bit and the row becomes
// horizontally scrollable (centered while it fits). The parent VStack
// (top bar, pager, switcher) therefore always keeps the screen width.

struct SpaceSwitcher: View {
    let spaces: [ZenSpace]
    @Binding var selectedIndex: Int
    let scheme: ColorScheme

    /// Shrink the touch targets slightly as the count grows so more
    /// spaces fit without scrolling; beyond that we scroll.
    private var itemSize: CGFloat {
        if spaces.count <= 5 { return 40 }
        if spaces.count <= 8 { return 36 }
        return 32
    }

    private var itemSpacing: CGFloat {
        if spaces.count <= 5 { return 22 }
        if spaces.count <= 8 { return 14 }
        return 10
    }

    private var iconSize: CGFloat {
        itemSize * 0.65
    }

    var body: some View {
        GeometryReader { geo in
            let available = geo.size.width
            // Content width including the side padding used inside the scroll view.
            let contentWidth = CGFloat(spaces.count) * itemSize
                + CGFloat(max(0, spaces.count - 1)) * itemSpacing
                + 40 // leading + trailing padding
            let needsScroll = contentWidth > available

            ScrollViewReader { proxy in
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: itemSpacing) {
                        ForEach(Array(spaces.enumerated()), id: \.element.id) { index, space in
                            let isSelected = (index == selectedIndex)
                            Button {
                                withAnimation(.spring(response: 0.3, dampingFraction: 0.85)) {
                                    selectedIndex = index
                                }
                            } label: {
                                itemContent(for: space, isSelected: isSelected)
                                    .frame(width: itemSize, height: 40)
                                    .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            .id(space.id)
                            .accessibilityLabel(Text(space.name.isEmpty ? String(localized: "share.workspace") : space.name))
                            .accessibilityAddTraits(isSelected ? [.isSelected] : [])
                        }
                    }
                    .padding(.horizontal, 20)
                    // Center the row while it fits; grow beyond the screen
                    // (and thus scroll) once there are too many spaces.
                    .frame(minWidth: available, minHeight: 40, alignment: .center)
                }
                .scrollDisabled(!needsScroll)
                .onChange(of: selectedIndex) { _, newIndex in
                    guard spaces.indices.contains(newIndex) else { return }
                    withAnimation(.spring(response: 0.3, dampingFraction: 0.85)) {
                        proxy.scrollTo(spaces[newIndex].id, anchor: .center)
                    }
                }
                .onAppear {
                    // Ensure a restored selection (e.g. index 7 of 8) starts visible.
                    guard spaces.indices.contains(selectedIndex) else { return }
                    proxy.scrollTo(spaces[selectedIndex].id, anchor: .center)
                }
            }
        }
        // Fixed height + clipped: the switcher can never push the parent
        // VStack wider than the screen, no matter how many spaces exist.
        .frame(height: 56)
        .frame(maxWidth: .infinity, alignment: .center)
        .clipped()
        .animation(.spring(response: 0.25, dampingFraction: 0.8), value: selectedIndex)
    }

    @ViewBuilder
    private func itemContent(for space: ZenSpace, isSelected: Bool) -> some View {
        let opacity: Double = isSelected ? 1.0 : 0.35
        let ink = Palette.ink(scheme).opacity(opacity)

        if let icon = space.icon, !icon.isEmpty {
            ZenIconView(
                icon: icon,
                size: iconSize,
                foreground: ink
            )
            .opacity(opacity)
        } else {
            Circle()
                .fill(ink)
                .frame(width: 10, height: 10)
        }
    }
}
