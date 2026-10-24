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
    let initialURL: URL?
    let space: ZenSpace
    let allSpaces: [ZenSpace]
    /// Whether a save here may write a normal tab. Comes from the synced
    /// browser's capability, like the share sheet.
    var allowsNormalTabs = false
    var onDismiss: () -> Void

    @Environment(\.colorScheme) private var scheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var model: MiniBrowserModel
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

    init(
        initialURL: URL?,
        initialTitle: String?,
        space: ZenSpace,
        allSpaces: [ZenSpace],
        allowsNormalTabs: Bool = false,
        onDismiss: @escaping () -> Void
    ) {
        self.initialURL = initialURL
        self.space = space
        self.allSpaces = allSpaces
        self.allowsNormalTabs = allowsNormalTabs
        self.onDismiss = onDismiss
        _model = State(initialValue: MiniBrowserModel(
            initialURL: initialURL,
            allowsNormalTabs: allowsNormalTabs
        ))
        _startsInSearch = State(initialValue: initialURL == nil)
    }

    /// Keyboard focus, the tap that opens the composer, or the search-open first paint.
    private var addressEditing: Bool { editingAddress || isAddressFocused || startsInSearch }

    /// The bar rides above the keyboard while the address is edited, so the
    /// text being typed stays visible. Otherwise it sits on the screen bottom.
    private var chromeBottomInset: CGFloat {
        if addressEditing, keyboardOverlap > 1 { return keyboardOverlap + 10 }
        return chromeBottomPadding
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
              !addressEditing,
              !model.menuExpanded,
              model.pinNotice == nil else { return false }
        return true
    }

    private var barMotion: Animation {
        reduceMotion ? .easeOut(duration: 0.15) : .spring(response: 0.35, dampingFraction: 0.86)
    }

    private var defaultBackground: Color {
        scheme == .dark ? Color(uiColor: .systemBackground) : Color.white
    }

    /// The page's own color, or the error panel's while it covers the page:
    /// the failed load leaves the previous page's color behind.
    private var sheetBackground: Color {
        if model.pageError != nil { return Palette.paper(scheme) }
        return model.themeColor ?? defaultBackground
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

    var body: some View {
        @Bindable var model = model

        NavigationStack {
            ZStack {
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

                // The page runs out into a soft blur at the bottom instead of
                // stopping against the controls.
                PageBottomFade()
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
                    .opacity(barCollapsed ? 0 : 1)
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
                    space: space,
                    allSpaces: allSpaces,
                    onToggleMenu: { model.toggleMenu() },
                    onClose: {
                        model.commitPendingPinSave()
                        onDismiss()
                    },
                    addressEditing: addressEditing,
                    onPickSuggestion: pickSuggestion,
                    navBar: {
                        MiniBrowserTopBar(
                            model: model,
                            addressEditing: addressEditing,
                            isAddressFocused: $isAddressFocused,
                            onBeginAddressEdit: beginAddressEditing,
                            onCancel: cancelAddressEditing,
                            onSubmit: submitAddress
                        )
                    }
                )
                .padding(.horizontal, 16)
                .padding(.bottom, chromeBottomInset)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
                .offset(y: barCollapsed ? 220 : 0)
                .opacity(barCollapsed ? 0 : 1)
                .accessibilityHidden(barCollapsed)

            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
            .background(sheetBackground.ignoresSafeArea())
            .ignoresSafeArea(edges: .bottom)
            .ignoresSafeArea(.keyboard)
            .presentationBackground(barBackground)
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
                model.loadInitial()
                if model.opensWithSearch {
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
            .onDisappear {
                model.commitPendingPinSave()
            }
            .sheet(isPresented: $model.siteSettingsPresented) {
                if let host = model.blockingHost {
                    SiteSettingsSheet(host: host, scheme: scheme) {
                        model.blockingSettingsDidChange()
                    }
                    .presentationDetents([.medium])
                    .presentationDragIndicator(.visible)
                }
            }
        }
    }

    /// The page stops at the bar: the plate is opaque, so there is nothing to
    /// paint under it and no viewport inset to reserve.
    private var webPane: some View {
        MiniBrowserRepresentable(model: model, bottomClearance: pageBottomClearance)
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
    private func beginAddressEditing() {
        // The full URL is written while the collapsed bar is still a label.
        // The text field is created afterwards, already holding that string.
        model.prepareAddressForEditing()
        model.collapseMenu()
        editingAddress = true
    }

    private func cancelAddressEditing() {
        if model.effectiveURL != nil {
            if isAddressFocused {
                isAddressFocused = false
            } else {
                editingAddress = false
                startsInSearch = false
                model.restoreAddressDisplay()
            }
        } else {
            withAnimation(.spring(response: 0.35, dampingFraction: 0.85)) {
                onDismiss()
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
                if model.leaveErrorPage() {
                    onDismiss()
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
                .font(.system(size: 16, weight: .semibold, design: .rounded))
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
                        .font(.system(size: 26, weight: .bold, design: .rounded))
                        .foregroundStyle(Palette.ink(scheme))
                        .fixedSize(horizontal: false, vertical: true)
                }

                if !article.byline.isEmpty {
                    Text(article.byline)
                        .font(.system(size: 13, weight: .medium, design: .rounded))
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
        .background(Palette.paper(scheme))
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
                .font(.system(size: 12, weight: .semibold, design: .rounded))
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
                .font(.system(size: 20, weight: .semibold, design: .rounded))
                .foregroundStyle(Palette.ink(scheme))
                .padding(.top, 8)
                .fixedSize(horizontal: false, vertical: true)
        case "h3", "h4", "h5", "h6":
            Text(block.text)
                .font(.system(size: 17, weight: .semibold, design: .rounded))
                .foregroundStyle(Palette.ink(scheme))
                .padding(.top, 4)
                .fixedSize(horizontal: false, vertical: true)
        case "blockquote":
            Text(block.text)
                .font(.system(size: 16, design: .rounded))
                .italic()
                .foregroundStyle(Palette.ink(scheme).opacity(0.7))
                .padding(.leading, 14)
                .overlay(alignment: .leading) {
                    Rectangle()
                        .fill(Palette.coral(scheme).opacity(0.5))
                        .frame(width: 2)
                }
                .fixedSize(horizontal: false, vertical: true)
        case "pre":
            Text(block.text)
                .font(.system(size: 13.5, design: .monospaced))
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
                    .font(.system(size: 15, weight: .bold, design: .rounded))
                    .foregroundStyle(Palette.coral(scheme).opacity(0.85))
                Text(block.text)
                    .font(.system(size: 17, design: .rounded))
                    .foregroundStyle(Palette.ink(scheme).opacity(0.9))
                    .fixedSize(horizontal: false, vertical: true)
            }
        default:
            Text(block.text)
                .font(.system(size: 17, design: .rounded))
                .foregroundStyle(Palette.ink(scheme).opacity(0.9))
                .lineSpacing(5)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}
