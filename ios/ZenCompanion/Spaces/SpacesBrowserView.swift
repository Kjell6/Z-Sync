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
        case activity
        case syncSetup
        case whatsNew

        var id: String {
            switch self {
            case .account: return "account"
            case .activity: return "activity"
            case .syncSetup: return "syncSetup"
            case .whatsNew: return "whatsNew"
            }
        }

        static func == (lhs: SpacesBrowserView.ActiveSheet, rhs: SpacesBrowserView.ActiveSheet) -> Bool {
            lhs.id == rhs.id
        }
    }

    @State private var activeSheet: ActiveSheet?
    /// The mini-browser is a page, not a sheet: it fills the screen on every
    /// size class, and its own close control is the only way out.
    @State private var browserSession: BrowserTabSession?
    /// Once-per-app-version release notes. Marked as presented as soon as the
    /// sheet opens, so a relaunch does not show it again.
    @State private var whatsNew: WhatsNewStore
    /// A pin inside the mini-browser (or a delete from it) fired while a sheet
    /// was up; refresh once the last sheet is gone.
    @State private var pendingStaleReload = false

    init(account: AccountSnapshot) {
        self.account = account
        _model = State(initialValue: BrowserModel(account: account))
        _whatsNew = State(initialValue: WhatsNewStore())
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
                            actionBar(scheme: effectiveScheme, placement: placement)
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
                            actionBar(scheme: effectiveScheme, placement: placement)
                        }
                    } else {
                        if placement == .top {
                            actionBar(scheme: effectiveScheme, placement: placement)
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
                                            essentialsColumns: model.essentialsColumns,
                                            onRefresh: {
                                                await model.manualRefresh()
                                            },
                                            onOpenTab: { tab, space in
                                                openTab(tab, in: space)
                                            },
                                            onDeleteTab: { id in
                                                await model.deleteTab(id: id)
                                            },
                                            onRenameTab: { id, label in
                                                await model.renameTab(id: id, label: label)
                                            },
                                            onMoveTab: { id, folderId in
                                                await model.moveTab(id: id, toFolder: folderId, in: space.id)
                                            },
                                            onPinTab: { id in
                                                await model.pinTab(id: id, in: space.id)
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
                        // With the action bar at the bottom, the essentials grid
                        // is the topmost row and would sit under the iPadOS 26
                        // window controls; reserve that band. No-op when the
                        // action bar already owns the top edge.
                        .containerCornerOffsetCompat(.top)

                        SpaceSwitcher(
                            spaces: model.snapshot.spaces,
                            selectedIndex: $model.selectedIndex,
                            scheme: effectiveScheme
                        )
                        // Bottom placement stacks the action bar under the
                        // switcher, so the switcher only needs air when it is
                        // the last row (top placement).
                        .padding(.bottom, placement == .top ? 4 : 0)

                        if placement == .bottom {
                            actionBar(scheme: effectiveScheme, placement: placement)
                        }

                        statusLine(scheme: effectiveScheme)
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
                    onToolbarPlacementChange: { model.setToolbarPlacement($0) },
                    onEssentialsColumnsChange: { model.setEssentialsColumns($0) }
                )
                // Single detent on purpose: with `[.fraction(0.85), .large]`
                // the sheet dropped back to the small detent when a page with
                // little content (Advanced, Search engine) was pushed, so that
                // screen no longer filled the height. `.large` keeps every
                // page full height, like the app's other sheets.
                .presentationDetents([.large])
                .presentationDragIndicator(.visible)
            case .activity:
                SyncedActivitySheet(
                    allSpaces: model.snapshot.spaces,
                    scheme: scheme,
                    onOpenURL: { url, space in
                        if model.alwaysOpenExternally {
                            ExternalBrowser.open(url)
                        } else {
                            // Compact: replace this sheet's content, so the
                            // spaces list does not flash through. Regular: the
                            // browser is a cover, so this sheet dismisses.
                            presentBrowser(url: url, title: nil, space: space)
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
            case .whatsNew:
                WhatsNewSheet(release: whatsNew.release, scheme: effectiveScheme)
                    .presentationDetents([.large])
                    .presentationDragIndicator(.visible)
            }
        }
        // Asked once, right after sign-in or on the first launch that has it.
        .fullScreenCover(isPresented: Binding(
            get: { !ContentBlocker.shared.asked },
            set: { _ in }
        )) {
            ContentBlockingSetupView(scheme: effectiveScheme)
        }
        .fullScreenCover(item: $browserSession) { session in
            miniBrowser(session, spaces: model.snapshot.spaces)
                .onDisappear {
                    // A pin inside the mini-browser fired while it was up.
                    if pendingStaleReload {
                        pendingStaleReload = false
                        Task { await model.reload() }
                    }
                }
        }
        .task { await model.initialLoad() }
        .task(id: ContentBlocker.shared.asked) {
            // Show release notes once per app version, after the first frame
            // has had a moment to settle. Demo mode skips it like the other
            // onboarding prompts. Only the main spaces screen reaches this
            // task: the Share Extension has its own view controller, so
            // sharing a link never triggers the sheet. The content blocking
            // question comes first; the notes wait for its answer.
            guard ContentBlocker.shared.asked else { return }
            try? await Task.sleep(for: .milliseconds(450))
            guard !account.isDemo, activeSheet == nil, whatsNew.shouldPresent else { return }
            whatsNew.markPresented()
            activeSheet = .whatsNew
        }
        .onReceive(NotificationCenter.default.publisher(for: .zenCompanionSnapshotStale)) { _ in
            // A pin inside the browser happens while the browser is up, so the
            // browser has to count as "open" too or the reload runs underneath it.
            guard activeSheet == nil, browserSession == nil else {
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
            if phase == .background { model.sceneEnteredBackground() }
            // Returning to the foreground (e.g. after sharing via the share
            // extension) refreshes what's on screen against the server.
            guard phase == .active else { return }
            Task { await model.sceneBecameActive() }
        }
        .onChange(of: model.searchOnOpenPending) { _, pending in
            guard pending else { return }
            // Leave an open browser or settings sheet where the user left it.
            if activeSheet == nil, browserSession == nil { openNewTab(in: model.activeSpace) }
            model.consumeSearchOnOpen()
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

    private func actionBar(scheme: ColorScheme, placement: ToolbarPlacement) -> some View {
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
        // grid), toward the screen edge at the bottom placement, where it is
        // the last row.
        .padding(.bottom, placement == .bottom ? 4 : 6)
        // iPadOS 26 windowed mode puts the system window controls on the
        // window's top-leading corner; the bar makes room on the leading edge
        // so the Activity button stays reachable. No-op when the insets are
        // zero (full screen, iPhone, iOS < 26).
        .containerCornerOffsetCompat(.horizontal)
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
                    in: RoundedRectangle(cornerRadius: Palette.liftCornerRadius, style: .continuous)
                )
                .glassRim(cornerRadius: Palette.liftCornerRadius)
                .contentShape(RoundedRectangle(cornerRadius: Palette.liftCornerRadius, style: .continuous))
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
                in: RoundedRectangle(cornerRadius: Palette.liftCornerRadius, style: .continuous)
            )
            .glassRim(cornerRadius: Palette.liftCornerRadius)
            .contentShape(RoundedRectangle(cornerRadius: Palette.liftCornerRadius, style: .continuous))
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
        presentBrowser(url: url, title: tab.displayTitle, space: space)
    }

    private func openNewTab(in space: ZenSpace) {
        presentBrowser(url: nil, title: nil, space: space)
    }

    /// Opens the browser as a page. A sheet that is already up has to go
    /// first: SwiftUI will not present a full-screen cover over one.
    private func presentBrowser(url: URL?, title: String?, space: ZenSpace) {
        let session = BrowserTabSession(url: url, initialTitle: title, space: space)
        guard activeSheet != nil else {
            browserSession = session
            return
        }
        activeSheet = nil
        Task { @MainActor in
            // Sheet dismissal has to start before the cover can take over.
            try? await Task.sleep(nanoseconds: 250_000_000)
            browserSession = session
        }
    }

    private func miniBrowser(_ session: BrowserTabSession, spaces: [ZenSpace]) -> some View {
        MiniBrowserView(
            initialURL: session.url,
            initialTitle: session.initialTitle,
            space: session.space,
            allSpaces: spaces,
            allowsNormalTabs: model.snapshot.normalTabsCapability.allowsNormalTabs,
            onDismiss: { browserSession = nil }
        )
    }

    // MARK: States (shown until the first successful sync)

    /// A line at the bottom of the dock, shown only when a delete or a
    /// refresh over cached spaces failed, so sync problems are visible
    /// without a banner.
    @ViewBuilder
    private func statusLine(scheme: ColorScheme) -> some View {
        if let deleteError = model.deleteError {
            Text(deleteError)
                .font(.system(size: 12, weight: .medium, design: .rounded))
                .foregroundStyle(Palette.ink(scheme).opacity(0.7))
                .multilineTextAlignment(.center)
                .padding(.horizontal, 20)
                .padding(.bottom, 6)
                .accessibilityAddTraits(.updatesFrequently)
        } else if model.loadError != nil, !model.reloading {
            Button {
                Task { await model.reload() }
            } label: {
                Text("home.refresh_failed")
                    .font(.system(size: 12, weight: .medium, design: .rounded))
                    .foregroundStyle(Palette.ink(scheme).opacity(0.55))
                    .padding(.horizontal, 20)
                    .padding(.vertical, 6)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
    }

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
                        .glassRimCapsule()
                        .foregroundStyle(Palette.ink(scheme))
                }
                .buttonStyle(PressedButtonStyle())
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
    var onRenameTab: (String, String) async -> Void = { _, _ in }
    var onMoveTab: (String, String?) async -> Void = { _, _ in }
    var moveSpace: ZenSpace? = nil
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
            .buttonStyle(PressedButtonStyle())

            if isExpanded {
                ForEach(folder.tabs) { tab in
                    TabRow(
                        tab: tab,
                        scheme: scheme,
                        deletable: true,
                        currentFolderId: folder.id,
                        moveSpace: moveSpace,
                        onOpen: { onOpenTab(tab) },
                        onDelete: { await onDeleteTab(tab.id) },
                        onRename: { label in await onRenameTab(tab.id, label) },
                        onMove: { folderId in await onMoveTab(tab.id, folderId) }
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
                        onRenameTab: onRenameTab,
                        onMoveTab: onMoveTab,
                        moveSpace: moveSpace,
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
    /// older records may carry (raw or inside Zen's emoji SVG).
    private var userIcon: String? {
        ZenIconDecoder.folderFlapIcon(folder.icon)
    }
}
