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
///   [tab] [＋] [⋯]   three separate glass buttons (tab and plus are placeholders)
///   [navigation bar] the full-width address bar, handed in as `navBar`
///   [menu panel]     only while the page menu is open: it grows above the bar
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
                dockButton(systemName: "square.on.square") {}
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
            // Pulls the outer buttons inwards. The navigation bar above keeps
            // its full width.
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
        }
        .buttonStyle(SquircleButtonStyle())
        .floatingGlassCapsule()
        .accessibilityLabel(Text("browser.close"))
    }

    private func dockButton(
        systemName: String,
        label: LocalizedStringKey? = nil,
        wide: Bool = false,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(Palette.ink(scheme))
                .frame(maxWidth: wide ? .infinity : nil)
                .frame(width: wide ? nil : controlSide, height: controlSide)
                .contentShape(Capsule())
        }
        .buttonStyle(SquircleButtonStyle())
        .floatingGlassCapsule()
        .accessibilityLabel(Text(label ?? LocalizedStringKey(systemName)))
    }

    // MARK: - Expanded menu

    /// Grows above the navigation bar. Everything inside shares one outer inset,
    /// so rows, tiles and the pin row start on the same edge on every side.
    private var expandedContent: some View {
        VStack(spacing: 0) {
            VStack(spacing: 0) {
                copyRow
                rowDivider
                readerRow
                rowDivider
                desktopRow
                rowDivider
                zoomRow
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
                title: model.saveKind == .normal ? "browser.save_short" : "browser.pin_short",
                accessibility: model.saveKind == .normal ? "browser.save_short" : "browser.pin_to_space"
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
            HStack(spacing: 12) {
                Image(systemName: "pin.fill")
                    .font(.system(size: 16, weight: .semibold))
                    .frame(width: 24)
                    .foregroundStyle(Color.primary)
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
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            tileLabel(systemName: systemName, title: title)
        }
        .buttonStyle(CardPressStyle())
        .disabled(model.effectiveURL == nil)
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

    private var rowDivider: some View {
        Rectangle()
            .fill(Color.primary.opacity(0.08))
            .frame(height: 0.5)
            .padding(.leading, 46)
    }
}

/// Press feedback for rows inside the bar.
/// control itself, so the press is a small scale rather than another material.
private struct CardPressStyle: ButtonStyle {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(!reduceMotion && configuration.isPressed ? 0.97 : 1)
            .opacity(configuration.isPressed ? 0.72 : 1)
            .animation(reduceMotion ? nil : .spring(response: 0.22, dampingFraction: 0.75), value: configuration.isPressed)
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
    @FocusState.Binding var isAddressFocused: Bool
    var onBeginAddressEdit: () -> Void
    var onCancel: () -> Void
    var onSubmit: () -> Void

    @Environment(\.colorScheme) private var scheme

    private let barHeight: CGFloat = MiniBrowserChromeMetrics.navBarHeight

    var body: some View {
        HStack(spacing: 6) {
            if addressEditing {
                editor
            } else {
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
                .buttonStyle(.plain)
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
        .padding(.horizontal, 8)
        .frame(height: barHeight)
        .floatingGlassCapsule()
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
        .buttonStyle(SquircleButtonStyle())
        .disabled(!enabled)
        .accessibilityLabel(Text(label))
    }

    private var editor: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 15, weight: .semibold))
                // Same ink as the other glyphs - the brand color reads as a
                // button state here, not as part of the field.
                .foregroundStyle(Palette.ink(scheme).opacity(0.85))

            TextField(
                "",
                text: Bindable(model).addressText,
                prompt: Text("browser.search_placeholder")
                    .foregroundStyle(Palette.ink(scheme).opacity(0.45))
            )
            .font(.system(size: 16, weight: .medium, design: .rounded))
            .foregroundStyle(Palette.ink(scheme))
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .keyboardType(.webSearch)
            .submitLabel(.go)
            .multilineTextAlignment(.leading)
            .focused($isAddressFocused)
            .onSubmit(onSubmit)
            .onAppear {
                DispatchQueue.main.async {
                    guard addressEditing else { return }
                    isAddressFocused = true
                }
            }

            if !model.addressText.isEmpty {
                Button {
                    model.clearAddressText()
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .font(.system(size: 15))
                        .foregroundStyle(Palette.ink(scheme).opacity(0.45))
                        .frame(width: 28, height: barHeight - 8)
                        .contentShape(Rectangle())
                }
                .buttonStyle(SquircleButtonStyle())
                .accessibilityLabel(Text("browser.clear"))
            }

            Button(action: onCancel) {
                Image(systemName: "xmark")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(Palette.ink(scheme).opacity(0.75))
                    .frame(width: 32, height: barHeight - 8)
                    .contentShape(Rectangle())
            }
            .buttonStyle(SquircleButtonStyle())
            .accessibilityLabel(Text("common.cancel"))
        }
    }
}
