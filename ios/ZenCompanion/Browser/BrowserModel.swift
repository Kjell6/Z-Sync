import Foundation
import Observation
import SwiftUI
import os

/// App-shell state owner for `SpacesBrowserView`: snapshot, pager indices,
/// load lifecycle and the share/setup tips. The view keeps only navigation
/// identity (`ActiveSheet`) and the notification/scenePhase observers.
@MainActor
@Observable
final class BrowserModel {
    private static let log = Logger(subsystem: "de.kjell.zencompanion", category: "browser")

    var snapshot = ZenSnapshot.empty
    var selectedIndex = 0
    // Background, top bar and switcher colors follow displayedIndex: the first
    // page flip of a swipe is adopted immediately (so the melt starts
    // mid-swipe as before), further flips within the same gesture are ignored
    // (they restarted the 0.28s crossfade mid-gesture and flashed bare paper),
    // and the final page is synced exactly once on settle.
    var displayedIndex = 0
    var pagerSettled = true
    var swipeLatched = false
    var loading = false
    var loadError: String?
    var reloading = false
    /// Dedicated zero-spaces state (desktop sync not enabled / empty account),
    /// distinct from network errors in `loadError`.
    var zeroSpaces = false
    var showShareTip = false
    /// The "nothing synced yet" setup card is dismissible; the flag survives
    /// launches until the user signs out.
    var syncSetupHintDismissed: Bool
    /// Flipped once when the in-app review dialog should appear. The view
    /// consumes this via StoreKit's `requestReview` environment action.
    var reviewPromptPending = false

    private let isDemo: Bool
    private let repository: SpacesRepository
    private let preferences: PreferencesStoring
    private let favicons: FaviconPrefetching
    private var reviewPromptTask: Task<Void, Never>?
    /// True once this process has started (or resumed) the review wait, so
    /// later reloads do not restart it.
    private var reviewArmedThisProcess = false
    /// True after the first-look / next-open wait finished. Share-tip dismiss can
    /// then ask without skipping the wait.
    private var reviewWaitElapsed = false

    init(
        account: AccountSnapshot,
        repository: SpacesRepository = AppServices.spaces,
        preferences: PreferencesStoring = AppServices.preferences,
        favicons: FaviconPrefetching = AppServices.favicons
    ) {
        self.isDemo = account.isDemo
        self.repository = repository
        self.preferences = preferences
        self.favicons = favicons
        self.syncSetupHintDismissed = preferences.bool(
            PreferenceKeys.didDismissSyncSetupHint,
            scope: .standard
        )
    }

    // MARK: - Derived state

    var activeSpace: ZenSpace {
        if snapshot.spaces.indices.contains(displayedIndex) {
            return snapshot.spaces[displayedIndex]
        }
        return snapshot.spaces.first ?? ZenSpace.fallback
    }

    /// Setup card while spaces synced but nothing in them: pinned/normal tabs
    /// and essentials are what this app displays, and they only sync when Zen's
    /// "Sync your sidebar across devices" switch is on.
    var showSyncSetupHint: Bool {
        !snapshot.spaces.isEmpty && !snapshot.hasSyncedTabs && !syncSetupHintDismissed
    }

    var currentTheme: ZenSpaceTheme? {
        snapshot.spaces.indices.contains(displayedIndex) ? snapshot.spaces[displayedIndex].theme : nil
    }

    /// An App Group value wins, otherwise the standard defaults value applies.
    var alwaysOpenExternally: Bool {
        let key = PreferenceKeys.alwaysOpenExternally
        if preferences.hasObject(key, scope: .appGroup) {
            return preferences.bool(key, scope: .appGroup)
        }
        return preferences.bool(key, scope: .standard)
    }

    /// The user's essentials grouping: an explicit app choice when set,
    /// otherwise `.automatic` (synced Zen pref, else wire-bucket inference).
    var essentialsGrouping: EssentialsGrouping {
        let key = PreferenceKeys.essentialsGrouping
        if preferences.hasObject(key, scope: .appGroup),
           let raw = preferences.string(key, scope: .appGroup),
           let value = EssentialsGrouping(rawValue: raw) {
            return value
        }
        if let raw = preferences.string(key, scope: .standard),
           let value = EssentialsGrouping(rawValue: raw) {
            return value
        }
        return .automatic
    }

    /// Essentials to show above `space` under the current grouping.
    func essentials(for space: ZenSpace) -> [ZenTab] {
        snapshot.essentials(for: space, grouping: essentialsGrouping)
    }

    // MARK: - Pager

    func selectSpace(_ index: Int) {
        selectedIndex = index
    }

    /// Tap/programmatic jumps happen while idle: follow immediately. During a
    /// gesture only the first flip of the segment is adopted; further flips
    /// are ignored until settle (see `displayedIndex` comment).
    func selectedIndexChangedFromPager() {
        if pagerSettled {
            displayedIndex = selectedIndex
        } else if !swipeLatched {
            swipeLatched = true
            displayedIndex = selectedIndex
        }
        persistSelectedSpace()
    }

    func pagerSettledChanged(_ isIdle: Bool) {
        pagerSettled = isIdle
        if isIdle {
            // Gesture/glide ended: follow the final page exactly once.
            swipeLatched = false
            displayedIndex = selectedIndex
        } else {
            // New gesture segment (drag start, lift into glide): arm the latch
            // so the first flip of this segment comes through.
            swipeLatched = false
        }
    }

    /// Restores the space the user last opened (also written by the share
    /// extension when pinning, so a fresh pin lands on the right space).
    static func restoredIndex(in snapshot: ZenSnapshot, preferences: PreferencesStoring) -> Int? {
        guard let id = preferences.string(PreferenceKeys.lastSpaceId, scope: .appGroup) else { return nil }
        return snapshot.spaces.firstIndex { $0.id == id }
    }

    func persistSelectedSpace() {
        guard snapshot.spaces.indices.contains(selectedIndex) else { return }
        preferences.setString(
            snapshot.spaces[selectedIndex].id,
            PreferenceKeys.lastSpaceId,
            scope: .appGroup
        )
    }

    // MARK: - Loading

    func initialLoad() async {
        if let cached = repository.cachedSnapshot(), !cached.spaces.isEmpty {
            snapshot = cached
            if let restored = Self.restoredIndex(in: cached, preferences: preferences) {
                selectedIndex = restored
                Self.log.info("restored last space: \(cached.spaces[restored].id, privacy: .public)")
            } else {
                selectedIndex = min(selectedIndex, max(cached.spaces.count - 1, 0))
            }
            favicons.prefetch(from: cached)
            considerReviewPrompt()
        }
        await reload()
    }

    /// Manual pull-to-refresh. Unlike a background reload this must always hit
    /// the network: if a reload is already in flight, wait for it and fetch
    /// again instead of silently doing nothing.
    func manualRefresh() async {
        var waited: Duration = .zero
        while reloading, waited < .seconds(15) {
            try? await Task.sleep(for: .milliseconds(100))
            waited += .milliseconds(100)
        }
        await reload()
    }

    func reload(retryAttempted: Bool = false) async {
        guard !reloading else { return }
        reloading = true
        loading = true
        do {
            let fresh = try await repository.refresh()
            loadError = nil
            guard !fresh.spaces.isEmpty else {
                // Sync worked but the account has no spaces: Zen Sync is
                // likely not enabled on the desktop yet. Dedicated help view.
                loadError = nil
                zeroSpaces = true
                snapshot = fresh
                reloading = false
                loading = false
                return
            }
            zeroSpaces = false
            // Keep the space the user is on. Fall back to the persisted id
            // (e.g. no cached snapshot yet) before clamping numerically.
            let currentId = snapshot.spaces.indices.contains(selectedIndex)
                ? snapshot.spaces[selectedIndex].id
                : preferences.string(PreferenceKeys.lastSpaceId, scope: .appGroup)
            snapshot = fresh
            favicons.prefetch(from: fresh)
            if let currentId,
               let index = fresh.spaces.firstIndex(where: { $0.id == currentId }) {
                selectedIndex = index
            } else {
                selectedIndex = min(selectedIndex, fresh.spaces.count - 1)
            }
            persistSelectedSpace()
            maybeShowShareTip()
            considerReviewPrompt()
        } catch {
            Self.log.error("reload failed: \(String(describing: error), privacy: .public)")
            if Self.isCancellation(error) {
                loadError = String(localized: "error.interrupted")
                if !retryAttempted {
                    reloading = false
                    loading = false
                    try? await Task.sleep(for: .milliseconds(700))
                    await reload(retryAttempted: true)
                    return
                }
            } else {
                loadError = error.zenUserMessage
            }
        }
        reloading = false
        loading = false
    }

    /// Silent foreground refresh: swap in fresh data, never surface network
    /// errors (the cached view stays as-is; the retry button covers failures).
    func reloadQuietly() async {
        guard !reloading else { return }
        reloading = true
        defer { reloading = false }
        do {
            let fresh = try await repository.refresh()
            guard !fresh.spaces.isEmpty else { return }
            let currentId = snapshot.spaces.indices.contains(selectedIndex)
                ? snapshot.spaces[selectedIndex].id
                : preferences.string(PreferenceKeys.lastSpaceId, scope: .appGroup)
            snapshot = fresh
            favicons.prefetch(from: fresh)
            if let currentId,
               let index = fresh.spaces.firstIndex(where: { $0.id == currentId }) {
                selectedIndex = index
            } else {
                selectedIndex = min(selectedIndex, fresh.spaces.count - 1)
            }
            persistSelectedSpace()
            considerReviewPrompt()
        } catch {
            Self.log.error("quiet reload failed: \(String(describing: error), privacy: .public)")
        }
    }

    /// Returning to the foreground (e.g. after sharing via the share
    /// extension) refreshes what's on screen against the server.
    func sceneBecameActive() async {
        if snapshot.spaces.isEmpty {
            await reload()
        } else {
            await reloadQuietly()
        }
    }

    // MARK: - Tips

    /// One-time, dismissable tip about pinning from other apps via the share
    /// extension. Delayed further so the user first takes in their spaces.
    func maybeShowShareTip() {
        guard !isDemo else { return }
        guard !preferences.bool(PreferenceKeys.didShowShareExtensionTip, scope: .standard) else { return }
        guard !snapshot.spaces.isEmpty else { return }
        preferences.setBool(true, PreferenceKeys.didShowShareExtensionTip, scope: .standard)
        Task { @MainActor in
            try? await Task.sleep(for: .seconds(4))
            withAnimation(.easeOut(duration: 0.35)) { showShareTip = true }
        }
    }

    func dismissShareTip() {
        withAnimation(.easeOut(duration: 0.25)) { showShareTip = false }
        if reviewWaitElapsed {
            requestReviewIfEligible()
        }
    }

    // MARK: - In-app review

    /// Native StoreKit stars dialog. Apple decides whether it actually
    /// appears (about three times a year; never on TestFlight). Custom
    /// "rate us" alerts are App Store guideline 5.6.1.
    enum ReviewPrompt {
        /// Time to look at synced spaces before the first ask.
        static let firstLookDelay: Duration = .seconds(12)
        /// Next launch after bouncing before the first-look wait — spaces should already be on screen.
        static let nextOpenDelay: Duration = .seconds(1.5)
    }

    /// Start the wait the first time tabs are on screen. If the app was
    /// closed before that, the next launch asks after a short settle.
    func considerReviewPrompt() {
        guard !isDemo else { return }
        guard !preferences.bool(PreferenceKeys.didRequestReview, scope: .standard) else { return }
        guard snapshot.hasSyncedTabs else { return }
        let alreadyArmed = preferences.bool(PreferenceKeys.didArmReviewPrompt, scope: .standard)
        if !alreadyArmed {
            preferences.setBool(true, PreferenceKeys.didArmReviewPrompt, scope: .standard)
            reviewArmedThisProcess = true
            scheduleReviewPrompt(after: ReviewPrompt.firstLookDelay)
            return
        }
        guard !reviewArmedThisProcess else { return }
        reviewArmedThisProcess = true
        scheduleReviewPrompt(after: ReviewPrompt.nextOpenDelay)
    }

    func scheduleReviewPrompt(after delay: Duration) {
        guard !preferences.bool(PreferenceKeys.didRequestReview, scope: .standard) else { return }
        reviewPromptTask?.cancel()
        reviewPromptTask = Task { @MainActor in
            try? await Task.sleep(for: delay)
            guard !Task.isCancelled else { return }
            reviewWaitElapsed = true
            requestReviewIfEligible()
        }
    }

    /// Ask once, after the aha: real synced tabs are on screen, no setup
    /// card, no share-extension tip covering the spaces.
    func requestReviewIfEligible() {
        guard !isDemo else { return }
        guard !preferences.bool(PreferenceKeys.didRequestReview, scope: .standard) else { return }
        guard snapshot.hasSyncedTabs else { return }
        guard !showShareTip else { return }
        guard !showSyncSetupHint else { return }
        guard !loading else { return }
        preferences.setBool(true, PreferenceKeys.didRequestReview, scope: .standard)
        reviewPromptPending = true
    }

    func consumeReviewPrompt() {
        reviewPromptPending = false
    }

    func dismissSyncSetupHint() {
        withAnimation(.easeOut(duration: 0.2)) { syncSetupHintDismissed = true }
        preferences.setBool(true, PreferenceKeys.didDismissSyncSetupHint, scope: .standard)
    }

    // MARK: - Tab deletion

    func deleteTab(id: String) async {
        try? await repository.deleteTab(id: id)
    }

    private static func isCancellation(_ error: Error) -> Bool {
        if error is CancellationError { return true }
        if let urlError = error as? URLError, urlError.code == .cancelled { return true }
        let ns = error as NSError
        return ns.domain == NSURLErrorDomain && ns.code == NSURLErrorCancelled
    }
}
