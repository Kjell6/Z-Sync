import Foundation

/// Where the spaces screen's action bar (activity, search, settings) sits.
///
/// A local layout preference: persisted under
/// `PreferenceKeys.toolbarPlacement`, exposed by `BrowserModel` and edited in
/// the settings sheet. Lives in `App` because both the spaces screen and the
/// settings feature read it.
enum ToolbarPlacement: String, CaseIterable, Identifiable, Equatable {
    /// Above the essentials grid. Default and unchanged from earlier builds.
    case top
    /// Below the space switcher, above the disclaimer.
    case bottom

    var id: String { rawValue }

    /// English display name for the settings picker.
    var title: String {
        switch self {
        case .top: return String(localized: "settings.toolbar.top")
        case .bottom: return String(localized: "settings.toolbar.bottom")
        }
    }
}
