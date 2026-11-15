import SwiftUI
import UIKit

enum Legal {
    static let supportEmail = "Support@Kjell.cc"
    static let publicPolicyURL = URL(string: "https://github.com/Kjell6/Z-Sync/blob/main/docs/privacy.md")!
    static var supportURL: URL { URL(string: "mailto:\(supportEmail)")! }
    static let feedbackURL = URL(string: "https://github.com/Kjell6/Z-Sync/issues")!
    /// Direct App Store deep link to the "Write a Review" page.
    static let writeReviewURL = URL(string: "https://apps.apple.com/app/id6804470330?action=write-review")!
}

/// Entry point: the spaces browser, signed in or not. Signed out it shows the
/// same screen with the account's content as a placeholder and a sign-in card.
struct HomeView: View {
    @State private var model = SessionModel()

    var body: some View {
        SpacesBrowserView(
            account: model.account,
            sessionNotice: model.sessionNotice,
            incomingLink: model.pendingOpenURL,
            onConsumeIncomingLink: { model.consumePendingOpenURL() },
            onPresentSignIn: { model.presentSignIn() },
            onPresentSignInHelp: { model.presentSignInHelp() },
            onDemoTap: { model.registerDemoTap() }
        )
        .onAppear {
            model.load()
        }
        // A link that opens Z-Sync: a web link from another app, or the app's
        // own zsync:// link from a Shortcut. It opens in the in-app browser at
        // once, signed in or out.
        .onOpenURL { url in
            model.openLinkFromAnotherApp(url)
        }
        .onReceive(NotificationCenter.default.publisher(for: .zenCompanionSignedOut)) { _ in
            model.signedOut()
        }
        .onReceive(NotificationCenter.default.publisher(for: .zenCompanionSessionInvalidated)) { _ in
            model.sessionExpired()
        }
        .sheet(isPresented: $model.showMozilla) {
            MozillaSignInView { snap in
                model.completeSignIn(snap)
            }
            .presentationBackground(Color(red: 251/255, green: 251/255, blue: 252/255))
        }
        .alert(model.saveError ?? "", isPresented: Binding(
            get: { model.saveError != nil },
            set: { if !$0 { model.clearSaveError() } }
        )) {
            Button("common.done", role: .cancel) { model.clearSaveError() }
        }
        .sheet(isPresented: $model.showSignInHelp) {
            SignInHelpSheet()
        }
    }
}

private struct SignInHelpSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    helpBlock(bodyKey: "signin.help.why")
                    helpBlock(titleKey: "signin.help.how_title", bodyKey: "signin.help.how")
                    helpBlock(titleKey: "signin.help.passkey_title", bodyKey: "signin.help.passkey")
                    helpBlock(titleKey: "signin.help.hide_title", bodyKey: "signin.help.hide")
                    Button {
                        if let url = URL(string: "https://accounts.firefox.com") {
                            ExternalBrowser.open(url)
                        }
                    } label: {
                        Text("signin.help.open_mozilla")
                            .zenFont(16, weight: .medium, design: .rounded)
                    }
                    .buttonStyle(PressedButtonStyle())
                    .padding(.top, 4)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(24)
            }
            .background(Palette.background(scheme).ignoresSafeArea())
            .navigationTitle(Text("signin.help.title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    ToolbarIconButton(systemName: "xmark", accessibilityKey: "common.done") {
                        dismiss()
                    }
                }
            }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }

    private func helpBlock(titleKey: LocalizedStringKey? = nil, bodyKey: LocalizedStringKey) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            if let titleKey {
                Text(titleKey)
                    .zenFont(15, weight: .semibold, design: .rounded)
                    .foregroundStyle(Palette.ink(scheme))
            }
            Text(bodyKey)
                .zenFont(15, design: .rounded)
                .foregroundStyle(Palette.ink(scheme).opacity(0.8))
                .lineSpacing(3)
        }
    }
}
