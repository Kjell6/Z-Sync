import SwiftUI

/// Bottom browser chrome. The page ends where this bar begins.
///
/// Android port (`MiniBrowserChrome` / `MiniBrowserFooter`): one bottom strip.
/// Collapsed, the page ends where the strip begins. Expanded, and while the
/// address field is editing, the strip draws over the page and keeps the
/// screen bottom, so the page does not move. The address composer runs behind
/// the keyboard. A page field does not lift this strip.
///
/// One opaque plate of the page color behind everything (`browserBar`), so the
/// bar and the open menu are the same surface. Controls use the app's tile
/// language: `Palette.lift`, the address field as a pill, the side controls as
/// circles.
struct MiniBrowserChrome: View {
    var model: MiniBrowserModel
    var addressEditing: Bool
    var animateChrome: Bool
    @FocusState.Binding var isAddressFocused: Bool
    let space: ZenSpace
    let allSpaces: [ZenSpace]
    /// Space under the controls. Includes the home indicator while the keyboard
    /// is hidden, so the plate runs to the physical bottom and the buttons sit
    /// above the indicator.
    var bottomInset: CGFloat
    /// Opaque page color of the plate. Same in both states, so the open menu
    /// keeps the bar's surface instead of turning glassy over the page.
    var barBackground: Color
    /// Collapsed URL label was tapped. The field does not exist yet.
    var onBeginAddressEdit: () -> Void
    var onCancel: () -> Void
    var onSubmit: () -> Void

    @Environment(\.colorScheme) private var scheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var copied = false
    @State private var copyGeneration = 0

    /// Same tile as the home action bar.
    private let tileRadius: CGFloat = Palette.liftCornerRadius
    private let controlSide: CGFloat = 44

    /// Side control corner: noticeably rounder than the app's 16pt tiles, but
    /// still square enough to leave a flat edge (not a circle).
    private var controlCornerRadius: CGFloat { controlSide * 0.45 }

    private var motion: Animation {
        reduceMotion ? .easeOut(duration: 0.15) : .spring(response: 0.38, dampingFraction: 0.86)
    }

    private var chromeAnimation: Animation? {
        animateChrome ? motion : nil
    }

    private var menuOpen: Bool { model.menuExpanded && !addressEditing }

    /// Expanded menu and the typing composer round the top. Collapsed stays cut.
    private var roundsSheetTop: Bool { menuOpen || addressEditing }

    /// The open menu keeps a tighter corner than the sheet around it; the
    /// taller keyboard composer can afford a rounder one.
    private var sheetTopRadius: CGFloat { addressEditing ? 40 : 32 }

    var body: some View {
        VStack(spacing: 0) {
            if menuOpen {
                // No ScrollView and no opacity transition. Both left a gesture
                // in the hierarchy, so after closing nothing on the page received taps.
                expandedContent
            }
            if addressEditing {
                inputComposer
                    .transition(.identity)
            } else {
                controlRow
                    .padding(.horizontal, 20)
                    // Option rows are 48pt with the label centered, so the air
                    // between two labels is about 24pt. 8pt read as too tight.
                    .padding(.top, menuOpen ? 24 : 8)
                    .padding(.bottom, bottomInset)
                    .transition(.identity)
            }
        }
        .frame(maxWidth: .infinity)
        // Hug the controls. A flexible height here becomes a full-screen hit
        // target once the menu has opened, and taps stop reaching the page.
        .fixedSize(horizontal: false, vertical: true)
        .browserBar(
            topRadius: roundsSheetTop ? sheetTopRadius : 0,
            backdrop: barBackground
        )
        .animation(chromeAnimation, value: model.canGoForward)
        .animation(chromeAnimation, value: addressEditing)
        .animation(chromeAnimation, value: model.addressText.isEmpty)
    }

    // MARK: - Compact row

    /// Keyboard composer. One field and cancel, with even air above the keys.
    /// Not the navigation row with a sheet radius pasted on.
    private var inputComposer: some View {
        HStack(spacing: 12) {
            addressCard
            homeTile(
                systemName: "xmark",
                label: "common.cancel",
                enabled: true,
                tint: Palette.coral(scheme)
            ) {
                onCancel()
            }
        }
        .padding(.horizontal, 20)
        .padding(.top, 18)
        .padding(.bottom, bottomInset)
    }

    /// Same rhythm as the home action bar: side tiles, field, side tile.
    /// The trailing tile opens the menu. It points up while closed.
    private var controlRow: some View {
        HStack(spacing: 12) {
            homeTile(
                systemName: "chevron.backward",
                label: "browser.back",
                enabled: model.canGoBack
            ) {
                model.goBack()
            }
            if MiniBrowserTools.showsForwardButton(
                canGoForward: model.canGoForward,
                addressEditing: false
            ) {
                homeTile(
                    systemName: "chevron.forward",
                    label: "browser.forward",
                    enabled: true
                ) {
                    model.goForward()
                }
                .transition(.opacity)
            }

            addressCard

            homeTile(
                systemName: menuOpen ? "chevron.down" : "chevron.up",
                label: menuOpen ? "browser.close_menu" : "browser.more",
                enabled: menuOpen || model.effectiveURL != nil
            ) {
                withAnimation(motion) {
                    model.toggleMenu()
                }
            }
        }
    }

    /// Pill field shared by the navigation row and the keyboard composer.
    /// Collapsed, the whole pill is the button, so press-down scales the
    /// shape the same way as the side controls. The editor is not that button.
    @ViewBuilder
    private var addressCard: some View {
        if addressEditing {
            addressSurface {
                HStack(spacing: 8) {
                    leadingIcon
                    addressEditor
                    trailingIcon
                }
            }
        } else {
            Button(action: onBeginAddressEdit) {
                addressSurface {
                    HStack(spacing: 8) {
                        leadingIcon
                        addressLabel
                        trailingIcon
                    }
                }
                .overlay(alignment: .bottom) {
                    if model.isLoading {
                        progressBar
                            .padding(.horizontal, 18)
                            .padding(.bottom, 1)
                    }
                }
            }
            .buttonStyle(SquircleButtonStyle())
            .accessibilityLabel(collapsedAddressLabel)
        }
    }

    private var collapsedAddressLabel: Text {
        model.addressText.isEmpty
            ? Text("browser.search_placeholder")
            : Text(model.addressText)
    }

    private func addressSurface<Content: View>(@ViewBuilder content: () -> Content) -> some View {
        content()
            .padding(.leading, 18)
            .padding(.trailing, 4)
            .frame(height: controlSide)
            .frame(maxWidth: .infinity)
            .background(Palette.lift(scheme), in: Capsule())
            .glassRimCapsule()
            .contentShape(Capsule())
    }

    /// Side control: a squircle that stays rounder than the app's tiles, so the
    /// row and the address pill read as one family.
    private func homeTile(
        systemName: String,
        label: LocalizedStringKey,
        enabled: Bool,
        tint: Color? = nil,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: 17, weight: .medium))
                .foregroundStyle(tint ?? Palette.ink(scheme).opacity(enabled ? 0.75 : 0.35))
                .frame(width: controlSide, height: controlSide)
                .background(
                    Palette.lift(scheme),
                    in: RoundedRectangle(cornerRadius: controlCornerRadius, style: .continuous)
                )
                .glassRim(cornerRadius: controlCornerRadius)
                .contentShape(RoundedRectangle(cornerRadius: controlCornerRadius, style: .continuous))
                .contentTransition(.symbolEffect(.replace))
        }
        .buttonStyle(SquircleButtonStyle())
        .disabled(!enabled)
        .accessibilityLabel(Text(label))
    }

    private var addressEditor: some View {
        TextField(
            "",
            text: Bindable(model).addressText,
            prompt: Text("browser.search_placeholder")
                .foregroundStyle(Palette.ink(scheme).opacity(0.55))
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
    }

    private var addressLabel: some View {
        Group {
            if model.addressText.isEmpty {
                Text("browser.search_placeholder")
                    .foregroundStyle(Palette.ink(scheme).opacity(0.55))
            } else {
                Text(model.addressText)
                    .foregroundStyle(Palette.ink(scheme))
            }
        }
        .font(.system(size: 16, weight: .medium, design: .rounded))
        .lineLimit(1)
        .frame(maxWidth: .infinity, alignment: .center)
    }

    @ViewBuilder
    private var leadingIcon: some View {
        if addressEditing || model.effectiveURL == nil {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(addressEditing ? Palette.coral(scheme) : Palette.ink(scheme).opacity(0.55))
                .frame(width: 22, height: 22)
        } else if model.effectiveURL?.scheme == "http" {
            Image(systemName: "lock.slash.fill")
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(.orange)
                .frame(width: 22, height: 22)
                .accessibilityHidden(true)
        } else if let url = model.effectiveURL {
            Favicon(urlString: url.absoluteString, size: 18)
                .frame(width: 22, height: 22)
        }
    }

    @ViewBuilder
    private var trailingIcon: some View {
        if addressEditing && !model.addressText.isEmpty {
            Button {
                model.clearAddressText()
            } label: {
                Image(systemName: "xmark.circle.fill")
                    .font(.system(size: 16))
                    .foregroundStyle(.secondary)
                    .frame(width: 36, height: controlSide)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text("browser.clear"))
        } else if !addressEditing && model.effectiveURL != nil {
            Button {
                if model.isLoading {
                    model.stopLoading()
                } else {
                    model.reload()
                }
            } label: {
                Image(systemName: model.isLoading ? "xmark" : "arrow.clockwise")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(.secondary)
                    .frame(width: 36, height: controlSide)
                    .contentShape(Rectangle())
                    .contentTransition(.symbolEffect(.replace))
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text(model.isLoading ? "browser.stop" : "browser.reload"))
        }
    }

    private var progressBar: some View {
        GeometryReader { geo in
            Capsule()
                .fill(Palette.coral(scheme))
                .frame(
                    width: geo.size.width * CGFloat(max(model.estimatedProgress, 0.05)),
                    height: 2.5
                )
                .animation(.linear(duration: 0.2), value: model.estimatedProgress)
        }
        .frame(height: 2.5)
        .accessibilityHidden(true)
    }

    // MARK: - Expanded menu

    /// Grows inside the same full-width plate. The bar's top corners, not this
    /// content, carry the reduced sheet radius.
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
            .padding(.horizontal, 8)
            .padding(.top, 18)

            actionRow
                .padding(.horizontal, 20)
                .padding(.top, 10)

            if model.showPinBanner {
                // No opacity transition. The sheet just grows by this row.
                pinSavedSection
                    .padding(.horizontal, 8)
                    .padding(.top, 16)
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
            .padding(.horizontal, 10)
            .frame(minHeight: 48)
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
        .frame(height: 68)
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
            .padding(.horizontal, 10)
            .frame(minHeight: 48)
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
            .padding(.horizontal, 10)
            .frame(minHeight: 48)
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
        .padding(.horizontal, 10)
        .frame(minHeight: 48)
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
        .padding(.horizontal, 10)
        .frame(minHeight: 48)
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
