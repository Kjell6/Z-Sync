import SwiftUI

@main
struct ZenCompanionApp: App {
    var body: some Scene {
        WindowGroup {
            HomeView()
                // Loads the switches and lists, and starts the weekly download.
                .task { ContentBlocker.shared.start() }
        }
    }
}
