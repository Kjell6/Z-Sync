import SwiftUI
import WebKit

/// In-app mini-browser. The page stays full-bleed behind the floating glass
/// bar, but its layout viewport ends above the bar while the bar is up, so
/// page content is never trapped under the glass.
/// The open menu and the address composer draw over the page, so the page
/// itself does not move. The address composer keeps running down to the
/// screen bottom, behind the keys (`MiniBrowserChrome`). A field on the page
/// does not lift that bar: the keys cover it, and a checkmark above them
/// closes the keyboard.
struct MiniBrowserView: View {
    /// The open pages. The view shows the active one and owns none of them.
    let pages: OpenPagesStore
    let space: ZenSpace
    let allSpaces: [ZenSpace]
    /// Whether a save here may write a normal tab. Comes from the synced
    /// browser's capability, like the share sheet.
    var allowsNormalTabs = false
    var onDismiss: () -> Void

    @Environment(\.colorScheme) private var scheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// Stands in for the frame between the last page going and the cover closing.
    @State private var placeholder = MiniBrowserModel(initialURL: nil)
    /// The native address field owns the real first responder; this mirrors it
    /// so the chrome can react (composer, keyboard, selection).
    @State private var isAddressFocused = false
    /// Composer is up. Separate from focus so the text field is created
    /// before it becomes first responder, and removed only after it resigns.
    @State private var editingAddress = false
    /// First-frame search chrome when opened from the search pill, so the
    /// sheet never animates unfocused → focused (X button, toolbar, alignment).
    @State private var startsInSearch: Bool
    /// Chrome springs stay off while the sheet presents, so the address bar
    /// rides the sheet instead of running a second animation.
    @State private var animateAddressChrome = false
    /// Distance from the screen bottom to the top of the keyboard.
    @State private var keyboardOverlap: CGFloat = 0
    /// Home-indicator inset. The plate fills it; the buttons sit above it.
    @State private var safeBottom: CGFloat = Self.windowSafeBottom()
    /// The overview is up, over the page and the chrome.
    @State private var showOverview: Bool
    @State private var confirmCloseAll = false
    /// The dock shows the overview's controls.
    @State private var overviewControls: Bool
    /// How far the deck has come in: 0 is only the page, 1 is the whole deck.
    @State private var overviewReveal: CGFloat
    /// The live page on its way between full screen and its card.
    @State private var pagePose = PageCardPose.identity
    /// The page is above the deck, moving with the finger or landing on its card.
    @State private var pageFloats = false
    /// The chrome is part of the card while the page is pulled up.
    @State private var chromeRides = false
    @State private var chromeFade: CGFloat = 1
    /// The pull has let go for the overview; the bar takes no new one.
    @State private var committingPull = false
    @State private var pullPassedPoint = false
    /// Every move of the overview takes a new number. A move that finishes
    /// after another has started finds its number gone and leaves things alone.
    @State private var transitionEpoch = 0
    /// The deck's cards above the page's own; see `OpenPagesOverview`.
    @State private var aheadReveal: CGFloat = 1
    /// A new page from the deck: its address field waits until it has flown in.
    @State private var holdsNewPageSearch = false
    /// A new deck each time the page leaves for it, with the order fresh.
    @State private var deckGeneration = 0
    /// The card growing back into its page, and how far it has got.
    @State private var expandingCardID: String?
    @State private var cardExpansion: CGFloat = 0
    /// The browser's own history is up. It belongs to the browser, not to a
    /// page: the overview has no page behind it when it is opened from home.
    @State private var historyShown = false
    /// The card the live page stands in for; it shows once the page lands.
    @State private var hiddenCardID: String?
    /// The overview is on its way out for a new page and shows no changes.
    @State private var overviewHoldsCards = false
    @State private var pageFrame: CGRect = .zero
    /// The neighbours sit beside the page while the address bar is swiped.
    @State private var peeking = false
    /// The swipe heads past the last page, towards a new one. Stays set until
    /// the page has settled, so the way back animates too.
    @State private var revealingNewPage = false
    /// Set once the browser is closing, so a second tap cannot close it twice.
    @State private var leaving = false
    /// How far the address bar swipe has moved the page.
    @State private var pageSwipe: CGFloat = 0
    @State private var paneWidth: CGFloat = 0

    init(
        pages: OpenPagesStore,
        space: ZenSpace,
        allSpaces: [ZenSpace],
        allowsNormalTabs: Bool = false,
        startsInOverview: Bool = false,
        onDismiss: @escaping () -> Void
    ) {
        self.pages = pages
        self.space = space
        self.allSpaces = allSpaces
        self.allowsNormalTabs = allowsNormalTabs
        self.onDismiss = onDismiss
        _startsInSearch = State(initialValue: pages.active?.model.opensWithSearch ?? false)
        _showOverview = State(initialValue: startsInOverview)
        // From home it comes up with the cover, like the browser does.
        _overviewControls = State(initialValue: startsInOverview)
        _overviewReveal = State(initialValue: startsInOverview ? 1 : 0)
    }

    /// The page on screen.
    private var model: MiniBrowserModel { pages.active?.model ?? placeholder }

    /// Keyboard focus, the tap that opens the composer, or the search-open first paint.
    private var addressEditing: Bool { editingAddress || isAddressFocused || startsInSearch }

    /// The bar rides above the keyboard while the address is edited, so the
    /// text being typed stays visible. Otherwise it sits on the screen bottom.
    /// The dock leaves before the keyboard has moved, so the bar holds its
    /// place until the keyboard reaches it: it goes up in one move, never
    /// down first.
    private var chromeBottomInset: CGFloat {
        guard addressEditing else { return chromeBottomPadding }
        let aboveDock = chromeBottomPadding + MiniBrowserChromeMetrics.dockHeight
            + MiniBrowserChromeMetrics.rowSpacing
        return max(aboveDock, keyboardOverlap + 10)
    }

    /// Air between the controls and the physical bottom. The home indicator
    /// occupies the bottom of the safe area; the controls sit just above it.
    private var chromeBottomPadding: CGFloat {
        max(8, safeBottom - 14)
    }

    /// The bar tucks away while the page scrolls down and returns on the way
    /// up. Editing the address, an open menu and a live toast keep it in place.
    private var barCollapsed: Bool {
        guard model.scrollHidesBar,
              !showOverview,
              !addressEditing,
              !model.menuExpanded,
              model.pinNotice == nil,
              !model.showPinBanner,
              model.pageError == nil,
              model.readerArticle == nil,
              model.dialogs.isEmpty,
              keyboardOverlap == 0 else { return false }
        return true
    }

    private var barMotion: Animation {
        reduceMotion ? .easeOut(duration: 0.15) : .spring(response: 0.35, dampingFraction: 0.86)
    }

    /// A page without a theme color takes the app's normal background, so the
    /// browser starts on the same surface as the Settings sheet. The web view
    /// paints its own page over it.
    private var defaultBackground: Color {
        Palette.background(scheme)
    }

    /// The page's own color, or the error panel's while it covers the page:
    /// the failed load leaves the previous page's color behind.
    private var sheetBackground: Color { pageColor(of: model) }

    private func pageColor(of page: MiniBrowserModel) -> Color {
        if page.pageError != nil { return Palette.background(scheme) }
        return page.themeColor ?? defaultBackground
    }

    /// The bar's surface: the page's own color, nudged a touch darker or
    /// lighter so the plate is visible against it while still reading as the
    /// site's color. Without a theme color, the system background is nudged.
    private var barBackground: Color {
        (model.themeColor ?? defaultBackground).nudgedForBar() ?? sheetBackground
    }

    /// The bar is nearly the page's color, so the page decides whether the
    /// controls must be light or dark — not the system theme.
    private var barScheme: ColorScheme {
        barBackground.isDarkBackground ? .dark : .light
    }

    /// The floating bar and dock, over the page, the overview and the
    /// picture flying between them.
    private var chrome: some View {
        // Hung from an empty layer: with the keyboard up and a full list of
        // suggestions the column is taller than the screen, and laid out as a
        // sibling it would stretch the whole browser, page and bar with it.
        Color.clear.overlay(alignment: .bottom) {
            MiniBrowserChrome(
                model: model,
                pages: pages,
                space: space,
                allSpaces: allSpaces,
                onToggleMenu: { model.toggleMenu() },
                onClose: { leaveBrowser() },
                addressEditing: addressEditing,
                onPickSuggestion: pickSuggestion,
                onPickVisit: pickVisit,
                onOpenPages: openOverview,
                overviewShown: overviewControls,
                onNewPage: openNewPage,
                onOpenHistory: openHistory,
                onCloseAll: { confirmCloseAll = true },
                navBar: {
                    MiniBrowserTopBar(
                        model: model,
                        page: pages.active,
                        pages: pages,
                        addressEditing: addressEditing,
                        isAddressFocused: $isAddressFocused,
                        swipeOffset: $pageSwipe,
                        pageSpan: paneWidth + PageHostView.pageGap,
                        onBeginAddressEdit: beginAddressEditing,
                        onCancel: cancelAddressEditing,
                        onSubmit: submitAddress,
                        onReturnToOpener: returnToOpener,
                        onSwipeStart: beginSwipe,
                        onSwipeEnd: finishSwipe,
                        onPullStart: beginPagePull,
                        onPullChange: updatePagePull,
                        onPullEnd: endPagePull
                    )
                }
            )
            .padding(.horizontal, 16)
            .padding(.bottom, chromeBottomInset + (overviewControls ? MiniBrowserChromeMetrics.overviewDockLift : 0))
            .animation(.spring(response: 0.24, dampingFraction: 0.82), value: overviewControls)
            .frame(maxWidth: .infinity)
            .offset(y: barCollapsed ? 220 : 0)
            // While the page is pulled the chrome is part of it.
            .scaleEffect(chromeRides ? pagePose.scale : 1, anchor: .bottom)
            .offset(x: chromeRides ? pagePose.x : 0, y: chromeRides ? pagePose.y : 0)
            // With nothing left to show, the cover is on its way out.
            .opacity(barCollapsed || (pages.active == nil && !showOverview) ? 0 : Double(chromeFade))
            .allowsHitTesting(!committingPull)
            .accessibilityHidden(barCollapsed)
        }
    }

    /// Bottom to top: the page, the overview, the picture flying between them,
    /// and the chrome. The dock stays on top of it all, so a page zooming into
    /// its card passes under the buttons, never over them.
    var body: some View {
        NavigationStack {
            ZStack {
                ZStack {
                    if revealingNewPage {
                        newPagePeek
                    }

                    ZStack {
                        // A new page is still blank while it flies in. Its own
                        // surface fills the card, so the deck behind it does
                        // not show through the empty web view.
                        if holdsNewPageSearch {
                            ZStack {
                                RoundedRectangle(cornerRadius: pagePose.corner, style: .continuous)
                                    .fill(defaultBackground)
                                RoundedRectangle(cornerRadius: pagePose.corner, style: .continuous)
                                    .fill(Palette.lift(scheme))
                            }
                            .glassRim(cornerRadius: pagePose.corner)
                        }

                        webPane
                            .overlay {
                                if let error = model.pageError {
                                    errorPanel(error)
                                }
                            }

                        if let article = model.readerArticle {
                            ReaderView(
                                article: article,
                                scheme: scheme,
                                bottomClearance: pageBottomClearance
                            ) {
                                model.closeReader()
                            }
                            .transition(.opacity)
                        }
                    }
                    .background(revealingNewPage || pageFloats ? Color.clear : sheetBackground)
                    .background { floatingPageShadow }
                    .overlay {
                        // A tap on the page while it lands takes it back.
                        if committingPull {
                            Color.clear
                                .contentShape(Rectangle())
                                .onTapGesture { returnPage() }
                        }
                    }
                    .offset(x: pageSwipe)
                    .scaleEffect(pagePose.scale, anchor: .bottom)
                    .offset(x: pagePose.x, y: pagePose.y)

                    if model.menuExpanded {
                        // UIKit scrim, not a SwiftUI tap gesture. A SwiftUI
                        // gesture left on top of the web view kept eating
                        // touches after the menu closed.
                        PageDismissScrim {
                            withAnimation(.spring(response: 0.38, dampingFraction: 0.86)) {
                                model.collapseMenu()
                            }
                        }
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .accessibilityLabel(Text("browser.close_menu"))
                        .accessibilityAddTraits(.isButton)
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: {
                    pageFrame = $0
                    paneWidth = $0.width
                }
                .zIndex(pageFloats ? 2 : 0)

                if showOverview {
                    overview
                        .id(deckGeneration)
                        .allowsHitTesting(expandingCardID == nil && !overviewHoldsCards)
                        .zIndex(1)
                }

                // The page runs out into a soft blur at the bottom instead of
                // stopping against the controls.
                PageBottomFade()
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
                    // Back before the card has finished growing, so the page
                    // does not change at the bottom when the card goes.
                    .opacity(barCollapsed || (showOverview && expandingCardID == nil) ? 0 : 1)
                    .animation(barMotion, value: barCollapsed)
                    .animation(.easeOut(duration: 0.12), value: showOverview)
                    .animation(.easeOut(duration: 0.3), value: expandingCardID)
                    .zIndex(4)

                if let notice = model.pinNotice {
                    pinNoticeToast(notice)
                        .padding(.horizontal, 20)
                        .padding(.bottom, 132)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
                        .environment(\.colorScheme, barScheme)
                        .zIndex(5)
                }

                chrome
                    .zIndex(6)

                // A small, accessible way back even when the page cannot be
                // scrolled (for example after its content changes dynamically).
                if barCollapsed {
                    Button {
                        model.setScrollHidesBar(false)
                    } label: {
                        Image(systemName: "chevron.up")
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(barScheme == .dark ? Color.white : Color.black)
                            .frame(width: 30, height: 30)
                            .background(barBackground.opacity(0.9), in: Circle())
                            .frame(width: 48, height: 48)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Show browser controls")
                    .padding(.bottom, max(0, safeBottom - 14))
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
                    .zIndex(7)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
            .background(sheetBackground.ignoresSafeArea())
            .ignoresSafeArea(edges: .bottom)
            .ignoresSafeArea(.keyboard)
            .presentationBackground(pages.active == nil ? Palette.background(scheme) : barBackground)
            .modifier(BrowserDialogPresenter(model: model))
            .modifier(BrowserHistoryPresenter(isPresented: $historyShown, onOpen: openVisit))
            .confirmationDialog(
                Text("kept.close_all_title"),
                isPresented: $confirmCloseAll,
                titleVisibility: .visible
            ) {
                Button(role: .destructive) {
                    withAnimation(.spring(duration: 0.3, bounce: 0)) { pages.closeAll() }
                } label: {
                    Text("kept.close_all")
                }
            } message: {
                Text("kept.close_all_message")
            }
            .onChange(of: pageSwipe) { _, offset in
                if offset < 0, pages.neighbours.next == nil, !revealingNewPage { revealingNewPage = true }
            }
            .onChange(of: pages.activeID) { _, id in
                guard id != nil else {
                    // The overview stays up empty, like Safari's; its Done goes.
                    if !showOverview { onDismiss() }
                    return
                }
                activePageChanged()
            }
            .onDisappear {
                // The cover can go without the close button, for one with the
                // session. Whatever is still open ends the same way.
                if pages.activeID != nil { pages.endBrowserSession() }
            }
            .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillChangeFrameNotification)) { note in
                let overlap = Self.keyboardOverlap(from: note)
                // While the address field is focused, a frame that reports the
                // keyboard gone is a transient (predictive bar, dictation, a
                // hardware keyboard taking over). Dropping the composer to the
                // dock and springing it back is what made it jump under the
                // finger while typing.
                guard !(isAddressFocused && overlap == 0 && keyboardOverlap > 0) else { return }
                if overlap > 0 || keyboardOverlap > 0 { model.setScrollHidesBar(false) }
                withAnimation(Self.keyboardAnimation(from: note)) {
                    keyboardOverlap = overlap
                }
            }
            .animation(
                animateAddressChrome ? .spring(response: 0.35, dampingFraction: 0.85) : nil,
                value: addressEditing
            )
            .animation(barMotion, value: barCollapsed)
            .animation(.spring(response: 0.38, dampingFraction: 0.86), value: model.pinNotice != nil)
            .animation(.easeInOut(duration: 0.25), value: model.themeColor)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar(.hidden, for: .navigationBar)
            .statusBarHidden(barCollapsed)
            .tint(Palette.ink(scheme))
            .onAppear {
                safeBottom = Self.windowSafeBottom()
                // Opened from home for the overview, no page has asked for this.
                if pages.active != nil, model.opensWithSearch {
                    isAddressFocused = true
                }
                Task { @MainActor in
                    try? await Task.sleep(for: .milliseconds(450))
                    animateAddressChrome = true
                }
            }
            .onChange(of: model.currentURL) { _, newURL in
                model.currentURLDidChange(newURL, isAddressFocused: isAddressFocused)
            }
            .onChange(of: model.failedURL) { _, _ in
                model.pageErrorDidChange(isAddressFocused: isAddressFocused)
            }
            .onChange(of: model.pinNotice) { _, notice in
                // The refusal toast sits above the collapsed bar. An open
                // sheet would cover it, so a notice closes the menu.
                guard notice != nil, model.menuExpanded else { return }
                withAnimation(.spring(response: 0.38, dampingFraction: 0.86)) {
                    model.collapseMenu()
                }
            }
            .onChange(of: isAddressFocused) { _, focused in
                if focused {
                    model.collapseMenu()
                } else {
                    // Resign first. Remove the field and restore the short
                    // label only after that update, so the text write cannot
                    // hit a live editor.
                    DispatchQueue.main.async {
                        guard !isAddressFocused else { return }
                        editingAddress = false
                        startsInSearch = false
                        DispatchQueue.main.async {
                            guard !isAddressFocused else { return }
                            model.restoreAddressDisplay()
                        }
                    }
                }
            }
        }
    }

    private var overview: some View {
        OpenPagesOverview(
            pages: pages,
            spaces: allSpaces,
            saveKind: allowsNormalTabs ? model.saveKind : .pinned,
            hiddenCardID: hiddenCardID,
            expandingCardID: expandingCardID,
            expansion: cardExpansion,
            excludedCardID: overviewHoldsCards ? pages.activeID : nil,
            reveal: overviewReveal,
            aheadReveal: aheadReveal,
            topInset: pageFrame.minY,
            bottomClearance: MiniBrowserChromeMetrics.overviewDockHeight + MiniBrowserChromeMetrics.overviewDockLift
                + chromeBottomPadding + 10,
            onOpen: closeOverview(to:)
        )
    }

    /// The same shadow the card has, there from the moment the page leaves
    /// the screen's edges. Sized up by what the page's scale will take off.
    @ViewBuilder
    private var floatingPageShadow: some View {
        if pageFloats {
            // In the page's own color: a new page has nothing painted yet.
            RoundedRectangle(cornerRadius: pagePose.corner, style: .continuous)
                .fill(sheetBackground)
                .padding(.top, -pageFrame.minY)
                .shadow(color: .black.opacity(0.26), radius: 20, x: -4.4, y: 0)
                .opacity(Double(overviewReveal))
        }
    }

    private var screenSize: CGSize {
        CGSize(width: pageFrame.width, height: pageFrame.maxY)
    }

    /// The page stops at the bar: the plate is opaque, so there is nothing to
    /// paint under it and no viewport inset to reserve.
    private var webPane: some View {
        let neighbours = pages.neighbours
        return MiniBrowserRepresentable(
            model: model,
            bottomClearance: pageBottomClearance,
            previous: peeking ? neighbours.previous.map(peek) : nil,
            next: peeking ? neighbours.next.map(peek) : nil,
            slidingColor: peeking || pageFloats ? UIColor(sheetBackground) : nil,
            topOverflow: pageFrame.minY,
            cornerRadius: revealingNewPage ? DisplayCorners.radius : pagePose.corner
        )
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    /// Height the page has to keep clear at the bottom. Exactly the floating
    /// chrome while it is up, zero once it tucks away, computed from the same
    /// metrics the chrome draws with. The page stays full-bleed behind the
    /// glass; only its layout viewport ends above the bar, so cookie banners
    /// and sticky CTAs lay out in the visible strip instead of under it.
    private var pageBottomClearance: CGFloat {
        guard !barCollapsed else { return 0 }
        return MiniBrowserChromeMetrics.height(above: chromeBottomInset)
    }

    /// What waits past the last page: not a page yet, so it does not pretend to
    /// be one. A blank sheet grows up out of the background as the finger
    /// pulls it in, the way Safari's does.
    private var newPagePeek: some View {
        let span = paneWidth + PageHostView.pageGap
        let progress = span > 0 ? min(1, max(0, -pageSwipe / span)) : 0
        return ZStack {
            Palette.background(scheme)
                .ignoresSafeArea()
            RoundedRectangle(cornerRadius: DisplayCorners.radius, style: .continuous)
                .fill(defaultBackground)
                .ignoresSafeArea()
                .scaleEffect(0.9 + 0.1 * progress)
                .offset(x: (1 - progress) * paneWidth * 0.3)
                .opacity(0.45 + 0.55 * progress)
        }
        .allowsHitTesting(false)
    }

    private func peek(_ page: OpenPage) -> PeekingPage {
        PeekingPage(webView: page.model.webView, shot: page.shot, color: UIColor(pageColor(of: page.model)))
    }

    private static func windowSafeBottom() -> CGFloat {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
            .first(where: \.isKeyWindow)?
            .safeAreaInsets.bottom ?? 0
    }

    private static func keyboardOverlap(from note: Notification) -> CGFloat {
        guard let frame = note.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? CGRect else {
            return 0
        }
        let screenHeight = (note.object as? UIScreen)?.bounds.height
            ?? UIApplication.shared.connectedScenes
                .compactMap { $0 as? UIWindowScene }
                .flatMap(\.windows)
                .first(where: \.isKeyWindow)?
                .bounds.height
            ?? 0
        guard screenHeight > 0 else { return 0 }
        return max(0, screenHeight - frame.minY)
    }

    private static func keyboardAnimation(from note: Notification) -> Animation {
        let duration = note.userInfo?[UIResponder.keyboardAnimationDurationUserInfoKey] as? Double ?? 0.25
        let raw = note.userInfo?[UIResponder.keyboardAnimationCurveUserInfoKey] as? Int
            ?? UIView.AnimationCurve.easeInOut.rawValue
        switch UIView.AnimationCurve(rawValue: raw) {
        case .linear:
            return .linear(duration: duration)
        case .easeIn:
            return .easeIn(duration: duration)
        case .easeOut:
            return .easeOut(duration: duration)
        default:
            return .easeInOut(duration: duration)
        }
    }

}

private extension MiniBrowserView {
    /// Closes the browser. What happens to each page is the store's call; the
    /// picture for the list is taken before the page goes away.
    private func leaveBrowser() {
        guard !leaving else { return }
        leaving = true
        Task { @MainActor in
            if let page = pages.active { await pages.capture(page) }
            // The session ends once the cover is gone: while it slides away
            // it keeps showing the page, not an empty browser.
            onDismiss()
        }
    }

    /// Another page came on screen: the chrome starts over for it, in one
    /// step. A new page goes straight into the address field, the same way a
    /// tap on the bar does: the bar opens up while the keyboard comes.
    private func activePageChanged() {
        if isAddressFocused { isAddressFocused = false }
        keyboardOverlap = 0
        guard model.opensWithSearch else {
            holdChromeAnimation()
            editingAddress = false
            startsInSearch = false
            model.restoreAddressDisplay()
            return
        }
        guard !holdsNewPageSearch else { return }
        startNewPageSearch()
    }

    private func startNewPageSearch() {
        startsInSearch = true
        editingAddress = true
        DispatchQueue.main.async { isAddressFocused = true }
    }

    /// The chrome's springs belong to the user editing the address, not to a
    /// page change: they would run the bar through a morph mid-switch.
    private func holdChromeAnimation() {
        animateAddressChrome = false
        Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(450))
            animateAddressChrome = true
        }
    }

    /// How far up the finger has to go for the page to reach its card's size.
    private var pullTravel: CGFloat { max(screenSize.height * 0.32, 1) }

    /// The page's place in the deck: the front, unless it was just swiped to.
    private var activeDeckIndex: Int {
        let ordered = pages.deckOrder
        return ordered.firstIndex { $0.id == pages.activeID } ?? max(0, ordered.count - 1)
    }

    /// Where the page has to be to lie exactly on its card.
    private func cardPose() -> PageCardPose? {
        guard screenSize.width > 0, screenSize.height > 0 else { return nil }
        let deck = PageDeckGeometry(screen: screenSize, count: CGFloat(pages.pages.count))
        let place = CGFloat(activeDeckIndex)
        let card = deck.frame(index: place, focus: place)
        let scale = card.width / screenSize.width
        return PageCardPose(
            x: card.midX - screenSize.width / 2,
            y: card.maxY - screenSize.height,
            scale: scale,
            corner: PageDeckGeometry.cornerRadius / scale
        )
    }

    /// The finger took hold of the page: the deck goes in behind it right
    /// away, and the page's picture is taken while it is still the whole page.
    private func beginPagePull() {
        guard let page = pages.active, !committingPull else { return }
        transitionEpoch += 1
        // A flight cut short must not hold back the search of later new pages.
        holdsNewPageSearch = false
        aheadReveal = activeDeckIndex < pages.pages.count - 1 ? 0 : 1
        pullPassedPoint = false
        // A card may still be growing into this page, and that deck has its
        // focus and order from before. A page coming back keeps its deck.
        if !pageFloats { deckGeneration += 1 }
        expandingCardID = nil
        cardExpansion = 0
        overviewHoldsCards = false
        withAnimation(.spring(response: 0.38, dampingFraction: 0.86)) { model.collapseMenu() }
        hiddenCardID = page.id
        // Round from the first point on: the display's own curve, so nothing
        // changes where the page meets the screen's edge.
        if let card = cardPose() { pagePose.corner = card.corner }
        pageFloats = true
        chromeRides = true
        showOverview = true
        Task { @MainActor in
            await pages.capture(page)
        }
    }

    /// The page shrinks under the finger, like an app pulled up from the home
    /// bar: bottom edge with the finger, the deck coming in around it.
    private func updatePagePull(_ translation: CGSize) {
        guard pageFloats, !committingPull, let card = cardPose() else { return }
        let progress = min(1, max(0, -translation.height / pullTravel))
        let eased = 1 - pow(1 - progress, 1.7)
        pagePose = PageCardPose(
            x: translation.width * 0.6,
            y: min(0, translation.height),
            scale: 1 - (1 - card.scale) * eased,
            corner: card.corner
        )
        let appearing = min(1, max(0, (progress - 0.03) / 0.52))
        overviewReveal = appearing * appearing * (3 - 2 * appearing)
        chromeFade = 1 - min(1, max(0, (progress - 0.1) / 0.3))
        let passed = progress > Self.pullCommitPoint
        if passed != pullPassedPoint {
            pullPassedPoint = passed
            if passed { AppServices.haptics.selectionChanged() }
        }
    }

    private static let pullCommitPoint: CGFloat = 0.16

    /// A pull past the point, or a throw upwards, goes on to the deck. A pull
    /// back down, or too short a one, gives the page back.
    private func endPagePull(_ end: PagePullEnd) {
        guard pageFloats, !committingPull else { return }
        let progress = -end.translation.height / pullTravel
        let opens = !end.cancelled && end.velocity < 250
            && (progress > Self.pullCommitPoint || end.velocity < -350)
        if opens {
            landOnCard(velocity: end.velocity)
        } else {
            returnPage()
        }
    }

    /// The page lands on its card at the finger's speed. The card takes over
    /// once it is there, and the dock changes its controls while the page flies.
    private func landOnCard(velocity: CGFloat) {
        guard let card = cardPose() else {
            settleFloatingPage()
            fadeOverviewIn()
            return
        }
        transitionEpoch += 1
        let epoch = transitionEpoch
        committingPull = true
        let distance = card.y - pagePose.y
        let speed = distance == 0 ? 0 : min(10, max(-10, velocity / distance))
        withAnimation(.interpolatingSpring(duration: 0.42, bounce: 0, initialVelocity: speed)) {
            pagePose = card
            overviewReveal = 1
        } completion: {
            guard epoch == transitionEpoch else { return }
            var transaction = Transaction()
            transaction.disablesAnimations = true
            withTransaction(transaction) {
                hiddenCardID = nil
                pageFloats = false
                pagePose = .identity
                committingPull = false
            }
            withAnimation(.spring(duration: 0.32, bounce: 0)) { aheadReveal = 1 }
        }
        withAnimation(.easeOut(duration: 0.12)) { chromeFade = 0 } completion: {
            guard epoch == transitionEpoch else { return }
            var transaction = Transaction()
            transaction.disablesAnimations = true
            withTransaction(transaction) {
                chromeRides = false
                overviewControls = true
            }
            withAnimation(.easeOut(duration: 0.2)) { chromeFade = 1 }
        }
    }

    /// The page at once where a landing would leave it, under its card.
    private func settleFloatingPage() {
        var transaction = Transaction()
        transaction.disablesAnimations = true
        withTransaction(transaction) {
            hiddenCardID = nil
            pageFloats = false
            pagePose = .identity
            committingPull = false
            chromeRides = false
            chromeFade = 1
            overviewReveal = 1
            aheadReveal = 1
        }
    }

    private func returnPage() {
        transitionEpoch += 1
        let epoch = transitionEpoch
        committingPull = false
        overviewControls = false
        withAnimation(.spring(duration: 0.36, bounce: 0)) {
            pagePose = PageCardPose(x: 0, y: 0, scale: 1, corner: pagePose.corner)
            overviewReveal = 0
            chromeFade = 1
        } completion: {
            guard epoch == transitionEpoch else { return }
            var transaction = Transaction()
            transaction.disablesAnimations = true
            withTransaction(transaction) {
                pagePose = .identity
                showOverview = false
                pageFloats = false
                chromeRides = false
                hiddenCardID = nil
                aheadReveal = 1
            }
        }
    }

    /// The dock's button takes the same way the finger does.
    private func openOverview() {
        guard !reduceMotion, pages.active != nil else {
            withAnimation(.spring(response: 0.38, dampingFraction: 0.86)) { model.collapseMenu() }
            fadeOverviewIn()
            return
        }
        beginPagePull()
        let epoch = transitionEpoch
        Task { @MainActor in
            // The deck has to be on screen for its way in to animate.
            try? await Task.sleep(for: .milliseconds(30))
            guard epoch == transitionEpoch, pageFloats else { return }
            landOnCard(velocity: 0)
        }
    }

    private func fadeOverviewIn() {
        // A fade-out still running must not hide the deck coming back.
        transitionEpoch += 1
        overviewHoldsCards = false
        holdsNewPageSearch = false
        showOverview = true
        overviewControls = true
        withAnimation(.easeOut(duration: 0.2)) { overviewReveal = 1 }
    }

    private func fadeOverviewOut() {
        transitionEpoch += 1
        let epoch = transitionEpoch
        overviewControls = false
        withAnimation(.easeOut(duration: 0.2)) { overviewReveal = 0 } completion: {
            guard epoch == transitionEpoch else { return }
            showOverview = false
        }
    }

    /// The tapped card grows to the whole screen inside the deck, so the
    /// cards lying on it run off over it instead of it jumping in front of them.
    /// The live page takes over under the finished card.
    private func closeOverview(to page: OpenPage) {
        guard expandingCardID == nil else { return }
        if pageFloats {
            // Tapped while the page was still landing.
            if page.id == pages.activeID {
                returnPage()
                return
            }
            settleFloatingPage()
        }
        let wasAsleep = page.model.webView == nil
        // Chosen here, it is the current page at once. The deck's order still
        // holds for a moment, so going straight back to the deck — or swiping
        // from the page — finds the tab where it was, not already at the front.
        pages.beginSwitching()
        pages.activate(page.id)
        pages.finishSwitching(after: .seconds(1.5))
        guard !reduceMotion else {
            fadeOverviewOut()
            return
        }
        transitionEpoch += 1
        let epoch = transitionEpoch
        overviewControls = false
        withAnimation(PageDeckGeometry.openMotion) {
            expandingCardID = page.id
            cardExpansion = 1
            overviewReveal = 0
        } completion: {
            guard epoch == transitionEpoch else { return }
            Task { @MainActor in
                if wasAsleep { await waitForFirstPaint(of: page) }
                guard epoch == transitionEpoch else { return }
                // A cut, not a fade: the card shows this very page, and the
                // two blended into each other dimmed for a few frames.
                var transaction = Transaction()
                transaction.disablesAnimations = true
                withTransaction(transaction) {
                    showOverview = false
                    expandingCardID = nil
                    cardExpansion = 0
                }
            }
        }
    }

    /// The browser's own history, opened from the overview's dock.
    private func openHistory() {
        historyShown = true
    }

    /// A page from the browser's history. It lands in the page on screen, the
    /// way a typed address would; with the overview opened from home there is
    /// no page yet, so a new one takes it.
    private func openVisit(_ visit: BrowserVisit) {
        guard let url = URL(string: visit.url) else { return }
        let page: OpenPage
        if let active = pages.active {
            active.model.loadVisit(visit)
            page = active
        } else {
            page = pages.open(url: url)
        }
        guard showOverview else { return }
        // The sheet has already closed; the deck goes with it.
        closeOverview(to: page)
    }

    /// A page that was asleep has an empty web view at first, and fading the
    /// card away would flash it. The card stays over it until the page has
    /// loaded. It lets touches through meanwhile, so waiting blocks nothing.
    private func waitForFirstPaint(of page: OpenPage) async {
        var sawLoading = false
        for _ in 0..<80 {
            let model = page.model
            if model.isLoading { sawLoading = true }
            if model.estimatedProgress >= 0.9 || (sawLoading && !model.isLoading) { return }
            try? await Task.sleep(for: .milliseconds(50))
        }
    }

    /// Where a new page starts: beside the deck, as the card after the last.
    private func newPageStartPose() -> PageCardPose? {
        guard screenSize.width > 0, screenSize.height > 0 else { return nil }
        let count = pages.pages.count
        let deck = PageDeckGeometry(screen: screenSize, count: CGFloat(count))
        let last = CGFloat(max(0, count - 1))
        let card = deck.frame(index: last, focus: last - 1)
        let scale = card.width / screenSize.width
        return PageCardPose(
            x: card.midX - screenSize.width / 2,
            y: card.maxY - screenSize.height,
            scale: scale,
            corner: PageDeckGeometry.cornerRadius / scale
        )
    }

    /// A blank sheet comes in from the right and grows to the whole screen.
    /// The deck stays exactly where it is: only the new page moves, and the
    /// cards are covered instead of sliding or fading away under it.
    private func openNewPage() {
        transitionEpoch += 1
        let epoch = transitionEpoch
        pages.releaseSwitchingOrder()
        // The deck stays as it was while it goes: no card joins it on the way out.
        overviewHoldsCards = true
        holdsNewPageSearch = !reduceMotion
        pages.open(url: nil)
        guard !reduceMotion, let start = newPageStartPose() else {
            // No flight follows: the page change starts the search as usual.
            holdsNewPageSearch = false
            overviewControls = false
            withAnimation(.easeOut(duration: 0.2)) { overviewReveal = 0 } completion: {
                guard epoch == transitionEpoch else { return }
                showOverview = false
                overviewHoldsCards = false
            }
            return
        }
        var placed = Transaction()
        placed.disablesAnimations = true
        withTransaction(placed) {
            pagePose = start
            pageFloats = true
            chromeRides = false
        }
        Task { @MainActor in
            // On screen where it starts, before it moves.
            try? await Task.sleep(for: .milliseconds(30))
            guard epoch == transitionEpoch else { return }
            withAnimation(.spring(duration: 0.42, bounce: 0)) {
                pagePose = PageCardPose(x: 0, y: 0, scale: 1, corner: start.corner)
            } completion: {
                guard epoch == transitionEpoch else { return }
                var transaction = Transaction()
                transaction.disablesAnimations = true
                withTransaction(transaction) {
                    pagePose = .identity
                    pageFloats = false
                    // The page covers the deck by now; it goes without a trace.
                    showOverview = false
                    overviewHoldsCards = false
                    overviewReveal = 0
                    overviewControls = false
                }
                // Only now: bringing up the keyboard holds the main thread,
                // and the flight would stop dead in the middle.
                holdsNewPageSearch = false
                startNewPageSearch()
                withAnimation(.easeOut(duration: 0.22)) { chromeFade = 1 }
            }
            withAnimation(.easeOut(duration: 0.1)) { chromeFade = 0 }
        }
    }

    /// A swipe started: the neighbours wake and take their places beside the page.
    private func beginSwipe() {
        pages.beginSwitching()
        pages.prepareNeighbours()
        peeking = true
        Task { await pictureActivePage() }
    }

    private func pictureActivePage() async {
        guard let page = pages.active else { return }
        await pages.capture(page)
    }

    /// Back on a page a link opened, once it has no history left: it ends, and
    /// the page that opened it comes back.
    private func returnToOpener() {
        guard let page = pages.active else { return }
        pages.close(page.id)
    }

    /// The finger let go. The spring takes over at the finger's own speed, so
    /// the page never changes pace. Pages already sit where they end up, so
    /// the switch itself shows nothing.
    private func finishSwipe(_ end: PageSwipeEnd) {
        pages.finishSwitching()
        let span = paneWidth + PageHostView.pageGap
        guard end.target != .stay else {
            let speed = pageSwipe == 0 ? 0 : -end.velocity / pageSwipe
            withAnimation(.interpolatingSpring(duration: 0.3, bounce: 0, initialVelocity: speed)) {
                pageSwipe = 0
            } completion: {
                guard pageSwipe == 0 else { return }
                peeking = false
                revealingNewPage = false
            }
            return
        }
        let destination = end.direction * span
        let distance = destination - pageSwipe
        let speed = distance == 0 ? 0 : end.velocity / distance
        withAnimation(
            .interpolatingSpring(duration: 0.3, bounce: 0, initialVelocity: speed),
            completionCriteria: .logicallyComplete
        ) {
            pageSwipe = destination
        } completion: {
            var transaction = Transaction()
            transaction.disablesAnimations = true
            withTransaction(transaction) {
                switch end.target {
                case .page(let id): pages.activate(id)
                case .newPage: pages.open(url: nil)
                case .stay: break
                }
                peeking = false
                revealingNewPage = false
                pageSwipe = 0
            }
        }
        AppServices.haptics.selectionChanged()
    }

    private func beginAddressEditing() {
        // The full URL is written while the collapsed bar is still a label.
        // The text field is created afterwards, already holding that string.
        model.prepareAddressForEditing()
        model.collapseMenu()
        editingAddress = true
    }

    private func cancelAddressEditing() {
        if model.pageURL != nil {
            if isAddressFocused {
                isAddressFocused = false
            } else {
                editingAddress = false
                startsInSearch = false
                model.restoreAddressDisplay()
            }
        } else if let page = pages.active {
            // A new page nothing was typed into: it ends, and the page before
            // it comes back. The last one closes the browser.
            if let previous = pages.neighbours.previous {
                slideBack(from: page, to: previous)
            } else if pages.pages.count > 1 {
                pages.close(page.id)
            } else {
                leaveBrowser()
            }
        }
    }

    /// The way back from a new page runs the way it came: the keyboard goes
    /// down while the page before slides in from the side, live.
    private func slideBack(from page: OpenPage, to previous: OpenPage) {
        pages.prepareNeighbours()
        peeking = true
        // Resign first: the field goes once it has let go, so the keyboard
        // leaves with the page instead of waiting for it.
        if isAddressFocused {
            isAddressFocused = false
        } else {
            editingAddress = false
            startsInSearch = false
        }
        withAnimation(
            .spring(duration: 0.34, bounce: 0),
            completionCriteria: .logicallyComplete
        ) {
            pageSwipe = paneWidth + PageHostView.pageGap
        } completion: {
            var transaction = Transaction()
            transaction.disablesAnimations = true
            withTransaction(transaction) {
                // The page that slid in stays; closing alone would pick by list.
                pages.activate(previous.id)
                pages.close(page.id)
                peeking = false
                pageSwipe = 0
            }
        }
    }

    private func errorPanel(_ error: BrowserPageError) -> some View {
        BrowserErrorPanel(
            error: error,
            host: model.failedURL.map(BrowserInput.displayText(for:)) ?? "",
            isLoading: model.isLoading,
            onRetry: { model.retryFailedLoad() },
            onSearch: { model.searchFailedHost() },
            onOpenExternally: { model.openCurrentInExternalBrowser() },
            onLeave: {
                guard model.leaveErrorPage(), let page = pages.active else { return }
                if page.openerID != nil {
                    pages.close(page.id)
                } else {
                    leaveBrowser()
                }
            }
        )
    }

    private func submitAddress() {
        // Resolve first. Closing the field clears the "just submitted" latch,
        // and a text change in between must not start a suggest request.
        model.submitAddress()
        endAddressEditing()
    }

    private func pickSuggestion(_ suggestion: String) {
        model.selectSuggestion(suggestion)
        endAddressEditing()
    }

    private func pickVisit(_ visit: BrowserVisit) {
        model.openVisit(visit)
        endAddressEditing()
    }

    private func endAddressEditing() {
        if isAddressFocused {
            isAddressFocused = false
        } else {
            editingAddress = false
            startsInSearch = false
        }
    }

    // MARK: - Pin Refusal Toast

    private func pinNoticeToast(_ text: String) -> some View {
        HStack(spacing: 10) {
            Image(systemName: model.pinNoticeSymbol)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(.primary)

            Text(text)
                .zenFont(16, weight: .semibold, design: .rounded)
                .foregroundStyle(.primary)
                .lineLimit(2)
        }
        .padding(.horizontal, 16)
        .frame(minHeight: 48)
        .liquidGlassCapsule(interactive: true)
        .floatingGlassShadow(scheme: scheme)
        .transition(.asymmetric(
            insertion: .move(edge: .bottom).combined(with: .opacity).combined(with: .scale(scale: 0.95)),
            removal: .move(edge: .bottom).combined(with: .opacity)
        ))
    }
}

/// The live page between full screen and its card. Scaled about the bottom
/// edge, the one that travels with the finger.
struct PageCardPose: Equatable {
    var x: CGFloat
    var y: CGFloat
    var scale: CGFloat
    var corner: CGFloat

    static let identity = PageCardPose(x: 0, y: 0, scale: 1, corner: 0)
}

// MARK: - Reading view

/// The page as plain text. Covers the web pane and stops clear of the bar, so
/// the browser controls stay where they are while reading.
private struct ReaderView: View {
    let article: ReaderArticle
    let scheme: ColorScheme
    /// Height of the floating chrome the text must stay above.
    var bottomClearance: CGFloat = 0
    var onClose: () -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                topBar

                if !article.title.isEmpty {
                    Text(article.title)
                        .zenFont(26, weight: .bold, design: .rounded)
                        .foregroundStyle(Palette.ink(scheme))
                        .fixedSize(horizontal: false, vertical: true)
                }

                if !article.byline.isEmpty {
                    Text(article.byline)
                        .zenFont(13, weight: .medium, design: .rounded)
                        .foregroundStyle(Palette.ink(scheme).opacity(0.5))
                }

                ForEach(blocks) { block in
                    blockView(block)
                }
            }
            .padding(.horizontal, 22)
            .padding(.top, 14)
            .padding(.bottom, 36 + bottomClearance)
            .frame(maxWidth: 680, alignment: .leading)
            .frame(maxWidth: .infinity)
        }
        .background(Palette.background(scheme))
        .accessibilityLabel(Text("browser.reader"))
    }

    /// The extracted body. A leading heading that just repeats the title is
    /// dropped so the title is not printed twice.
    private var blocks: [ReaderBlock] {
        var items = article.blocks
        while let first = items.first, first.kind == "h1" || first.kind == "h2" {
            guard first.text.caseInsensitiveCompare(article.title) == .orderedSame else { break }
            items.removeFirst()
        }
        return items
    }

    private var topBar: some View {
        HStack(spacing: 10) {
            Text(article.site.isEmpty ? article.title : article.site)
                .zenFont(12, weight: .semibold, design: .rounded)
                .tracking(0.6)
                .foregroundStyle(Palette.ink(scheme).opacity(0.45))
                .lineLimit(1)

            Spacer(minLength: 8)

            Button(action: onClose) {
                Image(systemName: "xmark")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(Palette.ink(scheme).opacity(0.6))
                    .frame(width: 32, height: 32)
                    .background(Palette.lift(scheme), in: Circle())
                    .glassRim(cornerRadius: 16)
                    .contentShape(Circle())
            }
            .buttonStyle(SquircleButtonStyle())
            .accessibilityLabel(Text("common.done"))
        }
    }

    @ViewBuilder
    private func blockView(_ block: ReaderBlock) -> some View {
        switch block.kind {
        case "h1", "h2":
            Text(block.text)
                .zenFont(20, weight: .semibold, design: .rounded)
                .foregroundStyle(Palette.ink(scheme))
                .padding(.top, 8)
                .fixedSize(horizontal: false, vertical: true)
        case "h3", "h4", "h5", "h6":
            Text(block.text)
                .zenFont(17, weight: .semibold, design: .rounded)
                .foregroundStyle(Palette.ink(scheme))
                .padding(.top, 4)
                .fixedSize(horizontal: false, vertical: true)
        case "blockquote":
            Text(block.text)
                .zenFont(16, design: .rounded)
                .italic()
                .foregroundStyle(Palette.ink(scheme).opacity(0.7))
                .padding(.leading, 14)
                .overlay(alignment: .leading) {
                    Rectangle()
                        .fill(Palette.ink(scheme).opacity(0.25))
                        .frame(width: 2)
                }
                .fixedSize(horizontal: false, vertical: true)
        case "pre":
            Text(block.text)
                .zenFont(13.5, design: .monospaced)
                .foregroundStyle(Palette.ink(scheme).opacity(0.85))
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(
                    Palette.lift(scheme),
                    in: RoundedRectangle(cornerRadius: 10, style: .continuous)
                )
        case "li":
            HStack(alignment: .firstTextBaseline, spacing: 9) {
                Text(verbatim: "•")
                    .zenFont(15, weight: .bold, design: .rounded)
                    .foregroundStyle(Palette.ink(scheme).opacity(0.6))
                Text(block.text)
                    .zenFont(17, design: .rounded)
                    .foregroundStyle(Palette.ink(scheme).opacity(0.9))
                    .fixedSize(horizontal: false, vertical: true)
            }
        default:
            Text(block.text)
                .zenFont(17, design: .rounded)
                .foregroundStyle(Palette.ink(scheme).opacity(0.9))
                .lineSpacing(5)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

/// The browser's own history, over whatever is on screen: a page or the
/// overview. A modifier of its own so the browser's body stays one expression
/// the type checker can still read.
private struct BrowserHistoryPresenter: ViewModifier {
    @Binding var isPresented: Bool
    var onOpen: (BrowserVisit) -> Void

    func body(content: Content) -> some View {
        content.sheet(isPresented: $isPresented) {
            BrowserHistoryView(onOpen: onOpen)
                .presentationDragIndicator(.visible)
        }
    }
}
