import Foundation
import Observation

/// Session owner for `HomeView`: signed-in account, Mozilla sign-in result
/// handling, the hidden demo unlock and the sign-out reset.
@MainActor
@Observable
final class SessionModel {
    var account: AccountSnapshot?
    var saveError: String?
    var showMozilla = false
    var showSignInHelp = false
    /// Explains an automatic sign-out on the landing screen (e.g. the Mozilla
    /// account password changed on another device).
    var sessionNotice: String?
    /// A web link another app handed over. It waits here while signed out —
    /// the reader chose this app for the link — and the spaces screen takes it
    /// once, right after sign-in. If that never happens in this launch, the
    /// app being left ends it with the model.
    var pendingOpenURL: URL?

    private var demoTapCount = 0
    private var lastDemoTap = Date.distantPast

    private let accounts: AccountSessioning
    private let spaces: SpacesRepository
    private let preferences: PreferencesStoring
    private let haptics: HapticsPlaying
    private let signerOut: SessionSigningOut
    private let now: () -> Date

    init(
        accounts: AccountSessioning = AppServices.accountSession,
        spaces: SpacesRepository = AppServices.spaces,
        preferences: PreferencesStoring = AppServices.preferences,
        haptics: HapticsPlaying = AppServices.haptics,
        signerOut: SessionSigningOut = AppServices.sessionSignerOut,
        now: @escaping () -> Date = Date.init
    ) {
        self.accounts = accounts
        self.spaces = spaces
        self.preferences = preferences
        self.haptics = haptics
        self.signerOut = signerOut
        self.now = now
    }

    func load() {
        account = accounts.load()
        accounts.republishForShareExtension()
    }

    func signedOut() {
        account = nil
        preferences.remove(PreferenceKeys.didDismissSyncSetupHint, scope: .standard)
    }

    /// The stored session was rejected by the server: sign out and explain
    /// why on the landing screen. Temporary sync failures never come here.
    func sessionExpired() {
        guard let account, !account.isDemo else { return }
        signerOut.signOut()
        signedOut()
        sessionNotice = String(localized: "session.expired")
    }

    /// Called after `MozillaSignInView` finished. Never enters the signed-in
    /// state when the secret could not be persisted securely.
    func completeSignIn(_ snapshot: AccountSnapshot) {
        do {
            try accounts.save(snapshot)
            account = snapshot
            sessionNotice = nil
        } catch {
            saveError = error.zenUserMessage
        }
    }

    func clearSaveError() {
        saveError = nil
    }

    /// Routes a link that arrived through `.onOpenURL`. Any app can hand one
    /// over, so only an accepted address gets through: a plain `http`/`https`
    /// link, or the app's own `zsync://open?url=` form. Nothing else about the
    /// event is read.
    func openLinkFromAnotherApp(_ url: URL) {
        guard let address = BrowserInput.incomingWebAddress(url) else { return }
        // iOS hands back what this app just sent out once Z-Sync holds the
        // browser role. Consuming the record here keeps that arrival from
        // matching a later link; the URL goes to the in-app browser and is
        // never handed out again from this path.
        _ = ExternalBrowser.consumeOwnHandOff(address)
        pendingOpenURL = address
    }

    /// The spaces screen opened the pending link: it happens exactly once.
    func consumePendingOpenURL() {
        pendingOpenURL = nil
    }

    func presentSignIn() {
        showMozilla = true
    }

    func presentSignInHelp() {
        showSignInHelp = true
    }

    /// Hidden demo unlock: five taps within a rolling 2s window.
    func registerDemoTap() {
        let instant = now()
        if instant.timeIntervalSince(lastDemoTap) > 2 {
            demoTapCount = 0
        }
        lastDemoTap = instant
        demoTapCount += 1
        guard demoTapCount >= 5 else { return }
        demoTapCount = 0
        haptics.mediumImpact()
        enterDemo()
    }

    func enterDemo() {
        let snap = DemoCatalog.account
        spaces.deleteCachedSnapshot()
        spaces.cache(DemoCatalog.snapshot)
        try? accounts.save(snap)
        account = snap
        sessionNotice = nil
    }
}
