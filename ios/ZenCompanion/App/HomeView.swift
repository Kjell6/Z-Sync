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

/// Entry point: sign-in landing or the spaces browser.
struct HomeView: View {
    @State private var model = SessionModel()

    var body: some View {
        Group {
            if let snapshot = model.account {
                SpacesBrowserView(account: snapshot)
            } else {
                SignInLandingView(model: model)
            }
        }
        .onAppear {
            model.load()
        }
        .onReceive(NotificationCenter.default.publisher(for: .zenCompanionSignedOut)) { _ in
            model.signedOut()
        }
    }
}

/// Minimal landing: brand + one button, no onboarding text walls.
private struct SignInLandingView: View {
    @Bindable var model: SessionModel

    @Environment(\.colorScheme) private var scheme

    var body: some View {
        ZStack {
            Palette.paper(scheme).ignoresSafeArea()
            VStack(spacing: 18) {
                Spacer()
                ZenMark(scheme: scheme, size: 64)
                    .contentShape(Rectangle())
                    .onTapGesture(count: 1) { model.registerDemoTap() }
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(Text("demo.logo_hint"))
                    .accessibilityAddTraits(.isButton)
                Text("app.name")
                    .font(.system(size: 32, weight: .semibold, design: .rounded))
                    .tracking(-0.5)
                    .foregroundStyle(Palette.ink(scheme))
                    .contentShape(Rectangle())
                    .onTapGesture { model.registerDemoTap() }
                Text("home.tagline")
                    .font(.system(size: 16, weight: .regular, design: .rounded))
                    .foregroundStyle(Palette.ink(scheme).opacity(0.6))
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 40)
                Spacer()
                Button { model.presentSignIn() } label: {
                    Text("home.sign_in")
                        .font(.system(size: 17, weight: .semibold, design: .rounded))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 16)
                        .foregroundStyle(Palette.paper(scheme))
                        .background(Palette.ink(scheme), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                }
                .buttonStyle(.plain)
                Button { model.presentSignInHelp() } label: {
                    Text("signin.help.link")
                        .font(.system(size: 13, weight: .medium, design: .rounded))
                        .foregroundStyle(Palette.ink(scheme).opacity(0.55))
                        .multilineTextAlignment(.center)
                }
                .buttonStyle(.plain)
                Link("legal.privacy", destination: Legal.publicPolicyURL)
                    .font(.system(size: 13, weight: .medium, design: .rounded))
                    .foregroundStyle(Palette.ink(scheme).opacity(0.45))
                    .padding(.top, 4)
            }
            .frame(maxWidth: 440)
            .padding(24)
        }
        .tint(Palette.coral(scheme))
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
                            .font(.system(size: 16, weight: .medium, design: .rounded))
                    }
                    .padding(.top, 4)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(24)
            }
            .background(Palette.paper(scheme).ignoresSafeArea())
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
                    .font(.system(size: 15, weight: .semibold, design: .rounded))
                    .foregroundStyle(Palette.ink(scheme))
            }
            Text(bodyKey)
                .font(.system(size: 15, design: .rounded))
                .foregroundStyle(Palette.ink(scheme).opacity(0.8))
                .lineSpacing(3)
        }
    }
}
