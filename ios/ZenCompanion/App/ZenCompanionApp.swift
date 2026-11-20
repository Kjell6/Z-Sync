import SwiftUI

@main
struct ZenCompanionApp: App {
    init() {
        // Before any screen writes preferences: tells an update from a fresh
        // install, so only returning readers get release notes.
        WhatsNewStore.recordFirstLaunch()
    }

    var body: some Scene {
        WindowGroup {
            HomeView()
                // Loads the switches and lists, and starts the weekly download.
                .task { ContentBlocker.shared.start() }
        }
    }
}
