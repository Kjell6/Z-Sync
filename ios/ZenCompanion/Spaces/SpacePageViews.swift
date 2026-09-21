import SwiftUI

// MARK: - One space page

// MARK: - Space page container with interactive essentials pinning

struct SpacePageContainer: View {
    let space: ZenSpace
    let essentials: [ZenTab]
    /// True when the previous/next page shows an identical essentials grid;
    /// the grid is then kept screen-fixed during the swipe.
    let prevSharesEssentials: Bool
    let nextSharesEssentials: Bool
    let scheme: ColorScheme
    let containerWidth: CGFloat
    var onRefresh: () async -> Void = {}
    var onOpenTab: (ZenTab, ZenSpace) -> Void = { _, _ in }
    var onDeleteTab: (String) async -> Void = { _ in }

    var body: some View {
        GeometryReader { pageGeo in
            let minX = pageGeo.frame(in: .global).minX

            // Page is only active on-screen when within (-containerWidth, containerWidth)
            let isVisible: Bool = (minX > -containerWidth + 0.5 && minX < containerWidth - 0.5)

            // Pin essentials ONLY when this space is actively on screen and being swiped to the left towards a space with an identical grid
            let isPinning: Bool = (minX < -0.5 && minX > -containerWidth + 0.5 && nextSharesEssentials)

            // When swiping towards a previous space with an identical grid,
            // the left neighbor is already pinning its essentials on screen.
            // Hide our own essentials while minX > 0.5 to prevent drawing two identical semi-transparent grids on top of each other!
            let isHiddenByOverlappingNeighbor: Bool = (minX > 0.5 && prevSharesEssentials)

            let xOffset: CGFloat = isPinning ? -minX : 0
            let opacity: Double = (isVisible && !isHiddenByOverlappingNeighbor) ? 1 : 0

            VStack(spacing: 0) {
                if !essentials.isEmpty {
                    EssentialsGrid(
                        tabs: essentials,
                        scheme: scheme,
                        onOpen: { tab in onOpenTab(tab, space) }
                    )
                    .padding(.top, 8)
                    .padding(.bottom, 6)
                    .offset(x: xOffset)
                    .opacity(opacity)
                }

                SpacePageView(
                    space: space,
                    scheme: scheme,
                    onRefresh: onRefresh,
                    onOpenTab: { tab in onOpenTab(tab, space) },
                    onDeleteTab: onDeleteTab
                )
            }
            .frame(width: containerWidth)
        }
    }
}

// MARK: - One space page


private struct SpacePageView: View {
    let space: ZenSpace
    let scheme: ColorScheme
    let onRefresh: () async -> Void
    var onOpenTab: (ZenTab) -> Void = { _ in }
    var onDeleteTab: (String) async -> Void = { _ in }

    @State private var containerHeight: CGFloat = 0
    @State private var contentHeight: CGFloat = 0
    @State private var scrollOffsetY: CGFloat = 0

    private var showTopStroke: Bool {
        scrollOffsetY < -2
    }

    private var showBottomStroke: Bool {
        let maxScroll = max(0, contentHeight - containerHeight)
        return maxScroll > 4 && (-scrollOffsetY < maxScroll - 4)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            title

            GeometryReader { geo in
                ZStack(alignment: .top) {
                    ScrollView(.vertical, showsIndicators: false) {
                        VStack(alignment: .leading, spacing: 0) {
                            // Top anchor to track exact scroll position relative to container
                            GeometryReader { anchorGeo in
                                Color.clear.preference(
                                    key: ScrollAnchorPreferenceKey.self,
                                    value: anchorGeo.frame(in: .named("spaceContainer_\(space.id)")).minY
                                )
                            }
                            .frame(height: 0)

                            ForEach(space.pinned) { item in
                            switch item {
                            case .tab(let tab):
                                TabRow(
                                    tab: tab,
                                    scheme: scheme,
                                    deletable: true,
                                    onOpen: { onOpenTab(tab) },
                                    onDelete: { await onDeleteTab(tab.id) }
                                )
                                .padding(.horizontal, 20)
                            case .folder(let folder):
                                FolderBlock(
                                    folder: folder,
                                    scheme: scheme,
                                    onOpenTab: onOpenTab,
                                    onDeleteTab: onDeleteTab
                                )
                            case .split(let split):
                                SplitRow(
                                    split: split,
                                    scheme: scheme,
                                    onOpenTab: onOpenTab,
                                    onDelete: { await onDeleteTab(split.id) }
                                )
                                .padding(.horizontal, 20)
                            }
                        }

                        // Normal (unpinned) tabs synced via the desktop opt-in
                        // `zen.spaces-sync.normal-tabs`. Separated from the
                        // pinned section by a divider, in sidebar order.
                        if !space.tabs.isEmpty {
                            if !space.pinned.isEmpty {
                                // Ink-based line (not `Divider()`): adapts to
                                // light/dark spaces and stays visible on dark
                                // gradients. 1pt at 35% ink so the pinned /
                                // normal section break actually reads.
                                Rectangle()
                                    .fill(Palette.ink(scheme).opacity(0.35))
                                    .frame(height: 1)
                                    .padding(.horizontal, 20)
                                    .padding(.vertical, 10)
                            }
                            ForEach(space.tabs) { item in
                                switch item {
                                case .tab(let tab):
                                    TabRow(
                                        tab: tab,
                                        scheme: scheme,
                                        deletable: true,
                                        onOpen: { onOpenTab(tab) },
                                        onDelete: { await onDeleteTab(tab.id) }
                                    )
                                    .padding(.horizontal, 20)
                                case .split(let split):
                                    SplitRow(
                                        split: split,
                                        scheme: scheme,
                                        onOpenTab: onOpenTab,
                                        onDelete: { await onDeleteTab(split.id) }
                                    )
                                    .padding(.horizontal, 20)
                                case .folder(let folder):
                                    FolderBlock(
                                        folder: folder,
                                        scheme: scheme,
                                        onOpenTab: onOpenTab,
                                        onDeleteTab: onDeleteTab
                                    )
                                }
                            }
                        }

                        if space.pinned.isEmpty && space.tabs.isEmpty {
                            Text("tabs.empty")
                                .font(.system(size: 14, design: .rounded))
                                .foregroundStyle(Palette.ink(scheme).opacity(0.45))
                                .padding(.horizontal, 20)
                                .padding(.top, 12)
                                .padding(.bottom, 6)
                        }
                    }
                    .padding(.top, 4)
                    .padding(.bottom, 24)
                    .background(
                        GeometryReader { contentGeo in
                            Color.clear.preference(
                                key: ContentHeightPreferenceKey.self,
                                value: contentGeo.size.height
                            )
                        }
                    )
                }
                // .always (not .basedOnSize): pull-to-refresh needs the
                // vertical bounce even when the content is shorter than the
                // viewport, otherwise .refreshable never triggers.
                .scrollBounceBehavior(.always, axes: .vertical)
                .refreshable { await onRefresh() }
                .onScrollGeometryChangeCompat(
                    onOffsetChange: { y in scrollOffsetY = -y },
                    onContentHeightChange: { h in contentHeight = h },
                    onContainerHeightChange: { ch in containerHeight = ch }
                )

                // Top Stroke (Visible only when content is scrolled past top edge)
                if showTopStroke {
                    Rectangle()
                        .fill(Palette.ink(scheme).opacity(0.12))
                        .frame(height: 0.5)
                        .frame(maxWidth: .infinity, alignment: .top)
                        .transition(.opacity)
                }

                // Bottom Stroke (Visible only when remaining content extends below bottom edge)
                if showBottomStroke {
                    Rectangle()
                        .fill(Palette.ink(scheme).opacity(0.12))
                        .frame(height: 0.5)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
                        .transition(.opacity)
                }
            }
            .coordinateSpace(name: "spaceContainer_\(space.id)")
            .animation(.easeInOut(duration: 0.18), value: showTopStroke)
            .animation(.easeInOut(duration: 0.18), value: showBottomStroke)
            .onPreferenceChange(ScrollAnchorPreferenceKey.self) { minY in
                scrollOffsetY = minY
            }
            .onPreferenceChange(ContentHeightPreferenceKey.self) { h in
                contentHeight = h
            }
            .onAppear {
                containerHeight = geo.size.height
            }
            .onChange(of: geo.size.height) { _, newHeight in
                containerHeight = newHeight
            }
        }
        }
    }

    private var title: some View {
        // The icon occupies the same 28pt leading slot and 12pt gap as a tab
        // row's favicon, so header and tab titles share one text column.
        HStack(spacing: 12) {
            if let icon = space.icon, !icon.isEmpty {
                ZenIconView(icon: icon, size: 24, foreground: Palette.ink(scheme))
                    .frame(width: 28, height: 28, alignment: .leading)
            }
            Text(space.name.isEmpty ? String(localized: "share.workspace") : space.name)
                .font(.system(size: 20, weight: .bold, design: .rounded))
                .tracking(-0.3)
                .foregroundStyle(Palette.ink(scheme))
                .lineLimit(1)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 20)
        .padding(.top, 6)
        .padding(.bottom, 8)
        .accessibilityElement(children: .combine)
    }
}
