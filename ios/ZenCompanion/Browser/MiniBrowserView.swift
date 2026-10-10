import SwiftUI
import WebKit

/// In-app mini-browser. The page fills the sheet. Every control lives in one
/// untinted Liquid Glass card along the bottom (`MiniBrowserChrome`): back,
/// forward when there is a forward entry, the address field, and More.
/// More unfolds that same card into the page menu.
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

    private var defaultBackground: Color {
        scheme == .dark ? Color(uiColor: .systemBackground) : Color.white
    }

    private var sheetBackground: Color {
        model.themeColor ?? defaultBackground
    }

    var body: some View {
        @Bindable var model = model

        NavigationStack {
            ZStack {
                sheetBackground
                    .ignoresSafeArea()

                VStack(spacing: 0) {
                    webPane
                    bottomStack
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

    /// Page, dimmed while the card is unfolded so the menu reads as a dialog.
    /// The dimmer is not a tint on the glass.
    private var webPane: some View {
        ZStack {
            MiniBrowserRepresentable(model: model, bottomBarVisible: false)
            if model.menuExpanded {
                Color.black.opacity(scheme == .dark ? 0.32 : 0.14)
                    .contentShape(Rectangle())
                    .onTapGesture {
                        withAnimation(.spring(response: 0.38, dampingFraction: 0.86)) {
                            model.collapseMenu()
                        }
                    }
                    .accessibilityLabel(Text("browser.close_menu"))
                    .accessibilityAddTraits(.isButton)
            }
        }
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
        .padding(.horizontal, 12)
        .padding(.bottom, 8)
    }

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
            // Left: Filled Pin Icon + "Pinned"
            HStack(spacing: 8) {
                Image(systemName: "pin.fill")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(Palette.onCoral(scheme))

                Text(model.pinSaveKind == .pinned ? "browser.pinned" : "browser.saved_as_normal")
                    .font(.system(size: 15.5, weight: .semibold, design: .rounded))
                    .foregroundStyle(Palette.onCoral(scheme))
            }

            Spacer(minLength: 14)

            // Elegant subtle vertical divider
            Rectangle()
                .fill(Palette.onCoral(scheme).opacity(0.22))
                .frame(width: 1, height: 22)
                .padding(.horizontal, 12)

            // Right: Destination picker (native menu, space + optional folder)
            PinDestinationMenuButton(
                destination: model.pinnedDestination,
                spaces: allSpaces,
                scheme: scheme,
                titleSize: 15,
                allowsFolders: model.pinSaveKind == .pinned,
                inkColor: UIColor(Palette.onCoral(scheme)),
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
        .padding(.horizontal, 18)
        .frame(height: 58)
        .clipShape(Capsule())
        .liquidGlassTintedCapsule(
            Palette.coral(scheme),
            glassVisible: !model.pinBannerGlassSuppressed
        )
        .shadow(color: Color.black.opacity(scheme == .dark ? 0.35 : 0.12), radius: 18, x: 0, y: 8)
        .overlay(
            Capsule()
                .stroke(Palette.onCoral(scheme).opacity(0.14), lineWidth: 0.5)
        )
        .transition(.asymmetric(
            insertion: .move(edge: .bottom).combined(with: .opacity).combined(with: .scale(scale: 0.95)),
            removal: .move(edge: .bottom).combined(with: .opacity)
        ))
    }

    // MARK: - Pin Refusal Toast

    private func pinNoticeToast(_ text: String) -> some View {
        HStack(spacing: 10) {
            Image(systemName: "pin.slash.fill")
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(Palette.onCoral(scheme))

            Text(text)
                .font(.system(size: 15.5, weight: .semibold, design: .rounded))
                .foregroundStyle(Palette.onCoral(scheme))
                .lineLimit(2)
        }
        .padding(.horizontal, 18)
        .frame(minHeight: 48)
        .clipShape(Capsule())
        .liquidGlassTintedCapsule(Palette.coral(scheme))
        .shadow(color: Color.black.opacity(scheme == .dark ? 0.35 : 0.12), radius: 18, x: 0, y: 8)
        .overlay(
            Capsule()
                .stroke(Palette.onCoral(scheme).opacity(0.14), lineWidth: 0.5)
        )
        .transition(.asymmetric(
            insertion: .move(edge: .bottom).combined(with: .opacity).combined(with: .scale(scale: 0.95)),
            removal: .move(edge: .bottom).combined(with: .opacity)
        ))
    }
}
