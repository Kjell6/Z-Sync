import SwiftUI
import WebKit

/// A clean, native iOS In-App Mini-Browser with genuine Liquid Glass material:
/// - Minimalist Liquid Glass Address Pill (Height 48pt) with embedded progress bar
/// - Round Liquid Glass "X" cancel button in focus mode
/// - Native UIKit grouped ToolbarItemGroup (Back/Forward and Safari/Pin)
/// - Pin button with instant tap (pin to current space) and long-press Menu without preview lift
/// - Asynchronous background pinning with loading spinner and 2.5s auto-revert checkmark
/// - Webview positioned strictly UNDER the pill (no content behind header, no progressive blur)
/// - Entire Sheet adopts the adaptive website theme color
/// - Real HTTPS vs Insecure (HTTP) security indicator
/// - Edge-to-edge WKWebView with target="_blank" support
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
            ZStack(alignment: .bottom) {
                ZStack(alignment: .top) {
                    // 1. Entire Sheet Background
                    sheetBackground
                        .ignoresSafeArea()

                    // 2. Main Vertical Stack: Header on top, Web View full-bleed below
                    VStack(spacing: 0) {
                        // Top Header (Liquid Glass Address Pill)
                        HStack(spacing: 10) {
                            // Liquid Glass Address Pill
                            HStack(spacing: 10) {
                                // Security / Search Indicator
                                securityIcon

                                // Address & Search Text
                                TextField("browser.search_placeholder", text: $model.addressText)
                                    .font(.system(size: 16, weight: .semibold, design: .rounded))
                                    .textInputAutocapitalization(.never)
                                    .autocorrectionDisabled()
                                    .keyboardType(.webSearch)
                                    .submitLabel(.go)
                                    .multilineTextAlignment(addressEditing ? .leading : .center)
                                    .focused($isAddressFocused)
                                    .onSubmit {
                                        submitAddress()
                                    }
                                    .frame(maxWidth: .infinity)

                                // Stable Trailing Icon Container (Clear button when editing, Reload/Stop otherwise)
                                ZStack {
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
                                        .transition(.scale.combined(with: .opacity))
                                    } else if !addressEditing {
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
                                                .frame(width: 44, height: 44)
                                                .contentShape(Rectangle())
                                                .contentTransition(.symbolEffect(.replace))
                                        }
                                        .buttonStyle(.plain)
                                        .transition(.scale.combined(with: .opacity))
                                    }
                                }
                                .animation(
                                    animateAddressChrome ? .spring(response: 0.25, dampingFraction: 0.85) : nil,
                                    value: addressEditing
                                )
                                .animation(
                                    animateAddressChrome ? .spring(response: 0.25, dampingFraction: 0.85) : nil,
                                    value: model.addressText.isEmpty
                                )
                            }
                            .padding(.leading, 14)
                            .padding(.trailing, 4)
                            .frame(height: 48)
                            .frame(maxWidth: .infinity)
                            .overlay(alignment: .bottom) {
                                // Edge-to-edge progress bar strictly clipped inside the capsule
                                if model.isLoading && !addressEditing {
                                    GeometryReader { geo in
                                        Rectangle()
                                            .fill(Palette.coral(scheme))
                                            .frame(width: geo.size.width * CGFloat(max(model.estimatedProgress, 0.05)), height: 2.5)
                                            .animation(.linear(duration: 0.2), value: model.estimatedProgress)
                                    }
                                    .frame(height: 2.5)
                                }
                            }
                            .clipShape(Capsule())
                            .liquidGlassCapsule()

                            // Round Liquid Glass Cancel "X" Button
                            if addressEditing {
                                Button {
                                    withAnimation(.spring(response: 0.35, dampingFraction: 0.85)) {
                                        if model.effectiveURL != nil {
                                            isAddressFocused = false
                                            startsInSearch = false
                                        } else {
                                            onDismiss()
                                        }
                                    }
                                } label: {
                                    Image(systemName: "xmark")
                                        .font(.system(size: 16, weight: .semibold))
                                        .foregroundStyle(Palette.coral(scheme))
                                        .frame(width: 48, height: 48)
                                        .liquidGlassCircle()
                                }
                                .buttonStyle(.plain)
                                .transition(animateAddressChrome
                                    ? .asymmetric(
                                        insertion: .scale(scale: 0.8).combined(with: .opacity).combined(with: .move(edge: .trailing)),
                                        removal: .scale(scale: 0.8).combined(with: .opacity).combined(with: .move(edge: .trailing))
                                    )
                                    : .identity
                                )
                            }
                        }
                        .padding(.horizontal, 16)
                        .padding(.top, 16)
                        .padding(.bottom, 12)
                        .overlay(alignment: .bottom) {
                            if #available(iOS 26.0, *) {
                                EmptyView()
                            } else {
                                chromeStroke
                            }
                        }

                        // iOS 26: full-bleed under glass; layout viewport is
                        // shrunk via obscuredContentInsets. Older iOS: the
                        // web view sits between the header and a full-width
                        // bottom bar, separated by hairline strokes.
                        webPane
                    }
                }

                if model.showPinBanner {
                    pinBanner
                        .padding(.horizontal, 20)
                        .padding(.bottom, 6)
                } else if let notice = model.pinNotice {
                    pinNoticeToast(notice)
                        .padding(.horizontal, 20)
                        .padding(.bottom, 6)
                }
            }
            .animation(
                animateAddressChrome ? .spring(response: 0.35, dampingFraction: 0.85) : nil,
                value: addressEditing
            )
            .animation(.easeInOut(duration: 0.25), value: model.themeColor)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar(.hidden, for: .navigationBar)
            .toolbar(addressEditing ? .hidden : .visible, for: .bottomBar)
            .hiddenBottomScrollEdgeEffect()
            .legacyBottomToolbarBackground(sheetBackground)
            .tint(Palette.ink(scheme))
            .toolbar {
                if #available(iOS 26.0, *) {
                    ToolbarItemGroup(placement: .bottomBar) {
                        ControlGroup {
                            toolbarBackButton
                            toolbarForwardButton
                        }
                        Spacer()
                        ControlGroup {
                            toolbarSafariButton
                            toolbarPinButton
                        }
                    }
                } else {
                    ToolbarItemGroup(placement: .bottomBar) {
                        toolbarBackButton
                        toolbarForwardButton
                        Spacer()
                        toolbarSafariButton
                        toolbarPinButton
                    }
                }
            }
            .presentationBackground(sheetBackground)
            // Keyboard must not re-layout the address bar; it stays glued to the sheet.
            .ignoresSafeArea(.keyboard)
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
                if !focused { startsInSearch = false }
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

    // MARK: - Security Indicator

    private var securityIcon: some View {
        ZStack {
            // Non-focused Security Icon (Grey Lock / Insecure Warning / Globe)
            Group {
                if let url = model.currentURL {
                    if url.scheme == "https" {
                        Image(systemName: "lock.fill")
                            .foregroundStyle(.secondary)
                    } else if url.scheme == "http" {
                        Image(systemName: "lock.slash.fill")
                            .foregroundStyle(.orange)
                    } else {
                        Image(systemName: "globe")
                            .foregroundStyle(.secondary)
                    }
                } else {
                    Image(systemName: "magnifyingglass")
                        .foregroundStyle(.secondary)
                }
            }
            .opacity(addressEditing ? 0 : 1)
            .scaleEffect(addressEditing ? 0.7 : 1.0)

            // Focused Search Icon (Vibrant Coral Magnifying Glass)
            Image(systemName: "magnifyingglass")
                .foregroundStyle(Palette.coral(scheme))
                .opacity(addressEditing ? 1 : 0)
                .scaleEffect(addressEditing ? 1.0 : 0.7)
        }
        .font(.system(size: 15, weight: .semibold))
        .frame(width: 20, height: 20)
    }

    // MARK: - Bottom chrome (iOS 26 glass ControlGroups vs native toolbar)

    private var chromeStroke: some View {
        Rectangle()
            .fill(Palette.ink(scheme).opacity(0.12))
            .frame(height: 0.5)
            .frame(maxWidth: .infinity)
    }

    @ViewBuilder
    private var webPane: some View {
        let representable = MiniBrowserRepresentable(
            model: model,
            // Overlay inset only for iOS 26 floating glass. On older iOS the
            // native bottom bar already owns that space.
            bottomBarVisible: {
                if #available(iOS 26.0, *) { return !addressEditing }
                return false
            }()
        )
        if #available(iOS 26.0, *) {
            representable.ignoresSafeArea(edges: .bottom)
        } else {
            representable
        }
    }

    private var toolbarBackButton: some View {
        Button {
            model.goBack()
        } label: {
            Image(systemName: "chevron.backward")
        }
        .disabled(!model.canGoBack)
        .accessibilityLabel(Text("browser.back"))
    }

    private var toolbarForwardButton: some View {
        Button {
            model.goForward()
        } label: {
            Image(systemName: "chevron.forward")
        }
        .disabled(!model.canGoForward)
        .accessibilityLabel(Text("browser.forward"))
    }

    private var toolbarSafariButton: some View {
        Button {
            model.openCurrentInExternalBrowser()
        } label: {
            Image(systemName: "safari")
                .symbolRenderingMode(.monochrome)
        }
        .disabled(model.effectiveURL == nil)
        .accessibilityLabel(Text("browser.open_in_browser"))
    }

    private var toolbarPinButton: some View {
        Button {
            model.triggerPinBanner(spaces: allSpaces, fallbackSpace: space)
        } label: {
            Image(systemName: "pin")
                .symbolRenderingMode(.monochrome)
        }
        .disabled(model.effectiveURL == nil)
        .accessibilityLabel(Text("browser.pin_to_space"))
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
