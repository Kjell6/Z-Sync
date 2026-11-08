import SwiftUI

/// A page's question, answered exactly once. WebKit raises an exception when a
/// completion handler is dropped, so an answer nobody gave falls back when the
/// dialog goes away with its page.
final class DialogAnswer<Value> {
    private var handler: ((Value) -> Void)?
    private let fallback: Value

    init(fallback: Value, _ handler: @escaping (Value) -> Void) {
        self.fallback = fallback
        self.handler = handler
    }

    func send(_ value: Value) {
        handler?(value)
        handler = nil
    }

    deinit {
        handler?(fallback)
    }
}

struct BrowserLogin: Equatable {
    let user: String
    let password: String
}

/// What the browser shows on top of a page, one at a time.
struct BrowserDialog: Identifiable {
    enum Kind {
        case alert(host: String, message: String, DialogAnswer<Void>)
        case confirm(host: String, message: String, DialogAnswer<Bool>)
        case prompt(host: String, message: String, defaultText: String, DialogAnswer<String?>)
        case login(host: String, failedBefore: Bool, DialogAnswer<BrowserLogin?>)
        /// Nil when the dialog went away with its page: nobody refused, so
        /// nothing is remembered.
        case permission(host: String, kinds: [SitePermission], DialogAnswer<Bool?>)
        /// The site is allowed, but the system says no for the whole app.
        case systemAccessOff(kinds: [SitePermission])
    }

    let id = UUID()
    let kind: Kind

    var title: String {
        switch kind {
        case .alert(let host, _, _), .confirm(let host, _, _), .prompt(let host, _, _, _):
            host.isEmpty ? String(localized: "browser.dialog.this_page") : host
        case .login(let host, _, _):
            String(localized: "browser.dialog.login_title \(host)")
        case .permission(let host, let kinds, _):
            Self.permissionTitle(host: host, kinds: kinds)
        case .systemAccessOff(let kinds):
            Self.systemAccessTitle(kinds: kinds)
        }
    }

    static func permissionTitle(host: String, kinds: [SitePermission]) -> String {
        let site = host.isEmpty ? String(localized: "browser.dialog.this_page") : host
        if kinds.contains(.camera), kinds.contains(.microphone) {
            return String(localized: "browser.permission.camera_microphone \(site)")
        }
        switch kinds.first {
        case .camera: return String(localized: "browser.permission.camera \(site)")
        case .microphone: return String(localized: "browser.permission.microphone \(site)")
        default: return String(localized: "browser.permission.location \(site)")
        }
    }

    static func systemAccessTitle(kinds: [SitePermission]) -> String {
        switch kinds.first {
        case .camera: String(localized: "browser.permission.camera_off")
        case .microphone: String(localized: "browser.permission.microphone_off")
        default: String(localized: "browser.permission.location_off")
        }
    }
}

/// Shows the active page's dialogs. A page out of sight keeps its question
/// until it is back on screen.
struct BrowserDialogPresenter: ViewModifier {
    let model: MiniBrowserModel

    /// The dialog on screen. Kept apart from the model's list: SwiftUI ignores
    /// a new alert while the last one is still animating out, and a page's
    /// second `alert()` would then wait for an answer nobody can give.
    @State private var shown: BrowserDialog?
    @State private var text = ""
    @State private var user = ""
    @State private var password = ""

    /// A little longer than the system's dismissal.
    private static let dismissalDelay: Duration = .milliseconds(450)

    func body(content: Content) -> some View {
        content
            .alert(
                shown?.title ?? "",
                isPresented: Binding(
                    get: { shown != nil },
                    set: { _ in }
                ),
                presenting: shown
            ) { current in
                actions(for: current)
            } message: { current in
                message(for: current)
            }
            .task(id: model.dialogs.first?.id) {
                await showNext()
            }
    }

    private func showNext() async {
        let wasShowing = shown != nil
        shown = nil
        guard let next = model.dialogs.first else { return }
        if wasShowing {
            try? await Task.sleep(for: Self.dismissalDelay)
            guard !Task.isCancelled else { return }
        }
        user = ""
        password = ""
        if case .prompt(_, _, let defaultText, _) = next.kind {
            text = defaultText
        } else {
            text = ""
        }
        shown = next
    }

    @ViewBuilder
    private func actions(for dialog: BrowserDialog) -> some View {
        switch dialog.kind {
        case .alert(_, _, let answer):
            Button("common.ok") { finish(dialog) { answer.send(()) } }
        case .confirm(_, _, let answer):
            Button("common.cancel", role: .cancel) { finish(dialog) { answer.send(false) } }
            Button("common.ok") { finish(dialog) { answer.send(true) } }
        case .prompt(_, _, _, let answer):
            TextField("", text: $text)
            Button("common.cancel", role: .cancel) { finish(dialog) { answer.send(nil) } }
            Button("common.ok") {
                let value = text
                finish(dialog) { answer.send(value) }
            }
        case .login(_, _, let answer):
            TextField("browser.dialog.user", text: $user)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .textContentType(.username)
            SecureField("browser.dialog.password", text: $password)
                .textContentType(.password)
            Button("common.cancel", role: .cancel) { finish(dialog) { answer.send(nil) } }
            Button("browser.dialog.sign_in") {
                let login = BrowserLogin(user: user, password: password)
                finish(dialog) { answer.send(login) }
            }
        case .permission(_, _, let answer):
            Button("browser.permission.deny", role: .cancel) { finish(dialog) { answer.send(false) } }
            Button("browser.permission.allow") { finish(dialog) { answer.send(true) } }
        case .systemAccessOff:
            Button("common.cancel", role: .cancel) { finish(dialog) {} }
            Button("browser.permission.open_settings") {
                finish(dialog) {
                    if let url = URL(string: UIApplication.openSettingsURLString) {
                        UIApplication.shared.open(url)
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func message(for dialog: BrowserDialog) -> some View {
        switch dialog.kind {
        case .alert(_, let message, _), .confirm(_, let message, _), .prompt(_, let message, _, _):
            Text(verbatim: String(message.prefix(1_000)))
        case .login(_, let failedBefore, _):
            if failedBefore { Text("browser.dialog.login_failed") }
        case .permission:
            Text("browser.permission.message")
        case .systemAccessOff:
            Text("browser.permission.off_message")
        }
    }

    private func finish(_ dialog: BrowserDialog, _ answer: () -> Void) {
        model.dismissDialog(dialog)
        answer()
    }
}
