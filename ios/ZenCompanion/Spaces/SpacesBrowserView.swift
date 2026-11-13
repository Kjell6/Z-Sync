import StoreKit
import SwiftUI

/// The browser being open. The pages themselves live in `OpenPagesStore`; this
/// is the space a pin falls back to.
struct BrowserTabSession: Identifiable {
    let id = UUID()
    var space: ZenSpace
    /// Opened from the count on home: the overview first, no page yet.
    var startsInOverview = false
}

/// The whole app: swipe between spaces (pinned tabs + folders per space),
/// space switcher at the bottom and the action bar (activity, search,
/// settings) above the essentials grid or below the switcher, per the
/// user's `ToolbarPlacement`. Signed out the same screen stands: the account's
/// content is a placeholder skeleton and a card offers sign-in.
struct SpacesBrowserView: View {
    /// The signed-in account, nil while the reader uses the app without one.
    let account: AccountSnapshot?
    /// Why the last session ended, shown in the signed-out card.
    var sessionNotice: String? = nil
    /// A web link another app handed to Z-Sync before this screen was up. It
    /// opens at once, signed in or out.
    var incomingLink: URL? = nil
    /// The link was taken: it opens once, like a tab tapped here.
    var onConsumeIncomingLink: () -> Void = {}
    /// Starts the existing Mozilla sign-in flow from the signed-out card.
    var onPresentSignIn: () -> Void = {}
    /// The sign-in help sheet under the card.
    var onPresentSignInHelp: () -> Void = {}
    /// The hidden demo unlock behind the card's app mark and title.
    var onDemoTap: () -> Void = {}

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

    init(
        account: AccountSnapshot?,
        sessionNotice: String? = nil,
        incomingLink: URL? = nil,
        onConsumeIncomingLink: @escaping () -> Void = {},
        onPresentSignIn: @escaping () -> Void = {},
        onPresentSignInHelp: @escaping () -> Void = {},
        onDemoTap: @escaping () -> Void = {}
    ) {
        self.account = account
        self.sessionNotice = sessionNotice
        self.incomingLink = incomingLink
        self.onConsumeIncomingLink = onConsumeIncomingLink
        self.onPresentSignIn = onPresentSignIn
        self.onPresentSignInHelp = onPresentSignInHelp
        self.onDemoTap = onDemoTap
        _model = State(initialValue: BrowserModel(account: account))
        _whatsNew = State(initialValue: WhatsNewStore())
    }

    private var isSignedIn: Bool { account != nil }
    private var isDemo: Bool { account?.isDemo == true }

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
                    if isDemo {
                        Text("demo.banner")
                            .zenFont(12, weight: .medium, design: .rounded)
                            .foregroundStyle(Palette.ink(effectiveScheme).opacity(0.7))
                            .multilineTextAlignment(.center)
                            .padding(.horizontal, 20)
                            .padding(.vertical, 8)
                            .frame(maxWidth: .infinity)
                            .background(Palette.lift(effectiveScheme).opacity(0.65))
                            // A fixed strip under the status bar: type stops at
                            // the first accessibility size like the chrome does.
                            .dynamicTypeSize(...DynamicTypeSize.accessibility1)
                    }

                    let placement = model.toolbarPlacement

                    if !isSignedIn {
                        signedOutHome(scheme: effectiveScheme, placement: placement)
                    } else if model.snapshot.spaces.isEmpty {
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

                        // Sits between the content and the switcher: the dock
                        // keeps its shape and the text never reads as part of
                        // the toolbar below it.
                        statusLine(scheme: effectiveScheme)

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
                    }
                }
                // 30pt on a phone with a home indicator: the inset minus a
                // hair, so the dock hugs the safe-area edge without falling
                // into the indicator or the display corners.
                .padding(.bottom, BottomDock.padding)
                .ignoresSafeArea(.container, edges: .bottom)
            }
        }
        // Keyboard from the mini-browser sheet must not lift the space switcher.
        .ignoresSafeArea(.keyboard)
        .overlay(alignment: .top) {
            if isSignedIn, model.showShareTip, !model.snapshot.spaces.isEmpty {
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
                    onEssentialsColumnsChange: { model.setEssentialsColumns($0) },
                    onSignIn: { onPresentSignIn() }
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
        .task(id: model.isSignedIn) { await model.initialLoad() }
        .onChange(of: account?.uid) { _, _ in
            // The sign-in sheet can replace the session under this screen.
            // The browser model and the shared page store follow the account.
            model.accountChanged(isSignedIn: isSignedIn, isDemo: isDemo)
            openPages.canSave = isSignedIn
        }
        .task(id: incomingLink) {
            // A link from another app opens on top of whatever is showing,
            // like a tab tapped here. A sheet that is up makes way first.
            guard let url = incomingLink else { return }
            onConsumeIncomingLink()
            presentBrowser(url: url, title: nil, space: model.activeSpace)
        }
        .task(id: ContentBlocker.shared.asked) {
            // Show release notes once per app version, after the first frame
            // has had a moment to settle. Demo mode skips it like the other
            // onboarding prompts. Only the main spaces screen reaches this
            // task: the Share Extension has its own view controller, so
            // sharing a link never triggers the sheet. The content blocking
            // question comes first; the notes wait for its answer.
            guard ContentBlocker.shared.asked else { return }
            try? await Task.sleep(for: .milliseconds(450))
            guard !isDemo, activeSheet == nil, whatsNew.shouldPresent else { return }
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
            openPages.closeExpired()
            Task { await model.sceneBecameActive() }
        }
        .onChange(of: model.searchOnOpenPending) { _, pending in
            guard pending else { return }
            // Leave an open browser or settings sheet where the user left it.
            // A link from another app is the page this launch is for.
            if activeSheet == nil, browserSession == nil, incomingLink == nil {
                openNewTab(in: model.activeSpace)
            }
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
            // The activity is the account's synced history, so signed out the
            // row is search and settings only.
            if isSignedIn {
                actionCard(systemName: "archivebox", accessibilityKey: "activity.title", scheme: scheme) {
                    activeSheet = .activity
                }
            }

            searchPill(scheme: scheme)

            actionCard(systemName: "gearshape", accessibilityKey: "Settings", scheme: scheme) {
                activeSheet = .account
            }
        }
        .padding(.horizontal, 20)
        // Breathing room so the bar and the tile group beside it don't read as
        // one block: below the bar at the top placement (toward the essentials
        // grid). At the bottom placement the safe-area inset is the only gap
        // between the bar and the screen edge, so the dock stays as short as
        // possible.
        .padding(.bottom, placement == .bottom ? 0 : 6)
        // Chrome band: the pill and cards are 44pt tall, so type stops at the
        // first accessibility size. Content below scales fully.
        .dynamicTypeSize(...DynamicTypeSize.accessibility1)
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
                    .zenFont(16, weight: .medium, design: .rounded)
                    .foregroundStyle(Palette.ink(scheme).opacity(0.55))
                    .lineLimit(1)
                Spacer(minLength: 0)
            }
            .padding(.leading, 16)
            .padding(.trailing, pageCount > 0 ? 52 : 12)
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
        .overlay(alignment: .trailing) {
            if pageCount > 0 {
                // Its own target: the rest of the field still starts a search.
                Button {
                    prepareOpenPages()
                    show(BrowserTabSession(space: model.activeSpace, startsInOverview: true))
                } label: {
                    PageCountBadge(count: pageCount, side: 26)
                        .foregroundStyle(Palette.ink(scheme).opacity(0.8))
                        .frame(width: 48, height: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(SquircleButtonStyle())
                .accessibilityLabel(Text("browser.page_count \(pageCount)"))
            }
        }
    }

    private var openPages: OpenPagesStore { .shared }
    private var pageCount: Int { openPages.pages.count }

    /// Brings the shared page store in line with this session before it opens
    /// a browser: normal saves follow the synced capability, and saving at all
    /// follows the account.
    private func prepareOpenPages() {
        openPages.allowsNormalTabs = model.snapshot.normalTabsCapability.allowsNormalTabs
        openPages.canSave = isSignedIn
    }

    private func openTab(_ tab: ZenTab, in space: ZenSpace) {
        guard let url = URL(string: tab.url) else { return }
        if model.alwaysOpenExternally {
            ExternalBrowser.open(url)
            return
        }
        presentBrowser(url: url, title: tab.displayTitle, space: space, openedFromSpace: true)
    }

    private func openNewTab(in space: ZenSpace) {
        presentBrowser(url: nil, title: nil, space: space)
    }

    /// Opens the browser as a page. A sheet that is already up has to go
    /// first: SwiftUI will not present a full-screen cover over one.
    private func presentBrowser(url: URL?, title: String?, space: ZenSpace, openedFromSpace: Bool = false) {
        prepareOpenPages()
        openPages.open(url: url, title: title, openedFromSpace: openedFromSpace)
        show(BrowserTabSession(space: space))
    }

    private func show(_ session: BrowserTabSession) {
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
            pages: openPages,
            space: session.space,
            allSpaces: spaces,
            allowsNormalTabs: model.snapshot.normalTabsCapability.allowsNormalTabs,
            canSave: isSignedIn,
            startsInOverview: session.startsInOverview,
            onDismiss: { browserSession = nil }
        )
    }

    // MARK: States (shown until the first successful sync)

    /// A quiet line between the content and the space switcher, shown only
    /// when a delete or a refresh over cached spaces failed. Sitting above the
    /// switcher keeps it out of the toolbar and it reads as part of the list.
    @ViewBuilder
    private func statusLine(scheme: ColorScheme) -> some View {
        if let deleteError = model.deleteError {
            Text(deleteError)
                .zenFont(12, weight: .medium, design: .rounded)
                .foregroundStyle(Palette.ink(scheme).opacity(0.7))
                .multilineTextAlignment(.center)
                .padding(.horizontal, 20)
                .padding(.bottom, 2)
                .accessibilityAddTraits(.updatesFrequently)
        } else if model.loadError != nil, !model.reloading {
            Button {
                Task { await model.reload() }
            } label: {
                Text("home.refresh_failed")
                    .zenFont(12, weight: .medium, design: .rounded)
                    .foregroundStyle(Palette.ink(scheme).opacity(0.55))
                    .padding(.horizontal, 20)
                    .padding(.vertical, 4)
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
                    .zenFont(15, weight: .medium, design: .rounded)
                    .foregroundStyle(Palette.ink(scheme).opacity(0.6))
            }
        } else if let loadError = model.loadError {
            VStack(spacing: 16) {
                Image(systemName: "exclamationmark.triangle")
                    .font(.system(size: 30, weight: .medium))
                    .foregroundStyle(Palette.ink(scheme).opacity(0.35))
                Text(loadError)
                    .zenFont(15, design: .rounded)
                    .foregroundStyle(Palette.ink(scheme).opacity(0.7))
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 32)
                Button { Task { await model.reload() } } label: {
                    Label(String(localized: "spaces.retry"), systemImage: "arrow.clockwise")
                        .zenFont(16, weight: .semibold, design: .rounded)
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

    // MARK: - Signed out: the same home with the account's content sketched

    /// The signed-in layout, with the account's content as a skeleton and the
    /// sign-in card where the space's rows would be. The action bar, the
    /// search pill and settings stay; the activity (synced history), refresh
    /// and the space switcher cannot work without an account.
    @ViewBuilder
    private func signedOutHome(scheme: ColorScheme, placement: ToolbarPlacement) -> some View {
        if placement == .top {
            actionBar(scheme: scheme, placement: placement)
        }

        SignedOutEssentialsSkeleton(
            columns: model.essentialsColumns.columnCount(forItemCount: 8),
            scheme: scheme
        )
        // The real grid's place on the screen, with its own air.
        .padding(.top, 4)
        .padding(.bottom, 6)

        ScrollView(.vertical, showsIndicators: false) {
            VStack(spacing: 0) {
                signInCard(scheme: scheme)
                    .padding(.horizontal, 20)
                    .padding(.top, 10)
                    .padding(.bottom, 18)

                SignedOutTabsSkeleton(scheme: scheme)
            }
            .padding(.bottom, 10)
            .frame(maxWidth: .infinity)
        }

        SignedOutSpaceSwitcher(scheme: scheme)
            // Bottom placement stacks the action bar under the switcher, so
            // the switcher only needs air when it is the last row.
            .padding(.bottom, placement == .top ? 4 : 0)

        if placement == .bottom {
            actionBar(scheme: scheme, placement: placement)
        }
    }

    /// The prompt in the content area, between the skeleton essentials and the
    /// skeleton tab rows. The skeleton around it stays visible. The app mark
    /// and the title keep the hidden demo unlock.
    private func signInCard(scheme: ColorScheme) -> some View {
        VStack(spacing: 14) {
            ZenMark(scheme: scheme, size: 48)
                .contentShape(Rectangle())
                .onTapGesture { onDemoTap() }
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(Text("demo.logo_hint"))
                .accessibilityAddTraits(.isButton)

            VStack(spacing: 6) {
                Text("signedout.title")
                    .zenFont(20, weight: .semibold, design: .rounded)
                    .tracking(-0.2)
                    .foregroundStyle(Palette.ink(scheme))
                    .contentShape(Rectangle())
                    .onTapGesture { onDemoTap() }
                    .accessibilityAddTraits(.isHeader)
                Text("signedout.message")
                    .zenFont(15, design: .rounded)
                    .foregroundStyle(Palette.ink(scheme).opacity(0.6))
                    .fixedSize(horizontal: false, vertical: true)
            }

            if let sessionNotice {
                Text(sessionNotice)
                    .zenFont(14, weight: .medium, design: .rounded)
                    .foregroundStyle(Palette.ink(scheme).opacity(0.75))
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                    .frame(maxWidth: .infinity)
                    .background(
                        Palette.ink(scheme).opacity(0.06),
                        in: RoundedRectangle(cornerRadius: 12, style: .continuous)
                    )
            }

            Button { onPresentSignIn() } label: {
                Text("home.sign_in")
                    .zenFont(17, weight: .semibold, design: .rounded)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 15)
                    .foregroundStyle(Palette.paper(scheme))
                    .background(Palette.ink(scheme), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            }
            .buttonStyle(PressedButtonStyle())

            HStack(spacing: 18) {
                Button { onPresentSignInHelp() } label: {
                    Text("signin.help.link")
                        .zenFont(13, weight: .medium, design: .rounded)
                        .foregroundStyle(Palette.ink(scheme).opacity(0.55))
                }
                .buttonStyle(PressedButtonStyle())

                Link("legal.privacy", destination: Legal.publicPolicyURL)
                    .zenFont(13, weight: .medium, design: .rounded)
                    .foregroundStyle(Palette.ink(scheme).opacity(0.45))
            }
            .multilineTextAlignment(.center)
        }
        .multilineTextAlignment(.center)
        .padding(.horizontal, 20)
        .padding(.vertical, 22)
        .frame(maxWidth: 440)
        .frame(maxWidth: .infinity)
        .liftCard(scheme)
        .tint(Palette.coral(scheme))
    }
}

// MARK: - Signed-out placeholders

/// Two rows of rounded tiles where the essentials grid is, drawn with the
/// grid's own metrics. Decoration: hidden from VoiceOver, never tappable.
private struct SignedOutEssentialsSkeleton: View {
    let columns: Int
    let scheme: ColorScheme

    var body: some View {
        VStack(spacing: 10) {
            ForEach(0..<2, id: \.self) { _ in
                HStack(spacing: 10) {
                    ForEach(0..<max(1, columns), id: \.self) { _ in
                        RoundedRectangle(cornerRadius: Palette.liftCornerRadius, style: .continuous)
                            .fill(Palette.lift(scheme))
                            .frame(maxWidth: .infinity)
                            .frame(height: 54)
                            .glassRim(cornerRadius: Palette.liftCornerRadius)
                    }
                }
            }
        }
        .padding(.horizontal, 20)
        .skeletonShimmer()
        .accessibilityHidden(true)
        .allowsHitTesting(false)
    }
}

/// Placeholder rows in the shape of pinned tabs: a favicon-sized square and a
/// title-sized bar, then a folder-shaped row. Decoration only.
private struct SignedOutTabsSkeleton: View {
    let scheme: ColorScheme

    var body: some View {
        VStack(spacing: 0) {
            tabRow(titleWidth: 168)
            tabRow(titleWidth: 112)
            tabRow(titleWidth: 196)
            folderRow
        }
        .skeletonShimmer()
        .accessibilityHidden(true)
        .allowsHitTesting(false)
    }

    private func tabRow(titleWidth: CGFloat) -> some View {
        HStack(spacing: 12) {
            RoundedRectangle(cornerRadius: 8, style: .continuous)
                .fill(Palette.lift(scheme))
                .frame(width: 28, height: 28)
                .glassRim(cornerRadius: 8)
            RoundedRectangle(cornerRadius: 5, style: .continuous)
                .fill(Palette.ink(scheme).opacity(0.1))
                .frame(width: titleWidth, height: 11)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 9)
    }

    private var folderRow: some View {
        HStack(spacing: 12) {
            SkeletonFolderIcon(color: Palette.ink(scheme).opacity(0.1))
            RoundedRectangle(cornerRadius: 5, style: .continuous)
                .fill(Palette.ink(scheme).opacity(0.1))
                .frame(width: 136, height: 12)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 9)
    }
}

/// The folder silhouette in low ink: a body with a small tab on its top edge.
private struct SkeletonFolderIcon: View {
    let color: Color

    var body: some View {
        RoundedRectangle(cornerRadius: 4, style: .continuous)
            .fill(color)
            .frame(width: 28, height: 21)
            .overlay(alignment: .topLeading) {
                RoundedRectangle(cornerRadius: 2, style: .continuous)
                    .fill(color)
                    .frame(width: 12, height: 6)
                    .offset(x: 3, y: -3)
            }
            .frame(width: 28, height: 28, alignment: .bottom)
    }
}

/// Two pills where the space switcher is, in its fixed band. They are not
/// buttons: the switcher is a control that needs an account.
private struct SignedOutSpaceSwitcher: View {
    let scheme: ColorScheme

    /// The real switcher's row height, so the dock keeps its place.
    private let rowHeight: CGFloat = 38

    var body: some View {
        HStack(spacing: 22) {
            ForEach(0..<2, id: \.self) { _ in
                Circle()
                    .fill(Palette.ink(scheme).opacity(0.1))
                    .frame(width: 26, height: 26)
                    .frame(width: 40, height: rowHeight)
            }
        }
        .frame(maxWidth: .infinity)
        .frame(height: rowHeight)
        .skeletonShimmer()
        .accessibilityHidden(true)
        .allowsHitTesting(false)
    }
}

/// A very slow breath on the placeholder shapes. Reduce Motion keeps them
/// still; the skeletons hide themselves from VoiceOver.
private struct SkeletonShimmer: ViewModifier {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var bright = false

    func body(content: Content) -> some View {
        content
            .opacity(reduceMotion ? 1 : (bright ? 1 : 0.72))
            .animation(
                reduceMotion ? nil : .easeInOut(duration: 2.8).repeatForever(autoreverses: true),
                value: bright
            )
            .onAppear {
                guard !reduceMotion else { return }
                bright = true
            }
    }
}

private extension View {
    func skeletonShimmer() -> some View {
        modifier(SkeletonShimmer())
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

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
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
                        .zenFont(17, weight: .bold)
                        .foregroundStyle(Palette.ink(scheme))
                        .lineLimit(dynamicTypeSize.isAccessibilitySize ? 2 : 1)

                    Spacer(minLength: 0)
                }
                .padding(.vertical, 9)
                .contentShape(Rectangle())
            }
            .buttonStyle(PressedButtonStyle())

            if isExpanded {
                if let live = folder.live,
                   let source = live.sourceURL,
                   folder.tabs.isEmpty,
                   folder.subfolders?.isEmpty ?? true {
                    liveFolderSource(source, type: live.type)
                        .padding(.leading, isNested ? 0 : 20)
                        .transition(.opacity)
                }
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

    /// Zen syncs only a live folder's provider configuration, never the items it
    /// generates. When the folder is empty (the normal case on mobile), the
    /// expanded body offers the configured source instead of looking broken.
    private func liveFolderSource(_ source: String, type: String?) -> some View {
        let url = URL(string: source)
        let label = providerLabel(type)
        return Button {
            onOpenTab(ZenTab(id: "live-source-\(folder.id)", url: source, title: label))
        } label: {
            HStack(spacing: 10) {
                Image(systemName: "link")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(Palette.ink(scheme).opacity(0.5))
                VStack(alignment: .leading, spacing: 1) {
                    Text(label)
                        .zenFont(13, weight: .semibold, design: .rounded)
                        .foregroundStyle(Palette.ink(scheme))
                    Text(sourceDisplay(url))
                        .zenFont(11, design: .rounded)
                        .foregroundStyle(Palette.ink(scheme).opacity(0.55))
                        .lineLimit(1)
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.right")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(Palette.ink(scheme).opacity(0.3))
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(
                Palette.lift(scheme),
                in: RoundedRectangle(cornerRadius: 10, style: .continuous)
            )
            .contentShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
        }
        .buttonStyle(PressedButtonStyle())
        .accessibilityLabel(Text(label))
        .accessibilityValue(Text(sourceDisplay(url)))
    }

    /// Provider label for the source row; unknown ids fall back to a neutral
    /// label rather than exposing the raw id.
    private func providerLabel(_ type: String?) -> String {
        switch type {
        case "rss": return String(localized: "folder.live.rss")
        case "github": return String(localized: "folder.live.github")
        default: return String(localized: "folder.live.other")
        }
    }

    /// Host plus path, so `github.com/pulls` stays readable without the scheme.
    private func sourceDisplay(_ url: URL?) -> String {
        guard let url, let host = url.host, !host.isEmpty else {
            return url?.absoluteString ?? ""
        }
        let path = url.path
        return path.isEmpty || path == "/" ? host : host + path
    }
}
