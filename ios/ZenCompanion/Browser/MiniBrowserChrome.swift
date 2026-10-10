import SwiftUI

/// Bottom browser chrome. The page ends where this bar begins.
///
/// Android port (`MiniBrowserChrome` / `MiniBrowserFooter`): one bottom strip,
/// not separate floating pieces and not an overlay on the page. Collapsed, the
/// strip is cut off by the screen edges. Expanded, it keeps that full width
/// and the screen bottom, and its top corners use the display corner radius.
/// Buttons match the home action bar: `Palette.lift`, 13pt continuous corners.
/// The material behind them is one untinted glass surface.
struct MiniBrowserChrome: View {
    var model: MiniBrowserModel
    var addressEditing: Bool
    var animateChrome: Bool
    @FocusState.Binding var isAddressFocused: Bool
    let space: ZenSpace
    let allSpaces: [ZenSpace]
    /// Space under the controls. Includes the home indicator while the keyboard
    /// is hidden, so the glass runs to the physical bottom and the buttons sit
    /// above the indicator.
    var bottomInset: CGFloat
    /// Display corner radius for the expanded bar's top edge.
    var displayCornerRadius: CGFloat
    var onCancel: () -> Void
    var onSubmit: () -> Void

    @Environment(\.colorScheme) private var scheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var copied = false
    @State private var copyGeneration = 0

    /// Same tile as the home action bar.
    private let tileRadius: CGFloat = 13
    private let controlSide: CGFloat = 44
    /// Gap between the expanded sheet corner and the URL bar. The bar's top
    /// radius is the sheet radius minus this, so the two curves share a center.
    private let urlBarInset: CGFloat = 12
    private let urlBarBottomRadius: CGFloat = 16

    private var motion: Animation {
        reduceMotion ? .easeOut(duration: 0.15) : .spring(response: 0.38, dampingFraction: 0.86)
    }

    private var chromeAnimation: Animation? {
        animateChrome ? motion : nil
    }

    private var menuOpen: Bool { model.menuExpanded && !addressEditing }

    var body: some View {
        VStack(spacing: 0) {
            if menuOpen {
                expandedMenu
                    .transition(.opacity)
            }
            controlRow
                .padding(.horizontal, 20)
                .padding(.top, menuOpen ? 4 : 10)
                .padding(.bottom, bottomInset)
        }
        .frame(maxWidth: .infinity)
        .liquidGlassBar(topRadius: menuOpen ? displayCornerRadius : 0)
        .overlay(alignment: .top) {
            if !menuOpen {
                Rectangle()
                    .fill(Color.primary.opacity(0.12))
                    .frame(height: 0.5)
            }
        }
        .animation(chromeAnimation, value: model.menuExpanded)
        .animation(chromeAnimation, value: model.canGoForward)
        .animation(chromeAnimation, value: addressEditing)
        .animation(chromeAnimation, value: model.addressText.isEmpty)
    }

    // MARK: - Compact row

    /// Same rhythm as the home action bar: side tiles, field, side tile.
    /// The trailing tile opens the menu. It points up while closed.
    private var controlRow: some View {
        HStack(spacing: 12) {
            if !addressEditing {
                homeTile(
                    systemName: "chevron.backward",
                    label: "browser.back",
                    enabled: model.canGoBack
                ) {
                    model.goBack()
                }
                if MiniBrowserTools.showsForwardButton(
                    canGoForward: model.canGoForward,
                    addressEditing: addressEditing
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
            }

            addressField
                .padding(.leading, 16)
                .padding(.trailing, 4)
                .frame(height: controlSide)
                .frame(maxWidth: .infinity)
                .overlay(alignment: .bottom) {
                    if model.isLoading && !addressEditing {
                        progressBar
                            .padding(.horizontal, 18)
                            .padding(.bottom, 1)
                    }
                }
                .background(
                    Palette.lift(scheme),
                    in: RoundedRectangle(cornerRadius: tileRadius, style: .continuous)
                )
                .glassRim(cornerRadius: tileRadius)

            if addressEditing {
                homeTile(
                    systemName: "xmark",
                    label: "common.cancel",
                    enabled: true,
                    tint: Palette.coral(scheme)
                ) {
                    onCancel()
                }
                .transition(.opacity)
            } else {
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
    }

    /// Home action-bar tile: lift fill, 13pt continuous corners, 44pt side.
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
                    in: RoundedRectangle(cornerRadius: tileRadius, style: .continuous)
                )
                .glassRim(cornerRadius: tileRadius)
                .contentShape(RoundedRectangle(cornerRadius: tileRadius, style: .continuous))
                .contentTransition(.symbolEffect(.replace))
        }
        .buttonStyle(SquircleButtonStyle())
        .disabled(!enabled)
        .accessibilityLabel(Text(label))
    }

    private var addressField: some View {
        HStack(spacing: 8) {
            leadingIcon
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
                .multilineTextAlignment(addressEditing ? .leading : .center)
                .focused($isAddressFocused)
                .onSubmit(onSubmit)
            trailingIcon
        }
        .frame(maxWidth: .infinity)
        .frame(minHeight: controlSide)
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

    /// Grows inside the same full-width glass. The bar's top corners, not this
    /// content, carry the display radius.
    private var expandedMenu: some View {
        ViewThatFits(in: .vertical) {
            expandedContent
            ScrollView {
                expandedContent
            }
            .scrollBounceBehavior(.basedOnSize)
            .frame(maxHeight: 460)
        }
    }

    private var expandedContent: some View {
        VStack(spacing: 0) {
            urlPill
                .padding(.horizontal, urlBarInset)
                .padding(.top, urlBarInset)
                .padding(.bottom, 4)

            VStack(spacing: 0) {
                copyRow
                rowDivider
                desktopRow
                rowDivider
                zoomRow
            }
            .padding(.horizontal, 8)
            .padding(.top, 4)

            actionRow
                .padding(.horizontal, 20)
                .padding(.top, 10)
                .padding(.bottom, 8)
        }
    }

    /// Top radius of the URL bar, concentric with the sheet's top corners.
    private var urlBarTopRadius: CGFloat {
        max(tileRadius, displayCornerRadius - urlBarInset)
    }

    /// How far the controls sit inside the bar so they clear its corner curve.
    private var urlBarContentInset: CGFloat {
        let topSpace = (urlBarHeight - controlSide) / 2
        return max(12, curveInset(radius: urlBarTopRadius, distanceFromEdge: topSpace) + 6)
    }

    /// Tall enough that the top radius is actually drawn, not clamped into a capsule.
    private var urlBarHeight: CGFloat {
        let top = distanceAlongCurve(radius: urlBarTopRadius, inset: 8)
        let bottom = distanceAlongCurve(radius: urlBarBottomRadius, inset: 4)
        return max(controlSide, top + controlSide + bottom)
    }

    private var urlBarShape: UnevenRoundedRectangle {
        UnevenRoundedRectangle(
            topLeadingRadius: urlBarTopRadius,
            bottomLeadingRadius: urlBarBottomRadius,
            bottomTrailingRadius: urlBarBottomRadius,
            topTrailingRadius: urlBarTopRadius,
            style: .continuous
        )
    }

    /// Horizontal inset of a continuous corner at `distanceFromEdge` from that edge.
    private func curveInset(radius: CGFloat, distanceFromEdge: CGFloat) -> CGFloat {
        guard radius > 1, distanceFromEdge < radius else { return 0 }
        let along = radius - distanceFromEdge
        return radius - sqrt(max(0, radius * radius - along * along))
    }

    /// Distance from the edge at which the corner's horizontal inset has fallen to `inset`.
    private func distanceAlongCurve(radius: CGFloat, inset: CGFloat) -> CGFloat {
        guard radius > inset, inset > 0 else { return 0 }
        let remaining = radius - inset
        return radius - sqrt(max(0, radius * radius - remaining * remaining))
    }

    /// Long bar inside the sheet: back and forward on the leading side, the
    /// URL in the middle, reload on the trailing side. No page title.
    /// Top corners follow the sheet radius; the controls sit below that curve.
    private var urlPill: some View {
        HStack(spacing: 0) {
            pillButton(
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
                pillButton(
                    systemName: "chevron.forward",
                    label: "browser.forward",
                    enabled: true
                ) {
                    model.goForward()
                }
            }
            HStack(spacing: 6) {
                if model.effectiveURL?.scheme == "http" {
                    Image(systemName: "lock.slash.fill")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(.orange)
                        .accessibilityHidden(true)
                }
                Text(pillURL)
                    .font(.system(size: 16, weight: .semibold, design: .rounded))
                    .foregroundStyle(.primary)
                    .lineLimit(1)
                    .truncationMode(.middle)
                    .frame(maxWidth: .infinity)
            }
            .padding(.horizontal, 6)
            pillButton(
                systemName: model.isLoading ? "xmark" : "arrow.clockwise",
                label: model.isLoading ? "browser.stop" : "browser.reload",
                enabled: model.effectiveURL != nil
            ) {
                if model.isLoading {
                    model.stopLoading()
                } else {
                    model.reload()
                }
            }
        }
        .padding(.horizontal, urlBarContentInset)
        .frame(height: controlSide)
        .frame(maxWidth: .infinity)
        .frame(height: urlBarHeight)
        .background(Palette.lift(scheme), in: urlBarShape)
        .overlay(alignment: .bottom) {
            if model.isLoading {
                progressBar
                    .padding(.horizontal, urlBarContentInset + 8)
                    .padding(.bottom, 1)
            }
        }
        .overlay {
            urlBarShape
                .strokeBorder(Color.primary.opacity(0.12), lineWidth: 0.5)
                .allowsHitTesting(false)
        }
    }

    /// Host, plus the path when the page is not the site root.
    private var pillURL: String {
        guard let url = model.effectiveURL else {
            let typed = model.addressText.trimmingCharacters(in: .whitespacesAndNewlines)
            return typed.isEmpty ? String(localized: "browser.search_placeholder") : typed
        }
        let host = url.host ?? url.absoluteString
        let path = url.path
        guard !path.isEmpty, path != "/" else { return host }
        return host + path
    }

    private func pillButton(
        systemName: String,
        label: LocalizedStringKey,
        enabled: Bool,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: 16, weight: .medium))
                .foregroundStyle(Palette.ink(scheme).opacity(enabled ? 0.75 : 0.35))
                .frame(width: 40, height: 40)
                .contentShape(Rectangle())
                .contentTransition(.symbolEffect(.replace))
        }
        .buttonStyle(SquircleButtonStyle())
        .disabled(!enabled)
        .accessibilityLabel(Text(label))
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
                withAnimation(motion) {
                    model.collapseMenu()
                }
                model.triggerPinBanner(spaces: allSpaces, fallbackSpace: space)
            }
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
