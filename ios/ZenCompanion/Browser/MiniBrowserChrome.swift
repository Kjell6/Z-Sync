import SwiftUI

/// Bottom browser card.
///
/// Android port (`MiniBrowserChrome` / `MiniBrowserFooter`): one surface at the
/// bottom of the page, not a top field plus a separate toolbar.
///
/// Collapsed row, leading to trailing: Back, Forward only when
/// `MiniBrowserTools.showsForwardButton`, the address field, More.
/// More unfolds this same card. The unfolded menu is, top to bottom:
/// page title and favicon, then Pin / Open / Share / Find, then Copy Link,
/// Request Desktop Site, and Page Zoom. No translate control.
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
        VStack(spacing: 0) {
            compactRow
            if model.menuExpanded {
                // The menu keeps its height when it fits. On a short phone or
                // with large text it scrolls instead of leaving the card.
                ViewThatFits(in: .vertical) {
                    pageMenu
                    ScrollView {
                        pageMenu
                    }
                    .scrollBounceBehavior(.basedOnSize)
                    .frame(maxHeight: 420)
                }
                .transition(.opacity)
            }
        }
        .liquidGlassCard(cornerRadius: radius)
        .shadow(
            color: Color.black.opacity(scheme == .dark ? 0.35 : 0.12),
            radius: 18,
            x: 0,
            y: 8
        )
        .animation(chromeAnimation, value: model.menuExpanded)
        .animation(chromeAnimation, value: model.canGoForward)
        .animation(chromeAnimation, value: addressEditing)
        .animation(chromeAnimation, value: model.addressText.isEmpty)
    }

    // MARK: - Compact row

    private var compactRow: some View {
        HStack(spacing: 0) {
            if !addressEditing {
                navButton(
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
                    navButton(
                        systemName: "chevron.forward",
                        label: "browser.forward",
                        enabled: true
                    ) {
                        model.goForward()
                    }
                    .transition(.opacity.combined(with: .scale(scale: 0.86)))
                }
            }

            addressField

            if addressEditing {
                navButton(
                    systemName: "xmark",
                    label: "common.cancel",
                    enabled: true,
                    tint: Palette.coral(scheme)
                ) {
                    onCancel()
                }
                .transition(.opacity.combined(with: .scale(scale: 0.86)))
            } else {
                navButton(
                    systemName: model.menuExpanded ? "chevron.down" : "ellipsis",
                    label: model.menuExpanded ? "browser.close_menu" : "browser.more",
                    enabled: model.effectiveURL != nil
                ) {
                    withAnimation(motion) {
                        model.toggleMenu()
                    }
                }
            }
        }
        .padding(.leading, addressEditing ? 8 : 2)
        .padding(.trailing, 2)
        .padding(.vertical, 4)
        .overlay(alignment: .bottom) {
            if model.isLoading && !addressEditing {
                progressBar
                    .padding(.horizontal, 22)
                    .padding(.bottom, 1)
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

    private func navButton(
        systemName: String,
        label: LocalizedStringKey,
        enabled: Bool,
        tint: Color? = nil,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(tint ?? (enabled ? Color.primary : Color.secondary.opacity(0.45)))
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
                .contentTransition(.symbolEffect(.replace))
        }
        .buttonStyle(CardPressStyle())
        .disabled(!enabled)
        .accessibilityLabel(Text(label))
    }

    // MARK: - Expanded menu

    private var pageMenu: some View {
        VStack(spacing: 0) {
            Rectangle()
                .fill(Color.primary.opacity(0.10))
                .frame(height: 0.5)
                .padding(.horizontal, 18)

            identity
                .padding(.horizontal, 16)
                .padding(.top, 16)
                .padding(.bottom, 14)

            actionRow
                .padding(.horizontal, 16)
                .padding(.bottom, 8)

            VStack(spacing: 0) {
                copyRow
                rowDivider
                desktopRow
                rowDivider
                zoomRow
            }
            .padding(.horizontal, 8)
            .padding(.bottom, 8)
        }
    }

    private var identity: some View {
        HStack(alignment: .center, spacing: 12) {
            if let url = model.effectiveURL {
                Favicon(urlString: url.absoluteString, size: 40)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(identityTitle)
                    .font(.system(size: 17, weight: .semibold, design: .rounded))
                    .foregroundStyle(.primary)
                    .lineLimit(2)
                    .multilineTextAlignment(.leading)
                if !identityHost.isEmpty {
                    HStack(spacing: 4) {
                        if model.effectiveURL?.scheme == "http" {
                            Image(systemName: "lock.slash.fill")
                                .font(.system(size: 11, weight: .semibold))
                                .foregroundStyle(.orange)
                        }
                        Text(identityHost)
                            .font(.system(size: 13, weight: .medium, design: .rounded))
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                }
            }
            Spacer(minLength: 0)
        }
        .accessibilityElement(children: .combine)
    }

    private var identityTitle: String {
        let title = model.pageTitle.trimmingCharacters(in: .whitespacesAndNewlines)
        if !title.isEmpty { return title }
        if !model.pageHost.isEmpty { return model.pageHost }
        return String(localized: "browser.search_placeholder")
    }

    private var identityHost: String {
        let host = model.pageHost
        if host.isEmpty || host == identityTitle { return "" }
        return host
    }

    private var actionRow: some View {
        HStack(spacing: 8) {
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
            shareTile
            tile(
                systemName: "magnifyingglass",
                title: "browser.find_short",
                accessibility: "browser.find_on_page"
            ) {
                withAnimation(motion) {
                    model.presentFindOnPage()
                }
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

/// Press feedback for controls that sit on the glass card. Glass is the card,
/// not each control, so the press is a small scale rather than another material.
private struct CardPressStyle: ButtonStyle {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(!reduceMotion && configuration.isPressed ? 0.97 : 1)
            .opacity(configuration.isPressed ? 0.72 : 1)
            .animation(reduceMotion ? nil : .spring(response: 0.22, dampingFraction: 0.75), value: configuration.isPressed)
    }
}
