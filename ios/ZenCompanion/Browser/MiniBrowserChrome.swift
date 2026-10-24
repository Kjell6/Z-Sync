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

    /// Dock bottom to navigation bar top.
    static var height: CGFloat { navBarHeight + rowSpacing + dockHeight }

    /// Screen bottom to navigation bar top, given the bar's bottom padding.
    static func height(above bottomPadding: CGFloat) -> CGFloat {
        bottomPadding + height
    }
}

/// Bottom browser chrome: two rows of floating Liquid Glass, bottom to top.
///
///   [tab] [close] [⋯]  the dock: a tabs placeholder, the way out, the menu
///   [navigation bar]   the full-width address bar, handed in as `navBar`
///   [menu panel]       only while the page menu is open: it grows above the bar
///
/// Nothing spans the screen edge to edge and nothing has a hard edge: the page
/// stays visible behind the glass.
struct MiniBrowserChrome<NavBar: View>: View {
    var model: MiniBrowserModel
    let space: ZenSpace
    let allSpaces: [ZenSpace]
    var onToggleMenu: () -> Void
    /// Closes the mini-browser. The dock's middle control used to be a
    /// placeholder; it is the way out now that there is no sheet to swipe down.
    var onClose: () -> Void
    /// While the address is being typed the bar rides on the keyboard, and the
    /// dock would only sit under the keys: it is dropped then.
    var addressEditing = false
    /// A tapped suggestion goes through the same submit path as typed text.
    var onPickSuggestion: (String) -> Void
    /// The navigation bar. It stays in place while the menu grows above it.
    @ViewBuilder var navBar: () -> NavBar

    @Environment(\.colorScheme) private var scheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var copied = false
    @State private var copyGeneration = 0

    private let controlSide: CGFloat = MiniBrowserChromeMetrics.dockHeight
    /// Menu tiles keep the app's own tile corner.
    private let tileRadius: CGFloat = Palette.liftCornerRadius

    private var motion: Animation {
        reduceMotion ? .easeOut(duration: 0.12) : .spring(response: 0.24, dampingFraction: 0.82)
    }

    private var menuOpen: Bool { model.menuExpanded }

    /// The kind the next save will use: the setting, unless this account cannot
    /// write normal tabs.
    private var effectiveSaveKind: SaveKind { model.allowsNormalTabs ? model.saveKind : .pinned }

    var body: some View {
        VStack(spacing: MiniBrowserChromeMetrics.rowSpacing) {
            if menuOpen {
                expandedContent
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
            if addressEditing, !model.suggestions.isEmpty {
                suggestionList
                    .frame(maxWidth: .infinity)
                    .fixedSize(horizontal: false, vertical: true)
                    .floatingGlassPanel(cornerRadius: 26)
                    .transition(
                        .asymmetric(
                            insertion: .scale(scale: 0.88, anchor: .bottom)
                                .combined(with: .opacity),
                            removal: .opacity
                                .combined(with: .scale(scale: 0.95, anchor: .bottom))
                        )
                    )
            }

            navBar()

            if !addressEditing {
                HStack(spacing: 10) {
                    // Placeholder: the page stack arrives with real tabs.
                    dockButton(systemName: "square.on.square", label: "browser.tabs") {}
                    Spacer(minLength: 0)
                    // Closes the mini-browser.
                    closeButton
                    Spacer(minLength: 0)
                    dockButton(
                        systemName: "ellipsis",
                        label: menuOpen ? "browser.close_menu" : "browser.more"
                    ) {
                        withAnimation(motion) { onToggleMenu() }
                    }
                }
                // Pulls the outer buttons inwards. The navigation bar above
                // keeps its full width.
                .padding(.horizontal, 7)
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
        .animation(motion, value: model.menuExpanded)
        .animation(motion, value: model.suggestions)
    }

    // MARK: - Suggestions

    /// Part of the address editing state, not a new layer: the rows use the
    /// same ink and row height as the menu, and the panel sits in the space
    /// above the bar that only exists while typing.
    private var suggestionList: some View {
        VStack(spacing: 0) {
            ForEach(Array(model.suggestions.enumerated()), id: \.offset) { index, suggestion in
                Button {
                    onPickSuggestion(suggestion)
                } label: {
                    HStack(spacing: 10) {
                        Image(systemName: "magnifyingglass")
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(Palette.ink(scheme).opacity(0.45))
                            .frame(width: 22, height: 22)
                        Text(suggestion)
                            .font(.system(size: 16, weight: .medium, design: .rounded))
                            .foregroundStyle(Palette.ink(scheme))
                            .lineLimit(1)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .padding(.horizontal, 20)
                    .frame(minHeight: 44)
                    .contentShape(Rectangle())
                    .overlay(alignment: .bottom) {
                        if index < model.suggestions.count - 1 {
                            Rectangle()
                                .fill(Color.primary.opacity(0.08))
                                .frame(height: 0.5)
                                .padding(.leading, 52)
                        }
                    }
                }
                .buttonStyle(SuggestionPressStyle())
            }
        }
        .padding(.vertical, 6)
        .accessibilityElement(children: .contain)
    }

    /// Smaller than the side buttons, as the middle control of the row.
    private var closeButton: some View {
        Button(action: onClose) {
            Image(systemName: "xmark")
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(Palette.ink(scheme))
                .frame(width: 92, height: 42)
                .contentShape(Capsule())
                // Inside the label on purpose: the pressed state has to move
                // the whole pill, not only the glyph on it.
                .floatingGlassCapsule()
        }
        .buttonStyle(GlassPressStyle())
        .accessibilityLabel(Text("browser.close"))
    }

    private func dockButton(
        systemName: String,
        label: LocalizedStringKey? = nil,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(Palette.ink(scheme))
                .frame(width: controlSide, height: controlSide)
                .contentShape(Capsule())
                // Same as the close control: the glass is the button, so it has
                // to answer the finger as a whole.
                .floatingGlassCapsule()
        }
        .buttonStyle(GlassPressStyle())
        .accessibilityLabel(Text(label ?? LocalizedStringKey(systemName)))
    }

    // MARK: - Expanded menu

    /// Grows above the navigation bar. Everything inside shares one outer inset,
    /// so rows, tiles and the pin row start on the same edge on every side.
    private var expandedContent: some View {
        VStack(spacing: 0) {
            // At the top: the menu grows up from the bar, so this is the far
            // end, where Arc keeps the same card at the bottom of its sheet.
            if model.blockingCard != .hidden {
                blockingCard
                    .padding(.bottom, 8)
            }
            VStack(spacing: 0) {
                copyRow
                rowDivider
                readerRow
                rowDivider
                desktopRow
                rowDivider
                zoomRow
                rowDivider
                siteSettingsRow
            }

            actionRow
                .padding(.top, 8)

            if model.showPinBanner {
                // No opacity transition. The sheet just grows by this row.
                pinSavedSection
                    .padding(.top, 12)
                    .transition(.identity)
            }
        }
        .padding(14)
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

    /// Where the page was saved, and the control that changes that place.
    /// Sits under the quick actions so the open sheet grows upward.
    private var pinSavedSection: some View {
        VStack(spacing: 0) {
            rowDivider
            HStack(spacing: 10) {
                if model.allowsNormalTabs {
                    // Same choice as the share sheet, in the row's own scale:
                    // small enough to belong, raised enough to read as a button.
                    SaveKindMenuButton(
                        kind: model.pinSaveKind,
                        scheme: scheme,
                        style: .compact,
                        onOpen: { model.pinMenuWillOpen() }
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
                    .font(.system(size: 16, weight: .medium, design: .rounded))
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
            // Narrower than the menu rows above it, so the save row reads as
            // its own confirmation instead of another settings row.
            .padding(.horizontal, 8)
        }
    }

    @ViewBuilder
    private var shareTile: some View {
        if let url = model.effectiveURL {
            SwiftUI.ShareLink(item: url) {
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
                .font(.system(size: 11, weight: .semibold, design: .rounded))
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
                    .foregroundStyle(copied ? Palette.coral(scheme) : Color.primary)
                Text(copied ? "browser.copied" : "browser.copy_link")
                    .font(.system(size: 16, weight: .medium, design: .rounded))
                    .foregroundStyle(copied ? Palette.coral(scheme) : Color.primary)
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
                    .font(.system(size: 16, weight: .medium, design: .rounded))
                    .foregroundStyle(Color.primary)
                Spacer(minLength: 8)
                if model.readerLoading {
                    ProgressView()
                        .controlSize(.small)
                } else if model.readerArticle != nil {
                    Image(systemName: "checkmark")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(Palette.coral(scheme))
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
                .font(.system(size: 16, weight: .medium, design: .rounded))
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
            .tint(Palette.coral(scheme))
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
                .font(.system(size: 16, weight: .medium, design: .rounded))
                .lineLimit(1)
            Spacer(minLength: 8)
            HStack(spacing: 0) {
                zoomStepButton(systemName: "minus", label: "browser.zoom_out", enabled: model.canZoomOut) {
                    model.zoomOut()
                }
                Text(MiniBrowserTools.zoomPercentLabel(model.pageZoom))
                    .font(.system(size: 15, weight: .semibold, design: .rounded))
                    .monospacedDigit()
                    .frame(minWidth: 52)
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
    @ViewBuilder
    private var blockingCard: some View {
        let card = model.blockingCard
        Button {
            withAnimation(motion) { model.tapBlockingCard() }
        } label: {
            HStack(spacing: 12) {
                Image(systemName: card.symbol)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(Palette.coral(scheme))
                    .frame(width: 32, height: 32)
                    .background(Palette.coral(scheme).opacity(0.14), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                VStack(alignment: .leading, spacing: 1) {
                    card.title
                        .font(.system(size: 15, weight: .semibold, design: .rounded))
                        .foregroundStyle(Color.primary)
                    Text(card.detail)
                        .font(.system(size: 13, design: .rounded))
                        .foregroundStyle(Color.primary.opacity(0.6))
                }
                .multilineTextAlignment(.leading)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .background(Palette.lift(scheme), in: RoundedRectangle(cornerRadius: tileRadius, style: .continuous))
            .glassRim(cornerRadius: tileRadius)
            .contentShape(Rectangle())
        }
        .buttonStyle(CardPressStyle())
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
                    .font(.system(size: 16, weight: .medium, design: .rounded))
                    .foregroundStyle(Color.primary)
                Spacer(minLength: 8)
                Image(systemName: "chevron.right")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(Color.primary.opacity(0.35))
            }
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
            .padding(.leading, 46)
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

// MARK: - Top bar

/// Full-width navigation bar over the page: back, forward, the address and the
/// reload. Same glass as the dock below.
struct MiniBrowserTopBar: View {
    var model: MiniBrowserModel
    var addressEditing: Bool
    @Binding var isAddressFocused: Bool
    var onBeginAddressEdit: () -> Void
    var onCancel: () -> Void
    var onSubmit: () -> Void

    @Environment(\.colorScheme) private var scheme

    private let barHeight: CGFloat = MiniBrowserChromeMetrics.navBarHeight

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
                // Same thin progress line Android draws under its row.
                .overlay(alignment: .bottom) {
                    if model.isLoading, model.estimatedProgress > 0, model.estimatedProgress < 1 {
                        GeometryReader { proxy in
                            Capsule()
                                .fill(Palette.coral(scheme))
                                .frame(
                                    width: max(2, proxy.size.width * model.estimatedProgress),
                                    height: 2
                                )
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        .frame(height: 2)
                        .padding(.horizontal, 12)
                        .padding(.bottom, 1)
                    }
                }
                .floatingGlassCapsule()
        }
    }

    private var navigationRow: some View {
        HStack(spacing: 6) {
            iconButton(systemName: "chevron.backward", label: "browser.back", enabled: model.canGoBack) {
                model.goBack()
            }
            iconButton(systemName: "chevron.forward", label: "browser.forward", enabled: model.canGoForward) {
                model.goForward()
            }

            Button(action: onBeginAddressEdit) {
                HStack(spacing: 8) {
                    siteIcon
                    Text(displayTitle)
                        .font(.system(size: 16, weight: .semibold, design: .rounded))
                        .foregroundStyle(Palette.ink(scheme))
                        .lineLimit(1)
                        .truncationMode(.tail)
                    Spacer(minLength: 0)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(AddressPressStyle())
            .accessibilityLabel(Text("browser.search_placeholder"))

            iconButton(
                systemName: model.isLoading ? "xmark" : "arrow.clockwise",
                label: model.isLoading ? "browser.stop" : "browser.reload",
                enabled: true
            ) {
                if model.isLoading { model.stopLoading() } else { model.reload() }
            }
        }
    }

    private var displayTitle: String {
        let title = model.pageTitle.trimmingCharacters(in: .whitespacesAndNewlines)
        if !title.isEmpty { return title }
        let host = model.pageHost
        return host.isEmpty ? String(localized: "browser.search_placeholder") : host
    }

    private var siteIcon: some View {
        Group {
            if let url = model.effectiveURL, url.scheme == "http" || url.scheme == "https" {
                Favicon(urlString: url.absoluteString, size: 16)
            } else {
                Image(systemName: "magnifyingglass")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(Palette.ink(scheme).opacity(0.55))
            }
        }
        .frame(width: 22, height: 22)
    }

    private func iconButton(
        systemName: String,
        label: LocalizedStringKey,
        enabled: Bool,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(Palette.ink(scheme).opacity(enabled ? 0.85 : 0.3))
                .frame(width: 32, height: barHeight - 8)
                .contentShape(Rectangle())
                .contentTransition(.symbolEffect(.replace))
        }
        .buttonStyle(GlyphPressStyle())
        .disabled(!enabled)
        .accessibilityLabel(Text(label))
    }

    private var editor: some View {
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
        .padding(.horizontal, 16)
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
