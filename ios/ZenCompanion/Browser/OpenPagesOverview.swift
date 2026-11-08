import SwiftUI
import UIKit

/// How round this display's corners are. Apple has no public API for it, so
/// the values come by panel height; only home-button phones and unknown panels
/// fall through to square.
@MainActor
enum DisplayCorners {
    static let radius: CGFloat = {
        guard let screen = UIApplication.shared.connectedScenes
            .compactMap({ ($0 as? UIWindowScene)?.screen }).first else { return 0 }
        if screen.traitCollection.userInterfaceIdiom == .pad { return 18 }
        switch Int(max(screen.nativeBounds.width, screen.nativeBounds.height)) {
        case 2622, 2736, 2868: return 62
        case 2556, 2796: return 55
        case 2778: return 53.33
        case 2532: return 47.33
        case 2340: return 44
        case 1792: return 41.5
        case 2436, 2688: return 39
        default: return 0
        }
    }()
}

/// Where the iPhone's app switcher puts its cards, as one pure calculation, so
/// the page can land exactly on its card without measuring anything.
///
/// A card has the display's own proportions. The focused card sits right of
/// the middle; each card further right lies on top and runs away quickly,
/// each card further left tucks in behind with less and less showing.
struct PageDeckGeometry {
    let screen: CGSize
    let count: Int

    /// The display's own curve, scaled down with the card.
    @MainActor static var cornerRadius: CGFloat { max(20, DisplayCorners.radius * focusScale) }
    /// A tapped card growing back into its page, and the deck parting around it.
    static let openMotion = Animation.spring(duration: 0.4, bounce: 0)

    private static let focusScale: CGFloat = 0.6875
    private static let scaleStep: CGFloat = 0.0105
    /// The focused card's left edge, as a share of the width.
    private static let restingLeft: CGFloat = 0.29
    /// Left edges grow as `spread * (growth^offset - 1)` from there. Two curves
    /// that meet without a kink: one tucks the cards behind, one throws them off.
    private static let behindSpread: CGFloat = 0.369
    private static let behindGrowth: CGFloat = 2.39
    private static let aheadSpread: CGFloat = 0.3876
    private static let aheadGrowth: CGFloat = 2.29
    private static let middle: CGFloat = 0.49

    var cardSize: CGSize {
        CGSize(width: screen.width * Self.focusScale, height: screen.height * Self.focusScale)
    }

    func frame(index: CGFloat, focus: CGFloat) -> CGRect {
        let offset = index - focus
        let scale = Self.focusScale + Self.scaleStep * min(2, max(-4, offset))
        let size = CGSize(width: screen.width * scale, height: screen.height * scale)
        let left = screen.width * Self.left(at: min(3, offset))
        return CGRect(
            x: left + endShift(focus: focus),
            y: screen.height * Self.middle - size.height / 2,
            width: size.width,
            height: size.height
        )
    }

    /// With nothing on top of it the last card moves to the true middle.
    private func endShift(focus: CGFloat) -> CGFloat {
        let last = CGFloat(max(0, count - 1))
        let near = min(1, max(0, 1 - (last - focus)))
        let eased = near * near * (3 - 2 * near)
        let centered = (screen.width - cardSize.width) / 2
        return (centered - screen.width * Self.restingLeft) * eased
    }

    private static func left(at offset: CGFloat) -> CGFloat {
        offset < 0
            ? restingLeft + behindSpread * (pow(behindGrowth, offset) - 1)
            : restingLeft + aheadSpread * (pow(aheadGrowth, offset) - 1)
    }
}

/// One card's place in the deck. The focus is interpolated here, frame by
/// frame, so a card follows the deck's curve while it settles instead of
/// cutting straight across to where it ends.
private struct DeckCardPlacement: ViewModifier, Animatable {
    let deck: PageDeckGeometry
    var index: CGFloat
    var focus: CGFloat
    var reveal: CGFloat
    var lift: CGFloat
    /// 1 is this card grown to the whole screen.
    var expansion: CGFloat

    var animatableData: AnimatablePair<AnimatablePair<CGFloat, CGFloat>, AnimatablePair<CGFloat, AnimatablePair<CGFloat, CGFloat>>> {
        get { AnimatablePair(AnimatablePair(index, focus), AnimatablePair(reveal, AnimatablePair(lift, expansion))) }
        set {
            index = newValue.first.first
            focus = newValue.first.second
            reveal = newValue.second.first
            lift = newValue.second.second.first
            expansion = newValue.second.second.second
        }
    }

    func body(content: Content) -> some View {
        let resting = deck.frame(index: index, focus: focus)
        let frame = CGRect(
            x: resting.minX * (1 - expansion),
            y: resting.minY * (1 - expansion),
            width: resting.width + (deck.screen.width - resting.width) * expansion,
            height: resting.height + (deck.screen.height - resting.height) * expansion
        )
        let offset = index - focus
        // The neighbours come in from the sides as the page shrinks.
        let aside = abs(offset) < 0.01 || expansion > 0
            ? 0
            : (offset < 0 ? -1 : 1) * deck.screen.width * 0.75 * (1 - reveal)
        return content
            .scaleEffect(frame.width / max(deck.cardSize.width, 1))
            .position(x: frame.midX + aside, y: frame.midY + lift)
    }
}

/// Cards the deck is not resting on sit a little in the dark, the ones behind
/// more than the one lying on top, so the focused card stands out.
private struct DeckCardDim: ViewModifier, Animatable {
    var offset: CGFloat

    var animatableData: CGFloat {
        get { offset }
        set { offset = newValue }
    }

    func body(content: Content) -> some View {
        content.opacity(Double(offset < 0 ? min(0.3, -offset * 0.2) : min(0.16, offset * 0.14)))
    }
}

/// Fades a card's icon and name with its place in the deck.
private struct DeckHeaderFade: ViewModifier, Animatable {
    var offset: CGFloat
    var reveal: CGFloat
    /// Only the focused card has room for its name.
    let isName: Bool

    var animatableData: AnimatablePair<CGFloat, CGFloat> {
        get { AnimatablePair(offset, reveal) }
        set {
            offset = newValue.first
            reveal = newValue.second
        }
    }

    func body(content: Content) -> some View {
        let place = isName
            ? 1 - min(1, abs(offset) * 2.5)
            : 1 - min(1, max(0, -offset - 1.5) * 2)
        return content.opacity(Double(place * reveal))
    }
}

/// Every open page as a card, the way the iPhone shows its running apps. The
/// deck slides sideways under the finger and comes to rest on a card. A card
/// opens with a tap and closes when it is pulled up; long press has the rest.
/// It sits between the page and the browser's dock, which stays on screen and
/// changes its controls, so the two never stack.
struct OpenPagesOverview: View {
    let pages: OpenPagesStore
    let spaces: [ZenSpace]
    /// How a pin from here is written; folders are offered only for pins.
    let saveKind: SaveKind
    /// The card the page itself stands in for right now.
    var hiddenCardID: String?
    /// The card growing back into its page, and how far it has got.
    var expandingCardID: String?
    var expansion: CGFloat
    /// A page that is not part of the deck yet.
    var excludedCardID: String?
    /// 0 with only the page on screen, 1 with the deck fully there.
    var reveal: CGFloat
    /// Cards that lie on top of the page's own card wait until it has landed:
    /// the page flies above the deck and could not pass under them.
    var aheadReveal: CGFloat
    /// Status bar height: the part of a card above the page's picture.
    var topInset: CGFloat
    /// Height of the browser's dock, which stays over the overview.
    var bottomClearance: CGFloat
    var onOpen: (OpenPage) -> Void

    @Environment(\.colorScheme) private var scheme
    @State private var pinError: String?
    /// The card in focus; between two whole numbers while the deck moves.
    @State private var focus: CGFloat
    /// Page ids, least recently used first, fixed when the deck comes up: like
    /// the running apps, the deck reorders only the next time it opens, never
    /// under the finger or under a card that was just tapped.
    @State private var order: [String]
    @State private var dragStartFocus: CGFloat?
    @State private var dragAxis: DragAxis?
    /// The card being pulled up, and how far.
    @State private var liftedID: String?
    @State private var lift: CGFloat = 0
    /// How far the other cards have closed the gap the lifted one will leave.
    @State private var gap: CGFloat = 0

    private enum DragAxis { case sideways, up, ignored }

    init(
        pages: OpenPagesStore,
        spaces: [ZenSpace],
        saveKind: SaveKind,
        hiddenCardID: String?,
        expandingCardID: String?,
        expansion: CGFloat,
        excludedCardID: String?,
        reveal: CGFloat,
        aheadReveal: CGFloat,
        topInset: CGFloat,
        bottomClearance: CGFloat,
        onOpen: @escaping (OpenPage) -> Void
    ) {
        self.pages = pages
        self.spaces = spaces
        self.saveKind = saveKind
        self.hiddenCardID = hiddenCardID
        self.expandingCardID = expandingCardID
        self.expansion = expansion
        self.excludedCardID = excludedCardID
        self.reveal = reveal
        self.aheadReveal = aheadReveal
        self.topInset = topInset
        self.bottomClearance = bottomClearance
        self.onOpen = onOpen
        let ordered = pages.deckOrder.filter { $0.id != excludedCardID }
        _order = State(initialValue: ordered.map(\.id))
        // Right after a swipe the page on screen need not be the front card.
        let start = pages.activeID.flatMap { id in ordered.firstIndex { $0.id == id } }
            ?? max(0, ordered.count - 1)
        _focus = State(initialValue: CGFloat(start))
    }

    private var visiblePages: [OpenPage] {
        let open = pages.pages.filter { $0.id != excludedCardID }
        let known = order.compactMap { id in open.first { $0.id == id } }
        // A popup can arrive while the deck is up; it is the newest.
        return known + open.filter { !order.contains($0.id) }
    }

    var body: some View {
        GeometryReader { proxy in
            let deck = PageDeckGeometry(screen: proxy.size, count: visiblePages.count)
            ZStack {
                backdrop
                    .opacity(Double(reveal))

                if pages.pages.isEmpty {
                    emptyState
                        .padding(.bottom, bottomClearance)
                        .opacity(Double(reveal))
                } else {
                    cards(deck)
                }

                lifetimeNote
                    .padding(.bottom, bottomClearance + 2)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
                    .opacity(pages.pages.isEmpty ? 0 : Double(reveal))
            }
            .simultaneousGesture(deckGesture(deck))
        }
        .ignoresSafeArea()
        .onChange(of: pages.pages.map(\.id)) { _, ids in
            // A page can go without passing through `close`: a popup that
            // closes itself, a page that expires. The deck must rest on a
            // card that still exists.
            let last = CGFloat(max(0, ids.count - 1))
            if focus > last { focus = last }
        }
        .alert(
            Text("browser.pin_failed"),
            isPresented: Binding(get: { pinError != nil }, set: { if !$0 { pinError = nil } })
        ) {
            Button("common.done", role: .cancel) {}
        } message: {
            Text(pinError ?? "")
        }
    }

    // MARK: - Deck

    private func cards(_ deck: PageDeckGeometry) -> some View {
        ZStack {
            let cards = visiblePages
            let lifted = liftedID.flatMap { id in cards.firstIndex { $0.id == id } }
            let closedFocus = lifted.map { focusAfterClosing($0, count: cards.count) } ?? focus
            ForEach(Array(cards.enumerated()), id: \.element.id) { index, page in
                // Where the lifted card is going to leave a hole, the others
                // are already on their way into it. The card itself stays.
                let closing = lifted != nil && index != lifted
                let place = closing && index > lifted! ? CGFloat(index) - gap : CGFloat(index)
                let deckFocus = closing ? focus + (closedFocus - focus) * gap : focus
                OpenPageCard(
                    page: page,
                    store: pages,
                    isCurrent: page.id == pages.activeID,
                    hidesPicture: page.id == hiddenCardID,
                    isFrontCard: index == cards.count - 1,
                    size: deck.cardSize,
                    topInset: topInset * deck.cardSize.width / max(deck.screen.width, 1),
                    offset: place - deckFocus,
                    reveal: reveal,
                    isExpanding: page.id == expandingCardID,
                    onOpen: {
                        // The deck parts around the card in the same move:
                        // the cards on top of it run off, it never jumps in
                        // front of them.
                        withAnimation(PageDeckGeometry.openMotion) { focus = CGFloat(index) }
                        onOpen(page)
                    },
                    onClose: { close(page) }
                ) {
                    menu(for: page)
                }
                .modifier(DeckCardPlacement(
                    deck: deck,
                    index: place,
                    focus: deckFocus,
                    reveal: place > deckFocus ? min(reveal, aheadReveal) : reveal,
                    lift: page.id == liftedID ? lift : 0,
                    expansion: page.id == expandingCardID ? expansion : 0
                ))
                // Further right is further on top, as with the running apps.
                .zIndex(Double(index))
            }
        }
        .allowsHitTesting(reveal > 0.99)
    }

    /// One finger, one decision: sideways moves the deck, up lifts the card
    /// under the finger. Both follow the finger exactly and hand its speed on.
    private func deckGesture(_ deck: PageDeckGeometry) -> some Gesture {
        DragGesture(minimumDistance: 8, coordinateSpace: .global)
            .onChanged { value in
                if dragAxis == nil { chooseAxis(for: value, in: deck) }
                switch dragAxis {
                case .sideways:
                    let start = dragStartFocus ?? focus
                    focus = rubberBanded(start - value.translation.width / span(deck))
                case .up:
                    let height = value.translation.height
                    // Up is one to one. Down gives a little and comes back.
                    lift = height < 0 ? height : 70 * (1 - exp(-height / 140))
                    gap = min(0.4, max(0, -lift) / max(deck.cardSize.height * 0.75, 1))
                default:
                    break
                }
            }
            .onEnded { value in
                let axis = dragAxis
                dragAxis = nil
                dragStartFocus = nil
                switch axis {
                case .sideways: settle(velocity: value.velocity.width, in: deck)
                case .up: releaseLift(value, in: deck)
                default: break
                }
            }
    }

    /// How far the finger travels to move the deck by one card.
    private func span(_ deck: PageDeckGeometry) -> CGFloat {
        max(deck.screen.width * 0.5, 1)
    }

    private func chooseAxis(for value: DragGesture.Value, in deck: PageDeckGeometry) {
        let width = abs(value.translation.width)
        let height = abs(value.translation.height)
        if width > height {
            dragAxis = .sideways
            dragStartFocus = focus
        } else if let id = cardID(at: value.startLocation, in: deck) {
            dragAxis = .up
            liftedID = id
            lift = 0
        } else {
            dragAxis = .ignored
        }
    }

    /// The topmost card under the finger.
    private func cardID(at point: CGPoint, in deck: PageDeckGeometry) -> String? {
        let cards = visiblePages
        for index in cards.indices.reversed()
        where deck.frame(index: CGFloat(index), focus: focus).contains(point) {
            return cards[index].id
        }
        return nil
    }

    private func rubberBanded(_ position: CGFloat) -> CGFloat {
        let last = CGFloat(max(0, visiblePages.count - 1))
        if position < 0 { return position * 0.3 }
        if position > last { return last + (position - last) * 0.3 }
        return position
    }

    /// The deck coasts on at the finger's speed and comes to rest on a card.
    private func settle(velocity: CGFloat, in deck: PageDeckGeometry) {
        let last = CGFloat(max(0, visiblePages.count - 1))
        let cardsPerSecond = -velocity / span(deck)
        let coasted = focus + cardsPerSecond * 0.18
        let reach = max(focus.rounded() - 4, min(focus.rounded() + 4, coasted.rounded()))
        let target = min(last, max(0, reach))
        let distance = target - focus
        let speed = distance == 0 ? 0 : min(12, max(-12, cardsPerSecond / distance))
        withAnimation(.interpolatingSpring(duration: 0.42, bounce: 0, initialVelocity: speed)) {
            focus = target
        }
    }

    /// Thrown or pulled far enough the card leaves over the top; otherwise it
    /// drops back into the deck.
    private func releaseLift(_ value: DragGesture.Value, in deck: PageDeckGeometry) {
        guard let id = liftedID, let page = visiblePages.first(where: { $0.id == id }) else {
            liftedID = nil
            lift = 0
            return
        }
        let thrown = value.velocity.height < -700
        guard lift < 0, thrown || lift < -deck.cardSize.height * 0.22 else {
            let speed = lift == 0 ? 0 : min(12, max(-12, -value.velocity.height / lift))
            withAnimation(.interpolatingSpring(duration: 0.46, bounce: 0.32, initialVelocity: speed)) {
                lift = 0
                gap = 0
            } completion: {
                if lift == 0 { liftedID = nil }
            }
            return
        }
        AppServices.haptics.selectionChanged()
        let destination = -deck.screen.height
        let distance = destination - lift
        let speed = distance == 0 ? 0 : min(8, max(0, value.velocity.height / distance))
        withAnimation(
            .interpolatingSpring(duration: 0.3, bounce: 0, initialVelocity: speed),
            completionCriteria: .logicallyComplete
        ) {
            lift = destination
            gap = 1
        } completion: {
            close(page)
        }
    }

    // MARK: - Backdrop

    /// The app's own background, the one the overview always had.
    private var backdrop: some View {
        Palette.background(scheme)
        // Nothing reaches the browser underneath.
        .contentShape(Rectangle())
        .onTapGesture {}
        .task(id: pages.activeID ?? pages.pages.first?.id) {
            if let page = pages.active ?? pages.pages.first { await pages.loadShot(of: page) }
        }
    }

    /// A small note under the cards, so nothing closes by surprise.
    private var lifetimeNote: some View {
        HStack(spacing: 5) {
            Image(systemName: "clock")
            Text(verbatim: pages.lifetimeHint)
        }
        .font(.footnote)
        .foregroundStyle(Color.secondary)
        .frame(maxWidth: .infinity)
    }

    // MARK: - Empty

    private var emptyState: some View {
        VStack(spacing: 8) {
            Text("kept.empty_title")
                .font(.system(size: 20, weight: .semibold))
                .foregroundStyle(Color.primary)
            Text("kept.empty_message")
                .font(.system(size: 15))
                .foregroundStyle(Color.secondary)
                .multilineTextAlignment(.center)
        }
        .padding(.horizontal, 40)
    }

    // MARK: - Menu

    /// Long press. Pin to space offers each space and, for a pin, its folders:
    /// the choice the browser's Pin offers.
    @ViewBuilder
    private func menu(for page: OpenPage) -> some View {
        if pages.keepsOnlyChosenPages, pages.canKeep(page) {
            Button {
                pages.toggleKeep(page.id)
            } label: {
                Label {
                    Text(page.isKept ? "browser.kept" : "browser.keep_page")
                } icon: {
                    Image(systemName: page.isKept ? "bookmark.fill" : "bookmark")
                }
            }
        }

        if let url = page.url {
            Menu {
                ForEach(spaces) { space in
                    let folders = saveKind == .pinned ? PinDestinationModel.folderEntries(in: space) : []
                    if folders.isEmpty {
                        Button(space.name) { pin(page, url: url, to: PinDestination(spaceId: space.id)) }
                    } else {
                        Menu(space.name) {
                            Button(space.name) { pin(page, url: url, to: PinDestination(spaceId: space.id)) }
                            ForEach(folders, id: \.folderId) { folder in
                                Button(String(repeating: "   ", count: folder.depth) + folder.name) {
                                    pin(page, url: url, to: PinDestination(spaceId: space.id, folderId: folder.folderId))
                                }
                            }
                        }
                    }
                }
            } label: {
                Label {
                    Text("kept.pin_to_space")
                } icon: {
                    Image(systemName: "pin")
                }
            }
            .disabled(spaces.isEmpty)

            Button {
                UIPasteboard.general.url = url
                AppServices.haptics.selectionChanged()
            } label: {
                Label {
                    Text("kept.copy_link")
                } icon: {
                    Image(systemName: "link")
                }
            }

            SwiftUI.ShareLink(item: url) {
                Label {
                    Text("browser.share")
                } icon: {
                    Image(systemName: "square.and.arrow.up")
                }
            }
        }

        Divider()

        Button(role: .destructive) {
            close(page)
        } label: {
            Label {
                Text("kept.close_page")
            } icon: {
                Image(systemName: "xmark")
            }
        }
    }

    // MARK: - Actions

    /// The cards close the gap: the next one slides in from the right, the
    /// last one hands the focus to the card before it.
    private func close(_ page: OpenPage) {
        let cards = visiblePages
        guard let index = cards.firstIndex(where: { $0.id == page.id }) else { return }
        let target = focusAfterClosing(index, count: cards.count)
        withAnimation(.spring(duration: 0.34, bounce: 0)) {
            _ = pages.close(page.id)
            focus = target
            if liftedID == page.id {
                liftedID = nil
                lift = 0
                gap = 0
            }
        }
    }

    private func focusAfterClosing(_ index: Int, count: Int) -> CGFloat {
        var target = focus.rounded()
        if CGFloat(index) < target { target -= 1 }
        return min(CGFloat(max(0, count - 2)), max(0, target))
    }

    /// Once the page belongs to a space it leaves the list; a failed write
    /// leaves it where it was.
    private func pin(_ page: OpenPage, url: URL, to destination: PinDestination) {
        let kind = saveKind
        Task { @MainActor in
            do {
                try await AppServices.pinWriter.addTab(
                    url: url,
                    title: page.title,
                    to: destination.spaceId,
                    // Normal tabs are never placed in a folder.
                    folderId: kind == .normal ? nil : destination.folderId,
                    kind: kind
                )
                AppServices.haptics.pinSucceeded()
                close(page)
            } catch {
                pinError = error.zenUserMessage
            }
        }
    }
}

/// One page: the whole screen in small, its icon and name above it.
private struct OpenPageCard<Menu: View>: View {
    let page: OpenPage
    let store: OpenPagesStore
    let isCurrent: Bool
    let hidesPicture: Bool
    /// Nothing lies on top of this card, so its name has the whole width.
    let isFrontCard: Bool
    let size: CGSize
    let topInset: CGFloat
    let offset: CGFloat
    let reveal: CGFloat
    /// Growing to the whole screen: it casts nothing that could show through
    /// when it fades into the live page.
    let isExpanding: Bool
    var onOpen: () -> Void
    var onClose: () -> Void
    @ViewBuilder var menu: () -> Menu

    private let iconSide: CGFloat = 22
    private let headerInset: CGFloat = 20
    private let iconGap: CGFloat = 8
    private var shape: RoundedRectangle {
        RoundedRectangle(cornerRadius: PageDeckGeometry.cornerRadius, style: .continuous)
    }

    /// Up to the icon of the card lying on top.
    private var nameWidth: CGFloat {
        let room = isFrontCard ? size.width - headerInset * 2 : size.width * 0.727 - 12
        return max(0, room - iconSide - iconGap)
    }

    var body: some View {
        OpenPageShot(page: page, store: store, topInset: topInset)
            .frame(width: size.width, height: size.height)
            .overlay {
                Color.black
                    .modifier(DeckCardDim(offset: offset))
                    .allowsHitTesting(false)
            }
            .clipShape(shape)
            // One picture, so the page's color under it never shows through
            // while the card fades.
            .compositingGroup()
            // Light: every card in the pile adds its own to the one below.
            .shadow(color: .black.opacity(isExpanding ? 0 : 0.26), radius: 14, x: -3, y: 0)
            .opacity(hidesPicture ? 0 : 1)
            .overlay(alignment: .topLeading) {
                header.offset(x: headerInset, y: -(iconSide + 8))
            }
            .contentShape(shape)
            .contentShape(.contextMenuPreview, shape)
            .onTapGesture(perform: onOpen)
            .contextMenu { menu() }
            .accessibilityElement(children: .contain)
            .accessibilityLabel(isCurrent ? Text(verbatim: "\(page.title), \(String(localized: "kept.current_page"))") : Text(verbatim: page.title))
            .accessibilityAddTraits(.isButton)
            .accessibilityAction(.default, onOpen)
            .accessibilityAction(named: Text("kept.close_page")) { onClose() }
            .accessibilityHidden(hidesPicture)
    }

    private var header: some View {
        HStack(spacing: iconGap) {
            icon
                .modifier(DeckHeaderFade(offset: offset, reveal: reveal, isName: false))
            Text(page.title)
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(Color.primary)
                .lineLimit(1)
                .frame(maxWidth: nameWidth, alignment: .leading)
                .modifier(DeckHeaderFade(offset: offset, reveal: reveal, isName: true))
        }
        .allowsHitTesting(false)
    }

    @ViewBuilder
    private var icon: some View {
        if let url = page.url {
            Favicon(urlString: url.absoluteString, size: iconSide)
                .shadow(color: .black.opacity(0.28), radius: 4, y: 2)
        } else {
            RoundedRectangle(cornerRadius: 5.5, style: .continuous)
                .fill(Color.secondary.opacity(0.25))
                .frame(width: iconSide, height: iconSide)
                .overlay {
                    Image(systemName: "globe")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(Color.secondary)
                }
        }
    }
}

/// The page as it looked when it was last on screen, on its own color, which
/// also fills the status bar's strip above it; its favicon until then.
struct OpenPageShot: View {
    let page: OpenPage
    let store: OpenPagesStore
    var topInset: CGFloat = 0

    @Environment(\.colorScheme) private var scheme

    var body: some View {
        ZStack {
            (page.model.themeColor ?? Palette.background(scheme))
            if let image = page.shot {
                Color.clear
                    .overlay(alignment: .top) {
                        Image(uiImage: image)
                            .resizable()
                            .scaledToFill()
                    }
                    .clipped()
                    .padding(.top, topInset)
            } else if let url = page.url {
                Favicon(urlString: url.absoluteString, size: 36)
            }
        }
        .task(id: page.id) { await store.loadShot(of: page) }
    }
}

/// The page count in a rounded square, the way Safari shows open tabs.
struct PageCountBadge: View {
    let count: Int
    var side: CGFloat = 22

    var body: some View {
        Text(verbatim: count > 99 ? "99+" : "\(count)")
            .font(.system(size: side > 22 ? 14 : 13, weight: .heavy, design: .rounded))
            .lineLimit(1)
            .padding(.horizontal, 4)
            .frame(minWidth: side, minHeight: side)
            .overlay(
                RoundedRectangle(cornerRadius: side * 0.32, style: .continuous)
                    .strokeBorder(lineWidth: 2)
            )
    }
}
