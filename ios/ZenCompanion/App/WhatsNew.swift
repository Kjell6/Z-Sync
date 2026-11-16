import Foundation

/// One feature pill in the sheet. Copy lives here rather than in the
/// String Catalog because release notes are content, not chrome: they are
/// replaced wholesale each release and stay English-only like the rest of the
/// app.
struct WhatsNewItem: Identifiable, Hashable {
    let id: String
    let title: String
}

/// The notes shown by the What's New sheet. Keep this to genuinely
/// user-visible changes, in the order they should be read. The version is the
/// store release these notes belong to, not the build currently running.
struct WhatsNewRelease: Equatable {
    let version: String
    let title: String
    let subtitle: String
    /// Asset name of the hero image. It is shown full width at its own
    /// aspect ratio, so its design (and colours) live in the image itself.
    let heroImage: String
    let heroAccessibilityLabel: String
    /// Small caption above the feature pills.
    let featuresHeading: String
    let items: [WhatsNewItem]

    static let latest = WhatsNewRelease(
        version: "1.6",
        title: "Meet the new Quick Browser",
        subtitle: "Rebuilt from the ground up, with real tabs that stay open.",
        heroImage: "WhatsNewHero",
        heroAccessibilityLabel: "Three phones showing the new browser: the open pages as cards, a page with the new bottom bar, and search suggestions",
        featuresHeading: "WITH EVERYTHING A BROWSER NEEDS",
        items: [
            WhatsNewItem(id: "history", title: "History"),
            WhatsNewItem(id: "ad-blocking", title: "Ad blocking"),
            WhatsNewItem(id: "suggestions", title: "Search suggestions"),
            WhatsNewItem(id: "downloads", title: "Downloads"),
            WhatsNewItem(id: "bottom-bar", title: "New bottom bar"),
            WhatsNewItem(id: "more", title: "and more")
        ]
    )
}

/// Remembers the release whose notes were already shown. The release version
/// is the key, so the notes appear once when the app reaches 1.6 and are not
/// repeated on later launches.
@MainActor
struct WhatsNewStore {
    private let preferences: PreferencesStoring
    private let currentVersion: String
    let release: WhatsNewRelease

    init(
        preferences: PreferencesStoring = AppServices.preferences,
        currentVersion: String = WhatsNewStore.bundleVersion,
        release: WhatsNewRelease = .latest
    ) {
        self.preferences = preferences
        self.currentVersion = currentVersion
        self.release = release
    }

    nonisolated static var bundleVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0"
    }

    /// True only once the running app has reached the release these notes
    /// describe, and until they have been shown for it. A fresh 1.6 install
    /// also returns true, so the first signed-in launch introduces the
    /// release; that is safer than guessing whether the app was updated.
    var shouldPresent: Bool {
        currentVersion == release.version
            && preferences.string(PreferenceKeys.whatsNewShownVersion, scope: .standard) != release.version
    }

    /// Call when the sheet is presented, not when it is dismissed: if the app
    /// is killed while the sheet is up, it must not reappear on every launch.
    func markPresented() {
        preferences.setString(release.version, PreferenceKeys.whatsNewShownVersion, scope: .standard)
    }
}
