import Foundation
import UIKit
import WebKit

// MARK: - Preferences

/// Exact keys used before extraction.
enum PreferenceKeys {
    static let lastSpaceId = "lastSpaceId"
    static let didDismissSyncSetupHint = "didDismissSyncSetupHint"
    static let didShowShareExtensionTip = "didShowShareExtensionTip"
    static let didRequestReview = "didRequestReview"
    static let didArmReviewPrompt = "didArmReviewPrompt"
    static let selectedSearchEngine = "selected_search_engine"
    static let alwaysOpenExternally = "always_open_links_externally"
    static let essentialsGrouping = "essentials_grouping"
    static let saveKind = "save_kind"
}

enum PreferenceScope {
    case standard
    case appGroup
}

/// Injectable seam for the two stores the app reads and writes today:
/// `UserDefaults.standard` and the App Group defaults.
@MainActor
protocol PreferencesStoring {
    func string(_ key: String, scope: PreferenceScope) -> String?
    func bool(_ key: String, scope: PreferenceScope) -> Bool
    func hasObject(_ key: String, scope: PreferenceScope) -> Bool
    func setString(_ value: String?, _ key: String, scope: PreferenceScope)
    func setBool(_ value: Bool, _ key: String, scope: PreferenceScope)
    func remove(_ key: String, scope: PreferenceScope)
}

struct LivePreferencesStore: PreferencesStoring {
    nonisolated init() {}

    private func store(_ scope: PreferenceScope) -> UserDefaults {
        switch scope {
        case .standard: return UserDefaults.standard
        case .appGroup: return AppGroup.defaults
        }
    }

    func string(_ key: String, scope: PreferenceScope) -> String? { store(scope).string(forKey: key) }
    func bool(_ key: String, scope: PreferenceScope) -> Bool { store(scope).bool(forKey: key) }
    func hasObject(_ key: String, scope: PreferenceScope) -> Bool { store(scope).object(forKey: key) != nil }
    func setString(_ value: String?, _ key: String, scope: PreferenceScope) { store(scope).set(value, forKey: key) }
    func setBool(_ value: Bool, _ key: String, scope: PreferenceScope) { store(scope).set(value, forKey: key) }
    func remove(_ key: String, scope: PreferenceScope) { store(scope).removeObject(forKey: key) }
}

// MARK: - Spaces data

@MainActor
protocol SpacesRepository {
    func cachedSnapshot() -> ZenSnapshot?
    func cache(_ snapshot: ZenSnapshot)
    func refresh() async throws -> ZenSnapshot
    func deleteCachedSnapshot()
    func deleteTab(id: String) async throws
}

struct LiveSpacesRepository: SpacesRepository {
    nonisolated init() {}

    func cachedSnapshot() -> ZenSnapshot? { SpacesSyncService.cachedSnapshot() }
    func cache(_ snapshot: ZenSnapshot) { SpacesSyncService.cache(snapshot) }
    func refresh() async throws -> ZenSnapshot { try await SpacesSyncService.refresh() }
    func deleteCachedSnapshot() { SpacesSyncService.deleteCachedSnapshot() }
    func deleteTab(id: String) async throws { try await SpacesSyncService.deleteTab(id: id) }
}

@MainActor
protocol FaviconPrefetching {
    func prefetch(from snapshot: ZenSnapshot)
}

struct LiveFaviconPrefetcher: FaviconPrefetching {
    nonisolated init() {}

    func prefetch(from snapshot: ZenSnapshot) {
        FaviconLoader.shared.prefetch(from: snapshot)
    }
}

// MARK: - Pin / browser side effects

@MainActor
protocol PinWriting {
    @discardableResult
    func addTab(
        url: URL,
        title: String,
        to spaceId: String,
        folderId: String?,
        kind: SaveKind
    ) async throws -> AddTabOutcome
}

struct LivePinWriter: PinWriting {
    nonisolated init() {}

    @discardableResult
    func addTab(
        url: URL,
        title: String,
        to spaceId: String,
        folderId: String?,
        kind: SaveKind
    ) async throws -> AddTabOutcome {
        try await SpacesSyncService.addTab(
            url: url,
            title: title,
            to: spaceId,
            folderId: folderId,
            kind: kind
        )
    }
}

@MainActor
protocol URLOpening {
    func open(_ url: URL)
}

struct LiveURLOpener: URLOpening {
    nonisolated init() {}

    func open(_ url: URL) { ExternalBrowser.open(url) }
}

@MainActor
protocol SearchEngineProviding: AnyObject {
    var current: SearchEngine { get set }
    var custom: [SearchEngine] { get set }
}

final class LiveSearchEngineProvider: SearchEngineProviding {
    nonisolated init() {}

    var current: SearchEngine {
        get { SearchEngines.current }
        set { SearchEngines.current = newValue }
    }

    var custom: [SearchEngine] {
        get { SearchEngines.custom }
        set { SearchEngines.custom = newValue }
    }
}

@MainActor
protocol HapticsPlaying {
    func pinSucceeded()
    func selectionChanged()
    func mediumImpact()
}

struct LiveHaptics: HapticsPlaying {
    nonisolated init() {}

    func pinSucceeded() {
        let generator = UINotificationFeedbackGenerator()
        generator.prepare()
        generator.notificationOccurred(.success)
    }

    func selectionChanged() {
        let generator = UISelectionFeedbackGenerator()
        generator.prepare()
        generator.selectionChanged()
    }

    func mediumImpact() {
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
    }
}

// MARK: - Session / sign-in / sign-out

@MainActor
protocol AccountSessioning {
    func load() -> AccountSnapshot?
    func save(_ snapshot: AccountSnapshot) throws
    func republishForShareExtension()
}

struct LiveAccountSession: AccountSessioning {
    nonisolated init() {}

    func load() -> AccountSnapshot? { AccountStore.load() }
    func save(_ snapshot: AccountSnapshot) throws { try AccountStore.save(snapshot) }
    func republishForShareExtension() { AccountStore.republishForShareExtension() }
}

@MainActor
protocol SessionSigningOut {
    func signOut()
}

struct LiveSessionSigningOut: SessionSigningOut {
    nonisolated init() {}

    func signOut() {
        // Sign-in runs in an ephemeral (nonPersistent) store, so sign-out
        // purges that one; the default store (mini browser) keeps its data.
        WKWebsiteDataStore.nonPersistent().removeData(
            ofTypes: WKWebsiteDataStore.allWebsiteDataTypes(),
            modifiedSince: Date.distantPast
        ) {}
        AccountStore.clear()
        SpacesSyncService.deleteCachedSnapshot()
        NotificationCenter.default.post(name: .zenCompanionSignedOut, object: nil)
    }
}

struct FxALoginCompletion {
    let uid: String
    let sessionToken: String
    let kB: Data
}

@MainActor
protocol FxALoginCompleting {
    func complete(login: FxAWebLogin) async throws -> FxALoginCompletion
}

struct LiveFxALoginCompleter: FxALoginCompleting {
    private let transport: SyncHTTPTransport

    nonisolated init(transport: SyncHTTPTransport = AccountStore.transport) {
        self.transport = transport
    }

    func complete(login: FxAWebLogin) async throws -> FxALoginCompletion {
        let result = try await FxAClient(transport: transport).completeWebLogin(
            email: login.email,
            uid: login.uid,
            sessionToken: login.sessionToken,
            keyFetchToken: login.keyFetchToken,
            unwrapBKeyHex: login.unwrapBKey
        )
        return FxALoginCompletion(uid: result.uid, sessionToken: result.session, kB: result.kB)
    }
}

@MainActor
protocol ActivityLoading {
    func load() async throws -> SyncedActivityService.Activity
}

struct LiveActivityLoader: ActivityLoading {
    nonisolated init() {}

    func load() async throws -> SyncedActivityService.Activity {
        try await SyncedActivityService.load()
    }
}

// MARK: - Live service hooks (replaceable in tests, mirroring
// `AccountStore.transport` / `SpacesSyncService.safeSyncEnabledOverride`)

enum AppServices {
    static var spaces: SpacesRepository = LiveSpacesRepository()
    static var preferences: PreferencesStoring = LivePreferencesStore()
    static var favicons: FaviconPrefetching = LiveFaviconPrefetcher()
    static var pinWriter: PinWriting = LivePinWriter()
    static var urlOpener: URLOpening = LiveURLOpener()
    static var searchEngine: SearchEngineProviding = LiveSearchEngineProvider()
    static var haptics: HapticsPlaying = LiveHaptics()
    static var accountSession: AccountSessioning = LiveAccountSession()
    static var sessionSignerOut: SessionSigningOut = LiveSessionSigningOut()
    static var fxALoginCompleter: FxALoginCompleting = LiveFxALoginCompleter()
    static var activityLoader: ActivityLoading = LiveActivityLoader()
}
