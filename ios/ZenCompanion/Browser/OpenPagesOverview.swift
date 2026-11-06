import SwiftUI
import UIKit

/// Where each page's picture sits on screen, so a page can zoom into it.
struct OverviewCardFrames: PreferenceKey {
    static let defaultValue: [String: CGRect] = [:]

    static func reduce(value: inout [String: CGRect], nextValue: () -> [String: CGRect]) {
        value.merge(nextValue()) { _, new in new }
    }
}

/// Every open page as a card, the way Safari lays out its tabs. It sits between
/// the page and the browser's dock, which stays on screen and changes its
/// controls, so the two never stack. A card opens with a tap and closes with
/// its X or a swipe; long press has the rest.
struct OpenPagesOverview: View {
    let pages: OpenPagesStore
    let spaces: [ZenSpace]
    /// How a pin from here is written; folders are offered only for pins.
    let saveKind: SaveKind
    /// The card a flying picture stands in for right now.
    var hiddenCardID: String?
    /// A page that is not part of the grid yet.
    var excludedCardID: String?
    /// Where the cards sit on screen, so a page can zoom into one.
    var onCardFrames: ([String: CGRect]) -> Void
    /// Height of the browser's dock, which stays over the overview.
    var bottomClearance: CGFloat
    var onOpen: (OpenPage) -> Void

    @Environment(\.colorScheme) private var scheme
    @State private var pinError: String?
    /// The card being dragged to a new place.
    @State private var draggedID: String?

    /// Two on a phone, as many as fit on an iPad.
    private let columns = [GridItem(.adaptive(minimum: 150, maximum: 260), spacing: 16)]

    var body: some View {
        ZStack {
            Palette.background(scheme)
                .ignoresSafeArea()
                // Nothing reaches the browser underneath.
                .contentShape(Rectangle())
                .onTapGesture {}

            if pages.pages.isEmpty {
                emptyState
            } else {
                grid
            }
        }
        .onPreferenceChange(OverviewCardFrames.self, perform: onCardFrames)
        .alert(
            Text("browser.pin_failed"),
            isPresented: Binding(get: { pinError != nil }, set: { if !$0 { pinError = nil } })
        ) {
            Button("common.done", role: .cancel) {}
        } message: {
            Text(pinError ?? "")
        }
    }

    private var grid: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVGrid(columns: columns, spacing: 18) {
                    ForEach(pages.pages.filter { $0.id != excludedCardID }) { page in
                        OpenPageCard(
                            page: page,
                            store: pages,
                            isCurrent: page.id == pages.activeID,
                            hidesPicture: page.id == hiddenCardID,
                            onOpen: { onOpen(page) },
                            onClose: { close(page) }
                        ) {
                            menu(for: page)
                        }
                        .id(page.id)
                        .onDrag {
                            draggedID = page.id
                            return NSItemProvider(object: page.id as NSString)
                        }
                        .onDrop(
                            of: [.text],
                            delegate: CardReorder(target: page.id, pages: pages, dragged: $draggedID)
                        )
                        .transition(.scale(scale: 0.8).combined(with: .opacity))
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 12)

                lifetimeNote
                    .padding(.top, 18)
                    .padding(.bottom, 12)
            }
            .safeAreaPadding(.bottom, bottomClearance)
            .onAppear {
                if let id = pages.activeID { proxy.scrollTo(id, anchor: .center) }
            }
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

    private func close(_ page: OpenPage) {
        withAnimation(.spring(duration: 0.32, bounce: 0)) {
            _ = pages.close(page.id)
        }
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

/// Cards make room as a dragged one passes over them.
private struct CardReorder: DropDelegate {
    let target: String
    let pages: OpenPagesStore
    @Binding var dragged: String?

    func dropEntered(info: DropInfo) {
        guard let dragged, dragged != target else { return }
        withAnimation(.spring(duration: 0.28, bounce: 0)) {
            pages.move(dragged, onto: target)
        }
    }

    func dropUpdated(info: DropInfo) -> DropProposal? {
        DropProposal(operation: .move)
    }

    func performDrop(info: DropInfo) -> Bool {
        dragged = nil
        return true
    }
}

/// One page: its picture with a close button, its name underneath. A sideways
/// swipe throws it away.
private struct OpenPageCard<Menu: View>: View {
    let page: OpenPage
    let store: OpenPagesStore
    let isCurrent: Bool
    let hidesPicture: Bool
    var onOpen: () -> Void
    var onClose: () -> Void
    @ViewBuilder var menu: () -> Menu

    @Environment(\.colorScheme) private var scheme
    @State private var drag: CGFloat = 0
    @State private var swiping = false
    @State private var cardWidth: CGFloat = 0

    private let radius: CGFloat = 18

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: radius, style: .continuous)
        VStack(spacing: 7) {
            OpenPageShot(page: page, store: store)
                .aspectRatio(0.72, contentMode: .fit)
                .frame(maxWidth: .infinity)
                .clipShape(shape)
                // The rim the buttons have: a pale page still ends somewhere.
                .glassRim(cornerRadius: radius)
                .opacity(hidesPicture ? 0 : 1)
                .background {
                    GeometryReader { proxy in
                        Color.clear.preference(
                            key: OverviewCardFrames.self,
                            value: [page.id: proxy.frame(in: .global)]
                        )
                    }
                }
                .overlay(alignment: .topTrailing) {
                    Button(action: onClose) {
                        Image(systemName: "xmark")
                            .font(.system(size: 11, weight: .bold))
                            .foregroundStyle(Color.primary.opacity(0.75))
                            .frame(width: 26, height: 26)
                            .background(.regularMaterial, in: Circle())
                            .padding(8)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .opacity(hidesPicture ? 0 : 1)
                    .accessibilityLabel(Text("kept.close_page_named \(page.title)"))
                }
                .contentShape(.contextMenuPreview, shape)
                .contentShape(shape)
                .onTapGesture(perform: onOpen)
                .contextMenu { menu() }

            HStack(spacing: 6) {
                if let url = page.url {
                    Favicon(urlString: url.absoluteString, size: 14)
                }
                if page.isKept, store.keepsOnlyChosenPages {
                    Image(systemName: "bookmark.fill")
                        .font(.system(size: 10, weight: .semibold))
                        .foregroundStyle(Color.secondary)
                }
                Text(page.title)
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(Color.primary)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity)
        }
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { cardWidth = $0 }
        .offset(x: drag)
        .opacity(1 - min(0.7, abs(drag) / max(cardWidth * 1.4, 1)))
        .simultaneousGesture(swipeToClose)
        .accessibilityElement(children: .contain)
        .accessibilityLabel(isCurrent ? Text(verbatim: "\(page.title), \(String(localized: "kept.current_page"))") : Text(verbatim: page.title))
        .accessibilityAddTraits(.isButton)
        .accessibilityAction(.default, onOpen)
    }

    private var swipeToClose: some Gesture {
        DragGesture(minimumDistance: 16)
            .onChanged { value in
                if !swiping {
                    guard abs(value.translation.width) > abs(value.translation.height) * 1.5 else { return }
                    swiping = true
                }
                drag = value.translation.width
            }
            .onEnded { value in
                defer { swiping = false }
                guard swiping else { return }
                let flung = abs(value.predictedEndTranslation.width) > cardWidth
                if abs(value.translation.width) > cardWidth * 0.45 || flung {
                    withAnimation(.spring(duration: 0.24, bounce: 0)) {
                        drag = value.translation.width < 0 ? -cardWidth * 1.5 : cardWidth * 1.5
                    } completion: {
                        onClose()
                    }
                } else {
                    withAnimation(.spring(duration: 0.3, bounce: 0.2)) { drag = 0 }
                }
            }
    }
}

/// The page as it looked when it was last on screen; its favicon until then.
struct OpenPageShot: View {
    let page: OpenPage
    let store: OpenPagesStore

    @Environment(\.colorScheme) private var scheme

    var body: some View {
        ZStack {
            // A page still without a picture sits on the app's own lift, so an
            // empty card reads as part of the overview rather than a white hole.
            Palette.lift(scheme)
            if let image = page.shot {
                Color.clear.overlay(alignment: .top) {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFill()
                }
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
