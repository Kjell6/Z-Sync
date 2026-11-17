import Foundation
import Observation

/// Finishes a Mozilla web login: owns the `pending`/`finishing`/error state
/// and maps the FxA completion into an `AccountSnapshot`. `MozillaSignInView`
/// keeps only presentation.
@MainActor
@Observable
final class SignInModel {
    var error: String?
    var hint: String?
    private(set) var pending: FxAWebLogin?
    private(set) var finishing = false
    /// The account Mozilla already confirmed. The login keys work once, so a
    /// retry after a failed save hands this back instead of asking again.
    private var completed: AccountSnapshot?

    private let completer: FxALoginCompleting

    init(completer: FxALoginCompleting = AppServices.fxALoginCompleter) {
        self.completer = completer
    }

    /// WebChannel delivered login keys. Returns the completed account on
    /// success (the caller presents/dismisses), nil otherwise.
    @discardableResult
    func received(login: FxAWebLogin) async -> AccountSnapshot? {
        pending = login
        hint = nil
        return await finish(login)
    }

    /// Toolbar checkmark. Ignored while a finish is already running.
    func confirmPending() async -> AccountSnapshot? {
        guard let pending, !finishing else { return nil }
        if let completed {
            error = nil
            return completed
        }
        return await finish(pending)
    }

    /// The finished account could not be stored on the device. The sheet
    /// stays open with the reason, and the checkmark tries the save again.
    func saveFailed(_ message: String) {
        finishing = false
        error = message
    }

    func showUnverifiedHint() {
        hint = String(localized: "signin.unverifiedHint")
    }

    func reportError(_ text: String) {
        error = text
    }

    func clearHint() {
        hint = nil
    }

    func clearError() {
        error = nil
    }

    private func finish(_ login: FxAWebLogin) async -> AccountSnapshot? {
        guard !finishing else { return nil }
        finishing = true
        error = nil
        do {
            let result = try await completer.complete(login: login)
            let account = AccountSnapshot(
                email: login.email,
                uid: result.uid,
                sessionTokenHex: result.sessionToken,
                kBHex: FxACrypto.hex(result.kB)
            )
            completed = account
            return account
        } catch {
            finishing = false
            // One friendly line; HTTP bodies and server detail stay in the log.
            self.error = error.zenUserMessage
            return nil
        }
    }
}
