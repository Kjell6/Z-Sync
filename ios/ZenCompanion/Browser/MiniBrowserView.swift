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
    /// Fades the overview while a page zooms into or out of it.
    @State private var overviewVisible: Bool
    /// The picture flying between the full page and its card.
    @State private var hero: OverviewHero?
    @State private var heroAtFullSize = true
    @State private var heroOpacity: Double = 1
    /// The card the picture stands in for; it shows once the picture lands.
    @State private var hiddenCardID: String?
    /// The overview is on its way out for a new page and shows no changes.
    @State private var overviewHoldsCards = false
    /// The page at full size, from the moment the overview opened.
    @State private var lastPicture: (pageID: String, image: UIImage)?
    @State private var cardFrames: [String: CGRect] = [:]
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
        _overviewVisible = State(initialValue: startsInOverview)
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
              model.pinNotice == nil else { return false }
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
                    .background(revealingNewPage ? Color.clear : sheetBackground)
                    .offset(x: pageSwipe)

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

                if showOverview {
                    overview
                        .opacity(overviewVisible ? 1 : 0)
                }

                heroLayer

                // The page runs out into a soft blur at the bottom instead of
                // stopping against the controls.
                PageBottomFade()
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
                    .opacity(barCollapsed || showOverview ? 0 : 1)
                    .animation(barMotion, value: barCollapsed)

                if let notice = model.pinNotice {
                    pinNoticeToast(notice)
                        .padding(.horizontal, 20)
                        .padding(.bottom, 132)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
                        .environment(\.colorScheme, barScheme)
                }

                MiniBrowserChrome(
                    model: model,
                    pages: pages,
                    space: space,
                    allSpaces: allSpaces,
                    onToggleMenu: { model.toggleMenu() },
                    onClose: { leaveBrowser() },
                    addressEditing: addressEditing,
                    onPickSuggestion: pickSuggestion,
                    onOpenPages: openOverview,
                    overviewShown: overviewVisible,
                    onNewPage: openNewPage,
                    onDone: {
                        if let page = pages.active {
                            closeOverview(to: page)
                        } else {
                            onDismiss()
                        }
                    },
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
                            onSwipeEnd: finishSwipe
                        )
                    }
                )
                .padding(.horizontal, 16)
                .padding(.bottom, chromeBottomInset)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
                .offset(y: barCollapsed ? 220 : 0)
                // With nothing left to show, the cover is on its way out.
                .opacity(barCollapsed || (pages.active == nil && !showOverview) ? 0 : 1)
                .accessibilityHidden(barCollapsed)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
            .background(sheetBackground.ignoresSafeArea())
            .ignoresSafeArea(edges: .bottom)
            .ignoresSafeArea(.keyboard)
            .presentationBackground(pages.active == nil ? Palette.background(scheme) : barBackground)
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
            excludedCardID: overviewHoldsCards ? pages.activeID : nil,
            onCardFrames: { cardFrames = $0 },
            bottomClearance: MiniBrowserChromeMetrics.dockHeight + chromeBottomPadding + 12,
            onOpen: closeOverview(to:)
        )
    }

    /// The page shrinking into its card, or growing back out of it. Both frames
    /// are in screen coordinates and the layer spans the whole screen, so the
    /// picture starts exactly over the thing it stands in for.
    @ViewBuilder
    private var heroLayer: some View {
        if let hero {
            let frame = heroAtFullSize ? pageFrame : hero.card
            ZStack(alignment: .topLeading) {
                Color.clear
                Color.clear
                    .overlay(alignment: .top) {
                        Image(uiImage: hero.image)
                            .resizable()
                            .scaledToFill()
                    }
                    .frame(width: frame.width, height: frame.height)
                    .clipShape(RoundedRectangle(cornerRadius: heroAtFullSize ? 0 : 18, style: .continuous))
                    .position(x: frame.midX, y: frame.midY)
            }
            .opacity(heroOpacity)
            .ignoresSafeArea()
            .allowsHitTesting(false)
        }
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
            slidingColor: peeking ? UIColor(sheetBackground) : nil,
            topOverflow: pageFrame.minY,
            cornerRadius: revealingNewPage ? Self.screenCornerRadius : 0
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

    private static let screenCornerRadius: CGFloat = 48

    /// What waits past the last page: not a page yet, so it does not pretend to
    /// be one. A blank sheet grows up out of the background as the finger
    /// pulls it in, the way Safari's does.
    private var newPagePeek: some View {
        let span = paneWidth + PageHostView.pageGap
        let progress = span > 0 ? min(1, max(0, -pageSwipe / span)) : 0
        return ZStack {
            Palette.background(scheme)
                .ignoresSafeArea()
            RoundedRectangle(cornerRadius: Self.screenCornerRadius, style: .continuous)
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

    private static var heroMotion: Animation { .spring(duration: 0.36, bounce: 0) }

    /// The page shrinks into its card. Nothing moves until the overview has
    /// laid out behind the scenes and the card's place is known for certain;
    /// the target is then fixed, so nothing can change it mid-flight.
    private func openOverview() {
        withAnimation(.spring(response: 0.38, dampingFraction: 0.86)) {
            model.collapseMenu()
        }
        Task { @MainActor in
            guard let page = pages.active, let picture = await pages.capture(page), !reduceMotion else {
                fadeOverviewIn()
                return
            }
            lastPicture = (page.id, picture)
            hiddenCardID = page.id
            showOverview = true
            // Laid out at zero opacity; the grid has scrolled to the card by now.
            try? await Task.sleep(for: .milliseconds(60))
            guard let card = cardFrames[page.id], !pageFrame.isEmpty else {
                hiddenCardID = nil
                fadeOverviewIn()
                return
            }
            heroAtFullSize = true
            heroOpacity = 1
            hero = OverviewHero(pageID: page.id, image: picture, card: card)
            withAnimation(Self.heroMotion) {
                heroAtFullSize = false
                overviewVisible = true
            } completion: {
                hiddenCardID = nil
                hero = nil
            }
        }
    }

    private func fadeOverviewIn() {
        showOverview = true
        withAnimation(.easeOut(duration: 0.2)) { overviewVisible = true }
    }

    private func fadeOverviewOut() {
        withAnimation(.easeOut(duration: 0.2)) { overviewVisible = false } completion: {
            showOverview = false
        }
    }

    /// The chosen page grows out of its card and lands on the live page.
    private func closeOverview(to page: OpenPage) {
        let picture = lastPicture?.pageID == page.id ? lastPicture?.image : page.shot
        let card = cardFrames[page.id]
        pages.activate(page.id)
        guard !reduceMotion, let picture, let card else {
            fadeOverviewOut()
            return
        }
        hiddenCardID = page.id
        heroAtFullSize = false
        heroOpacity = 1
        hero = OverviewHero(pageID: page.id, image: picture, card: card)
        Task { @MainActor in
            // From home the page has only just joined the screen.
            try? await Task.sleep(for: .milliseconds(40))
            withAnimation(Self.heroMotion) {
                heroAtFullSize = true
                overviewVisible = false
            } completion: {
                showOverview = false
                hiddenCardID = nil
                // A woken page may still be loading under the picture.
                withAnimation(.easeOut(duration: 0.14)) { heroOpacity = 0 } completion: {
                    hero = nil
                    heroOpacity = 1
                }
            }
        }
    }

    /// A blank page takes over under the overview, which then fades away.
    private func openNewPage() {
        // The grid stays as it was while it fades: no card joins it on the way out.
        overviewHoldsCards = true
        pages.open(url: nil)
        withAnimation(.easeOut(duration: 0.2)) { overviewVisible = false } completion: {
            showOverview = false
            overviewHoldsCards = false
        }
    }

    /// A swipe started: the neighbours wake and take their places beside the page.
    private func beginSwipe() {
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

/// The picture flying between the page and its card in the overview.
private struct OverviewHero {
    let pageID: String
    let image: UIImage
    /// Where the card sits on screen, fixed when the flight starts.
    let card: CGRect
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
