import StoreKit
import SwiftUI

/// Identifies an active browser session with an optional start URL, title, and target space.
struct BrowserTabSession: Identifiable {
    let id = UUID()
    var url: URL?
    var initialTitle: String?
    var space: ZenSpace
}

/// The whole app: swipe between spaces (pinned tabs + folders per space),
/// space switcher at the bottom and the action bar (activity, search,
/// settings) above the essentials grid or below the switcher, per the
/// user's `ToolbarPlacement`.
struct SpacesBrowserView: View {
    let account: AccountSnapshot

    @Environment(\.colorScheme) private var scheme
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.requestReview) private var requestReview
    @State private var model: BrowserModel

    private enum ActiveSheet: Identifiable, Equatable {
        case account
        case browser(BrowserTabSession)
        case activity
        case syncSetup

        var id: String {
            switch self {
            case .account: return "account"
            case .browser(let session): return session.id.uuidString
            case .activity: return "activity"
            case .syncSetup: return "syncSetup"
            }
        }

        static func == (lhs: SpacesBrowserView.ActiveSheet, rhs: SpacesBrowserView.ActiveSheet) -> Bool {
            lhs.id == rhs.id
        }
    }

    @State private var activeSheet: ActiveSheet?
    /// A pin inside the mini-browser (or a delete from it) fired while a sheet
    /// was up; refresh once the last sheet is gone.
    @State private var pendingStaleReload = false

    init(account: AccountSnapshot) {
        self.account = account
        _model = State(initialValue: BrowserModel(account: account))
    }

    private var effectiveScheme: ColorScheme {
        if let isDark = model.currentTheme?.isDarkTheme {
            return isDark ? .dark : .light
        }
        return scheme
    }

    var body: some View {
        @Bindable var model = model

        NavigationStack {
            ZStack {
                ZenSpaceGradientBackground(
                    theme: model.currentTheme,
                    scheme: effectiveScheme,
                    // TEST (dark-mode experiment): follow the system appearance
                    // so Light and Dark Mode visibly differ. Revert by deleting.
                    darkenDots: scheme == .dark
                )
                .ignoresSafeArea()

                VStack(spacing: 0) {
                    if account.isDemo {
                        Text("demo.banner")
                            .font(.system(size: 12, weight: .medium, design: .rounded))
                            .foregroundStyle(Palette.ink(effectiveScheme).opacity(0.7))
                            .multilineTextAlignment(.center)
                            .padding(.horizontal, 20)
                            .padding(.vertical, 8)
                            .frame(maxWidth: .infinity)
                            .background(Palette.lift(effectiveScheme).opacity(0.65))
                    }

                    let placement = model.toolbarPlacement

                    if model.snapshot.spaces.isEmpty {
                        if placement == .top {
                            actionBar(scheme: effectiveScheme)
                        }
                        Spacer(minLength: 0)
                        if model.zeroSpaces {
                            ZeroSpacesHelp(reloading: model.reloading, scheme: effectiveScheme, onSetup: {
                                activeSheet = .syncSetup
                            }, onRetry: {
                                Task { await model.reload() }
                            })
                        } else {
                            stateArea(scheme: effectiveScheme)
                        }
                        Spacer(minLength: 0)
                        if placement == .bottom {
                            actionBar(scheme: effectiveScheme)
                        }
                    } else {
                        if placement == .top {
                            actionBar(scheme: effectiveScheme)
                        }

                        if model.showSyncSetupHint {
                            SyncSetupHint(scheme: effectiveScheme) {
                                activeSheet = .syncSetup
                            } onDismiss: {
                                model.dismissSyncSetupHint()
                            }
                            .padding(.horizontal, 20)
                            .padding(.bottom, 8)
                            .transition(.opacity)
                        }

                        GeometryReader { windowProxy in
                            let width = windowProxy.size.width
                            let height = windowProxy.size.height
                            // Resolved once per render so adjacent pages can be
                            // compared by their effective grid, not just by
                            // container guid.
                            let essentialsBySpace = model.snapshot.spaces.map { model.essentials(for: $0) }

                            ScrollView(.horizontal, showsIndicators: false) {
                                LazyHStack(spacing: 0) {
                                    ForEach(Array(model.snapshot.spaces.enumerated()), id: \.element.id) { index, space in
                                        let spaceScheme: ColorScheme = {
                                            if let isDark = space.theme?.isDarkTheme {
                                                return isDark ? .dark : .light
                                            }
                                            return scheme
                                        }()
                                        let spaceEssentials = essentialsBySpace[index]
                                        let prevSharesEssentials = index > 0
                                            && essentialsGridsMatch(essentialsBySpace[index - 1], spaceEssentials)
                                        let nextSharesEssentials = index < essentialsBySpace.count - 1
                                            && essentialsGridsMatch(essentialsBySpace[index + 1], spaceEssentials)
                                        SpacePageContainer(
                                            space: space,
                                            essentials: spaceEssentials,
                                            prevSharesEssentials: prevSharesEssentials,
                                            nextSharesEssentials: nextSharesEssentials,
                                            scheme: spaceScheme,
                                            containerWidth: width,
                                            onRefresh: {
                                                await model.manualRefresh()
                                            },
                                            onOpenTab: { tab, space in
                                                openTab(tab, in: space)
                                            },
                                            onDeleteTab: { id in
                                                await model.deleteTab(id: id)
                                            }
                                        )
                                        .frame(width: width, height: height)
                                        .id(index)
                                    }
                                }
                                .scrollTargetLayout()
                            }
                            .scrollTargetBehavior(.paging)
                            .scrollPosition(id: Binding(
                                get: { model.selectedIndex },
                                set: { if let val = $0 { model.selectSpace(val) } }
                            ))
                            .onPagerSettleCompat { _, isIdle in
                                model.pagerSettledChanged(isIdle)
                            }
                            .onChange(of: model.selectedIndex) { _, _ in
                                model.selectedIndexChangedFromPager()
                            }
                        }

                        SpaceSwitcher(
                            spaces: model.snapshot.spaces,
                            selectedIndex: $model.selectedIndex,
                            scheme: effectiveScheme
                        )

                        if placement == .bottom {
                            actionBar(scheme: effectiveScheme)
                        }

                        Text("home.disclaimer")
                            .font(.system(size: 10.5, weight: .regular, design: .rounded))
                            .foregroundStyle(Palette.ink(effectiveScheme).opacity(0.3))
                            .padding(.bottom, 6)
                    }
                }
            }
        }
        // Keyboard from the mini-browser sheet must not lift the space switcher.
        .ignoresSafeArea(.keyboard)
        .overlay(alignment: .top) {
            if model.showShareTip && !model.snapshot.spaces.isEmpty {
                ShareExtensionTip(scheme: scheme) {
                    model.dismissShareTip()
                }
                .padding(.horizontal, 16)
                .transition(.move(edge: .top).combined(with: .opacity))
            }
        }
        .sheet(item: $activeSheet) { sheet in
            switch sheet {
            case .account:
                SettingsSheet(
                    account: account,
                    loadError: model.loadError,
                    scheme: scheme,
                    normalTabsCapability: model.snapshot.normalTabsCapability,
                    onToolbarPlacementChange: { model.setToolbarPlacement($0) }
                )
                .presentationDetents([.fraction(0.85), .large])
                .presentationDragIndicator(.visible)
            case .browser(let session):
                MiniBrowserView(
                    initialURL: session.url,
                    initialTitle: session.initialTitle,
                    space: session.space,
                    allSpaces: model.snapshot.spaces,
                    onDismiss: { activeSheet = nil }
                )
                .presentationDetents([.large])
                .presentationDragIndicator(.visible)
                .geometryGroup()
            case .activity:
                SyncedActivitySheet(
                    allSpaces: model.snapshot.spaces,
                    scheme: scheme,
                    onOpenURL: { url, space in
                        if model.alwaysOpenExternally {
                            ExternalBrowser.open(url)
                        } else {
                            // Swap to the mini-browser without dismissing this sheet first.
                            activeSheet = .browser(BrowserTabSession(
                                url: url,
                                initialTitle: nil,
                                space: space
                            ))
                        }
                    }
                )
                .presentationDetents([.large])
                .presentationDragIndicator(.visible)
            case .syncSetup:
                SyncSetupSheet(scheme: scheme, onRefresh: {
                    Task { await model.reload() }
                })
                .presentationDetents([.large])
                .presentationDragIndicator(.visible)
            }
        }
        .task { await model.initialLoad() }
        .onReceive(NotificationCenter.default.publisher(for: .zenCompanionSnapshotStale)) { _ in
            guard activeSheet == nil else {
                pendingStaleReload = true
                return
            }
            Task { await model.reload() }
        }
        .onChange(of: activeSheet) { _, sheet in
            // A pin inside the mini-browser (or a delete from it) fired while
            // a sheet was up; refresh once the last sheet is gone.
            if sheet == nil, pendingStaleReload {
                pendingStaleReload = false
                Task { await model.reload() }
            }
        }
        .onChange(of: scenePhase) { _, phase in
            // Returning to the foreground (e.g. after sharing via the share
            // extension) refreshes what's on screen against the server.
            guard phase == .active else { return }
            Task { await model.sceneBecameActive() }
        }
        .onChange(of: model.reviewPromptPending) { _, pending in
            guard pending else { return }
            requestReview()
            model.consumeReviewPrompt()
        }
    }

    // MARK: - Action bar: Activity (left) + Search (center) + Settings (right)
    // Side cards share the essentials/search tile look and align their outer
    // edges with the 20pt content padding used across the screen. Rendered
    // above the essentials grid or below the space switcher, per
    // `model.toolbarPlacement`.

    private func actionBar(scheme: ColorScheme) -> some View {
        HStack(spacing: 12) {
            actionCard(systemName: "archivebox", accessibilityKey: "activity.title", scheme: scheme) {
                activeSheet = .activity
            }

            searchPill(scheme: scheme)

            actionCard(systemName: "gearshape", accessibilityKey: "Settings", scheme: scheme) {
                activeSheet = .account
            }
        }
        .padding(.horizontal, 20)
        // Breathing room so the bar and the tile group beside it don't read as
        // one block: below the bar at the top placement (toward the essentials
        // grid), toward the disclaimer at the bottom placement.
        .padding(.bottom, 6)
    }

    private func actionCard(
        systemName: String,
        accessibilityKey: String,
        scheme: ColorScheme,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: 17, weight: .medium))
                .foregroundStyle(Palette.ink(scheme).opacity(0.75))
                .frame(width: 44, height: 44)
                .background(
                    Palette.lift(scheme),
                    in: RoundedRectangle(cornerRadius: 13, style: .continuous)
                )
                .contentShape(RoundedRectangle(cornerRadius: 13, style: .continuous))
        }
        .buttonStyle(SquircleButtonStyle())
        .accessibilityLabel(Text(LocalizedStringKey(accessibilityKey)))
    }

    // MARK: - Search pill: quick lookup entry, styled after the essentials tiles

    private func searchPill(scheme: ColorScheme) -> some View {
        Button {
            openNewTab(in: model.activeSpace)
        } label: {
            HStack(spacing: 10) {
                Image(systemName: "magnifyingglass")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(Palette.ink(scheme).opacity(0.55))
                Text("browser.web_placeholder")
                    .font(.system(size: 16, weight: .medium, design: .rounded))
                    .foregroundStyle(Palette.ink(scheme).opacity(0.55))
                    .lineLimit(1)
                Spacer(minLength: 0)
            }
            .padding(.leading, 16)
            .padding(.trailing, 12)
            .frame(height: 44)
            .background(
                Palette.lift(scheme),
                in: RoundedRectangle(cornerRadius: 13, style: .continuous)
            )
            .contentShape(RoundedRectangle(cornerRadius: 13, style: .continuous))
        }
        .buttonStyle(SquircleButtonStyle())
        .accessibilityLabel(Text("browser.web_placeholder"))
    }

    private func openTab(_ tab: ZenTab, in space: ZenSpace) {
        guard let url = URL(string: tab.url) else { return }
        if model.alwaysOpenExternally {
            ExternalBrowser.open(url)
            return
        }
        activeSheet = .browser(BrowserTabSession(
            url: url,
            initialTitle: tab.title,
            space: space
        ))
    }

    private func openNewTab(in space: ZenSpace) {
        activeSheet = .browser(BrowserTabSession(
            url: nil,
            initialTitle: nil,
            space: space
        ))
    }

    // MARK: States (shown until the first successful sync)

    @ViewBuilder
    private func stateArea(scheme: ColorScheme) -> some View {
        if model.loading && model.loadError == nil {
            VStack(spacing: 14) {
                ProgressView()
                    .controlSize(.large)
                    .tint(Palette.coral(scheme))
                Text("spaces.connecting")
                    .font(.system(size: 15, weight: .medium, design: .rounded))
                    .foregroundStyle(Palette.ink(scheme).opacity(0.6))
            }
        } else if let loadError = model.loadError {
            VStack(spacing: 16) {
                Image(systemName: "exclamationmark.triangle")
                    .font(.system(size: 30, weight: .medium))
                    .foregroundStyle(Palette.ink(scheme).opacity(0.35))
                Text(loadError)
                    .font(.system(size: 15, design: .rounded))
                    .foregroundStyle(Palette.ink(scheme).opacity(0.7))
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 32)
                Button { Task { await model.reload() } } label: {
                    Label(String(localized: "spaces.retry"), systemImage: "arrow.clockwise")
                        .font(.system(size: 16, weight: .semibold, design: .rounded))
                        .padding(.horizontal, 20)
                        .padding(.vertical, 12)
                        .background(Palette.lift(scheme), in: Capsule())
                        .foregroundStyle(Palette.ink(scheme))
                }
                .buttonStyle(.plain)
            }
        }
    }
}

extension Notification.Name {
    static let zenCompanionSignedOut = Notification.Name("zenCompanionSignedOut")
}

// MARK: - Pinned folder block (Collapsible with distinct open/closed icons)

struct FolderBlock: View {
    let folder: ZenFolder
    let scheme: ColorScheme
    var onOpenTab: (ZenTab) -> Void = { _ in }
    var onDeleteTab: (String) async -> Void = { _ in }
    /// True when rendered inside another folder's expanded content. The
    /// parent already applies the level indent via `.padding(.leading, 20)`,
    /// so a nested block must not add its own horizontal padding — otherwise
    /// sub-folders sit 20pt further right than sibling tabs on the same level
    /// (Android parity: nested FolderBlocks get no extra padding).
    var isNested: Bool = false

    @State private var isExpanded = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button {
                withAnimation(.spring(response: 0.35, dampingFraction: 0.8)) {
                    isExpanded.toggle()
                }
            } label: {
                HStack(spacing: 12) {
                    folderIconView

                    Text(folder.name.isEmpty ? String(localized: "tabs.folder") : folder.name)
                        .font(.system(size: 17, weight: .bold))
                        .foregroundStyle(Palette.ink(scheme))
                        .lineLimit(1)

                    Spacer(minLength: 0)
                }
                .padding(.vertical, 9)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            if isExpanded {
                ForEach(folder.tabs) { tab in
                    TabRow(
                        tab: tab,
                        scheme: scheme,
                        deletable: true,
                        onOpen: { onOpenTab(tab) },
                        onDelete: { await onDeleteTab(tab.id) }
                    )
                    .padding(.leading, 20)
                    .transition(.opacity.combined(with: .move(edge: .top)))
                }
                ForEach(folder.subfolders ?? []) { subfolder in
                    FolderBlock(
                        folder: subfolder,
                        scheme: scheme,
                        onOpenTab: onOpenTab,
                        onDeleteTab: onDeleteTab,
                        isNested: true
                    )
                    .padding(.leading, 20)
                    .transition(.opacity.combined(with: .move(edge: .top)))
                }
            }
        }
        .padding(.horizontal, isNested ? 0 : 20)
    }

    @ViewBuilder
    private var folderIconView: some View {
        // The folder silhouette is always the row's icon; a user-chosen icon
        // renders inside it, exactly like Zen Desktop's folder SVG. Replacing
        // the silhouette made folders indistinguishable from tabs.
        ZenFolderSidebarIcon(
            isExpanded: isExpanded,
            scheme: scheme,
            userIcon: userIcon
        )
        .frame(width: 28, height: 28)
    }

    /// The folder's user icon, nil for "no icon" and the plain folder emojis
    /// older records may carry.
    private var userIcon: String? {
        guard let icon = folder.icon, !icon.isEmpty, icon != "📁", icon != "📂" else { return nil }
        return icon
    }
}
