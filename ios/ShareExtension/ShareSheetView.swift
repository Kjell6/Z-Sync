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
            pageTitle: pageTitle
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
            await warming
        }
    }

    private var picker: some View {
        VStack(spacing: 0) {
            Spacer(minLength: 20)

            Favicon(urlString: model.url?.absoluteString ?? "", size: 72)
                .padding(.bottom, 18)

            Text(model.headlineTitle)
                .font(.system(size: 22, weight: .semibold, design: .rounded))
                .foregroundStyle(Palette.ink(effectiveScheme))
                .multilineTextAlignment(.center)
                .lineLimit(3)
                .padding(.horizontal, 28)

            if let urlText = model.url?.absoluteString {
                Text(urlText)
                    .font(.system(size: 14, weight: .regular, design: .rounded))
                    .foregroundStyle(Palette.ink(effectiveScheme).opacity(0.55))
                    .multilineTextAlignment(.center)
                    .lineLimit(2)
                    .padding(.horizontal, 32)
                    .padding(.top, 6)
            }

            PinDestinationMenuButton(
                destination: model.destination,
                spaces: model.spaces,
                scheme: effectiveScheme,
                titleSize: 17,
                allowsFolders: model.saveKind == .pinned,
                onSelect: { chosen in
                    model.selectDestination(chosen)
                }
            )
            .fixedSize()
            .padding(.horizontal, 18)
            .padding(.vertical, 11)
            .background(
                Capsule(style: .continuous)
                    .fill(Palette.lift(effectiveScheme))
            )
            .padding(.top, 28)
            .padding(.horizontal, 24)
            .accessibilityLabel(Text("share.workspace"))

            Spacer()

            VStack(spacing: 14) {
                Button {
                    Task { await model.save() }
                } label: {
                    Group {
                        if model.phase == .saving {
                            ProgressView().tint(Palette.paper(effectiveScheme))
                        } else {
                            Text("share.save")
                                .font(.system(size: 17, weight: .semibold, design: .rounded))
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 16)
                    .foregroundStyle(Palette.paper(effectiveScheme))
                    .background(model.canSave ? Palette.ink(effectiveScheme) : Palette.ink(effectiveScheme).opacity(0.28), in: Capsule())
                    .contentShape(Capsule())
                }
                .buttonStyle(.plain)
                .disabled(!model.canSave)

                Button(action: cancel) {
                    Text("common.cancel")
                        .font(.system(size: 16, weight: .medium, design: .rounded))
                        .foregroundStyle(Palette.ink(effectiveScheme).opacity(0.6))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 10)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
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
                .font(.system(size: 22, weight: .semibold, design: .rounded))
                .foregroundStyle(Palette.ink(effectiveScheme))
                .multilineTextAlignment(.center)
            Text(detail)
                .font(.system(size: 16, weight: .regular, design: .rounded))
                .foregroundStyle(Palette.ink(effectiveScheme).opacity(0.62))
                .multilineTextAlignment(.center)
                .padding(.horizontal, 28)
            if let action {
                Button(action: run) {
                    Text(action)
                        .font(.system(size: 17, weight: .semibold, design: .rounded))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 16)
                        .foregroundStyle(Palette.paper(effectiveScheme))
                        .background(Palette.ink(effectiveScheme), in: Capsule())
                        .contentShape(Capsule())
                }
                .buttonStyle(.plain)
                .padding(.horizontal, 24)
                .padding(.top, 8)
            }
            Spacer()
        }
    }
}
