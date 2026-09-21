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

    private var demoTapCount = 0
    private var lastDemoTap = Date.distantPast

    private let accounts: AccountSessioning
    private let spaces: SpacesRepository
    private let preferences: PreferencesStoring
    private let haptics: HapticsPlaying
    private let now: () -> Date

    init(
        accounts: AccountSessioning = AppServices.accountSession,
        spaces: SpacesRepository = AppServices.spaces,
        preferences: PreferencesStoring = AppServices.preferences,
        haptics: HapticsPlaying = AppServices.haptics,
        now: @escaping () -> Date = Date.init
    ) {
        self.accounts = accounts
        self.spaces = spaces
        self.preferences = preferences
        self.haptics = haptics
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

    /// Called after `MozillaSignInView` finished. Never enters the signed-in
    /// state when the secret could not be persisted securely.
    func completeSignIn(_ snapshot: AccountSnapshot) {
        do {
            try accounts.save(snapshot)
            account = snapshot
        } catch {
            saveError = error.zenUserMessage
        }
    }

    func clearSaveError() {
        saveError = nil
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
    }
}
