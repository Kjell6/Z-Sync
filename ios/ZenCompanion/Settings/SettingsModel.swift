import Foundation
import Observation

/// Preferences state owner for `SettingsSheet`: search engine and
/// "always open externally" persistence plus the sign-out intent. The view
/// keeps only presentation state (confirmation dialog visibility).
@MainActor
@Observable
final class SettingsModel {
    var selectedSearchEngine: SearchEngine {
        didSet { searchEngine.current = selectedSearchEngine }
    }

    /// User-defined engines, kept in sync with the injected provider.
    private(set) var customSearchEngines: [SearchEngine]

    /// Everything the picker and management UI shows: built-ins, then customs.
    var availableSearchEngines: [SearchEngine] { SearchEngines.builtIn + customSearchEngines }

    var alwaysOpenExternally: Bool {
        didSet {
            preferences.setBool(alwaysOpenExternally, PreferenceKeys.alwaysOpenExternally, scope: .appGroup)
            preferences.setBool(alwaysOpenExternally, PreferenceKeys.alwaysOpenExternally, scope: .standard)
        }
    }

    var essentialsGrouping: EssentialsGrouping {
        didSet {
            guard essentialsGrouping != oldValue else { return }
            preferences.setString(essentialsGrouping.rawValue, PreferenceKeys.essentialsGrouping, scope: .appGroup)
            preferences.setString(essentialsGrouping.rawValue, PreferenceKeys.essentialsGrouping, scope: .standard)
        }
    }

    /// How shared/in-app tabs are saved: pinned (default) or normal. Written
    /// to both stores so the Share Extension reads the same choice.
    var saveKind: SaveKind {
        didSet {
            guard saveKind != oldValue else { return }
            preferences.setString(saveKind.rawValue, PreferenceKeys.saveKind, scope: .appGroup)
            preferences.setString(saveKind.rawValue, PreferenceKeys.saveKind, scope: .standard)
        }
    }

    private let searchEngine: SearchEngineProviding
    private let preferences: PreferencesStoring
    private let signerOut: SessionSigningOut

    init(
        searchEngine: SearchEngineProviding = AppServices.searchEngine,
        preferences: PreferencesStoring = AppServices.preferences,
        signerOut: SessionSigningOut = AppServices.sessionSignerOut
    ) {
        self.searchEngine = searchEngine
        self.preferences = preferences
        self.signerOut = signerOut
        self.selectedSearchEngine = searchEngine.current
        self.customSearchEngines = searchEngine.custom
        let key = PreferenceKeys.alwaysOpenExternally
        self.alwaysOpenExternally = preferences.hasObject(key, scope: .appGroup)
            ? preferences.bool(key, scope: .appGroup)
            : preferences.bool(key, scope: .standard)
        self.essentialsGrouping = Self.loadEssentialsGrouping(preferences)
        self.saveKind = Self.loadSaveKind(preferences)
    }

    private static func loadEssentialsGrouping(_ preferences: PreferencesStoring) -> EssentialsGrouping {
        let key = PreferenceKeys.essentialsGrouping
        if let raw = preferences.string(key, scope: .appGroup), let value = EssentialsGrouping(rawValue: raw) {
            return value
        }
        if let raw = preferences.string(key, scope: .standard), let value = EssentialsGrouping(rawValue: raw) {
            return value
        }
        return .automatic
    }

    /// AppGroup first, standard defaults fallback, `.pinned` default.
    private static func loadSaveKind(_ preferences: PreferencesStoring) -> SaveKind {
        let key = PreferenceKeys.saveKind
        if let raw = preferences.string(key, scope: .appGroup), let value = SaveKind(rawValue: raw) {
            return value
        }
        if let raw = preferences.string(key, scope: .standard), let value = SaveKind(rawValue: raw) {
            return value
        }
        return .pinned
    }

    /// Validates and appends a custom engine, selecting it on success.
    /// Returns nil on success, otherwise the reason the draft was rejected.
    @discardableResult
    func addCustomSearchEngine(name: String, template: String) -> SearchEngineValidation? {
        if let error = SearchEngineTemplate.validate(name: name, template: template) {
            return error
        }
        let engine = SearchEngine(
            id: UUID().uuidString,
            displayName: name.trimmingCharacters(in: .whitespacesAndNewlines),
            template: template.trimmingCharacters(in: .whitespacesAndNewlines),
            isBuiltIn: false
        )
        customSearchEngines.append(engine)
        searchEngine.custom = customSearchEngines
        selectedSearchEngine = engine
        return nil
    }

    /// Removes a custom engine. If it was selected, the choice falls back to
    /// DuckDuckGo. Built-ins are ignored.
    func deleteCustomSearchEngine(_ engine: SearchEngine) {
        guard !engine.isBuiltIn else { return }
        customSearchEngines.removeAll { $0.id == engine.id }
        searchEngine.custom = customSearchEngines
        if selectedSearchEngine.id == engine.id {
            selectedSearchEngine = .duckDuckGo
        }
    }

    func signOut() {
        signerOut.signOut()
    }
}
