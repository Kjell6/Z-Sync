import Foundation

enum AppGroup {
    static let id = "group.de.kjell.zencompanion"
    static let defaults = UserDefaults(suiteName: id) ?? .standard

    static var container: URL {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: id)
            ?? FileManager.default.temporaryDirectory
    }
}
