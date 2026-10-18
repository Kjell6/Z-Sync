import Foundation

/// How many essentials sit on one row of the start-page grid.
///
/// A local layout preference: persisted under
/// `PreferenceKeys.essentialsColumns`, exposed by `BrowserModel` and edited in
/// the settings sheet (Advanced). Lives in `App` because both the spaces screen
/// and the settings feature read it.
enum EssentialsColumns: String, CaseIterable, Identifiable, Equatable {
    /// The adaptive default: 1–4 items fill one row, 5–6 use three columns,
    /// 7 or more use four.
    case automatic
    case one = "1"
    case two = "2"
    case three = "3"
    case four = "4"
    case five = "5"

    var id: String { rawValue }

    /// English display name for the settings picker.
    var title: String {
        switch self {
        case .automatic: return String(localized: "settings.essentials_columns.automatic")
        case .one, .two, .three, .four, .five: return rawValue
        }
    }

    /// The number of columns a grid holding `itemCount` essentials uses. A
    /// fixed choice is capped at the item count so a short final row never
    /// leaves an empty column behind — it stretches to fill the width instead.
    /// Never returns less than one column.
    func columnCount(forItemCount itemCount: Int) -> Int {
        switch self {
        case .automatic:
            return Self.adaptiveColumnCount(forItemCount: itemCount)
        case .one:
            return max(1, min(1, itemCount))
        case .two:
            return max(1, min(2, itemCount))
        case .three:
            return max(1, min(3, itemCount))
        case .four:
            return max(1, min(4, itemCount))
        case .five:
            return max(1, min(5, itemCount))
        }
    }

    /// The pre-setting rule, kept as the `.automatic` choice: one row up to
    /// four items, then three columns for 5–6, then four columns.
    private static func adaptiveColumnCount(forItemCount itemCount: Int) -> Int {
        switch itemCount {
        case ...0: return 1
        case 1...4: return itemCount
        case 5, 6: return 3
        default: return 4
        }
    }
}
