import SwiftUI

/// Bottom browser chrome, drawn on top of the page.
///
/// Android port (`MiniBrowserChrome` / `MiniBrowserFooter`): overlay the page.
/// Do not reserve a solid strip under the controls.
///
/// Collapsed, each piece is its own material. On iOS 26 Back, Forward, and
/// More are real `.glass` buttons so the material flexes under the finger.
/// Forward shows only when `MiniBrowserTools.showsForwardButton` is true.
/// The address field is its own interactive capsule. More morphs those pieces
/// into one card on the page: the glass shapes flow, they do not scale-fade.
/// The card's top is a long URL pill (back, forward, URL, reload). Copy,
/// desktop site, and zoom sit under it. Find, Share, Open, and Pin are the
/// bottom row, Pin at the trailing edge. No page title. No translate control.
/// The material is untinted regular glass (a plain material on Android).
struct MiniBrowserChrome: View {
    var model: MiniBrowserModel
    var addressEditing: Bool
    var animateChrome: Bool
    @FocusState.Binding var isAddressFocused: Bool
    let space: ZenSpace
    let allSpaces: [ZenSpace]
    var onCancel: () -> Void
    var onSubmit: () -> Void

    @Environment(\.colorScheme) private var scheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Namespace private var glassNamespace
    @State private var copied = false
    @State private var copyGeneration = 0

    private let radius: CGFloat = 28

    private var motion: Animation {
        reduceMotion ? .easeOut(duration: 0.15) : .spring(response: 0.38, dampingFraction: 0.86)
    }

    private var chromeAnimation: Animation? {
        animateChrome ? motion : nil
    }

    var body: some View {
        glassContainer
            .fixedSize(horizontal: false, vertical: true)
            .animation(chromeAnimation, value: model.menuExpanded)
            .animation(chromeAnimation, value: model.canGoForward)
            .animation(chromeAnimation, value: addressEditing)
            .animation(chromeAnimation, value: model.addressText.isEmpty)
    }

    /// One container so the collapsed glasses can flow into the expanded card.
    /// Spacing matches the row gap: close enough to morph when a piece appears
    /// or disappears, not so close that the circles merge while collapsed.
    @ViewBuilder
    private var glassContainer: some View {
        if #available(iOS 26.0, *) {
            GlassEffectContainer(spacing: 10) {
                morphingChrome
            }
        } else {
            morphingChrome
        }
    }

    private var morphingChrome: some View {
        ZStack(alignment: .bottom) {
            if model.menuExpanded && !addressEditing {
                expandedCard
                    .transition(glassSwap)
            } else {
                controlRow
                    .transition(glassSwap)
            }
        }
    }

    /// The glass container owns the motion. A scale or fade on the whole row
    /// hides that morph. Reduced motion keeps a plain fade.
    private var glassSwap: AnyTransition {
        reduceMotion ? .opacity : .identity
    }

    // MARK: - Compact row

    /// Separate glass pieces. Back and More are circles. The address field is
    /// its own capsule. Nothing here shares one background.
    private var controlRow: some View {
        HStack(spacing: 10) {
            if !addressEditing {
                glassCircle(
                    systemName: "chevron.backward",
                    label: "browser.back",
                    enabled: model.canGoBack,
                    glassID: "back"
                ) {
                    model.goBack()
                }
                if MiniBrowserTools.showsForwardButton(
                    canGoForward: model.canGoForward,
                    addressEditing: addressEditing
                ) {
                    glassCircle(
                        systemName: "chevron.forward",
                        label: "browser.forward",
                        enabled: true,
                        glassID: "forward"
                    ) {
                        model.goForward()
                    }
                    .transition(.opacity)
                }
            }

            addressField
                .padding(.leading, 14)
                .padding(.trailing, 4)
                .frame(height: 44)
                .frame(maxWidth: .infinity)
                .overlay(alignment: .bottom) {
                    if model.isLoading && !addressEditing {
                        progressBar
                            .padding(.horizontal, 18)
                            .padding(.bottom, 1)
                    }
                }
                .clipShape(Capsule())
                .liquidGlassCapsule(interactive: true)
                .modifier(GlassIdentity(id: "address", namespace: glassNamespace, reduceMotion: reduceMotion))
                .floatingGlassShadow(scheme: scheme)

            if addressEditing {
                glassCircle(
                    systemName: "xmark",
                    label: "common.cancel",
                    enabled: true,
                    tint: Palette.coral(scheme)
                ) {
                    onCancel()
                }
                .transition(.opacity)
            } else {
                glassCircle(
                    systemName: "ellipsis",
                    label: "browser.more",
                    enabled: model.effectiveURL != nil,
                    glassID: "more"
                ) {
                    withAnimation(motion) {
                        model.toggleMenu()
                    }
                }
            }
        }
    }

    private var addressField: some View {
        HStack(spacing: 8) {
            leadingIcon
            TextField("browser.search_placeholder", text: Bindable(model).addressText)
                .font(.system(size: 16, weight: .semibold, design: .rounded))
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
        .frame(minHeight: 44)
    }

    @ViewBuilder
    private var leadingIcon: some View {
        if addressEditing || model.effectiveURL == nil {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(addressEditing ? Palette.coral(scheme) : Color.secondary)
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
                    .frame(width: 36, height: 44)
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
                    .frame(width: 36, height: 44)
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

    /// iOS 26 uses a real glass button so the material flexes with the touch.
    /// A custom `glassEffect` circle cannot reproduce that. Older systems keep
    /// the material fallback.
    @ViewBuilder
    private func glassCircle(
        systemName: String,
        label: LocalizedStringKey,
        enabled: Bool,
        tint: Color? = nil,
        glassID: String? = nil,
        action: @escaping () -> Void
    ) -> some View {
        if #available(iOS 26.0, *) {
            nativeGlassCircle(
                systemName: systemName,
                label: label,
                enabled: enabled,
                tint: tint,
                glassID: glassID,
                action: action
            )
        } else {
            legacyGlassCircle(
                systemName: systemName,
                label: label,
                enabled: enabled,
                tint: tint,
                action: action
            )
        }
    }

    @available(iOS 26.0, *)
    private func nativeGlassCircle(
        systemName: String,
        label: LocalizedStringKey,
        enabled: Bool,
        tint: Color?,
        glassID: String?,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: 17, weight: .semibold))
                .contentTransition(.symbolEffect(.replace))
        }
        .labelStyle(.iconOnly)
        .buttonStyle(.glass)
        .buttonBorderShape(.circle)
        .controlSize(.regular)
        .disabled(!enabled)
        .accessibilityLabel(Text(label))
        .modifier(OptionalTint(color: tint))
        .modifier(GlassIdentity(id: glassID, namespace: glassNamespace, reduceMotion: reduceMotion))
    }

    private func legacyGlassCircle(
        systemName: String,
        label: LocalizedStringKey,
        enabled: Bool,
        tint: Color?,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(tint ?? (enabled ? Color.primary : Color.secondary.opacity(0.45)))
                .frame(width: 44, height: 44)
                .contentShape(Circle())
                .contentTransition(.symbolEffect(.replace))
        }
        .buttonStyle(CardPressStyle())
        .disabled(!enabled)
        .accessibilityLabel(Text(label))
        .liquidGlassCircle()
        .floatingGlassShadow(scheme: scheme)
    }

    // MARK: - Expanded menu

    /// One glass surface. The collapsed circles and capsule share glass IDs
    /// with pieces inside this card so the material moves into it.
    private var expandedCard: some View {
        ViewThatFits(in: .vertical) {
            expandedContent
            ScrollView {
                expandedContent
            }
            .scrollBounceBehavior(.basedOnSize)
            .frame(maxHeight: 460)
        }
        .liquidGlassCard(cornerRadius: radius)
        .modifier(GlassIdentity(id: "address", namespace: glassNamespace, reduceMotion: reduceMotion))
        .floatingGlassShadow(scheme: scheme)
    }

    private var expandedContent: some View {
        VStack(spacing: 0) {
            urlPill
                .padding(.horizontal, 12)
                .padding(.top, 12)
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
                .padding(.horizontal, 12)
                .padding(.top, 10)
                .padding(.bottom, 12)
        }
    }

    /// Long pill inside the card: back and forward on the leading side, the
    /// URL in the middle, reload on the trailing side. No page title.
    private var urlPill: some View {
        HStack(spacing: 0) {
            pillButton(
                systemName: "chevron.backward",
                label: "browser.back",
                enabled: model.canGoBack,
                glassID: "back"
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
                    enabled: true,
                    glassID: "forward"
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
                enabled: model.effectiveURL != nil,
                glassID: "more"
            ) {
                if model.isLoading {
                    model.stopLoading()
                } else {
                    model.reload()
                }
            }
        }
        .padding(.horizontal, 4)
        .frame(height: 48)
        .background(
            Color.primary.opacity(scheme == .dark ? 0.14 : 0.06),
            in: Capsule()
        )
        .overlay(alignment: .bottom) {
            if model.isLoading {
                progressBar
                    .padding(.horizontal, 22)
                    .padding(.bottom, 1)
            }
        }
        .clipShape(Capsule())
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
        glassID: String,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(enabled ? Color.primary : Color.secondary.opacity(0.45))
                .frame(width: 40, height: 40)
                .contentShape(Circle())
                .contentTransition(.symbolEffect(.replace))
        }
        .buttonStyle(CardPressStyle())
        .disabled(!enabled)
        .accessibilityLabel(Text(label))
        .modifier(GlassIdentity(id: glassID, namespace: glassNamespace, reduceMotion: reduceMotion))
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
        .foregroundStyle(.primary)
        .frame(maxWidth: .infinity)
        .frame(height: 68)
        .background(
            Color.primary.opacity(scheme == .dark ? 0.12 : 0.06),
            in: RoundedRectangle(cornerRadius: 16, style: .continuous)
        )
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

/// Links a collapsed glass piece to its place inside the expanded card.
/// `matchedGeometry` is what makes the material flow. A nil id leaves the
/// view alone (the cancel circle has no partner).
private struct GlassIdentity: ViewModifier {
    var id: String?
    var namespace: Namespace.ID
    var reduceMotion: Bool

    @ViewBuilder
    func body(content: Content) -> some View {
        if #available(iOS 26.0, *), let id {
            content
                .glassEffectID(id, in: namespace)
                .glassEffectTransition(reduceMotion ? .identity : .matchedGeometry)
        } else {
            content
        }
    }
}

/// Applies a tint only when one was asked for, so the other glass buttons
/// stay the untinted system material.
private struct OptionalTint: ViewModifier {
    var color: Color?

    @ViewBuilder
    func body(content: Content) -> some View {
        if let color {
            content.tint(color)
        } else {
            content
        }
    }
}

/// Press feedback for controls on the floating glass. The glass is the
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
