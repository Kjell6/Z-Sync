import SwiftUI
import UIKit

/// Share-extension chrome. Bootstrap, save and destination rules live in
/// `ShareModel`; URL extraction lives in `ShareItemLoader`.
struct ShareSheetView: View {
    let itemProviders: [NSItemProvider]
    let finish: () -> Void
    let cancel: () -> Void

    @Environment(\.colorScheme) private var scheme
    @State private var model: ShareModel

    init(
        itemProviders: [NSItemProvider],
        pageTitle: String,
        finish: @escaping () -> Void,
        cancel: @escaping () -> Void
    ) {
        self.itemProviders = itemProviders
        self.finish = finish
        self.cancel = cancel
        _model = State(initialValue: ShareModel(
            session: LiveShareSession(),
            pageTitle: pageTitle,
            titleResolver: { _, url in await PageTitleLoader.fetchTitle(for: url) }
        ))
    }

    private var selectedSpace: ZenSpace? { model.selectedSpace }

    private var effectiveScheme: ColorScheme {
        if let isDark = selectedSpace?.theme?.isDarkTheme {
            return isDark ? .dark : .light
        }
        return scheme
    }

    var body: some View {
        Group {
            switch model.phase {
            case .loading:
                ProgressView()
                    .tint(Palette.coral(effectiveScheme))
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            case .signedOut:
                status(
                    title: String(localized: "share.signed_out.title"),
                    detail: String(localized: "share.signed_out.detail"),
                    action: String(localized: "common.done"),
                    run: finish
                )
            case .failed:
                status(
                    title: String(localized: "share.failed.title"),
                    detail: model.error ?? "Unknown error",
                    action: String(localized: "common.done"),
                    run: finish
                )
            case .saved:
                status(
                    title: String(localized: "share.saved.title"),
                    detail: model.savedAsPinnedFallback
                        ? String(localized: "share.saved.fallback_pinned")
                        : String(localized: "share.saved.detail \(model.savedDestinationName)"),
                    systemImage: "checkmark",
                    action: nil,
                    run: {}
                )
            case .pick, .saving:
                picker
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .animation(.spring(response: 0.3, dampingFraction: 0.8), value: model.phase)
        .background(
            ZenSpaceGradientBackground(
                theme: selectedSpace?.theme,
                scheme: effectiveScheme,
                // TEST (dark-mode experiment): follow the system appearance.
                // Revert by deleting this argument.
                darkenDots: scheme == .dark
            )
            .ignoresSafeArea()
        )
        .onAppear { model.onFinished = finish }
        .onChange(of: model.phase) { _, newPhase in
            switch newPhase {
            case .saved:
                let generator = UINotificationFeedbackGenerator()
                generator.prepare()
                generator.notificationOccurred(.success)
            case .failed:
                let generator = UINotificationFeedbackGenerator()
                generator.prepare()
                generator.notificationOccurred(.error)
            default:
                break
            }
        }
        .task {
            async let warming: Void = model.bootstrap()
            if model.url == nil, let found = await ShareItemLoader.firstHTTPURL(from: itemProviders) {
                model.url = found
            }
            await model.resolveSharedTitle()
            await warming
        }
    }

    /// Fills the sheet whenever it fits; scrolls once the system text size
    /// makes it taller than the sheet, so Save and Cancel stay reachable.
    private var picker: some View {
        ViewThatFits(in: .vertical) {
            pickerContent
            ScrollView { pickerContent }
        }
    }

    private var pickerContent: some View {
        VStack(spacing: 0) {
            Spacer(minLength: 20)

            Favicon(urlString: model.url?.absoluteString ?? "", size: 72)
                .padding(.bottom, 18)

            Text(model.headlineTitle)
                .zenFont(22, weight: .semibold, design: .rounded)
                .foregroundStyle(Palette.ink(effectiveScheme))
                .multilineTextAlignment(.center)
                .lineLimit(3)
                .padding(.horizontal, 28)

            if let urlText = model.url?.absoluteString {
                Text(urlText)
                    .zenFont(14, weight: .regular, design: .rounded)
                    .foregroundStyle(Palette.ink(effectiveScheme).opacity(0.55))
                    .multilineTextAlignment(.center)
                    .lineLimit(2)
                    .padding(.horizontal, 32)
                    .padding(.top, 6)
            }

            HStack(spacing: 10) {
                PinDestinationMenuButton(
                    destination: model.destination,
                    spaces: model.spaces,
                    scheme: effectiveScheme,
                    titleSize: 17,
                    allowsFolders: model.saveKind == .pinned,
                    isEnabled: model.phase != .saving,
                    inkColor: UIColor(Palette.ink(effectiveScheme)),
                    onSelect: { chosen in
                        model.selectDestination(chosen)
                    }
                )
                // The space icon's art sits inside its 24pt frame with a
                // transparent margin the system chevron does not have, so an
                // even pad reads a point heavier on the leading side. 17/18
                // keeps the white space either side of the pill equal.
                .padding(.leading, 17)
                .padding(.trailing, 18)
                .padding(.vertical, 11)
                .modifier(DomedFill(shape: Capsule(style: .continuous), scheme: effectiveScheme))
                .glassRimCapsule()
                .accessibilityLabel(Text("share.workspace"))

                if model.allowsNormalTabs {
                    // Keep the round button whole; the pill is the part that
                    // may have to truncate on a narrow sheet.
                    SaveKindMenuButton(
                        kind: model.saveKind,
                        scheme: effectiveScheme,
                        isEnabled: model.phase != .saving,
                        onSelect: { model.selectSaveKind($0) }
                    )
                    .layoutPriority(1)
                }
            }
            .padding(.top, 28)
            .padding(.horizontal, 24)
            // The write is already in flight; changing the target or the kind
            // now could only contradict what is being written.
            .opacity(model.phase == .saving ? 0.55 : 1)
            .allowsHitTesting(model.phase != .saving)

            Spacer()

            VStack(spacing: 14) {
                Button {
                    Task { await model.save() }
                } label: {
                    Group {
                        if model.phase == .saving {
                            ProgressView().tint(Palette.paper(effectiveScheme))
                        } else {
                            Text(LocalizedStringKey(model.saveKind == .normal ? "share.save_normal" : "share.save"))
                                .zenFont(17, weight: .semibold, design: .rounded)
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 16)
                    .foregroundStyle(Palette.paper(effectiveScheme))
                    .modifier(DomedFill(shape: Capsule(), scheme: effectiveScheme, fill: model.canSave ? Palette.ink(effectiveScheme) : Palette.ink(effectiveScheme).opacity(0.28)))
                    .contentShape(Capsule())
                }
                .buttonStyle(LiquidPressButtonStyle())
                .disabled(!model.canSave)

                Button(action: cancel) {
                    Text("common.cancel")
                        .zenFont(16, weight: .medium, design: .rounded)
                        .foregroundStyle(Palette.ink(effectiveScheme).opacity(0.6))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 10)
                        .contentShape(Rectangle())
                }
                .buttonStyle(PressedButtonStyle())
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 28)
        }
    }

    private func status(title: String, detail: String, systemImage: String? = nil, action: String?, run: @escaping () -> Void) -> some View {
        VStack(spacing: 12) {
            Spacer()
            if let systemImage {
                Image(systemName: systemImage)
                    .font(.system(size: 34, weight: .semibold))
                    .foregroundStyle(Palette.ink(effectiveScheme))
                    .padding(.bottom, 2)
                    .accessibilityHidden(true)
            }
            Text(title)
                .zenFont(22, weight: .semibold, design: .rounded)
                .foregroundStyle(Palette.ink(effectiveScheme))
                .multilineTextAlignment(.center)
            Text(detail)
                .zenFont(16, weight: .regular, design: .rounded)
                .foregroundStyle(Palette.ink(effectiveScheme).opacity(0.62))
                .multilineTextAlignment(.center)
                .padding(.horizontal, 28)
            if let action {
                Button(action: run) {
                    Text(action)
                        .zenFont(17, weight: .semibold, design: .rounded)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 16)
                        .foregroundStyle(Palette.paper(effectiveScheme))
                        .modifier(DomedFill(shape: Capsule(), scheme: effectiveScheme, fill: Palette.ink(effectiveScheme)))
                        .contentShape(Capsule())
                }
                .buttonStyle(LiquidPressButtonStyle())
                .padding(.horizontal, 24)
                .padding(.top, 8)
            }
            Spacer()
        }
    }
}
