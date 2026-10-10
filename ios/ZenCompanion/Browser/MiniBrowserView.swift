import SwiftUI
import WebKit

/// In-app mini-browser. The page fills the sheet, including the area under the
/// bottom controls. Back, Forward, the address field, and More are separate
/// untinted Liquid Glass pieces floating on that page (`MiniBrowserChrome`).
/// More morphs those pieces into one glass card, still on the page.
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
    /// Distance from the screen bottom to the top of the keyboard. The page
    /// stays full-bleed; only the floating glass lifts.
    @State private var keyboardOverlap: CGFloat = 0
    /// Height of the floating glass, measured from the physical bottom,
    /// not including the keyboard lift.
    @State private var chromeHeight: CGFloat = 72

    /// Resting gap from the physical screen edge to the glass. The bar sits
    /// in the home-indicator area, the way a browser toolbar does.
    private let chromeBottomInset: CGFloat = 12
    /// Gap left between the keyboard and the glass.
    private let chromeKeyboardGap: CGFloat = 4

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

    /// Extra lift so the glass sits just above the keyboard. The resting inset
    /// is already part of the measured chrome, so it is not added twice.
    private var keyboardLift: CGFloat {
        max(0, keyboardOverlap + chromeKeyboardGap - chromeBottomInset)
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
                sheetBackground
                    .ignoresSafeArea()

                webPane
                    .ignoresSafeArea(edges: .bottom)

                if model.menuExpanded {
                    Color.clear
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .contentShape(Rectangle())
                        .onTapGesture {
                            withAnimation(.spring(response: 0.38, dampingFraction: 0.86)) {
                                model.collapseMenu()
                            }
                        }
                        .accessibilityLabel(Text("browser.close_menu"))
                        .accessibilityAddTraits(.isButton)
                }

                bottomStack
                    .padding(.horizontal, 12)
                    .padding(.bottom, chromeBottomInset)
                    .background {
                        GeometryReader { geo in
                            Color.clear.preference(key: ChromeHeightKey.self, value: geo.size.height)
                        }
                    }
                    .padding(.bottom, keyboardLift)
                    // Outside the padding, so the 12pt gap is from the physical
                    // bottom and the bar starts in the home-indicator area.
                    .ignoresSafeArea(edges: .bottom)
            }
            .onPreferenceChange(ChromeHeightKey.self) { chromeHeight = $0 }
            .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillChangeFrameNotification)) { note in
                keyboardOverlap = Self.keyboardOverlap(from: note)
            }
            // The page stays under the glass. Keyboard avoidance would open a
            // solid gap and lift the web view off the controls.
            .ignoresSafeArea(.keyboard)
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
            .presentationBackground(sheetBackground)
            .geometryGroup()
            .onAppear {
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

    /// Full-bleed page. The glass overlays it; nothing solid is reserved below.
    private var webPane: some View {
        MiniBrowserRepresentable(
            model: model,
            bottomBarVisible: true,
            bottomChromeHeight: chromeHeight + keyboardLift
        )
        .frame(maxWidth: .infinity, maxHeight: .infinity)
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

    private var bottomStack: some View {
        VStack(spacing: 8) {
            if model.showPinBanner {
                pinBanner
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            } else if let notice = model.pinNotice {
                pinNoticeToast(notice)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
            MiniBrowserChrome(
                model: model,
                addressEditing: addressEditing,
                animateChrome: animateAddressChrome,
                isAddressFocused: $isAddressFocused,
                space: space,
                allSpaces: allSpaces,
                onCancel: cancelAddressEditing,
                onSubmit: submitAddress
            )
        }
    }

}

private struct ChromeHeightKey: PreferenceKey {
    static var defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = max(value, nextValue())
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
