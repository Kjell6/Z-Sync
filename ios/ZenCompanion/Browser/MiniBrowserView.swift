import SwiftUI
import WebKit

/// In-app mini-browser. Collapsed, the page ends where the bottom bar begins.
/// The open menu and the keyboard composer draw over the page, so the page
/// itself does not move. The keyboard composer keeps running down to the
/// screen bottom, behind the keys (`MiniBrowserChrome`).
struct MiniBrowserView: View {
    let initialURL: URL?
    let space: ZenSpace
    let allSpaces: [ZenSpace]
    var onDismiss: () -> Void

    @Environment(\.colorScheme) private var scheme
    @State private var model: MiniBrowserModel
    @FocusState private var isAddressFocused: Bool
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

    /// Keyboard focus or the search-open first paint.
    private var addressEditing: Bool { isAddressFocused || startsInSearch }

    /// Space inside the glass, below the controls. While typing this includes
    /// the keyboard height, so the same glass continues behind the keys down
    /// to the screen bottom. The field stays just above the keys.
    private var chromeContentBottomInset: CGFloat {
        if keyboardOverlap > 1 { return keyboardOverlap + 16 }
        return safeBottom + 8
    }

    /// Height of the collapsed bar. The page always reserves exactly this, so
    /// opening the menu or the keyboard does not push the page up.
    private var collapsedBarHeight: CGFloat { 10 + 44 + safeBottom + 8 }

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
                        .frame(height: collapsedBarHeight)
                        .accessibilityHidden(true)
                        .allowsHitTesting(false)
                }

                if model.showPinBanner {
                    pinBanner
                        .padding(.horizontal, 20)
                        .padding(.bottom, collapsedBarHeight + 8)
                        .frame(maxWidth: .infinity)
                } else if let notice = model.pinNotice {
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
                    onCancel: cancelAddressEditing,
                    onSubmit: submitAddress
                )
                .fixedSize(horizontal: false, vertical: true)
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
            .animation(.spring(response: 0.38, dampingFraction: 0.86), value: model.showPinBanner)
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
            .onChange(of: isAddressFocused) { _, focused in
                if focused {
                    model.collapseMenu()
                } else {
                    startsInSearch = false
                }
                model.addressFocusDidChange(focused)
                if focused && !model.addressText.isEmpty {
                    DispatchQueue.main.async {
                        UIApplication.shared.sendAction(#selector(UIResponder.selectAll(_:)), to: nil, from: nil, for: nil)
                    }
                }
            }
            .onDisappear {
                model.commitPendingPinSave()
            }
        }
        .geometryGroup()
    }

    /// The page stops at the bar. No extra viewport inset under the glass.
    private var webPane: some View {
        MiniBrowserRepresentable(
            model: model,
            bottomBarVisible: false,
            bottomChromeHeight: 0,
            acceptsPageTaps: !model.menuExpanded
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
    private func cancelAddressEditing() {
        withAnimation(.spring(response: 0.35, dampingFraction: 0.85)) {
            if model.effectiveURL != nil {
                isAddressFocused = false
                startsInSearch = false
            } else {
                onDismiss()
            }
        }
    }

    private func submitAddress() {
        withAnimation(.spring(response: 0.3, dampingFraction: 0.8)) {
            isAddressFocused = false
            startsInSearch = false
        }
        model.submitAddress()
    }

    // MARK: - Pin Toast Banner View

    @ViewBuilder
    private var pinBanner: some View {
        HStack(spacing: 0) {
            HStack(spacing: 8) {
                Image(systemName: "pin.fill")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(.primary)

                Text(model.pinSaveKind == .pinned ? "browser.pinned" : "browser.saved_as_normal")
                    .font(.system(size: 16, weight: .semibold, design: .rounded))
                    .foregroundStyle(.primary)
            }

            Spacer(minLength: 14)

            Rectangle()
                .fill(Color.primary.opacity(0.16))
                .frame(width: 1, height: 18)
                .padding(.horizontal, 12)

            PinDestinationMenuButton(
                destination: model.pinnedDestination,
                spaces: allSpaces,
                scheme: scheme,
                titleSize: 16,
                allowsFolders: model.pinSaveKind == .pinned,
                onSelect: { chosen in
                    model.selectPinDestination(chosen)
                },
                onMenuWillOpen: {
                    // Keep the banner alive while the picker is open.
                    model.pinMenuWillOpen()
                },
                onMenuDidDismiss: {
                    model.pinMenuDidDismiss()
                }
            )
        }
        .padding(.horizontal, 16)
        .frame(height: 48)
        .liquidGlassCapsule(
            interactive: true,
            glassVisible: !model.pinBannerGlassSuppressed
        )
        .floatingGlassShadow(scheme: scheme)
        .transition(.asymmetric(
            insertion: .move(edge: .bottom).combined(with: .opacity).combined(with: .scale(scale: 0.95)),
            removal: .move(edge: .bottom).combined(with: .opacity)
        ))
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
