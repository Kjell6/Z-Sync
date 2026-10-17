import SwiftUI
import WebKit

/// In-app mini-browser. Collapsed, the page ends where the bottom bar begins.
/// The open menu and the address composer draw over the page, so the page
/// itself does not move. The address composer keeps running down to the
/// screen bottom, behind the keys (`MiniBrowserChrome`). A field on the page
/// does not lift that bar: the keys cover it, and a checkmark above them
/// closes the keyboard.
struct MiniBrowserView: View {
    let initialURL: URL?
    let space: ZenSpace
    let allSpaces: [ZenSpace]
    var onDismiss: () -> Void

    @Environment(\.colorScheme) private var scheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var model: MiniBrowserModel
    @FocusState private var isAddressFocused: Bool
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
    /// Home-indicator inset. The glass fills it; the buttons sit above it.
    @State private var safeBottom: CGFloat = Self.windowSafeBottom()
    /// Corner radius of the display this sheet is on.
    @State private var displayCornerRadius: CGFloat = DisplayCornerRadius.points()

    init(
        initialURL: URL?,
        initialTitle: String?,
        space: ZenSpace,
        allSpaces: [ZenSpace],
        onDismiss: @escaping () -> Void
    ) {
        self.initialURL = initialURL
        self.space = space
        self.allSpaces = allSpaces
        self.onDismiss = onDismiss
        _model = State(initialValue: MiniBrowserModel(initialURL: initialURL))
        _startsInSearch = State(initialValue: initialURL == nil)
    }

    /// Keyboard focus, the tap that opens the composer, or the search-open first paint.
    private var addressEditing: Bool { editingAddress || isAddressFocused || startsInSearch }

    /// Space inside the glass, below the controls. The address composer
    /// includes the keyboard height, so the same glass continues behind the
    /// keys. A page field does not: the bar stays on the screen bottom.
    private var chromeContentBottomInset: CGFloat {
        if addressEditing, keyboardOverlap > 1 { return keyboardOverlap + 16 }
        return chromeBottomPadding
    }

    /// Air between the controls and the physical bottom. The home indicator
    /// only occupies the bottom ~13pt, so trimming the safe area keeps the bar
    /// slim without sliding the tiles under the gesture.
    private var chromeBottomPadding: CGFloat {
        max(12, safeBottom - 10)
    }

    /// Webpage or find field, not the address composer.
    private var pageKeyboard: Bool {
        MiniBrowserTools.showsPageKeyboardDone(
            keyboardOverlap: keyboardOverlap,
            addressEditing: addressEditing
        )
    }

    /// Height of the collapsed bar. The page always reserves exactly this, so
    /// opening the menu or the keyboard does not push the page up.
    private var collapsedBarHeight: CGFloat { 8 + 44 + chromeBottomPadding }

    /// The bar tucks away while the page scrolls down and returns on the way
    /// up. Editing the address, an open menu and a live toast keep it in place.
    private var barCollapsed: Bool {
        guard model.scrollHidesBar,
              !addressEditing,
              !model.menuExpanded,
              !pageKeyboard,
              model.pinNotice == nil else { return false }
        return true
    }

    private var barMotion: Animation {
        reduceMotion ? .easeOut(duration: 0.15) : .spring(response: 0.35, dampingFraction: 0.86)
    }

    private var defaultBackground: Color {
        scheme == .dark ? Color(uiColor: .systemBackground) : Color.white
    }

    private var sheetBackground: Color {
        model.themeColor ?? defaultBackground
    }

    var body: some View {
        @Bindable var model = model

        NavigationStack {
            ZStack(alignment: .bottom) {
                VStack(spacing: 0) {
                    ZStack {
                        webPane

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

                    Color.clear
                        .frame(height: barCollapsed ? 0 : collapsedBarHeight)
                        .accessibilityHidden(true)
                        .allowsHitTesting(false)
                }

                if let notice = model.pinNotice {
                    pinNoticeToast(notice)
                        .padding(.horizontal, 20)
                        .padding(.bottom, collapsedBarHeight + 8)
                        .frame(maxWidth: .infinity)
                }

                MiniBrowserChrome(
                    model: model,
                    addressEditing: addressEditing,
                    animateChrome: animateAddressChrome,
                    isAddressFocused: $isAddressFocused,
                    space: space,
                    allSpaces: allSpaces,
                    bottomInset: chromeContentBottomInset,
                    displayCornerRadius: displayCornerRadius,
                    onBeginAddressEdit: beginAddressEditing,
                    onCancel: cancelAddressEditing,
                    onSubmit: submitAddress
                )
                .fixedSize(horizontal: false, vertical: true)
                .offset(y: barCollapsed ? collapsedBarHeight : 0)
                .accessibilityHidden(pageKeyboard || barCollapsed)

                if pageKeyboard {
                    // Above the keys, trailing. Not inside the bar: the bar
                    // stays on the screen bottom, under the keyboard.
                    pageKeyboardDone
                        .padding(.trailing, 20)
                        .padding(.bottom, keyboardOverlap + 12)
                        .frame(maxWidth: .infinity, alignment: .trailing)
                        .transition(.identity)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
            .background(sheetBackground.ignoresSafeArea())
            .ignoresSafeArea(edges: .bottom)
            .ignoresSafeArea(.keyboard)
            .presentationBackground(sheetBackground)
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
                displayCornerRadius = DisplayCornerRadius.points()
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
                    guard !model.addressText.isEmpty else { return }
                    // Select after the keyboard has the field. Doing it in this
                    // turn deadlocks UIKeyboardTaskQueue on the main thread.
                    DispatchQueue.main.async {
                        guard isAddressFocused else { return }
                        UIApplication.shared.sendAction(
                            #selector(UIResponder.selectAll(_:)),
                            to: nil,
                            from: nil,
                            for: nil
                        )
                    }
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
        }
    }

    /// The page stops at the bar. No extra viewport inset under the glass.
    private var webPane: some View {
        MiniBrowserRepresentable(
            model: model,
            bottomBarVisible: false,
            bottomChromeHeight: 0
        )
        .frame(maxWidth: .infinity, maxHeight: .infinity)
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

    /// Closes the webpage keyboard. The address field is not first responder
    /// in this state. WebKit sometimes ignores `endEditing`, so the responder
    /// action always runs as well.
    private func dismissPageKeyboard() {
        _ = model.webView?.endEditing(true)
        UIApplication.shared.sendAction(
            #selector(UIResponder.resignFirstResponder),
            to: nil,
            from: nil,
            for: nil
        )
    }

    /// One glass squircle over the page. A lift tile would disappear on a
    /// white page; this control has no bar behind it.
    private var pageKeyboardDone: some View {
        Button(action: dismissPageKeyboard) {
            Image(systemName: "checkmark")
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(Palette.ink(scheme))
                .frame(width: 44, height: 44)
                .liquidGlassCard(cornerRadius: Palette.liftCornerRadius)
        }
        .buttonStyle(SquircleButtonStyle())
        .accessibilityLabel(Text("common.done"))
    }

    private func submitAddress() {
        if isAddressFocused {
            isAddressFocused = false
        } else {
            editingAddress = false
            startsInSearch = false
        }
        model.submitAddress()
    }

    // MARK: - Pin Refusal Toast

    private func pinNoticeToast(_ text: String) -> some View {
        HStack(spacing: 10) {
            Image(systemName: "pin.slash.fill")
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
