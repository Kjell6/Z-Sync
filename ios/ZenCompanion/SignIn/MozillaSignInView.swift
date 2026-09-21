import SwiftUI

/// Official Mozilla accounts page + WebChannel (same path as Firefox desktop).
/// Direct POST /account/login is blocked by Mozilla's CDN (HTTP 406).
struct MozillaSignInView: View {
    var onSuccess: (AccountSnapshot) -> Void
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var scheme
    @State private var model = SignInModel()
    @State private var handoff: LoginHandoff?

    /// Lifecycle-owned handoff for the WebChannel login callback: the callback
    /// only stores the payload, the `.task(id:)` below drives the completion.
    private struct LoginHandoff: Identifiable {
        let id = UUID()
        let login: FxAWebLogin
    }

    var body: some View {
        NavigationStack {
            ZStack {
                Color(red: 251/255, green: 251/255, blue: 252/255).ignoresSafeArea()
                FxAWebView(
                    onLogin: { result in
                        handoff = LoginHandoff(login: result)
                    },
                    onUnverified: {
                        model.showUnverifiedHint()
                    },
                    onError: { text in
                        model.reportError(text)
                    }
                )
                if model.finishing {
                    HStack(spacing: 10) {
                        ProgressView()
                            .controlSize(.small)
                            .tint(Palette.coral(scheme))
                        Text("signin.finishing")
                            .font(.system(size: 14, weight: .medium, design: .rounded))
                            .foregroundStyle(Palette.ink(scheme).opacity(0.75))
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                    .background(.background, in: Capsule())
                    .shadow(color: .black.opacity(0.10), radius: 8, y: 2)
                    .padding(.top, 8)
                    .frame(maxHeight: .infinity, alignment: .top)
                    .transition(.opacity)
                }
                if model.hint != nil || model.error != nil {
                    VStack(spacing: 8) {
                        if let hint = model.hint {
                            NoticeBubble(
                                text: hint,
                                color: .blue,
                                onClose: { model.clearHint() }
                            )
                        }
                        if let error = model.error {
                            NoticeBubble(
                                text: error,
                                color: .red,
                                onClose: { model.clearError() }
                            )
                        }
                    }
                    .padding(.horizontal, 24)
                    // Pinned under the header bar so the keyboard never
                    // pushes it over the page's input fields.
                    .frame(maxHeight: .infinity, alignment: .top)
                }
            }
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    ToolbarIconButton(systemName: "xmark", accessibilityKey: "common.cancel") {
                        dismiss()
                    }
                }
                if model.pending != nil {
                    ToolbarItem(placement: .confirmationAction) {
                        ToolbarIconButton(systemName: "checkmark", accessibilityKey: "signin.confirm") {
                            Task {
                                if let account = await model.confirmPending() {
                                    onSuccess(account)
                                    dismiss()
                                }
                            }
                        }
                    }
                }
            }
        }
        .interactiveDismissDisabled(model.finishing)
        .task(id: handoff?.id) {
            guard let handoff else { return }
            if let account = await model.received(login: handoff.login) {
                onSuccess(account)
                dismiss()
            }
        }
    }
}

struct FxAWebLogin {
    var email: String
    var uid: String
    var sessionToken: String
    var keyFetchToken: String
    var unwrapBKey: String
}

/// Opaque notice bubble with a close button, pinned under the header.
private struct NoticeBubble: View {
    let text: String
    let color: Color
    let onClose: () -> Void

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            Text(text)
                .font(.footnote)
                .foregroundStyle(.primary)
                .frame(maxWidth: .infinity, alignment: .leading)
            Button(action: onClose) {
                Image(systemName: "xmark")
                    .font(.footnote.bold())
                    .foregroundStyle(.secondary)
                    .padding(4)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text("common.close"))
        }
        .padding(12)
        .background(color.opacity(0.14), in: RoundedRectangle(cornerRadius: 12))
        .background(.background, in: RoundedRectangle(cornerRadius: 12))
        .overlay(
            RoundedRectangle(cornerRadius: 12)
                .strokeBorder(color.opacity(0.35), lineWidth: 1)
        )
        .shadow(color: .black.opacity(0.08), radius: 4, y: 2)
    }
}
