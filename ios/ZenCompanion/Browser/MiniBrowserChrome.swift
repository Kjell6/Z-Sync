import SwiftUI

/// How tall the floating chrome is. One source of truth: the rows draw with
/// these values and the page keeps exactly this much clear at the bottom
/// (`MiniBrowserWebView.obscuredContentInsets`), so the two cannot drift.
enum MiniBrowserChromeMetrics {
    /// The navigation bar row (back, address, reload).
    static let navBarHeight: CGFloat = 44
    /// Air between the navigation bar and the dock.
    static let rowSpacing: CGFloat = 8
    /// The dock row. The side buttons are square at this size.
    static let dockHeight: CGFloat = 44
    /// The same row over the overview, where the buttons are all there is.
    static let overviewDockHeight: CGFloat = 48
    /// How much further up the dock sits over the overview.
    static let overviewDockLift: CGFloat = 16
    /// The widest the chrome gets. Wider than any iPhone, narrower than the
    /// open iPhone Duo held upright, so on iPad and the Duo it stays a bar
    /// rather than a strip across the screen.
    static let maxWidth: CGFloat = 560

    /// Dock bottom to navigation bar top.
    static var height: CGFloat { navBarHeight + rowSpacing + dockHeight }

    /// Screen bottom to navigation bar top, given the bar's bottom padding.
    static func height(above bottomPadding: CGFloat) -> CGFloat {
        bottomPadding + height
    }
}

/// How a pull upwards on the address bar ended.
struct PagePullEnd {
    let translation: CGSize
    /// Points per second; negative is upwards.
    let velocity: CGFloat
    /// A system gesture or a call took the finger away.
    let cancelled: Bool
}

/// Bottom browser chrome: two rows of floating Liquid Glass, bottom to top.
///
///   [pages] [+] [close]  the dock: the open pages, a new page, the way out
///   [navigation bar]   the full-width address bar with the menu, handed in as `navBar`
///   [menu panel]       only while the page menu is open: it grows above the bar
///
/// Nothing spans the screen edge to edge and nothing has a hard edge: the page
/// stays visible behind the glass.
struct MiniBrowserChrome<NavBar: View>: View {
    var model: MiniBrowserModel
    var pages: OpenPagesStore
    let space: ZenSpace
    let allSpaces: [ZenSpace]
    /// Whether saving into a space is offered. Signed out the pin tile and its
    /// saved-to row are gone, and the row of page tools closes up.
    var canSave = true
    /// Closes the mini-browser. The dock's middle control used to be a
    /// placeholder; it is the way out now that there is no sheet to swipe down.
    var onClose: () -> Void
    /// While the address is being typed the bar rides on the keyboard, and the
    /// dock would only sit under the keys: it is dropped then.
    var addressEditing = false
    /// A tapped suggestion goes through the same submit path as typed text.
    var onPickSuggestion: (String) -> Void
    /// A page from the history, picked from the list.
    var onPickVisit: (BrowserVisit) -> Void
    /// Opens the overview of the open pages.
    var onOpenPages: () -> Void
    /// The overview is up. The dock stays in place and its outer controls turn
    /// into the overview's: one set of buttons, never two stacked on each other.
    var overviewShown = false
    /// Whether a page is open behind the overview, so there is a browser to leave.
    var onNewPage: () -> Void = {}
    /// Opens the browser's own history from the overview.
    var onOpenHistory: () -> Void = {}
    var onCloseAll: () -> Void = {}
    /// The navigation bar. It stays in place while the menu grows above it.
    @ViewBuilder var navBar: () -> NavBar

    @Environment(\.colorScheme) private var scheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var copied = false
    @State private var copyGeneration = 0

    private var controlSide: CGFloat {
        overviewShown ? MiniBrowserChromeMetrics.overviewDockHeight : MiniBrowserChromeMetrics.dockHeight
    }
    /// The glyphs grow with their buttons.
    private var glyphSize: CGFloat { overviewShown ? 19 : 18 }
    /// Menu tiles keep the app's own tile corner.
    private let tileRadius: CGFloat = Palette.liftCornerRadius

    private var motion: Animation {
        reduceMotion ? .easeOut(duration: 0.12) : .spring(response: 0.24, dampingFraction: 0.82)
    }

    /// The suggestion list's motion: short and ease-out, with no bounce, so a
    /// row arriving does not kick the panel. Reduce Motion keeps a quicker
    /// fade and drops the travel.
    private var suggestionMotion: Animation {
        reduceMotion ? .easeOut(duration: 0.12) : .easeOut(duration: 0.26)
    }

    private var menuOpen: Bool { model.menuExpanded }

    /// The kind the next save will use: the setting, unless this account cannot
    /// write normal tabs.
    private var effectiveSaveKind: SaveKind { model.allowsNormalTabs ? model.saveKind : .pinned }

    var body: some View {
        VStack(spacing: MiniBrowserChromeMetrics.rowSpacing) {
            if menuOpen {
                menuPanel
                    .frame(maxWidth: .infinity)
                    .fixedSize(horizontal: false, vertical: true)
                    .floatingGlassPanel(cornerRadius: 26)
                    // Grows out of the bar it sits on: quick, and with enough
                    // travel to read as a card unfolding rather than a fade.
                    .transition(
                        .asymmetric(
                            insertion: .scale(scale: 0.88, anchor: .bottom)
                                .combined(with: .opacity),
                            removal: .opacity
                                .combined(with: .scale(scale: 0.95, anchor: .bottom))
                        )
                    )
            }

            // Only while the address field is open. The list grows out of the
            // bar the same way the page menu does, and shares its glass.
            if addressEditing, !model.suggestionRows.isEmpty {
                suggestionList
                    .frame(maxWidth: .infinity)
                    .fixedSize(horizontal: false, vertical: true)
                    .floatingGlassPanel(cornerRadius: 26)
                    .transition(reduceMotion ? AnyTransition.opacity : .asymmetric(
                        insertion: .scale(scale: 0.88, anchor: .bottom)
                            .combined(with: .opacity),
                        removal: .opacity
                            .combined(with: .scale(scale: 0.95, anchor: .bottom))
                    ))
            }

            navBar()
                .opacity(overviewShown ? 0 : 1)
                .allowsHitTesting(!overviewShown)
                .accessibilityHidden(overviewShown)

            if !addressEditing {
                HStack(spacing: 10) {
                    leadingControl
                    Spacer(minLength: 0)
                    // A new page, on a page and over the overview alike: the
                    // dock reads the same in both, so nothing trades places.
                    newPageButton
                    Spacer(minLength: 0)
                    // Closes the mini-browser, under the right thumb.
                    closeButton
                }
                // Pulls the outer buttons inwards. The navigation bar above
                // keeps its full width.
                .padding(.horizontal, 7)
                .transition(.opacity)
            }
        }
        // No `GlassEffectContainer` around the chrome on purpose. A container
        // merges neighbouring glass into one effect, so as soon as the menu or
        // the suggestion list grows in, the bar and dock that were already on
        // screen change their look. Each control keeps its own `.glassEffect`
        // so the chrome looks the same whatever is open.
        .onChange(of: model.addressText) { _, _ in
            model.refreshSuggestions(isEditing: addressEditing)
        }
        .onChange(of: addressEditing) { _, editing in
            model.refreshSuggestions(isEditing: editing)
        }
        .animation(motion, value: overviewShown)
        .animation(motion, value: model.menuExpanded)
        .animation(motion, value: model.menuSiteSettings)
        // The list arrives on its own, so its own motion is what carries it:
        // the column's height and the rows' opacity together.
        .animation(suggestionMotion, value: model.suggestionRows)
        // Chrome, not content: the rows have fixed 44pt metrics, so type stops
        // at the first accessibility size instead of overflowing the bar.
        .dynamicTypeSize(...DynamicTypeSize.accessibility1)
    }

    // MARK: - Suggestions

    /// Part of the address editing state, not a new layer: the rows use the
    /// same ink and row height as the menu, and the panel sits in the space
    /// above the bar that only exists while typing.
    ///
    /// One list, keyed by what each row says. The model merges the history and
    /// the answer, so the answer arriving can only add a row: a page from the
    /// history keeps its place at the top, and a row that stays is not rebuilt
    /// when the rows around it change.
    private var suggestionList: some View {
        VStack(spacing: 0) {
            ForEach(Array(model.suggestionRows.enumerated()), id: \.element.id) { index, row in
                Button {
                    if let visit = row.visit {
                        onPickVisit(visit)
                    } else {
                        onPickSuggestion(row.text)
                    }
                } label: {
                    suggestionRow(row, last: index == model.suggestionRows.count - 1)
                }
                .buttonStyle(SuggestionPressStyle())
                // Only the rows that come or go fade; the rest stay still.
                .transition(.opacity)
            }
        }
        .padding(.vertical, 6)
        .accessibilityElement(children: .contain)
    }

    /// A page prints its name over its address, a search phrase prints alone.
    private func suggestionRow(_ row: AddressSuggestion, last: Bool) -> some View {
        HStack(spacing: 10) {
            Image(systemName: row.visit == nil ? "magnifyingglass" : "clock")
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(Palette.ink(scheme).opacity(0.45))
                .frame(width: 22, height: 22)
            VStack(alignment: .leading, spacing: 0) {
                Text(verbatim: row.text)
                    .zenFont(16, weight: .medium, design: .rounded)
                    .foregroundStyle(Palette.ink(scheme))
                    .lineLimit(1)
                if let visit = row.visit {
                    Text(verbatim: visit.displayAddress)
                        .zenFont(13, design: .rounded)
                        .foregroundStyle(Palette.ink(scheme).opacity(0.6))
                        .lineLimit(1)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, 20)
        .frame(minHeight: 44)
        .contentShape(Rectangle())
        .overlay(alignment: .bottom) {
            if !last {
                Rectangle()
                    .fill(Color.primary.opacity(0.08))
                    .frame(height: 0.5)
                    .padding(.leading, 52)
            }
        }
    }

    /// The open pages, or the history once the overview is up. One piece of
    /// glass: only what is printed on it changes.
    private var leadingControl: some View {
        let count = pages.pages.count
        return ZStack {
            Button(action: onOpenPages) {
                PageCountBadge(count: count)
                    .foregroundStyle(Palette.ink(scheme))
                    .frame(width: controlSide, height: controlSide)
                    .contentShape(Capsule())
            }
            .buttonStyle(GlassPressStyle())
            .opacity(overviewShown ? 0 : 1)
            .allowsHitTesting(!overviewShown)
            .accessibilityHidden(overviewShown)
            .accessibilityLabel(Text("browser.page_count \(count)"))

            Button(action: onOpenHistory) {
                Image(systemName: "clock.arrow.circlepath")
                    .font(.system(size: glyphSize, weight: .semibold))
                    .foregroundStyle(Palette.ink(scheme))
                    .frame(width: controlSide, height: controlSide)
                    .contentShape(Capsule())
            }
            .buttonStyle(GlassPressStyle())
            .opacity(overviewShown ? 1 : 0)
            .allowsHitTesting(overviewShown)
            .accessibilityHidden(!overviewShown)
            .accessibilityLabel(Text("history.title"))
        }
        .floatingGlassCapsule()
    }

    /// A new page, the middle of the dock. A long press closes everything.
    private var newPageButton: some View {
        Menu {
            Button(role: .destructive, action: onCloseAll) {
                Label {
                    Text("kept.close_all")
                } icon: {
                    Image(systemName: "xmark")
                }
            }
            .disabled(pages.pages.isEmpty)
        } label: {
            Image(systemName: "plus")
                .font(.system(size: overviewShown ? 20 : 19, weight: .semibold))
                .foregroundStyle(Palette.ink(scheme))
                .frame(width: overviewShown ? 100 : 92, height: overviewShown ? 46 : 42)
                .contentShape(Capsule())
        } primaryAction: {
            onNewPage()
        }
        .floatingGlassCapsule()
        .accessibilityLabel(Text("kept.new_page"))
    }

    /// Closes the mini-browser. As big as the other side button.
    private var closeButton: some View {
        Button(action: onClose) {
            Image(systemName: "xmark")
                .font(.system(size: glyphSize - 1, weight: .semibold))
                .foregroundStyle(Palette.ink(scheme))
                .frame(width: controlSide, height: controlSide)
                .contentShape(Capsule())
                // Inside the label on purpose: the pressed state has to move
                // the whole pill, not only the glyph on it.
                .floatingGlassCapsule()
        }
        .buttonStyle(GlassPressStyle())
        .accessibilityLabel(Text("browser.close"))
    }

    // MARK: - Expanded menu

    /// The open menu: one glass panel with two pages. Site Settings is the
    /// second one, so opening the site's switches never stacks a sheet on top
    /// of the menu; only the panel's content and height change.
    private var menuPanel: some View {
        VStack(spacing: 0) {
            if model.menuSiteSettings, let host = model.blockingHost {
                siteSettingsPage(host: host)
            } else {
                expandedContent
            }
        }
        .padding(14)
    }

    /// The menu's second page. Its header is a quiet inline nav bar: the
    /// chevron at the leading edge, title and site centred, both a touch
    /// smaller so the row still belongs to the panel. The switches keep the
    /// card they have in Settings.
    private func siteSettingsPage(host: String) -> some View {
        VStack(spacing: 8) {
            siteSettingsHeader(host: host)
            SiteSettingsControls(host: host, scheme: scheme) {
                model.blockingSettingsDidChange()
            }
        }
    }

    private func siteSettingsHeader(host: String) -> some View {
        ZStack {
            VStack(spacing: 0) {
                Text("browser.site_settings")
                    .zenFont(16, weight: .semibold, design: .rounded)
                    .foregroundStyle(Color.primary)
                Text(blockingSiteKey(host))
                    .zenFont(12, design: .rounded)
                    .foregroundStyle(Color.primary.opacity(0.55))
                    .lineLimit(1)
                    .truncationMode(.middle)
            }
            // Clear of the chevron on both sides, so a long site name shrinks
            // in the middle instead of running under the button.
            .padding(.horizontal, 52)
            .frame(maxWidth: .infinity)
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(.isHeader)

            HStack(spacing: 0) {
                Button {
                    withAnimation(motion) { model.closeSiteSettings() }
                } label: {
                    Image(systemName: "chevron.backward")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(Color.primary)
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(GlyphPressStyle())
                .accessibilityLabel(Text("browser.back"))

                Spacer(minLength: 0)
            }
        }
        .frame(minHeight: 44)
    }

    /// The menu's first page: the blocking status, the page tools, the quick
    /// actions and the save row. Everything inside shares the panel's single
    /// outer inset, so rows, tiles and the pin row start on the same edge on
    /// every side.
    private var expandedContent: some View {
        VStack(spacing: 8) {
            // At the top: the menu grows up from the bar, so this is the far
            // end, where Arc keeps the same status at the bottom of its sheet.
            if model.blockingCard != .hidden {
                blockingCard
            }

            // The page tools, grouped into one card. The rows keep their
            // dividers inside it, so the card reads as a list rather than
            // four chips.
            VStack(spacing: 0) {
                if pages.keepsOnlyChosenPages {
                    keepRow
                    rowDivider
                }
                copyRow
                rowDivider
                readerRow
                rowDivider
                desktopRow
                rowDivider
                zoomRow
            }
            .padding(.horizontal, 14)
            .liftCard(scheme)

            // Its own card: one destination, not a group.
            siteSettingsRow
                .liftCard(scheme)

            actionRow

            if model.showPinBanner, canSave {
                // No opacity transition. The sheet just grows by this card.
                pinSavedSection
                    .transition(.identity)
            }
        }
    }

    /// Bottom row, trailing edge first for the thumb: Pin sits on the right.
    /// Left to right: Find, Share, Open, Pin.
    private var actionRow: some View {
        HStack(spacing: 8) {
            tile(
                systemName: "magnifyingglass",
                title: "browser.find_short",
                accessibility: "browser.find_on_page"
            ) {
                withAnimation(motion) {
                    model.presentFindOnPage()
                }
            }
            shareTile
            tile(
                systemName: "safari",
                title: "browser.open_short",
                accessibility: "browser.open_in_browser"
            ) {
                model.openCurrentInExternalBrowser()
                withAnimation(motion) {
                    model.collapseMenu()
                }
            }
            if canSave {
                tile(
                    systemName: "pin",
                    // Without the capability the save lands as a pin, whatever the
                    // setting says.
                    title: effectiveSaveKind == .normal ? "browser.save_short" : "browser.pin_short",
                    accessibility: effectiveSaveKind == .normal ? "browser.save_short" : "browser.pin_to_space",
                    // A page that failed to load has nothing worth pinning.
                    enabled: model.pageError == nil
                ) {
                    // Stay open. The saved-to row grows the sheet instead of a banner.
                    model.triggerPinBanner(spaces: allSpaces, fallbackSpace: space)
                }
            }
        }
    }

    /// Where the page was saved, and the control that changes that place.
    /// Sits under the quick actions so the open sheet grows upward.
    private var pinSavedSection: some View {
        HStack(spacing: 10) {
            if model.allowsNormalTabs {
                // Same choice as the share sheet, in the row's own scale:
                // small enough to belong, raised enough to read as a button.
                SaveKindMenuButton(
                    kind: model.pinSaveKind,
                    scheme: scheme,
                    style: .compact,
                    onOpen: { model.pinMenuWillOpen() },
                    onMenuDidDismiss: { model.pinMenuDidDismiss() }
                ) { kind in
                    model.selectPinSaveKind(kind)
                }
            } else {
                Image(systemName: "pin.fill")
                    .font(.system(size: 16, weight: .semibold))
                    .frame(width: 24)
                    .foregroundStyle(Color.primary)
            }
            Text(model.pinSaveKind == .pinned ? "browser.pinned" : "browser.saved_as_normal")
                .zenFont(16, weight: .medium, design: .rounded)
                .foregroundStyle(Color.primary)
                .lineLimit(1)
            Spacer(minLength: 8)
            PinDestinationMenuButton(
                destination: model.pinnedDestination,
                spaces: allSpaces,
                scheme: scheme,
                titleSize: 15,
                allowsFolders: model.pinSaveKind == .pinned,
                onSelect: { chosen in
                    model.selectPinDestination(chosen)
                },
                onMenuWillOpen: {
                    model.pinMenuWillOpen()
                },
                onMenuDidDismiss: {
                    model.pinMenuDidDismiss()
                }
            )
        }
        .frame(minHeight: 44)
        .padding(.horizontal, 14)
        .liftCard(scheme)
    }

    @ViewBuilder
    /// The system share sheet, which also carries Print for the page, as in
    /// Safari: the menu keeps no print row of its own.
    private var shareTile: some View {
        if model.effectiveURL != nil {
            Button {
                model.presentShare()
            } label: {
                tileLabel(systemName: "square.and.arrow.up", title: "browser.share")
            }
            .buttonStyle(CardPressStyle())
            .accessibilityLabel(Text("browser.share"))
        } else {
            tileLabel(systemName: "square.and.arrow.up", title: "browser.share")
                .opacity(0.4)
        }
    }

    private func tile(
        systemName: String,
        title: LocalizedStringKey,
        accessibility: LocalizedStringKey,
        enabled: Bool = true,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            tileLabel(systemName: systemName, title: title)
        }
        .buttonStyle(CardPressStyle())
        .disabled(!enabled || model.effectiveURL == nil)
        .accessibilityLabel(Text(accessibility))
    }

    private func tileLabel(systemName: String, title: LocalizedStringKey) -> some View {
        VStack(spacing: 6) {
            Image(systemName: systemName)
                .font(.system(size: 20, weight: .semibold))
                .frame(height: 24)
            Text(title)
                .zenFont(11, weight: .semibold, design: .rounded)
                .lineLimit(1)
                .minimumScaleFactor(0.75)
        }
        .foregroundStyle(Palette.ink(scheme).opacity(0.85))
        .frame(maxWidth: .infinity)
        .frame(height: 60)
        .background(
            Palette.lift(scheme),
            in: RoundedRectangle(cornerRadius: tileRadius, style: .continuous)
        )
        .glassRim(cornerRadius: tileRadius)
    }

    /// With "Keep only chosen pages" on: keeps this page. Tap Kept to let it go.
    private var keepRow: some View {
        let page = pages.active
        let kept = page?.isKept ?? false
        return Button {
            guard let page else { return }
            pages.toggleKeep(page.id)
            Task { _ = await pages.capture(page) }
        } label: {
            HStack(spacing: 12) {
                Image(systemName: kept ? "bookmark.fill" : "bookmark")
                    .font(.system(size: 16, weight: .semibold))
                    .frame(width: 24)
                Text(kept ? "browser.kept" : "browser.keep_page")
                    .zenFont(16, weight: .medium, design: .rounded)
                Spacer(minLength: 0)
                if kept {
                    Image(systemName: "checkmark")
                        .font(.system(size: 14, weight: .semibold))
                }
            }
            .foregroundStyle(kept ? Palette.ink(scheme) : Color.primary)
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(CardPressStyle())
        .disabled(page.map(pages.canKeep) != true)
    }

    private var copyRow: some View {
        Button {
            guard model.copyCurrentLink() else { return }
            copyGeneration += 1
            copied = true
            let generation = copyGeneration
            Task {
                try? await Task.sleep(for: .seconds(1.6))
                guard generation == copyGeneration else { return }
                copied = false
            }
        } label: {
            HStack(spacing: 12) {
                Image(systemName: copied ? "checkmark" : "link")
                    .font(.system(size: 16, weight: .semibold))
                    .frame(width: 24)
                    .foregroundStyle(copied ? Palette.ink(scheme) : Color.primary)
                Text(copied ? "browser.copied" : "browser.copy_link")
                    .zenFont(16, weight: .medium, design: .rounded)
                    .foregroundStyle(copied ? Palette.ink(scheme) : Color.primary)
                Spacer(minLength: 0)
            }
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(CardPressStyle())
        .disabled(model.effectiveURL == nil)
    }

    /// Lifts the article out of the page. Same shape as the other rows.
    private var readerRow: some View {
        Button {
            model.toggleReader()
            withAnimation(motion) {
                model.collapseMenu()
            }
        } label: {
            HStack(spacing: 12) {
                Image(systemName: "doc.plaintext")
                    .font(.system(size: 16, weight: .semibold))
                    .frame(width: 24)
                    .foregroundStyle(Color.primary)
                Text("browser.reader")
                    .zenFont(16, weight: .medium, design: .rounded)
                    .foregroundStyle(Color.primary)
                Spacer(minLength: 8)
                if model.readerLoading {
                    ProgressView()
                        .controlSize(.small)
                } else if model.readerArticle != nil {
                    Image(systemName: "checkmark")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(Palette.ink(scheme))
                }
            }
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(CardPressStyle())
        .disabled(model.effectiveURL == nil || model.readerLoading)
    }

    private var desktopRow: some View {
        HStack(spacing: 12) {
            Image(systemName: "laptopcomputer")
                .font(.system(size: 16, weight: .semibold))
                .frame(width: 24)
            Text("browser.desktop_site")
                .zenFont(16, weight: .medium, design: .rounded)
                .lineLimit(2)
            Spacer(minLength: 8)
            Toggle(
                "",
                isOn: Binding(
                    get: { model.requestsDesktopSite },
                    set: { _ in model.toggleDesktopSite() }
                )
            )
            .labelsHidden()
            .tint(Palette.ink(scheme))
        }
        .frame(minHeight: 44)
        .accessibilityElement(children: .combine)
    }

    private var zoomRow: some View {
        HStack(spacing: 12) {
            Image(systemName: "textformat.size")
                .font(.system(size: 16, weight: .semibold))
                .frame(width: 24)
            Text("browser.page_zoom")
                .zenFont(16, weight: .medium, design: .rounded)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
            Spacer(minLength: 8)
            HStack(spacing: 0) {
                zoomStepButton(systemName: "minus", label: "browser.zoom_out", enabled: model.canZoomOut) {
                    model.zoomOut()
                }
                Button {
                    model.resetPageZoom()
                } label: {
                    Text(MiniBrowserTools.zoomPercentLabel(model.pageZoom))
                        .zenFont(15, weight: .semibold, design: .rounded)
                        .monospacedDigit()
                        .fixedSize()
                        .frame(minWidth: 52)
                        .frame(height: 32)
                        .contentShape(Rectangle())
                }
                .buttonStyle(CardPressStyle())
                .disabled(!model.canResetZoom)
                .accessibilityLabel(
                    model.canResetZoom
                        ? Text("browser.zoom_reset")
                        : Text(verbatim: MiniBrowserTools.zoomPercentLabel(model.pageZoom))
                )
                zoomStepButton(systemName: "plus", label: "browser.zoom_in", enabled: model.canZoomIn) {
                    model.zoomIn()
                }
            }
            .padding(2)
            .background(
                Color.primary.opacity(scheme == .dark ? 0.12 : 0.06),
                in: Capsule()
            )
        }
        .frame(minHeight: 44)
    }

    private func zoomStepButton(
        systemName: String,
        label: LocalizedStringKey,
        enabled: Bool,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: 14, weight: .bold))
                .frame(width: 36, height: 32)
                .foregroundStyle(enabled ? Color.primary : Color.secondary.opacity(0.4))
                .contentShape(Rectangle())
        }
        .buttonStyle(CardPressStyle())
        .disabled(!enabled)
        .accessibilityLabel(Text(label))
    }

    /// The blocked count, or what blocking does on this page, with what a tap does.
    /// Its own card above the page tools, so the status reads as one of the
    /// menu's surfaces and the icon lines up with the rows below it.
    @ViewBuilder
    private var blockingCard: some View {
        let card = model.blockingCard
        Button {
            withAnimation(motion) { model.tapBlockingCard() }
        } label: {
            HStack(spacing: 12) {
                Image(systemName: card.symbol)
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(Color.primary)
                    .frame(width: 24)
                VStack(alignment: .leading, spacing: 1) {
                    card.title
                        .zenFont(16, weight: .medium, design: .rounded)
                        .foregroundStyle(Color.primary)
                    Text(card.detail)
                        .zenFont(13, design: .rounded)
                        .foregroundStyle(Color.primary.opacity(0.6))
                }
                .multilineTextAlignment(.leading)
                Spacer(minLength: 8)
            }
            .padding(.horizontal, 14)
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(CardPressStyle())
        .liftCard(scheme)
    }

    private var siteSettingsRow: some View {
        Button {
            withAnimation(motion) { model.openSiteSettings() }
        } label: {
            HStack(spacing: 12) {
                Image(systemName: "slider.horizontal.3")
                    .font(.system(size: 16, weight: .semibold))
                    .frame(width: 24)
                    .foregroundStyle(Color.primary)
                Text("browser.site_settings")
                    .zenFont(16, weight: .medium, design: .rounded)
                    .foregroundStyle(Color.primary)
                Spacer(minLength: 8)
                Image(systemName: "chevron.right")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(Color.primary.opacity(0.35))
            }
            .padding(.horizontal, 14)
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(CardPressStyle())
        .disabled(model.blockingHost == nil)
    }

    private var rowDivider: some View {
        Rectangle()
            .fill(Color.primary.opacity(0.08))
            .frame(height: 0.5)
            .padding(.leading, 36)
    }
}

/// Press feedback for the address area. It is not a pill of its own: a very
/// light fill behind the title, no scale, so the row does not jump.
private struct AddressPressStyle: ButtonStyle {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(Color.primary.opacity(configuration.isPressed ? 0.07 : 0))
                    .padding(.vertical, 3)
            )
            .opacity(configuration.isPressed ? 0.9 : 1)
            .animation(reduceMotion ? nil : .easeOut(duration: 0.08), value: configuration.isPressed)
    }
}

/// Press feedback for a glass control: the whole pill answers the finger, not
/// only the glyph printed on it. Same values as the app's other buttons.
private struct GlassPressStyle: ButtonStyle {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func makeBody(configuration: Configuration) -> some View {
        configuration.label.pressedFeedback(
            isPressed: configuration.isPressed,
            reduceMotion: reduceMotion
        )
    }
}

/// Press feedback for a control with no surface of its own: a soft fill behind
/// the glyph, so the touch target is visible on the bar.
private struct GlyphPressStyle: ButtonStyle {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background(
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .fill(Color.primary.opacity(configuration.isPressed ? 0.10 : 0))
            )
            .pressedFeedback(
                isPressed: configuration.isPressed,
                reduceMotion: reduceMotion
            )
    }
}

/// Press feedback for rows inside the bar: a soft fill and a small dim, since
/// the row already sits on the panel's own surface.
private struct CardPressStyle: ButtonStyle {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background(configuration.isPressed ? Color.primary.opacity(0.08) : Color.clear)
            .scaleEffect(!reduceMotion && configuration.isPressed ? 0.98 : 1)
            .opacity(configuration.isPressed ? 0.6 : 1)
            .animation(reduceMotion ? nil : .easeOut(duration: 0.08), value: configuration.isPressed)
    }
}

/// Highlight on touch-down. The row stays full width, so it does not scale.
private struct SuggestionPressStyle: ButtonStyle {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background(configuration.isPressed ? Color.primary.opacity(0.08) : Color.clear)
            .animation(reduceMotion ? nil : .easeOut(duration: 0.08), value: configuration.isPressed)
    }
}

/// How a swipe on the address bar ended.
struct PageSwipeEnd {
    enum Target: Equatable {
        case stay
        case page(String)
        case newPage
    }

    let target: Target
    /// Where the current page leaves to: -1 left, 1 right.
    let direction: CGFloat
    /// The finger's speed at release, in points per second.
    let velocity: CGFloat
}

/// Loading fills the whole bar from the left. A line on its edge would never
/// sit right against the capsule's curve. However the load ends, the fill first
/// reaches the end of the bar and only then fades away.
private struct LoadingFill: View {
    let isLoading: Bool
    let progress: Double
    let tint: Color

    @State private var shown: Double = 0
    @State private var opacity: Double = 0
    /// Tells a finish apart from a load that started while it was running.
    @State private var run = 0

    var body: some View {
        GeometryReader { proxy in
            Rectangle()
                .fill(tint)
                .frame(width: proxy.size.width * shown)
        }
        .opacity(opacity)
        .clipShape(Capsule())
        .allowsHitTesting(false)
        .onChange(of: isLoading, initial: true) { _, loading in
            if loading {
                start()
            } else if opacity > 0 {
                finish()
            }
        }
        .onChange(of: progress) { _, next in
            guard isLoading, next > shown else { return }
            withAnimation(.easeOut(duration: 0.25)) { shown = next }
        }
    }

    private func start() {
        run += 1
        var transaction = Transaction()
        transaction.disablesAnimations = true
        withTransaction(transaction) {
            shown = 0
            opacity = 1
        }
        withAnimation(.easeOut(duration: 0.25)) { shown = progress < 1 ? progress : 0 }
    }

    private func finish() {
        run += 1
        let current = run
        withAnimation(.easeOut(duration: 0.28)) {
            shown = 1
        } completion: {
            guard current == run else { return }
            withAnimation(.easeOut(duration: 0.3)) {
                opacity = 0
            } completion: {
                guard current == run else { return }
                shown = 0
            }
        }
    }
}

// MARK: - Top bar

/// Full-width navigation bar over the page, as in Safari: back and forward
/// only while they go somewhere, the domain centred, then the page menu and
/// the reload. Same glass as the dock below.
struct MiniBrowserTopBar: View {
    var model: MiniBrowserModel
    var page: OpenPage?
    var pages: OpenPagesStore
    var addressEditing: Bool
    /// Whether the page menu is open above the bar.
    var menuOpen = false
    /// A blank page is sliding in (the dock's plus): the bar beside this one is
    /// the new page's, even when a next page exists.
    var revealsNewPage = false
    var onToggleMenu: () -> Void = {}
    @Binding var isAddressFocused: Bool
    /// How far the swipe between pages has moved the page.
    @Binding var swipeOffset: CGFloat
    /// One page plus the gap: the distance the page travels in a full swipe.
    var pageSpan: CGFloat
    var onBeginAddressEdit: () -> Void
    var onCancel: () -> Void
    var onSubmit: () -> Void
    /// Back with nothing left to go back to, on a page a link opened.
    var onReturnToOpener: () -> Void
    var onSwipeStart: () -> Void
    var onSwipeEnd: (PageSwipeEnd) -> Void
    /// A pull upwards on the bar, the home-bar gesture that opens the pages.
    /// The page follows the finger from the first point on.
    var onPullStart: () -> Void = {}
    var onPullChange: (CGSize) -> Void = { _ in }
    var onPullEnd: (PagePullEnd) -> Void = { _ in }

    @Environment(\.colorScheme) private var scheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// A swipe is under way; the address under the finger must not open.
    @State private var swiping = false
    /// A pull upwards is under way.
    @State private var pulling = false
    /// Where the pull took over, so the page does not jump by the distance
    /// the gesture needed to recognise itself.
    @State private var pullOrigin: CGSize = .zero
    /// Resets when the drag ends in any way. A system gesture or a call ends
    /// it without `onEnded`, and the page must not stay where it was left.
    @GestureState private var fingerDown = false
    @State private var swipeLetGo = false
    @State private var barWidth: CGFloat = 0

    private let barHeight: CGFloat = MiniBrowserChromeMetrics.navBarHeight
    /// Space between the bar and the next page's bar while swiping.
    private let peekGap: CGFloat = 12

    var body: some View {
        if addressEditing {
            // Safari's editing state: the field is one capsule and the close
            // control is its own circle beside it, not a glyph inside the field.
            HStack(spacing: 8) {
                editor
                closeCircle
            }
        } else {
            navigationRow
                .padding(.horizontal, 8)
                .frame(height: barHeight)
                // Drawn over the whole bar, not inside the address button: the
                // button's frame jumps when back or forward come and go, the
                // label's place on the bar does not.
                .overlay(alignment: .leading) {
                    AddressLabel(
                        text: addressText,
                        symbol: Self.symbol(for: model.pageError == nil ? model.effectiveURL : nil),
                        barWidth: barWidth,
                        leadingInset: Self.leadingInset(back: canGoBack, forward: model.canGoForward),
                        trailingInset: Self.trailingInset,
                        dimmed: model.pageHost.isEmpty
                    )
                    .animation(reduceMotion ? nil : .easeOut(duration: 0.2), value: canGoBack)
                    .animation(reduceMotion ? nil : .easeOut(duration: 0.2), value: model.canGoForward)
                }
                .background {
                    LoadingFill(
                        isLoading: model.isLoading,
                        progress: model.estimatedProgress,
                        tint: Palette.ink(scheme).opacity(0.1)
                    )
                    // A page brings its own load: nothing carries over a switch.
                    .id(page?.id)
                }
                .floatingGlassCapsule()
                .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { barWidth = $0 }
                .offset(x: barOffset)
                .background { peekBars }
                .simultaneousGesture(swipeGesture)
                .onChange(of: fingerDown) { _, down in
                    guard !down else {
                        swipeLetGo = false
                        return
                    }
                    if pulling, !swipeLetGo {
                        pulling = false
                        onPullEnd(PagePullEnd(translation: .zero, velocity: 0, cancelled: true))
                    }
                    guard swiping, !swipeLetGo else { return }
                    swiping = false
                    onSwipeEnd(PageSwipeEnd(target: .stay, direction: swipeOffset < 0 ? -1 : 1, velocity: 0))
                }
        }
    }

    // MARK: - Swipe between pages

    /// The bar and the page travel the same share of their own width, so they
    /// reach the end together while the bar stays under the finger.
    private var barSpan: CGFloat { barWidth + peekGap }
    private var barOffset: CGFloat {
        pageSpan > 0 ? swipeOffset * barSpan / pageSpan : swipeOffset
    }

    /// Past the last page there is a new one, as in Safari. Not from a page
    /// that is itself still empty.
    private var offersNewPage: Bool { pages.neighbours.next == nil && model.pageURL != nil }

    /// The neighbours' bars wait just off the edges and follow the finger.
    @ViewBuilder
    private var peekBars: some View {
        if swipeOffset != 0 {
            let neighbours = pages.neighbours
            ZStack {
                if let previous = neighbours.previous {
                    peekBar(previous).offset(x: barOffset - barSpan)
                }
                if let next = neighbours.next, !revealsNewPage {
                    peekBar(next).offset(x: barOffset + barSpan)
                } else if offersNewPage || revealsNewPage, barOffset < 0 {
                    newPagePill
                }
            }
        }
    }

    /// Past the last page there is nothing to show an address for: a pill opens
    /// in the space the bar leaves behind, as wide as the finger has pulled it.
    private var newPagePill: some View {
        let width = max(0, -barOffset - peekGap)
        return Image(systemName: "plus")
            .font(.system(size: 18, weight: .semibold))
            .foregroundStyle(Palette.ink(scheme).opacity(0.85))
            .opacity(min(1, width / 90))
            .frame(width: width, height: barHeight)
            .floatingGlassCapsule()
            .offset(x: barWidth / 2 + peekGap + barOffset + width / 2)
            .allowsHitTesting(false)
    }

    /// The whole bar of the page coming in, drawn exactly like the real one:
    /// when the swipe ends, the real bar takes over without a visible change.
    private func peekBar(_ page: OpenPage) -> some View {
        let peek = page.model
        let url = page.url
        let back = peek.canGoBack || page.openerID != nil
        return HStack(spacing: Self.glyphSpacing) {
            if back {
                peekGlyph("chevron.backward")
            }
            if peek.canGoForward {
                peekGlyph("chevron.forward")
            }
            Spacer(minLength: 0)
            peekGlyph("ellipsis", upright: true)
            peekGlyph("arrow.clockwise")
        }
        .padding(.horizontal, Self.barPadding)
        .frame(width: barWidth, height: barHeight)
        .overlay(alignment: .leading) {
            AddressLabel(
                text: url.map(Self.domain(of:)) ?? String(localized: "browser.search_placeholder"),
                symbol: Self.symbol(for: url),
                barWidth: barWidth,
                leadingInset: Self.leadingInset(back: back, forward: peek.canGoForward),
                trailingInset: Self.trailingInset,
                dimmed: url == nil
            )
        }
        .floatingGlassCapsule()
        .allowsHitTesting(false)
    }

    private func peekGlyph(_ systemName: String, upright: Bool = false) -> some View {
        Image(systemName: systemName)
            .font(.system(size: 18, weight: .semibold))
            .rotationEffect(.degrees(upright ? 90 : 0))
            .foregroundStyle(Palette.ink(scheme).opacity(0.85))
            .frame(width: 32, height: barHeight - 8)
            // The same 44pt slot the real bar's glyphs take, so the bar that
            // slides in matches the one it replaces.
            .frame(minWidth: 44, minHeight: 44)
    }

    private var swipeGesture: some Gesture {
        // Global: the bar itself moves and shrinks under the finger.
        DragGesture(minimumDistance: 10, coordinateSpace: .global)
            .updating($fingerDown) { _, down, _ in down = true }
            .onChanged { value in
                let width = value.translation.width
                let height = value.translation.height
                if !swiping, !pulling {
                    // Up with the bar belongs to the pages, the way the home
                    // bar opens the app switcher. Sideways switches pages.
                    if height < 0, abs(height) > abs(width) {
                        pulling = true
                        pullOrigin = value.translation
                        onPullStart()
                    } else if abs(width) > abs(height) * 1.2 {
                        swiping = true
                        onSwipeStart()
                    } else {
                        return
                    }
                }
                if pulling {
                    onPullChange(CGSize(
                        width: width - pullOrigin.width,
                        height: height - pullOrigin.height
                    ))
                    return
                }
                let neighbours = pages.neighbours
                let hasTarget = width < 0
                    ? neighbours.next != nil || offersNewPage
                    : neighbours.previous != nil
                // Page and finger move as one. With nothing that way the page
                // gives a little and comes back.
                let scale = barSpan > 0 ? pageSpan / barSpan : 1
                swipeOffset = width * (hasTarget ? scale : 0.2)
            }
            .onEnded { value in
                if pulling {
                    swipeLetGo = true
                    // A beat later: the bar travels with the finger, so the
                    // address button still sees this as a tap on itself.
                    DispatchQueue.main.async { pulling = false }
                    onPullEnd(PagePullEnd(
                        translation: CGSize(
                            width: value.translation.width - pullOrigin.width,
                            height: value.translation.height - pullOrigin.height
                        ),
                        velocity: value.velocity.height,
                        cancelled: false
                    ))
                    return
                }
                guard swiping else { return }
                swipeLetGo = true
                let neighbours = pages.neighbours
                let width = value.translation.width
                let flung = value.predictedEndTranslation.width
                let target: PageSwipeEnd.Target
                switch width < 0 {
                case true: target = neighbours.next.map { .page($0.id) } ?? (offersNewPage ? .newPage : .stay)
                case false: target = neighbours.previous.map { .page($0.id) } ?? .stay
                }
                let passedHalf = abs(width) > barWidth * 0.5
                let thrownThatWay = abs(flung) > barWidth * 0.5 && (flung < 0) == (width < 0)
                onSwipeEnd(PageSwipeEnd(
                    target: passedHalf || thrownThatWay ? target : .stay,
                    direction: width < 0 ? -1 : 1,
                    velocity: value.velocity.width
                ))
                DispatchQueue.main.async { swiping = false }
            }
    }

    private var canGoBack: Bool { model.canGoBack || page?.openerID != nil }

    /// The bar's layout, so the label can know where the buttons end without
    /// measuring them: a measured frame arrives a frame late and the label
    /// would jump while back or forward fade.
    private static let barPadding: CGFloat = 8
    private static let glyphSpacing: CGFloat = 2
    private static let glyphSlot: CGFloat = 44
    /// Back and forward, each only when it is shown.
    static func leadingInset(back: Bool, forward: Bool) -> CGFloat {
        barPadding + CGFloat((back ? 1 : 0) + (forward ? 1 : 0)) * (glyphSlot + glyphSpacing)
    }
    /// The menu and the reload.
    static let trailingInset: CGFloat = barPadding + 2 * (glyphSlot + glyphSpacing)

    private var navigationRow: some View {
        HStack(spacing: Self.glyphSpacing) {
            // Only while they lead somewhere: most pages never go forward,
            // and the domain gets the room instead.
            if canGoBack {
                iconButton(systemName: "chevron.backward", label: "browser.back", enabled: true) {
                    if model.canGoBack { model.goBack() } else { onReturnToOpener() }
                }
                .transition(.opacity)
            }
            if model.canGoForward {
                iconButton(systemName: "chevron.forward", label: "browser.forward", enabled: true) {
                    model.goForward()
                }
                .transition(.opacity)
            }

            Button {
                guard !swiping, !pulling else { return }
                onBeginAddressEdit()
            } label: {
                // The label itself is drawn over the bar (see `body`).
                Color.clear
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .contentShape(Rectangle())
            }
            // An open padlock is the only sign of an unencrypted page, so
            // VoiceOver hears the connection too.
            .accessibilityValue(Text(connectionLabel ?? ""))
            .buttonStyle(AddressPressStyle())
            // The page's own title for VoiceOver, so the bar does not hide
            // where the reader is, while the eye gets the domain.
            .accessibilityLabel(Text(accessibilityTitle))

            iconButton(
                systemName: "ellipsis",
                label: menuOpen ? "browser.close_menu" : "browser.more",
                enabled: true,
                // Upright, so it does not read as the domain trailing off.
                upright: true
            ) {
                withAnimation(reduceMotion ? .easeOut(duration: 0.12) : .spring(response: 0.24, dampingFraction: 0.82)) {
                    onToggleMenu()
                }
            }

            iconButton(
                systemName: model.isLoading ? "xmark" : "arrow.clockwise",
                label: model.isLoading ? "browser.stop" : "browser.reload",
                enabled: true
            ) {
                if model.isLoading { model.stopLoading() } else { model.reload() }
            }
        }
        .animation(reduceMotion ? nil : .easeOut(duration: 0.2), value: canGoBack)
        .animation(reduceMotion ? nil : .easeOut(duration: 0.2), value: model.canGoForward)
    }

    /// The domain, the way Safari prints it, or the prompt on an empty page.
    private var addressText: String {
        guard let url = model.effectiveURL, !model.pageHost.isEmpty else {
            return String(localized: "browser.search_placeholder")
        }
        return Self.domain(of: url)
    }

    private var accessibilityTitle: String {
        let title = model.pageTitle.trimmingCharacters(in: .whitespacesAndNewlines)
        if !title.isEmpty { return title }
        let host = model.pageHost
        return host.isEmpty ? String(localized: "browser.search_placeholder") : host
    }

    /// The host without a leading "www.", as Safari shows it.
    static func domain(of url: URL) -> String {
        guard let host = url.host, !host.isEmpty else { return url.absoluteString }
        return host.hasPrefix("www.") ? String(host.dropFirst(4)) : host
    }

    /// An open padlock for an unencrypted page, the magnifier for an empty one,
    /// nothing for an encrypted page: the domain alone says enough there.
    static func symbol(for url: URL?) -> String? {
        switch url?.scheme?.lowercased() {
        case "https": return nil
        case "http": return "lock.open"
        case nil: return "magnifyingglass"
        default: return nil
        }
    }

    /// Whether the page came over an encrypted connection, for VoiceOver.
    /// An error page (a bad certificate among them) made no connection to
    /// vouch for, so it says nothing.
    private var connectionLabel: String? {
        guard model.pageError == nil else { return nil }
        switch model.effectiveURL?.scheme?.lowercased() {
        case "https": return String(localized: "browser.connection_secure")
        case "http": return String(localized: "browser.connection_insecure")
        default: return nil
        }
    }


    private func iconButton(
        systemName: String,
        label: LocalizedStringKey,
        enabled: Bool,
        upright: Bool = false,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: 18, weight: .semibold))
                .rotationEffect(.degrees(upright ? 90 : 0))
                .foregroundStyle(Palette.ink(scheme).opacity(enabled ? 0.85 : 0.3))
                .frame(width: 32, height: barHeight - 8)
                // The glyph keeps its slot; the button around it takes the
                // platform's least touch target, filling the bar's height.
                .frame(minWidth: 44, minHeight: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(GlyphPressStyle())
        .disabled(!enabled)
        .accessibilityLabel(Text(label))
    }

    private var editor: some View {
        HStack(spacing: 0) {
            NativeAddressField(
                text: Binding(get: { model.addressText }, set: { model.addressText = $0 }),
                placeholder: String(localized: "browser.search_placeholder"),
                ink: UIColor(Palette.ink(scheme)),
                // The system tint, so caret and selection use the usual iOS accent.
                caret: UIColor.tintColor,
                isFocused: isAddressFocused,
                onSubmit: onSubmit,
                onFocusChange: { focused in isAddressFocused = focused }
            )
            if !model.addressText.isEmpty {
                clearButton
            }
        }
        .padding(.leading, 16)
        // The clear glyph ends on the same inset the text starts on. The
        // button fills the pill's end, so the target stays large without the
        // air that made the right side look wider.
        .padding(.trailing, 2)
        .frame(height: barHeight)
        .floatingGlassCapsule()
        .accessibilityLabel(Text("browser.search_placeholder"))
        .onAppear {
            // The field is created before it can become first responder.
            DispatchQueue.main.async {
                guard addressEditing else { return }
                isAddressFocused = true
            }
        }
    }

    /// Clears the address while typing. Drawn here rather than by the text
    /// field: the system button brings its own trailing inset inside the
    /// field, which left a wider gap on that side than the text has on the
    /// other, and its touch area is only as big as its glyph.
    private var clearButton: some View {
        Button {
            model.addressText = ""
        } label: {
            Image(systemName: "xmark.circle.fill")
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(Palette.ink(scheme).opacity(0.35))
                // A full-height slot in the capsule's round end: 44pt of
                // target, with the glyph in the middle of it.
                .frame(width: barHeight, height: barHeight)
                .contentShape(Circle())
        }
        .buttonStyle(GlyphPressStyle())
        .accessibilityLabel(Text("browser.clear_search"))
    }

    /// The way out of editing. Safari keeps it outside the field, as a circle.
    private var closeCircle: some View {
        Button(action: onCancel) {
            Image(systemName: "xmark")
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(Palette.ink(scheme).opacity(0.8))
                .frame(width: barHeight, height: barHeight)
                .contentShape(Circle())
                .floatingGlassCapsule()
        }
        .buttonStyle(GlassPressStyle())
        .accessibilityLabel(Text("common.cancel"))
    }
}

private extension BlockingCard {
    var symbol: String {
        switch self {
        case .blocked, .hidden: return "checkmark.shield"
        case .showingOriginal: return "arrow.counterclockwise"
        case .offForSite: return "slider.horizontal.3"
        }
    }

    /// The count in bold, the rest of the sentence regular.
    var title: Text {
        switch self {
        case .blocked(let count):
            let format = String(localized: count == 1 ? "blocking.card.blocked.one" : "blocking.card.blocked.other")
            let parts = format.components(separatedBy: "%d")
            guard parts.count == 2 else { return Text(String(format: format, count)) }
            return Text(parts[0]) + Text("\(count)").bold() + Text(parts[1])
        case .showingOriginal:
            return Text("blocking.card.original")
        case .offForSite(let site):
            return Text(String(format: String(localized: "blocking.card.off_for_site"), site))
        case .hidden:
            return Text(verbatim: "")
        }
    }

    var detail: LocalizedStringKey {
        switch self {
        case .blocked, .hidden: return "blocking.card.restore"
        case .showingOriginal: return "blocking.card.block_again"
        case .offForSite: return "blocking.card.open_site_settings"
        }
    }
}

/// The domain in the navigation bar, drawn over the whole bar. It sits in the
/// middle of the bar, as in Safari, so back appearing beside it does not push
/// it around; only when the buttons leave too little room does it slide
/// aside. A domain too long for the space fades out at its end instead of
/// breaking off with "…". Its space comes in as insets from the bar's edges,
/// worked out from which buttons show, so it moves in the same animation as
/// the buttons rather than a frame after them.
private struct AddressLabel: View {
    let text: String
    let symbol: String?
    let barWidth: CGFloat
    /// Where the buttons on either side end, from the bar's edges.
    let leadingInset: CGFloat
    let trailingInset: CGFloat
    /// The prompt on an empty page is quieter than a real domain.
    var dimmed = false

    @Environment(\.colorScheme) private var scheme
    @State private var contentWidth: CGFloat = 0

    /// How long the fade at the end is.
    private let fadeLength: CGFloat = 28

    private var areaWidth: CGFloat { max(0, barWidth - leadingInset - trailingInset) }
    private var overflows: Bool { contentWidth > areaWidth + 0.5 }

    /// Where the text starts on the bar: centred while it fits there, else as
    /// close to that as its space allows.
    private var x: CGFloat {
        guard !overflows else { return leadingInset }
        let centred = (barWidth - contentWidth) / 2
        return min(max(centred, leadingInset), barWidth - trailingInset - contentWidth)
    }

    var body: some View {
        HStack(spacing: 5) {
            if let symbol {
                Image(systemName: symbol)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(Palette.ink(scheme).opacity(0.6))
            }
            Text(verbatim: text)
                .zenFont(16, weight: .semibold, design: .rounded)
                .foregroundStyle(Palette.ink(scheme).opacity(dimmed ? 0.55 : 1))
                .lineLimit(1)
        }
        .fixedSize()
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { contentWidth = $0 }
        .offset(x: x)
        .frame(width: barWidth, alignment: .leading)
        // Only the space between the buttons shows text; past its end it fades.
        .mask(alignment: .leading) {
            LinearGradient(
                stops: overflows
                    ? [
                        .init(color: .black, location: 0),
                        .init(color: .black, location: max(0, 1 - fadeLength / max(areaWidth, 1))),
                        .init(color: .clear, location: 1),
                    ]
                    : [.init(color: .black, location: 0), .init(color: .black, location: 1)],
                startPoint: .leading,
                endPoint: .trailing
            )
            .frame(width: areaWidth)
            .offset(x: leadingInset)
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}
